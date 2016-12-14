/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import scala.annotation.tailrec

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.planning.{BaseTableAccess, ExtractFiltersAndInnerJoins}
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.catalyst.CatalystConf
import org.apache.spark.sql.types._

/**
 * Encapsulates star-schema join detection.
 */
case class DetectStarSchemaJoin(conf: CatalystConf) extends PredicateHelper {

  /**
   * Star schema consists of one or more fact tables referencing a number of dimension
   * tables. In general, star-schema joins are detected using the following conditions:
   *  1. Informational RI constraints (reliable detection)
   *    + Dimension contains a primary key that is being joined to the fact table.
   *    + Fact table contains foreign keys referencing multiple dimension tables.
   *  2. Cardinality based heuristics
   *    + Usually, the table with the highest cardinality is the fact table.
   *    + Table being joined with the most number of tables is the fact table.
   *
   * This function finds the star join with the largest fact table using a combination
   * of the above two conditions. Then, it reorders the star join tables based on
   * the following heuristics:
   *  1. Place the largest fact table on the driving arm to avoid large tables on
   *     the inner of a join and thus favor hash joins.
   *  2. Apply the most selective dimensions early in the plan to reduce the data flow.
   *
   * The highlights of the algorithm are the following:
   *
   * Given a set of joined tables/plans, the algorithm first verifies if they are eligible
   * for star join detection. An eligible plan is a base table access with valid statistics.
   * A base table access represents Project or Filter operators above a LeafNode. Conservatively,
   * the algorithm only considers base table access as part of a star join since they provide
   * reliable statistics.
   *
   * If some of the table are not base table access with valid statistics, the algorithm falls
   * back to the positional join reordering since, in the absence of statistics, we cannot make
   * good planning decisions. Otherwise, the algorithm finds the largest fact table by sorting
   * the tables based on cardinality (number of rows).
   *
   * The algorithm next computes the set of dimension tables for the current fact table.
   * A dimension table is assumed to be in a RI relationship with the fact tables. To infer
   * that a column is a primary key, the algorithm compares the number of distinct values
   * in the column with the total number of rows in the table. If their relative difference
   * is within certain limits, and the column has no null values, it is assumed to be a
   * primary key.
   *
   * Conservatively, the algorithm only considers equi-joins between a fact and a dimension table.
   *
   * Given a star join, i.e. fact and dimension tables, the algorithm considers three cases:
   *
   * 1) The star join is an expanding join i.e. the fact table is joined using inequality
   * predicates or Cartesian product. In this case, the algorithm conservatively falls back
   * to the default join reordering since it cannot make good planning decisions in the absence
   * of the cost model.
   *
   * 2) The star join is a selective join. This case is detected by observing local predicates
   * on the dimension tables. In a star schema relationship, the join between the fact and the
   * dimension table is a FK-PK join. Heuristically, a selective dimension may reduce
   * the result of a join.
   *
   * 3) The star join is not a selective join (i.e. doesn't reduce the number of rows). In this
   * case, the algorithm conservatively falls back to the default join reordering.
   *
   * If an eligible star join was found in step 2 above, the algorithm reorders the tables based
   * on the following heuristics:
   * 1) Place the largest fact table on the driving arm to avoid large tables on the inner of a
   *    join and thus favor hash joins.
   * 2) Apply the most selective dimensions early in the plan to reduce data flow.
   *
   * Other assumptions made by the algorithm, mainly to prevent regressions in the absence of a
   * cost model, are the following:
   * a) Only considers star joins with more than one dimensions, which is a typical
   *    star join scenario.
   * b) If the top largest tables have comparable number of rows, fall back to the default
   *    join reordering. This will prevent changing the position of the large tables in the join.
   */
  def findStarJoinPlan(
      input: Seq[(LogicalPlan, InnerLike)],
      conditions: Seq[Expression]): Seq[(LogicalPlan, InnerLike)] = {
    assert(input.size >= 2)

    val emptyStarJoinPlan = Seq.empty[(LogicalPlan, InnerLike)]

    // Find if the input plans are eligible for star join detection.
    // An eligible plan is a base table access with valid statistics.
    val foundEligibleJoin = input.forall { plan =>
      plan._1 match {
        case BaseTableAccess(t, _) if t.statistics.rowCount.isDefined => true
        case _ => false
      }
    }

    if (!foundEligibleJoin) {
      // Some plans don't have stats or are complex plans. Conservatively fall back
      // to the default join reordering by returning an empty star join.
      // This restriction can be lifted once statistics are propagated in the plan.
      emptyStarJoinPlan

    } else {
      // Find the fact table using cardinality based heuristics i.e.
      // the table with the largest number of rows.
      val sortedFactTables = input.map { plan =>
        TableCardinality(plan, computeTableCardinality(plan._1, conditions))
      }.collect { case t @ TableCardinality(_, Some(_)) =>
        t
      }.sortBy(_.size)(implicitly[Ordering[Option[BigInt]]].reverse)

      sortedFactTables match {
        case Nil =>
          emptyStarJoinPlan
        case table1 :: table2 :: _ if table2.size.get.toDouble >
            conf.starJoinFactTableRatio * table1.size.get.toDouble =>
          // The largest tables have comparable number of rows.
          emptyStarJoinPlan
        case TableCardinality(factPlan @ (factTable, _), _) :: _ =>
          // Find the fact table joins.
          val allFactJoins = input.filterNot(_._1 eq factTable).filter { plan =>
            val joinCond = findJoinConditions(factTable, plan._1, conditions)
            joinCond.nonEmpty
          }

          // Find the corresponding join conditions.
          val allFactJoinCond = allFactJoins.flatMap { plan =>
            val joinCond = findJoinConditions(factTable, plan._1, conditions)
            joinCond
          }

          // Verify if the join columns have valid statistics
          val areStatsAvailable = allFactJoins.forall { plan =>
            val dimTable = plan._1
            allFactJoinCond.exists {
              case BinaryComparison(lhs: AttributeReference, rhs: AttributeReference) =>
                val dimCol = if (dimTable.outputSet.contains(lhs)) lhs else rhs
                val factCol = if (factTable.outputSet.contains(lhs)) lhs else rhs
                hasStatistics(dimCol, dimTable) && hasStatistics(factCol, factTable)
             case _ => false
            }
          }

          if (!areStatsAvailable) {
            emptyStarJoinPlan
          } else {
            // Find the subset of dimension tables. A dimension table is assumed to be in
            // RI relationship with the fact table. Also, conservatively, only consider
            // equi-join between a fact and a dimension table.
            val eligibleDimPlans = allFactJoins.filter { plan =>
              val dimTable = plan._1
              allFactJoinCond.exists {
                case cond @ BinaryComparison(lhs: AttributeReference, rhs: AttributeReference)
                    if cond.isInstanceOf[EqualTo] || cond.isInstanceOf[EqualNullSafe] =>
                  val dimCol = if (dimTable.outputSet.contains(lhs)) lhs else rhs
                  isUnique(dimCol, dimTable)
                case _ => false
              }
            }

            if (eligibleDimPlans.isEmpty) {
              // An eligible star join was not found because the join is not
              // an RI join, or the star join is an expanding join.
              // Conservatively fall back to the default join order.
              emptyStarJoinPlan
            } else if (eligibleDimPlans.size < 2) {
              // Conservatively assume that a fact table is joined with more than one dimension.
              emptyStarJoinPlan
            } else if (isSelectiveStarJoin(eligibleDimPlans.map {_._1}, conditions)) {
              // This is a selective star join. Reorder the dimensions in based on their
              // cardinality and return the star-join plan.
              val sortedDims = eligibleDimPlans.map { plan =>
                TableCardinality(plan, computeTableCardinality(plan._1, conditions))
              }.sortBy(_.size).map {
                case TableCardinality(plan, _) => plan
              }
              factPlan +: sortedDims
            } else {
              // This is a non selective star join. Conservatively fall back to the default
              // join order.
              emptyStarJoinPlan
            }
          }
      }
    }
  }

  /**
   * Determines if a column referenced by a base table access is a primary key.
   * A column is a PK if it is not nullable and has unique values.
   * To determine if a column has unique values in the absence of informational
   * RI constraints, the number of distinct values is compared to the total
   * number of rows in the table. If their relative difference
   * is within the expected limits (i.e. 2 * spark.sql.statistics.ndv.maxError based
   * on TPCDS data results), the column is assumed to have unique values.
   */
  private def isUnique(
      column: Attribute,
      plan: LogicalPlan): Boolean = plan match {
    case BaseTableAccess(t, _) =>
      val leafCol = findLeafNodeCol(column, plan)
      leafCol match {
        case Some(col) if t.outputSet.contains(col) =>
          val stats = t.statistics
          stats.rowCount match {
            case Some(rowCount) if rowCount >= 0 =>
              if (stats.colStats.nonEmpty && stats.colStats.contains(col.name)) {
                val colStats = stats.colStats.get(col.name)
                if (colStats.get.nullCount > 0) {
                  false
                } else {
                  val distinctCount = colStats.get.distinctCount
                  val relDiff = math.abs((distinctCount.toDouble / rowCount.toDouble) - 1.0d)
                  // ndvMaxErr adjusted based on TPCDS 1TB data results
                  if (relDiff <= conf.ndvMaxError * 2) true else false
                }
              } else false
            case None => false
          }
        case None => false
      }
    case _ => false
  }

  /**
   * Given a column over a base table access, it returns
   * the leaf node column from which the input column is derived.
   */
  @tailrec
  private def findLeafNodeCol(
      column: Attribute,
      plan: LogicalPlan): Option[Attribute] = plan match {
    case pl @ BaseTableAccess(_, _) =>
      pl match {
        case t: LeafNode if t.outputSet.contains(column) =>
          Option(column)
        case p: Project if p.outputSet.exists(_.semanticEquals(column)) =>
          val col = p.outputSet.find(_.semanticEquals(column)).get
          findLeafNodeCol(col, p.child)
        case f: Filter =>
          findLeafNodeCol(column, f.child)
        case _ => None
      }
    case _ => None
  }

  /**
   * Checks if a column has statistics.
   * The column is assumed to be over a base table access.
   */
  private def hasStatistics(
      column: Attribute,
      plan: LogicalPlan): Boolean = plan match {
    case BaseTableAccess(t, _) =>
      val leafCol = findLeafNodeCol(column, plan)
      leafCol match {
        case Some(col) if t.outputSet.contains(col) =>
          val stats = t.statistics
          val dataType = col.dataType
          stats.colStats.nonEmpty && stats.colStats.contains(col.name)
        case None => false
      }
    case _ => false
  }

  /**
   * Returns the join predicates between two input plans. It only
   * considers basic comparison operators.
   */
  @inline
  private def findJoinConditions(
      plan1: LogicalPlan,
      plan2: LogicalPlan,
      conditions: Seq[Expression]): Seq[Expression] = {
    val refs = plan1.outputSet ++ plan2.outputSet
    conditions.filter {
      case BinaryComparison(_, _) => true
      case _ => false
    }.filterNot(canEvaluate(_, plan1))
     .filterNot(canEvaluate(_, plan2))
     .filter(_.references.subsetOf(refs))
  }

  /**
   * Checks if a star join is a selective join. A star join is assumed
   * to be selective if there are local predicates on the dimension
   * tables.
   */
  private def isSelectiveStarJoin(
      dimTables: Seq[LogicalPlan],
      conditions: Seq[Expression]): Boolean = dimTables.exists {
    case plan @ BaseTableAccess(_, p) =>
      // Checks if any condition applies to the dimension tables.
      // Exclude the IsNotNull predicates until predicate selectivity is available.
      // In most cases, this predicate is artificially introduced by the Optimizer
      // to enforce nullability constraints.
      val localPredicates = conditions.filterNot(_.isInstanceOf[IsNotNull])
        .exists(canEvaluate(_, plan))

      // Checks if there are any predicates pushed down to the base table access.
      val pushedDownPredicates = p.nonEmpty && !p.forall(_.isInstanceOf[IsNotNull])

      localPredicates || pushedDownPredicates
    case _ => false
  }

  /**
   * Helper case class to hold (plan, rowCount) pairs.
   */
  private case class TableCardinality(plan: (LogicalPlan, InnerLike), size: Option[BigInt])

  /**
   * Computes table cardinality after applying the predicates.
   * Currently, the function returns table cardinality.
   * When predicate selectivity is implemented in Catalyst,
   * the function will be refined based on these estimates.
   */
  private def computeTableCardinality(
      input: LogicalPlan,
      conditions: Seq[Expression]): Option[BigInt] = input match {
    case BaseTableAccess(t, cond) if t.statistics.rowCount.isDefined =>
      val cardinality = t.statistics.rowCount.get
      // Collect predicate selectivity, when available.
      // val predicates = conditions.filter(canEvaluate(_, p)) ++ cond
      // Compute the output cardinality = cardinality * predicates' selectivity.
      Option(cardinality)
    case _ => None
  }
}


/**
 * Reorder the joins and push all the conditions into join, so that the bottom ones have at least
 * one condition.
 *
 * The order of joins will not be changed if all of them already have at least one condition.
 */
case class ReorderJoin(conf: CatalystConf) extends Rule[LogicalPlan] with PredicateHelper {
  /**
   * Join a list of plans together and push down the conditions into them.
   *
   * The joined plan are picked from left to right, prefer those has at least one join condition.
   *
   * @param input a list of LogicalPlans to inner join and the type of inner join.
   * @param conditions a list of condition for join.
   */
  @tailrec
  private def createOrderedJoin(input: Seq[(LogicalPlan, InnerLike)], conditions: Seq[Expression])
    : LogicalPlan = {
    assert(input.size >= 2)
    if (input.size == 2) {
      val (joinConditions, others) = conditions.partition(canEvaluateWithinJoin)
      val ((left, leftJoinType), (right, rightJoinType)) = (input(0), input(1))
      val innerJoinType = (leftJoinType, rightJoinType) match {
        case (Inner, Inner) => Inner
        case (_, _) => Cross
      }
      val join = Join(left, right, innerJoinType, joinConditions.reduceLeftOption(And))
      if (others.nonEmpty) {
        Filter(others.reduceLeft(And), join)
      } else {
        join
      }
    } else {
      val (left, _) :: rest = input.toList
      // find out the first join that have at least one join condition
      val conditionalJoin = rest.find { planJoinPair =>
        val plan = planJoinPair._1
        val refs = left.outputSet ++ plan.outputSet
        conditions
          .filterNot(l => l.references.nonEmpty && canEvaluate(l, left))
          .filterNot(r => r.references.nonEmpty && canEvaluate(r, plan))
          .exists(_.references.subsetOf(refs))
      }
      // pick the next one if no condition left
      val (right, innerJoinType) = conditionalJoin.getOrElse(rest.head)

      val joinedRefs = left.outputSet ++ right.outputSet
      val (joinConditions, others) = conditions.partition(
        e => e.references.subsetOf(joinedRefs) && canEvaluateWithinJoin(e))
      val joined = Join(left, right, innerJoinType, joinConditions.reduceLeftOption(And))

      // should not have reference to same logical plan
      createOrderedJoin(Seq((joined, Inner)) ++ rest.filterNot(_._1 eq right), others)
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case ExtractFiltersAndInnerJoins(input, conditions)
        if input.size > 2 && conditions.nonEmpty =>
      if (conf.starJoinOptimization) {
        val starJoinPlan = DetectStarSchemaJoin(conf).findStarJoinPlan(input, conditions)
        if (starJoinPlan.nonEmpty) {
          val rest = input.filterNot(starJoinPlan.contains(_))
          createOrderedJoin(starJoinPlan ++ rest, conditions)
        } else {
          createOrderedJoin(input, conditions)
        }
      } else {
        createOrderedJoin(input, conditions)
      }
  }
}

/**
 * Elimination of outer joins, if the predicates can restrict the result sets so that
 * all null-supplying rows are eliminated
 *
 * - full outer -> inner if both sides have such predicates
 * - left outer -> inner if the right side has such predicates
 * - right outer -> inner if the left side has such predicates
 * - full outer -> left outer if only the left side has such predicates
 * - full outer -> right outer if only the right side has such predicates
 *
 * This rule should be executed before pushing down the Filter
 */
object EliminateOuterJoin extends Rule[LogicalPlan] with PredicateHelper {

  /**
   * Returns whether the expression returns null or false when all inputs are nulls.
   */
  private def canFilterOutNull(e: Expression): Boolean = {
    if (!e.deterministic || SubqueryExpression.hasCorrelatedSubquery(e)) return false
    val attributes = e.references.toSeq
    val emptyRow = new GenericInternalRow(attributes.length)
    val boundE = BindReferences.bindReference(e, attributes)
    if (boundE.find(_.isInstanceOf[Unevaluable]).isDefined) return false
    val v = boundE.eval(emptyRow)
    v == null || v == false
  }

  private def buildNewJoinType(filter: Filter, join: Join): JoinType = {
    val conditions = splitConjunctivePredicates(filter.condition) ++ filter.constraints
    val leftConditions = conditions.filter(_.references.subsetOf(join.left.outputSet))
    val rightConditions = conditions.filter(_.references.subsetOf(join.right.outputSet))

    val leftHasNonNullPredicate = leftConditions.exists(canFilterOutNull)
    val rightHasNonNullPredicate = rightConditions.exists(canFilterOutNull)

    join.joinType match {
      case RightOuter if leftHasNonNullPredicate => Inner
      case LeftOuter if rightHasNonNullPredicate => Inner
      case FullOuter if leftHasNonNullPredicate && rightHasNonNullPredicate => Inner
      case FullOuter if leftHasNonNullPredicate => LeftOuter
      case FullOuter if rightHasNonNullPredicate => RightOuter
      case o => o
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case f @ Filter(condition, j @ Join(_, _, RightOuter | LeftOuter | FullOuter, _)) =>
      val newJoinType = buildNewJoinType(f, j)
      if (j.joinType == newJoinType) f else Filter(condition, j.copy(joinType = newJoinType))
  }
}

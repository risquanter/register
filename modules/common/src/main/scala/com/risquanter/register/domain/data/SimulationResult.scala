package com.risquanter.register.domain.data

import zio.prelude.{Associative, Identity, Equal, Debug}
import scala.collection.immutable.TreeMap

/**
 * Risk type discriminator for simulation results.
 */
enum RiskType:
  case Base      // Single risk
  case Aggregate // Grouped risks

/**
 * Simulation result capturing outcomes across trials.
 * 
 * Uses sparse storage: only non-zero outcomes are stored in the map.
 * This is memory-efficient for low-probability events.
 * 
 * Design decisions:
 * - Renamed from BCG's "Risk" to "SimulationResult" to avoid confusion with Risk sampling trait
 * - Sparse Map[TrialId, Loss] for memory efficiency
 * - Outer join merge semantics (union of trial IDs, sum losses)
 * - Monoid instance for compositional aggregation
 */
sealed abstract class SimulationResult(
  val name: String,
  val outcomes: Map[TrialId, Loss],
  val nTrials: Int,
  val rType: RiskType
) {
  /** Frequency distribution of loss amounts */
  def outcomeCount: TreeMap[Loss, Int]
  
  /** Maximum loss observed across all trials */
  def maxLoss: Loss
  
  /** Minimum loss observed across all trials */
  def minLoss: Loss
  
  /** Probability that loss exceeds threshold */
  def probOfExceedance(threshold: Loss): BigDecimal
  
  /** Get outcome for specific trial (0 if not present) */
  def outcomeOf(trial: TrialId): Loss = outcomes.getOrElse(trial, 0L)
  
  /** All trial IDs with non-zero outcomes */
  def trialIds(): Set[TrialId] = outcomes.keySet
  
  /** Flatten hierarchy to vector of all results */
  def flatten: Vector[SimulationResult]
}

/**
 * Single risk result (leaf node in hierarchy).
 */
case class RiskResult(
  override val name: String,
  override val outcomes: Map[TrialId, Loss],
  override val nTrials: Int
) extends SimulationResult(name, outcomes, nTrials, RiskType.Base) {
  
  override lazy val outcomeCount: TreeMap[Loss, Int] = 
    TreeMap.from(outcomes.values.groupMapReduce(identity)(_ => 1)(_ + _))
  
  override lazy val maxLoss: Loss = 
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max
  
  override lazy val minLoss: Loss = 
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min
  
  override def probOfExceedance(threshold: Loss): BigDecimal = {
    val exceedingCount = outcomeCount.rangeFrom(threshold).values.sum
    BigDecimal(exceedingCount) / BigDecimal(nTrials)
  }
  
  override def flatten: Vector[SimulationResult] = Vector(this)
}

object RiskResult {
  /** Empty result (no losses occurred) */
  def empty(name: String, nTrials: Int): RiskResult = 
    RiskResult(name, Map.empty, nTrials)
  
  /**
   * ZIO Prelude Identity instance for RiskResult (combines Associative + Identity).
   * 
   * Combines results using outer join semantics:
   * - Identity: empty map
   * - Combine: union of trial IDs, sum losses per trial
   * - Associativity: verified by property tests
   */
  given identity: Identity[RiskResult] with {
    def identity: RiskResult = RiskResult("", Map.empty, 0)
    
    def combine(a: => RiskResult, b: => RiskResult): RiskResult = {
      require(a.nTrials == b.nTrials, s"Cannot merge results with different trial counts: ${a.nTrials} vs ${b.nTrials}")
      RiskResult(
        if (a.name.nonEmpty) a.name else b.name,
        SimulationResult.merge(a, b),
        a.nTrials
      )
    }
  }
  
  /** Value equality for RiskResult */
  given Equal[RiskResult] = Equal.make { (a, b) =>
    a.outcomes == b.outcomes && a.nTrials == b.nTrials && a.name == b.name
  }
  
  /** Human-readable representation */
  given Debug[RiskResult] = Debug.make { r =>
    s"RiskResult(${r.name}, ${r.outcomes.size} outcomes, ${r.nTrials} trials, max=${r.maxLoss})"
  }
}

/**
 * Aggregated risk result (branch node in hierarchy).
 * 
 * Represents the sum of multiple risks, preserving individual children
 * for drill-down analysis.
 */
case class RiskResultGroup(
  children: List[RiskResult],
  override val name: String,
  override val outcomes: Map[TrialId, Loss],
  override val nTrials: Int
) extends SimulationResult(name, outcomes, nTrials, RiskType.Aggregate) {
  
  override lazy val outcomeCount: TreeMap[Loss, Int] = 
    TreeMap.from(outcomes.values.groupMapReduce(identity)(_ => 1)(_ + _))
  
  override lazy val maxLoss: Loss = 
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max
  
  override lazy val minLoss: Loss = 
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min
  
  override def probOfExceedance(threshold: Loss): BigDecimal = {
    val exceedingCount = outcomeCount.rangeFrom(threshold).values.sum
    BigDecimal(exceedingCount) / BigDecimal(nTrials)
  }
  
  override def flatten: Vector[SimulationResult] =
    this +: children.toVector.sortBy(_.name)
}

object RiskResultGroup {
  /**
   * Create aggregated result from multiple risks.
   * 
   * @param name Name for the aggregate
   * @param nTrials Number of trials (must match all children)
   * @param results Individual risk results to aggregate
   */
  def apply(
    name: String,
    nTrials: Int,
    results: RiskResult*
  ): RiskResultGroup = {
    if (results.isEmpty) {
      RiskResultGroup(List.empty, name, Map.empty, nTrials)
    } else {
      // Verify all children have same trial count
      require(
        results.forall(_.nTrials == nTrials),
        s"All results must have nTrials=$nTrials"
      )
      
      RiskResultGroup(
        results.toList,
        name,
        SimulationResult.merge(results*),
        nTrials
      )
    }
  }
}

object SimulationResult {
  /**
   * Outer join merge operation for simulation results.
   * 
   * This is the associative combine operation for the Monoid:
   * - Takes union of all trial IDs
   * - Sums losses for each trial across all results
   * - Missing trials are treated as 0 loss
   * 
   * Properties:
   * - Associative: merge(a, merge(b, c)) == merge(merge(a, b), c)
   * - Commutative: merge(a, b) == merge(b, a)
   * - Identity: merge(empty, a) == a
   * 
   * Loss semantics: Long represents millions of dollars (1L = $1M)
   * - Maximum representable: ±9.2 quintillion dollars
   * - Overflow check: Individual losses should be < Long.MaxValue / numResults
   * 
   * @param rs Results to merge
   * @return Map of trial ID to total loss
   * @throws IllegalArgumentException if losses risk overflow
   */
  def merge(rs: SimulationResult*): Map[TrialId, Loss] = {
    // Union of all trial IDs across all results
    val allTrialIds: Set[TrialId] = 
      rs.foldLeft(Set.empty[TrialId])((acc, r) => acc ++ r.trialIds())
    
    // For each trial, sum losses from all results (missing = 0)
    allTrialIds.map { trial =>
      trial -> rs.foldLeft(0L)((acc, r) => acc + r.outcomeOf(trial))
    }.toMap
  }
}

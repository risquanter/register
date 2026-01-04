package com.risquanter.register.domain.data

import zio.prelude.{Associative, Identity, Equal, Debug, Ord}
import scala.collection.immutable.TreeMap
import com.risquanter.register.domain.PreludeInstances.given

/**
 * Risk type discriminator for loss distributions.
 */
enum LossDistributionType:
  case Leaf      // Single risk distribution
  case Composite // Aggregated distributions

/**
 * Loss Exceedance Curve functional interface.
 * 
 * Represents the mathematical function: Loss → Probability
 * This is the core abstraction for risk quantification.
 */
trait LECCurve {
  /** Number of Monte Carlo trials backing this distribution */
  def nTrials: Int
  
  /** Probability that loss exceeds the given threshold: P(Loss ≥ threshold) */
  def probOfExceedance(threshold: Loss): BigDecimal
  
  /** Maximum loss observed across all trials */
  def maxLoss: Loss
  
  /** Minimum loss observed across all trials */
  def minLoss: Loss
}

/**
 * Loss Distribution - the empirical distribution backing an LEC curve.
 * 
 * This represents the complete simulation data from Monte Carlo trials.
 * It provides both the raw loss data and the derived LEC function.
 * 
 * Mathematical structure:
 * - Identity instance: Combines distributions via trial-wise loss summation
 * - Associative: (a + b) + c = a + (b + c) for distribution aggregation
 * - LECCurve: Provides Loss → Probability function via probOfExceedance
 * 
 * Storage:
 * - Sparse Map[TrialId, Loss]: Memory-efficient for low-probability events
 * - TreeMap[Loss, Int]: Frequency distribution for fast quantile queries
 * 
 * Design decisions:
 * - Renamed from BCG's "Risk" to avoid confusion with Risk sampling trait
 * - Outer join merge semantics (union of trial IDs, sum losses)
 * - Identity/Monoid instance for compositional aggregation
 */
sealed abstract class LossDistribution(
  val name: String,
  val outcomes: Map[TrialId, Loss],
  override val nTrials: Int,
  val distributionType: LossDistributionType
) extends LECCurve {
  /** Frequency distribution of loss amounts (histogram view) */
  def outcomeCount: TreeMap[Loss, Int]
  
  /** Get outcome for specific trial (0 if not present) */
  def outcomeOf(trial: TrialId): Loss = outcomes.getOrElse(trial, 0L)
  
  /** All trial IDs with non-zero outcomes */
  def trialIds(): Set[TrialId] = outcomes.keySet
  
  /** Flatten hierarchy to vector of all distributions */
  def flatten: Vector[LossDistribution]
}

/**
 * Single risk loss distribution (leaf node in hierarchy).
 * 
 * Represents the empirical loss distribution from simulating one risk
 * across multiple Monte Carlo trials.
 */
case class RiskResult(
  override val name: String,
  override val outcomes: Map[TrialId, Loss],
  override val nTrials: Int
) extends LossDistribution(name, outcomes, nTrials, LossDistributionType.Leaf) {
  
  override lazy val outcomeCount: TreeMap[Loss, Int] = 
    TreeMap.from(outcomes.values.groupMapReduce(identity)(_ => 1)(_ + _))(using Ord[Loss].toScala)
  
  override lazy val maxLoss: Loss = 
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max(using Ord[Loss].toScala)
  
  override lazy val minLoss: Loss = 
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min(using Ord[Loss].toScala)
  
  override def probOfExceedance(threshold: Loss): BigDecimal = {
    val exceedingCount = outcomeCount.rangeFrom(threshold).values.sum
    BigDecimal(exceedingCount) / BigDecimal(nTrials)
  }
  
  override def flatten: Vector[LossDistribution] = Vector(this)
}

object RiskResult {
  /** Empty result (no losses occurred) */
  def empty(name: String, nTrials: Int): RiskResult = 
    RiskResult(name, Map.empty, nTrials)
  
  /**
   * ZIO Prelude Identity instance for RiskResult (combines Associative + Identity).
   * 
   * Combines loss distributions using outer join semantics:
   * - Identity: Empty distribution with no losses
   * - Combine: Union of trial IDs, sum losses per trial
   * - Associativity: (a ⊕ b) ⊕ c = a ⊕ (b ⊕ c)
   * 
   * Mathematical interpretation:
   * This models the aggregation of independent risks by summing their
   * losses in each trial, creating a composite loss distribution.
   */
  given identity: Identity[RiskResult] with {
    def identity: RiskResult = RiskResult("", Map.empty, 0)
    
    def combine(a: => RiskResult, b: => RiskResult): RiskResult = {
      require(a.nTrials == b.nTrials, s"Cannot merge results with different trial counts: ${a.nTrials} vs ${b.nTrials}")
      RiskResult(
        if (a.name.nonEmpty) a.name else b.name,
        LossDistribution.merge(a, b),
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
 * Aggregated loss distribution (composite node in hierarchy).
 * 
 * Represents the sum of multiple risk distributions, preserving individual
 * children for drill-down analysis. The aggregate distribution models the
 * total loss from a portfolio of risks.
 */
case class RiskResultGroup(
  children: List[RiskResult],
  override val name: String,
  override val outcomes: Map[TrialId, Loss],
  override val nTrials: Int
) extends LossDistribution(name, outcomes, nTrials, LossDistributionType.Composite) {
  
  override lazy val outcomeCount: TreeMap[Loss, Int] = 
    TreeMap.from(outcomes.values.groupMapReduce(identity)(_ => 1)(_ + _))(using Ord[Loss].toScala)
  
  override lazy val maxLoss: Loss = 
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max(using Ord[Loss].toScala)
  
  override lazy val minLoss: Loss = 
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min(using Ord[Loss].toScala)
  
  override def probOfExceedance(threshold: Loss): BigDecimal = {
    val exceedingCount = outcomeCount.rangeFrom(threshold).values.sum
    BigDecimal(exceedingCount) / BigDecimal(nTrials)
  }
  
  override def flatten: Vector[LossDistribution] =
    this +: children.toVector.sortBy(_.name)
}

object RiskResultGroup {
  /**
   * Create aggregated loss distribution from multiple risks.
   * 
   * @param name Name for the aggregate distribution
   * @param nTrials Number of trials (must match all children)
   * @param results Individual risk loss distributions to aggregate
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
        LossDistribution.merge(results*),
        nTrials
      )
    }
  }
}

object LossDistribution {
  /**
   * Outer join merge operation for loss distributions.
   * 
   * This is the associative combine operation for the Identity instance:
   * - Takes union of all trial IDs across distributions
   * - Sums losses for each trial (missing trials treated as 0 loss)
   * - Preserves trial alignment for portfolio aggregation
   * 
   * Mathematical properties:
   * - Associative: merge(a, merge(b, c)) == merge(merge(a, b), c)
   * - Commutative: merge(a, b) == merge(b, a)
   * - Identity: merge(empty, a) == a
   * 
   * Loss semantics: Long represents millions of dollars (1L = $1M)
   * - Maximum representable: ±9.2 quintillion dollars
   * - Overflow check: Individual losses should be < Long.MaxValue / numDistributions
   * 
   * @param distributions Loss distributions to merge
   * @return Map of trial ID to aggregated loss
   * @throws IllegalArgumentException if distributions have different trial counts
   */
  def merge(distributions: LossDistribution*): Map[TrialId, Loss] = {
    // Union of all trial IDs across all distributions
    val allTrialIds: Set[TrialId] = 
      distributions.foldLeft(Set.empty[TrialId])((acc, d) => acc ++ d.trialIds())
    
    // For each trial, sum losses from all distributions (missing = 0)
    allTrialIds.map { trial =>
      trial -> distributions.foldLeft(0L)((acc, d) => acc + d.outcomeOf(trial))
    }.toMap
  }
}

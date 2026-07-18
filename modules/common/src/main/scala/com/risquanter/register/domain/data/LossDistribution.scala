package com.risquanter.register.domain.data

import zio.prelude.{Commutative, Debug, Equal, Identity, Ord, Validation}
import com.risquanter.register.configs.SimulationConfig
import scala.collection.immutable.TreeMap
import com.risquanter.register.domain.PreludeInstances.given
import com.risquanter.register.domain.data.iron.{NodeId, PositiveInt, ValidationMessages}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

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
  def probOfExceedance(threshold: Loss): Double

  /** Maximum loss observed across all trials */
  def maxLoss: Loss

  /** Minimum loss observed across all trials */
  def minLoss: Loss
}

/**
 * Trial-aligned simulation outcomes — the lawful commutative monoid.
 *
 * The algebraic content of a simulation result: the trial count plus the
 * sparse trial→loss map. Node identity does not participate in combination,
 * which is why the monoid lives here and not on `LossDistribution`
 * (`LossDistribution = NodeId × TrialOutcomes`; the label comes from tree
 * context, never from the algebra).
 *
 * Laws (all enforced under the same-nTrials alignment invariant):
 * - Associative: combine(a, combine(b, c)) == combine(combine(a, b), c)
 * - Commutative: combine(a, b) == combine(b, a)
 * - Identity: combine(empty, a) == a == combine(a, empty)
 */
case class TrialOutcomes(nTrials: PositiveInt, outcomes: Map[TrialId, Loss]) {
  /** Get outcome for specific trial (0 if not present) */
  def outcomeOf(trial: TrialId): Loss = outcomes.getOrElse(trial, 0L)

  /** All trial IDs with non-zero outcomes */
  def trialIds: Set[TrialId] = outcomes.keySet
}

object TrialOutcomes {
  /** Zero losses across the configured number of trials — the monoid identity. */
  def empty(using cfg: SimulationConfig): TrialOutcomes =
    TrialOutcomes(cfg.defaultNTrials, Map.empty)

  /**
   * Outer-join pointwise sum: union of trial IDs, missing trial = 0 loss.
   * Enforces the same-nTrials alignment invariant — pointwise summation is
   * only meaningful when both operands share the same trial coordinate space.
   *
   * Partiality: per-trial sums use `Math.addExact`, so a sum exceeding
   * `Long.MaxValue` throws `ArithmeticException` instead of silently wrapping
   * to a negative loss. The signature must stay total-shaped because the
   * zio-prelude instances below delegate to it, so there is deliberately no
   * `Validation`-returning variant of `combine` itself. Any construction
   * entry point that feeds user-derived data through this function must
   * catch `ArithmeticException` and convert it to a `ValidationError` before
   * it crosses a public API (ADR-010) — `RiskResultGroup.create` is the
   * conversion layer for the production aggregation path.
   */
  def combine(a: TrialOutcomes, b: TrialOutcomes): TrialOutcomes = {
    require(a.nTrials == b.nTrials, s"Cannot merge outcomes with different trial counts: ${a.nTrials} vs ${b.nTrials}")
    val allTrialIds = a.outcomes.keySet ++ b.outcomes.keySet
    TrialOutcomes(
      a.nTrials,
      allTrialIds.iterator.map(t => t -> Math.addExact(a.outcomeOf(t), b.outcomeOf(t))).toMap
    )
  }

  /** The Associative instance as well: Commutative extends Associative in
    * zio-prelude, so this single instance provides both laws.
    */
  given commutative: Commutative[TrialOutcomes] with
    override def combine(a: => TrialOutcomes, b: => TrialOutcomes): TrialOutcomes =
      TrialOutcomes.combine(a, b)

  given identity(using cfg: SimulationConfig): Identity[TrialOutcomes] with
    def identity: TrialOutcomes = TrialOutcomes.empty
    def combine(a: => TrialOutcomes, b: => TrialOutcomes): TrialOutcomes =
      TrialOutcomes.combine(a, b)

  given Debug[TrialOutcomes] = Debug.make { t =>
    s"TrialOutcomes(${t.nTrials} trials, ${t.outcomes.size} outcomes)"
  }
}

/**
 * Loss Distribution - the empirical distribution backing an LEC curve.
 *
 * This represents the complete simulation data from Monte Carlo trials.
 * It provides both the raw loss data and the derived LEC function.
 *
 * Mathematical structure:
 * - Product type: `NodeId × TrialOutcomes`. The node ID is a label supplied
 *   by tree context; `TrialOutcomes` carries the algebra (see its scaladoc).
 * - LECCurve: Provides Loss → Probability function via probOfExceedance
 *
 * Storage:
 * - Sparse Map[TrialId, Loss]: Memory-efficient for low-probability events
 * - TreeMap[Loss, Int]: Frequency distribution for fast quantile queries
 *
 * Design decisions:
 * - Renamed from BCG's "Risk" to avoid confusion with Risk sampling trait
 * - Outer join merge semantics (union of trial IDs, sum losses)
 * - Uses NodeId for node ID per ADR-018 (nominal wrapper over Iron type)
 */
sealed abstract class LossDistribution(
  val nodeId: NodeId,
  val trialOutcomes: TrialOutcomes
) extends LECCurve {

  /** Sparse trial→loss map (delegates to the embedded TrialOutcomes) */
  final def outcomes: Map[TrialId, Loss] = trialOutcomes.outcomes

  final override def nTrials: Int = trialOutcomes.nTrials

  /** Frequency distribution of loss amounts (histogram view) */
  final lazy val outcomeCount: TreeMap[Loss, Int] =
    TreeMap.from(outcomes.values.groupMapReduce(x => x)(_ => 1)(_ + _))(using Ord[Loss].toScala)

  final override lazy val maxLoss: Loss =
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max(using Ord[Loss].toScala)

  final override lazy val minLoss: Loss =
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min(using Ord[Loss].toScala)

  final override def probOfExceedance(threshold: Loss): Double = {
    val exceedingCount = outcomeCount.rangeFrom(threshold).values.sum
    exceedingCount.toDouble / nTrials.toDouble
  }

  /** Get outcome for specific trial (0 if not present) */
  def outcomeOf(trial: TrialId): Loss = trialOutcomes.outcomeOf(trial)

  /** All trial IDs with non-zero outcomes */
  def trialIds(): Set[TrialId] = trialOutcomes.trialIds

  /** Flatten hierarchy to vector of all distributions */
  def flatten: Vector[LossDistribution]
}

/**
 * Single risk loss distribution (leaf node in hierarchy).
 *
 * Represents the empirical loss distribution from simulating one risk
 * across multiple Monte Carlo trials.
 */
case class RiskResult private (
  override val nodeId: NodeId,
  override val trialOutcomes: TrialOutcomes,
  provenances: List[NodeProvenance] = Nil
) extends LossDistribution(nodeId, trialOutcomes) {

  override def flatten: Vector[LossDistribution] = Vector(this)
}

object RiskResult {
  /** Config-driven constructor; uses SimulationConfig.defaultNTrials */
  def apply(
    nodeId: NodeId,
    outcomes: Map[TrialId, Loss],
    provenances: List[NodeProvenance]
  )(using cfg: SimulationConfig): RiskResult =
    RiskResult(nodeId, TrialOutcomes(cfg.defaultNTrials, outcomes), provenances)

  /** Empty result (no losses occurred); uses SimulationConfig.defaultNTrials */
  def empty(nodeId: NodeId)(using cfg: SimulationConfig): RiskResult =
    RiskResult(nodeId, TrialOutcomes.empty, Nil)

  /** Attach node identity to identity-free result content (DD-16 corollary).
    *
    * The content-addressed cache stores `TrialOutcomes` + content-only
    * provenance with no node ID; the resolver labels the content with the
    * *requested* node's ID at the edge — the same content may serve any
    * number of content-identical nodes.
    */
  def fromTrialOutcomes(
    nodeId: NodeId,
    trialOutcomes: TrialOutcomes,
    provenances: List[NodeProvenance]
  ): RiskResult =
    RiskResult(nodeId, trialOutcomes, provenances)

  /** Value equality for RiskResult.
    *
    * Identity is defined over the simulation outcome (outcomes, nTrials, nodeId).
    * Provenance is an audit trail about how the result was produced and is
    * intentionally excluded: two results with byte-identical losses but different
    * run timestamps or library versions represent the same value.
    */
  given Equal[RiskResult] = Equal.make { (a, b) =>
    a.outcomes == b.outcomes && a.nTrials == b.nTrials && a.nodeId == b.nodeId
  }

  /** Human-readable representation */
  given Debug[RiskResult] = Debug.make { r =>
    s"RiskResult(${r.nodeId}, ${r.outcomes.size} outcomes, ${r.nTrials} trials, max=${r.maxLoss}, ${r.provenances.size} provenances)"
  }
}

/**
 * Aggregated loss distribution (composite node in hierarchy).
 *
 * Represents the sum of multiple risk distributions, preserving individual
 * children for drill-down analysis. The aggregate distribution models the
 * total loss from a portfolio of risks.
 */
case class RiskResultGroup private (
  children: List[LossDistribution],
  override val nodeId: NodeId,
  override val trialOutcomes: TrialOutcomes
) extends LossDistribution(nodeId, trialOutcomes) {

  override def flatten: Vector[LossDistribution] =
    this +: children.toVector.sortBy(_.nodeId.value)
}

object RiskResultGroup {
  /**
   * Create aggregated loss distribution from multiple risks.
   *
   * Portfolio construction is a named constructor, not a monoid operation:
   * the parent node ID comes from tree context; the outcomes are the
   * outer-join sum of the children (empty children list → empty outcomes).
   *
   * Failure modes are separated by origin (ADR-010):
   * - Loss overflow is reachable from validated user data (extreme
   *   distribution parameters), so the `ArithmeticException` thrown by
   *   `TrialOutcomes.combine` is converted here to a `ValidationError` —
   *   no exception crosses this public API.
   * - Mismatched trial counts are a programming error (the resolver builds
   *   all children under one `SimulationConfig`), so that `require` is left
   *   to propagate as a defect.
   *
   * @param nodeId Node ID for the aggregate distribution
   * @param results Individual risk loss distributions to aggregate
   */
  def create(
    nodeId: NodeId,
    results: LossDistribution*
  )(using cfg: SimulationConfig): Validation[ValidationError, RiskResultGroup] =
    try Validation.succeed(RiskResultGroup(nodeId, results*))
    catch {
      case _: ArithmeticException =>
        Validation.fail(ValidationError(
          field = s"riskPortfolio.${nodeId.value}",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = ValidationMessages.aggregatedLossOverflow
        ))
    }

  private def apply(
    nodeId: NodeId,
    results: LossDistribution*
  )(using cfg: SimulationConfig): RiskResultGroup = {
    require(
      results.isEmpty || results.map(_.nTrials).distinct.size == 1,
      s"Cannot aggregate distributions with different trial counts: ${results.map(_.nTrials).mkString(", ")}"
    )
    RiskResultGroup(
      results.toList,
      nodeId,
      TrialOutcomes(cfg.defaultNTrials, LossDistribution.merge(results*))
    )
  }
}

object LossDistribution {
  /**
   * Outer join merge operation for loss distributions.
   *
  * Combine operation for aggregating loss distributions:
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
   * - Overflow: per-trial sums are checked (`Math.addExact` in
   *   `TrialOutcomes.combine`) and throw `ArithmeticException`;
   *   `RiskResultGroup.create` converts that to a `ValidationError`
   *
   * @param distributions Loss distributions to merge
   * @return Map of trial ID to aggregated loss
   * @throws IllegalArgumentException if distributions have different trial counts
   */
  def merge(distributions: LossDistribution*): Map[TrialId, Loss] =
    distributions
      .map(_.trialOutcomes)
      .reduceOption(TrialOutcomes.combine)
      .map(_.outcomes)
      .getOrElse(Map.empty)
}

package com.risquanter.register.simulation

import zio.prelude.{Commutative, Debug, Equal, Identity, Ord, Validation}
import com.risquanter.register.configs.SimulationConfig
import scala.collection.immutable.TreeMap
import com.risquanter.register.domain.PreludeInstances.given
import com.risquanter.register.domain.data.{Loss, MitigationApplicationRecord, NodeProvenance, TrialId}
import com.risquanter.register.domain.data.iron.{NodeId, PositiveInt, ValidationMessages}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/** LEC functional interface: the mapping from loss threshold to exceedance probability. */
trait LECCurve {
  def nTrials: Int
  def probOfExceedance(threshold: Loss): Double
  def maxLoss: Loss
  def minLoss: Loss
}

/** Trial count and the sparse trial→loss map. The commutative monoid is defined per
  * fixed `nTrials` slice; mismatched counts throw `IllegalArgumentException`, and
  * per-trial sums past `Long.MaxValue` throw `ArithmeticException` rather than wrapping.
  */
case class TrialOutcomes(nTrials: PositiveInt, outcomes: Map[TrialId, Loss]) {
  def outcomeOf(trial: TrialId): Loss = outcomes.getOrElse(trial, 0L)
  def trialIds: Set[TrialId] = outcomes.keySet
}

object TrialOutcomes {
  /** Identity for the `cfg.defaultNTrials` slice only; combining with a different trial count throws. */
  def empty(using cfg: SimulationConfig): TrialOutcomes =
    TrialOutcomes(cfg.defaultNTrials, Map.empty)

  /** Outer-join pointwise sum. Mismatched `nTrials` throws `IllegalArgumentException`;
    * per-trial overflow throws `ArithmeticException`. Callers crossing a public API
    * must convert those (ADR-033 §3); `PortfolioLosses.create` does so. */
  def combine(a: TrialOutcomes, b: TrialOutcomes): TrialOutcomes = {
    require(a.nTrials == b.nTrials, s"Cannot merge outcomes with different trial counts: ${a.nTrials} vs ${b.nTrials}")
    val allTrialIds = a.outcomes.keySet ++ b.outcomes.keySet
    TrialOutcomes(
      a.nTrials,
      allTrialIds.iterator.map(t => t -> Math.addExact(a.outcomeOf(t), b.outcomeOf(t))).toMap
    )
  }

  /** Associative instance; `Commutative` extends `Associative` in zio-prelude. */
  given commutative: Commutative[TrialOutcomes] with
    override def combine(a: => TrialOutcomes, b: => TrialOutcomes): TrialOutcomes =
      TrialOutcomes.combine(a, b)

  /** Identity for the `cfg.defaultNTrials` slice. Combining with a different trial count throws. */
  given identity(using cfg: SimulationConfig): Identity[TrialOutcomes] with
    def identity: TrialOutcomes = TrialOutcomes.empty
    def combine(a: => TrialOutcomes, b: => TrialOutcomes): TrialOutcomes =
      TrialOutcomes.combine(a, b)

  given Debug[TrialOutcomes] = Debug.make { t =>
    s"TrialOutcomes(${t.nTrials} trials, ${t.outcomes.size} outcomes)"
  }
}

/** One node's loss distribution under one mitigation selection.
  * `trials` is the figure after this node's own result-stage layer; `source` is the input to that layer.
  * `applied` is the layer in precedence order, empty for an unmitigated node.
  * `provenance` is present for a simulated leaf and absent for a portfolio (ADR-003 §2).
  */
final case class LossDistribution private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  source: TrialOutcomes,
  applied: List[MitigationApplicationRecord],
  provenance: Option[NodeProvenance]
) extends LECCurve {

  def outcomes: Map[TrialId, Loss] = trials.outcomes
  def outcomeOf(trial: TrialId): Loss = trials.outcomeOf(trial)
  def trialIds(): Set[TrialId] = trials.trialIds

  override def nTrials: Int = trials.nTrials

  lazy val outcomeCount: TreeMap[Loss, Int] =
    TreeMap.from(outcomes.values.groupMapReduce(x => x)(_ => 1)(_ + _))(using Ord[Loss].toScala)

  override lazy val maxLoss: Loss =
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max(using Ord[Loss].toScala)

  override lazy val minLoss: Loss =
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min(using Ord[Loss].toScala)

  override def probOfExceedance(threshold: Loss): Double = {
    val exceedingCount = outcomeCount.rangeFrom(threshold).values.sum
    exceedingCount.toDouble / nTrials.toDouble
  }
}

object LossDistribution {

  /** Apply this node's result-stage layer to `source`. An empty layer passes `source` twice
    * (reference equality, no rebuild). Two failures are converted to
    * `CONSTRAINT_VIOLATION`: layer overflow (ADR-033 §3), and a layer that leaves no loss
    * where the node had one, which no mitigation may assert (ADR-034 §6). */
  def decorate(
    nodeId: NodeId,
    source: TrialOutcomes,
    provenance: Option[NodeProvenance],
    applied: List[MitigationApplicationRecord],
    run: TrialOutcomes => TrialOutcomes
  ): Validation[ValidationError, LossDistribution] =
    if (applied.isEmpty)
      Validation.succeed(LossDistribution(nodeId, source, source, Nil, provenance))
    else
      try {
        val mitigated = run(source)
        if (eliminatesEveryLoss(source, mitigated)) Validation.fail(layerEliminatesRisk(nodeId))
        else Validation.succeed(LossDistribution(nodeId, mitigated, source, applied, provenance))
      }
      catch { case _: ArithmeticException => Validation.fail(layerOverflow(nodeId)) }

  /** A layer eliminates the risk when the node had at least one loss and the
    * layer leaves none. A node whose outcomes held no loss to begin with is not
    * caught: its zero residual is what the simulation produced, not what a
    * mitigation asserted. */
  private def eliminatesEveryLoss(source: TrialOutcomes, mitigated: TrialOutcomes): Boolean =
    source.outcomes.exists(_._2 > 0L) && !mitigated.outcomes.exists(_._2 > 0L)

  /** Each simulated leaf's provenance record, keyed by the node carrying it.
    * Portfolio entries contribute nothing, having no record of their own. */
  def leafProvenances(results: Map[NodeId, LossDistribution]): Map[NodeId, NodeProvenance] =
    results.iterator.flatMap { case (id, d) => d.provenance.map(id -> _) }.toMap

  private def layerOverflow(nodeId: NodeId): ValidationError =
    ValidationError(
      field   = s"mitigatedResult.${nodeId.value}",
      code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = ValidationMessages.aggregatedLossOverflow
    )

  private def layerEliminatesRisk(nodeId: NodeId): ValidationError =
    ValidationError(
      field   = s"mitigatedResult.${nodeId.value}",
      code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = ValidationMessages.mitigationEliminatesRisk
    )

  /** Structural equality over every field; two readings differing only in timestamp are not equal. */
  given Equal[LossDistribution] = Equal.default

  given Debug[LossDistribution] = Debug.make { d =>
    s"LossDistribution(${d.nodeId}, ${d.outcomes.size} outcomes, ${d.nTrials} trials, " +
    s"max=${d.maxLoss}, ${d.applied.size} applied)"
  }
}

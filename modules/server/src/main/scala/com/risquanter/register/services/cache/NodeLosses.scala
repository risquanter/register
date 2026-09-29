package com.risquanter.register.services.cache

import zio.prelude.Validation
import com.risquanter.register.domain.data.NodeProvenance
import com.risquanter.register.domain.data.iron.{NodeId, ValidationMessages}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import com.risquanter.register.simulation.{LossDistribution, TrialOutcomes}

/** A node's figures before its own layer of result-stage mitigations.
  *
  * Internal to this package: the value consumers receive is
  * `LossDistribution`, which names no member of this family.
  */
private[cache] sealed trait NodeLosses {
  def nodeId: NodeId
  def trials: TrialOutcomes
}

/** A simulated leaf: the cached figures and the provenance record that
  * produced them. */
private[cache] final case class LeafLosses private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  provenance: NodeProvenance
) extends NodeLosses

private[cache] object LeafLosses {
  /** Both figures and record arrive already built from the cache, so there is
    * no invariant to check and no failure to report. */
  def create(
    nodeId: NodeId,
    trials: TrialOutcomes,
    provenance: NodeProvenance
  ): LeafLosses = LeafLosses(nodeId, trials, provenance)
}

/** An aggregated portfolio. `trials` is always the combine of the children's
  * `trials`. */
private[cache] final case class PortfolioLosses private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  children: List[LossDistribution]
) extends NodeLosses

private[cache] object PortfolioLosses {
  /** Derives the total from the children, so a portfolio cannot claim an
    * aggregate its children do not support.
    *
    * Failures separate by origin (ADR-010, ADR-033 §3): differing trial counts
    * are a programming error and the `require` propagates, while an overflow is
    * reachable from validated user data and is converted. An empty child list
    * is refused — nothing would supply a trial count. */
  def create(
    nodeId: NodeId,
    children: List[LossDistribution]
  ): Validation[ValidationError, PortfolioLosses] = {
    require(
      children.isEmpty || children.map(_.nTrials).distinct.sizeIs == 1,
      s"Cannot aggregate distributions with different trial counts: ${children.map(_.nTrials).mkString(", ")}"
    )
    try
      children.map(_.trials).reduceOption(TrialOutcomes.combine) match {
        case Some(combined) => Validation.succeed(PortfolioLosses(nodeId, combined, children))
        case None           => Validation.fail(emptyPortfolio(nodeId))
      }
    catch { case _: ArithmeticException => Validation.fail(aggregateOverflow(nodeId)) }
  }

  private def aggregateOverflow(nodeId: NodeId): ValidationError =
    ValidationError(
      field   = s"riskPortfolio.${nodeId.value}",
      code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = ValidationMessages.aggregatedLossOverflow
    )

  private def emptyPortfolio(nodeId: NodeId): ValidationError =
    ValidationError(
      field   = s"riskPortfolio.${nodeId.value}.childIds",
      code    = ValidationErrorCode.EMPTY_COLLECTION,
      message = ValidationMessages.portfolioHasNoChildren
    )
}

private[cache] object NodeLosses {
  def leaf(
    nodeId: NodeId,
    trials: TrialOutcomes,
    provenance: NodeProvenance
  ): LeafLosses = LeafLosses.create(nodeId, trials, provenance)

  def portfolio(
    nodeId: NodeId,
    children: List[LossDistribution]
  ): Validation[ValidationError, PortfolioLosses] =
    PortfolioLosses.create(nodeId, children)
}

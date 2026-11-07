package com.risquanter.register.testutil

import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.{Loss, NodeProvenance, TrialId}
import com.risquanter.register.domain.data.iron.{NodeId, PositiveInt}
import com.risquanter.register.simulation.{LossDistribution, TrialOutcomes}
import com.risquanter.register.testutil.TestHelpers.nodeId
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

/** Test-only `LossDistribution` fixtures. For config, use
  * `ConfigTestLoader.withCfg` directly.
  *
  * Fixtures go through `TrialOutcomes.create` and `LossDistribution.decorate`,
  * so they cannot express a state the resolver could not produce. There is no
  * portfolio counterpart: an aggregate is only ever derived from its children.
  */
object RiskResultTestSupport {

  /** Trial outcomes through the gate. A fixture with a trial id outside
    * `0 until nTrials` throws here. */
  def outcomesOf(nTrials: PositiveInt, outcomes: Map[TrialId, Loss]): TrialOutcomes =
    TrialOutcomes.create(nTrials, outcomes)

  /** One node's figures with no mitigation layer applied. */
  def leafOf(
    id: NodeId,
    outcomes: Map[TrialId, Loss],
    provenance: Option[NodeProvenance]
  )(using cfg: SimulationConfig): LossDistribution =
    LossDistribution
      .decorate(id, outcomesOf(cfg.defaultNTrials, outcomes), provenance, Nil, identity)
      .toEither
      .toOption
      .get

  /** Neutral element for a given trial count (zero-loss outcomes). */
  def identityFor(nTrials: Int): LossDistribution =
    withCfg(nTrials) {
      leafOf(nodeId("identity"), Map.empty[TrialId, Loss], None)
    }
}

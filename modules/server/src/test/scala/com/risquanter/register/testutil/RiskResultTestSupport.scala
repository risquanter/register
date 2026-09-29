package com.risquanter.register.testutil

import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.{Loss, NodeProvenance, TrialId}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.simulation.{LossDistribution, TrialOutcomes}
import com.risquanter.register.testutil.TestHelpers.nodeId
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

/** Test-only `LossDistribution` fixtures. For config, use
  * `ConfigTestLoader.withCfg` directly.
  *
  * Fixtures go through `LossDistribution.decorate`, so they cannot express a
  * state the resolver could not produce. There is no portfolio counterpart:
  * an aggregate is only ever derived from its children.
  */
object RiskResultTestSupport {

  /** One node's figures with no mitigation layer applied. */
  def leafOf(
    id: NodeId,
    outcomes: Map[TrialId, Loss],
    provenance: Option[NodeProvenance] = None
  )(using cfg: SimulationConfig): LossDistribution =
    LossDistribution
      .decorate(id, TrialOutcomes(cfg.defaultNTrials, outcomes), provenance, Nil, identity)
      .toEither
      .toOption
      .get

  /** Neutral element for a given trial count (zero-loss outcomes). */
  def identityFor(nTrials: Int): LossDistribution =
    withCfg(nTrials) {
      leafOf(nodeId("identity"), Map.empty[TrialId, Loss])
    }
}

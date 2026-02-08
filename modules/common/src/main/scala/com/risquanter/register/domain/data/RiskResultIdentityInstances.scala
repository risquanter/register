package com.risquanter.register.domain.data

import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.iron.{SafeId, NodeId}
import zio.prelude.Identity

/**
 * Context-dependent Identity instance for RiskResult.
 * Requires SimulationConfig to supply the configured nTrials.
 * Production code should continue to use non-empty folds with RiskResult.combine;
 * this instance is available where an Identity shape is explicitly needed.
 */
object RiskResultIdentityInstances {
  given identity(using cfg: SimulationConfig): Identity[RiskResult] with
    private val emptyNodeId: NodeId = NodeId(SafeId.fromString("00000000000000000000000000").getOrElse(
      throw new IllegalStateException("Invalid identity SafeId")
    ))

    def identity: RiskResult = RiskResult(emptyNodeId, Map.empty, Nil)

    def combine(a: => RiskResult, b: => RiskResult): RiskResult =
      RiskResult.combine(a, b)
}

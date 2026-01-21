package com.risquanter.register.testutil

import com.risquanter.register.domain.data.{Loss, RiskResult, TrialId}
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.testutil.TestHelpers.safeId
import io.github.iltotore.iron.refineUnsafe

/** Test-only helpers for RiskResult aggregation scenarios.
  */
object RiskResultTestSupport {
  private def simulationConfig(nTrials: Int): SimulationConfig =
    SimulationConfig(
      defaultNTrials = nTrials.refineUnsafe,
      maxTreeDepth = 5.refineUnsafe,
      defaultTrialParallelism = 8.refineUnsafe,
      maxConcurrentSimulations = 4.refineUnsafe,
      maxNTrials = 1000000.refineUnsafe,
      maxParallelism = 16.refineUnsafe,
      defaultSeed3 = 0L,
      defaultSeed4 = 0L
    )

  def withCfg[A](nTrials: Int)(f: SimulationConfig ?=> A): A = {
    given SimulationConfig = simulationConfig(nTrials)
    f
  }

  /** Neutral element for a given trial count (zero-loss outcomes). */
  def identityFor(nTrials: Int): RiskResult =
    withCfg(nTrials) {
      RiskResult(safeId("identity"), Map.empty[TrialId, Loss], Nil)
    }
}

package com.risquanter.register.testutil

import com.risquanter.register.domain.data.{RiskResult, TrialId, Loss}
import com.risquanter.register.testutil.TestHelpers.safeId

/**
 * Test-only helpers for RiskResult aggregation scenarios.
 */
object RiskResultTestSupport {
  /** Neutral element for a given trial count (zero-loss outcomes). */
  def identityFor(nTrials: Int): RiskResult =
    RiskResult(safeId("identity"), Map.empty[TrialId, Loss], nTrials)
}

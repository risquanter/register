package com.risquanter.register.testutil

import com.risquanter.register.domain.data.{Loss, RiskResult, TrialId}
import com.risquanter.register.testutil.TestHelpers.safeId
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

/** Test-only helpers for RiskResult domain fixtures.
  * 
  * Responsibility: Provides pre-built RiskResult instances for common test scenarios.
  * For config management, use ConfigTestLoader.withCfg directly.
  */
object RiskResultTestSupport {
  /** Neutral element for a given trial count (zero-loss outcomes). */
  def identityFor(nTrials: Int): RiskResult =
    withCfg(nTrials) {
      RiskResult(safeId("identity"), Map.empty[TrialId, Loss], Nil)
    }
}

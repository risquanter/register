package com.risquanter.register.testutil

import com.risquanter.register.configs.SimulationConfig
import com.typesafe.config.ConfigFactory

import java.io.File

/**
 * Test configuration management - single source of truth for SimulationConfig in tests.
 * 
 * Responsibilities:
 * - Load SimulationConfig from application.conf (with fallback to repo copy)
 * - Provide scoped config creation via withCfg for tests with varying nTrials
 * 
 * Usage:
 * {{{
 *   import com.risquanter.register.testutil.ConfigTestLoader.withCfg
 *   
 *   val result = withCfg(100) {
 *     // build whatever reads the simulation config, with nTrials fixed at 100
 *   }
 * }}}
 *
 * Note: For simulation-result fixtures, see the server module's
 * RiskResultTestSupport.
 */
object ConfigTestLoader {
  private def load(): SimulationConfig = {
    val merged = ConfigFactory
      .parseResources("application.conf")
      .withFallback(ConfigFactory.parseFile(new File("modules/server/src/main/resources/application.conf")))
      .withFallback(ConfigFactory.load())
      .resolve()

    val c = merged.getConfig("register.simulation")

    import io.github.iltotore.iron.refineUnsafe
    SimulationConfig(
      defaultNTrials = c.getInt("defaultNTrials").refineUnsafe,
      defaultTrialParallelism = c.getInt("defaultTrialParallelism").refineUnsafe,
      maxConcurrentSimulations = c.getInt("maxConcurrentSimulations").refineUnsafe,
      defaultSeed3 = c.getLong("defaultSeed3"),
      defaultSeed4 = c.getLong("defaultSeed4")
    )
  }

  lazy val simulation: SimulationConfig = load()

  def withCfg[A](nTrials: Int)(f: SimulationConfig ?=> A): A = {
    import io.github.iltotore.iron.refineUnsafe
    given SimulationConfig = ConfigTestLoader.simulation.copy(defaultNTrials = nTrials.refineUnsafe)
    f
  }
}

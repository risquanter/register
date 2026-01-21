package com.risquanter.register.testutil

import com.risquanter.register.configs.SimulationConfig
import com.typesafe.config.ConfigFactory

import java.io.File

/**
 * Test helper that loads SimulationConfig from the canonical application.conf.
 * Falls back to the repo copy under modules/server/src/main/resources to keep
 * a single source of truth when running common-module tests.
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
      maxTreeDepth = c.getInt("maxTreeDepth").refineUnsafe,
      defaultTrialParallelism = c.getInt("defaultTrialParallelism").refineUnsafe,
      maxConcurrentSimulations = c.getInt("maxConcurrentSimulations").refineUnsafe,
      maxNTrials = c.getInt("maxNTrials").refineUnsafe,
      maxParallelism = c.getInt("maxParallelism").refineUnsafe,
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

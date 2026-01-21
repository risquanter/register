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

    SimulationConfig(
      defaultNTrials = c.getInt("defaultNTrials"),
      maxTreeDepth = c.getInt("maxTreeDepth"),
      defaultParallelism = c.getInt("defaultParallelism"),
      maxConcurrentSimulations = c.getInt("maxConcurrentSimulations"),
      maxNTrials = c.getInt("maxNTrials"),
      maxParallelism = c.getInt("maxParallelism"),
      defaultSeed3 = c.getLong("defaultSeed3"),
      defaultSeed4 = c.getLong("defaultSeed4")
    )
  }

  lazy val simulation: SimulationConfig = load()
}

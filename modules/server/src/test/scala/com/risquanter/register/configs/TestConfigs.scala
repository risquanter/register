package com.risquanter.register.configs

import zio.*

/** Test configuration values - single source of truth for tests */
object TestConfigs {
  val simulation: SimulationConfig = SimulationConfig(
    defaultNTrials = 10000,
    maxTreeDepth = 5,
    defaultParallelism = 8
  )
  
  val simulationLayer: ULayer[SimulationConfig] = ZLayer.succeed(simulation)
}

package com.risquanter.register.configs

import zio.*
import io.github.iltotore.iron.refineUnsafe

/** Test configuration values - single source of truth for tests */
object TestConfigs {
  val simulation: SimulationConfig = SimulationConfig(
    defaultNTrials = 10000.refineUnsafe,
    maxTreeDepth = 5.refineUnsafe,
    defaultTrialParallelism = 8.refineUnsafe,
    maxConcurrentSimulations = 4.refineUnsafe,
    maxNTrials = 1000000.refineUnsafe,
    maxParallelism = 16.refineUnsafe,
    defaultSeed3 = 0L,
    defaultSeed4 = 0L
  )
  
  val simulationLayer: ULayer[SimulationConfig] = ZLayer.succeed(simulation)
  
  val telemetry: TelemetryConfig = TelemetryConfig(
    serviceName = "risk-register-test",
    instrumentationScope = "com.risquanter.register.test",
    otlpEndpoint = "http://localhost:4317",
    devExportIntervalSeconds = 1,
    prodExportIntervalSeconds = 10
  )
  
  val telemetryLayer: ULayer[TelemetryConfig] = ZLayer.succeed(telemetry)
}

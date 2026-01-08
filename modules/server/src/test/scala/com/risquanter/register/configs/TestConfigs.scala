package com.risquanter.register.configs

import zio.*

/** Test configuration values - single source of truth for tests */
object TestConfigs {
  val simulation: SimulationConfig = SimulationConfig(
    defaultNTrials = 10000,
    maxTreeDepth = 5,
    defaultParallelism = 8,
    maxConcurrentSimulations = 4,
    maxNTrials = 1000000,
    maxParallelism = 16
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

package com.risquanter.register.configs

import java.time.Duration

import io.github.iltotore.iron.refineUnsafe
import zio.*

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
    otlpEndpoint = TestSafeUrls.localhostOtlpEndpoint,
    devExportIntervalSeconds = 1,
    prodExportIntervalSeconds = 10
  )
  
  val telemetryLayer: ULayer[TelemetryConfig] = ZLayer.succeed(telemetry)

  val workspace: WorkspaceConfig = WorkspaceConfig(
    ttl = Duration.ofHours(24),
    idleTimeout = Duration.ofMinutes(1),
    reaperInterval = Duration.ofMinutes(1),
    maxCreatesPerIpPerHour = 100,
    maxTreesPerWorkspace = 10
  )

  val workspaceLayer: ULayer[WorkspaceConfig] = ZLayer.succeed(workspace)
}

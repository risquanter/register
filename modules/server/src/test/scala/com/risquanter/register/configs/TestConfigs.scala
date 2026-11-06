package com.risquanter.register.configs

import java.time.Duration

import io.github.iltotore.iron.refineUnsafe
import zio.*
import com.risquanter.register.domain.data.iron.PositiveInt

/** Test configuration values - single source of truth for tests */
object TestConfigs {
  val simulation: SimulationConfig = SimulationConfig(
    defaultNTrials = 10000.refineUnsafe,
    defaultTrialParallelism = 8.refineUnsafe,
    maxConcurrentSimulations = 4.refineUnsafe,
    defaultSeed3 = 0L,
    defaultSeed4 = 0L,
    curveTickCount = 100.refineUnsafe,
    curveTailCutoff = 0.005.refineUnsafe
  )
  
  val simulationLayer: ULayer[SimulationConfig] = ZLayer.succeed(simulation)
  
  val telemetry: TelemetryConfig = TelemetryConfig(
    serviceName = "risk-register-test",
    instrumentationScope = "com.risquanter.register.test",
    exporter = TelemetryExporter.Console,
    otlpEndpoint = TestSafeUrls.localhostOtlpEndpoint,
    devExportIntervalSeconds = 1,
    prodExportIntervalSeconds = 10
  )
  
  val telemetryLayer: ULayer[TelemetryConfig] = ZLayer.succeed(telemetry)

  val cascadeDeleteParallelism: PositiveInt = 8.refineUnsafe

  /** The merge's conflict scan fetch limit, as `application.conf` sets it. */
  val conflictScanFetchParallelism: PositiveInt = 8.refineUnsafe

  val workspace: WorkspaceConfig = WorkspaceConfig(
    ttl = Duration.ofHours(24),
    idleTimeout = Duration.ofMinutes(1),
    reaperInterval = Duration.ofMinutes(1),
    maxTreesPerWorkspace = 10.refineUnsafe,
    cascadeDeleteParallelism = cascadeDeleteParallelism
  )

  val workspaceLayer: ULayer[WorkspaceConfig] = ZLayer.succeed(workspace)
}

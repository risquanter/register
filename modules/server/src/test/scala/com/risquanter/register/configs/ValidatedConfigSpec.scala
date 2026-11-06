package com.risquanter.register.configs

import zio.*
import zio.config.magnolia.deriveConfig
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.domain.data.iron.BranchRef
import com.risquanter.register.domain.data.iron.Url.*

object ValidatedConfigSpec extends ZIOSpecDefault {

  private def withConfig[R, E, A](entries: (String, String)*)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    effect.withConfigProvider(
      ConfigProvider.fromMap(Map.from(entries))
    )

  private val telemetryConfig =
    deriveConfig[TelemetryConfig].nested("register", "telemetry")

  private val irminConfig =
    deriveConfig[IrminConfig].nested("register", "irmin")

  private val spiceDbConfig =
    deriveConfig[SpiceDbConfig].nested("register", "spicedb")

  private val simulationConfig =
    deriveConfig[SimulationConfig].nested("register", "simulation")

  private val workspaceConfig =
    deriveConfig[WorkspaceConfig].nested("register", "workspace")

  private def simulationEntries(tickCount: String, tailCutoff: String): Seq[(String, String)] = Seq(
    "register.simulation.defaultNTrials" -> "10000",
    "register.simulation.defaultTrialParallelism" -> "8",
    "register.simulation.maxConcurrentSimulations" -> "4",
    "register.simulation.defaultSeed3" -> "0",
    "register.simulation.defaultSeed4" -> "0",
    "register.simulation.curveTickCount" -> tickCount,
    "register.simulation.curveTailCutoff" -> tailCutoff
  )

  private def workspaceEntries(parallelism: String): Seq[(String, String)] = Seq(
    "register.workspace.ttl" -> "72h",
    "register.workspace.idleTimeout" -> "1h",
    "register.workspace.reaperInterval" -> "5m",
    "register.workspace.maxTreesPerWorkspace" -> "10",
    "register.workspace.cascadeDeleteParallelism" -> parallelism
  )

  def spec = suite("ValidatedConfig")(
    test("loads the curve settings as refined values") {
      withConfig(simulationEntries("100", "0.005")*) {
        ZIO.config(simulationConfig).map(config =>
          assertTrue(config.curveTickCount == 100, config.curveTailCutoff == 0.005))
      }
    },
    test("rejects a non-positive curve tick count") {
      withConfig(simulationEntries("0", "0.005")*) {
        ZIO.config(simulationConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("rejects a curve tail cutoff above 1") {
      withConfig(simulationEntries("100", "1.5")*) {
        ZIO.config(simulationConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("loads the cascade delete parallelism as a refined value") {
      withConfig(workspaceEntries("8")*) {
        ZIO.config(workspaceConfig).map(config => assertTrue(config.cascadeDeleteParallelism == 8))
      }
    },
    test("rejects a non-positive cascade delete parallelism") {
      withConfig(workspaceEntries("0")*) {
        ZIO.config(workspaceConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("loads TelemetryConfig with validated Url endpoint") {
      withConfig(
        "register.telemetry.serviceName" -> "risk-register",
        "register.telemetry.instrumentationScope" -> "com.risquanter.register",
        "register.telemetry.exporter" -> "otlp",
        "register.telemetry.otlpEndpoint" -> "http://localhost:4317",
        "register.telemetry.devExportIntervalSeconds" -> "5",
        "register.telemetry.prodExportIntervalSeconds" -> "60"
      ) {
        ZIO.config(telemetryConfig).map(config => assertTrue(config.otlpEndpoint.value == "http://localhost:4317"))
      }
    },
    test("rejects invalid telemetry OTLP endpoint") {
      withConfig(
        "register.telemetry.serviceName" -> "risk-register",
        "register.telemetry.instrumentationScope" -> "com.risquanter.register",
        "register.telemetry.exporter" -> "otlp",
        "register.telemetry.otlpEndpoint" -> "not-a-url",
        "register.telemetry.devExportIntervalSeconds" -> "5",
        "register.telemetry.prodExportIntervalSeconds" -> "60"
      ) {
        ZIO.config(telemetryConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("loads IrminConfig with BranchRef main and duration bounds") {
      withConfig(
        "register.irmin.url" -> "http://localhost:9080",
        "register.irmin.branch" -> "main",
        "register.irmin.timeout" -> "30s",
        "register.irmin.healthCheckAttemptTimeout" -> "5s",
        "register.irmin.healthCheckBudget" -> "45s",
        "register.irmin.conflictScanFetchParallelism" -> "8"
      ) {
        ZIO.config(irminConfig).map(config =>
          assertTrue(
            config.branch == BranchRef.Main,
            config.timeout == java.time.Duration.ofSeconds(30),
            config.healthCheckAttemptTimeout == java.time.Duration.ofSeconds(5),
            config.healthCheckBudget == java.time.Duration.ofSeconds(45)
          )
        )
      }
    },
    test("rejects invalid Irmin branch") {
      withConfig(
        "register.irmin.url" -> "http://localhost:9080",
        "register.irmin.branch" -> "feature/x",
        "register.irmin.timeout" -> "30s",
        "register.irmin.healthCheckAttemptTimeout" -> "5s",
        "register.irmin.healthCheckBudget" -> "45s",
        "register.irmin.conflictScanFetchParallelism" -> "8"
      ) {
        ZIO.config(irminConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("rejects non-positive Irmin readiness budget (ADR-031)") {
      withConfig(
        "register.irmin.url" -> "http://localhost:9080",
        "register.irmin.branch" -> "main",
        "register.irmin.timeout" -> "30s",
        "register.irmin.healthCheckAttemptTimeout" -> "5s",
        "register.irmin.healthCheckBudget" -> "0s",
        "register.irmin.conflictScanFetchParallelism" -> "8"
      ) {
        ZIO.config(irminConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("rejects a non-positive conflict scan fetch parallelism") {
      withConfig(
        "register.irmin.url" -> "http://localhost:9080",
        "register.irmin.branch" -> "main",
        "register.irmin.timeout" -> "30s",
        "register.irmin.healthCheckAttemptTimeout" -> "5s",
        "register.irmin.healthCheckBudget" -> "45s",
        "register.irmin.conflictScanFetchParallelism" -> "0"
      ) {
        ZIO.config(irminConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("rejects negative Irmin timeout") {
      withConfig(
        "register.irmin.url" -> "http://localhost:9080",
        "register.irmin.branch" -> "main",
        "register.irmin.timeout" -> "-5s",
        "register.irmin.healthCheckAttemptTimeout" -> "5s",
        "register.irmin.healthCheckBudget" -> "45s",
        "register.irmin.conflictScanFetchParallelism" -> "8"
      ) {
        ZIO.config(irminConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("loads SpiceDbConfig with http URL (mesh mTLS)") {
      withConfig(
        "register.spicedb.url"   -> "http://spicedb.svc.cluster.local:8080",
        "register.spicedb.token" -> "some-pre-shared-key"
      ) {
        ZIO.config(spiceDbConfig).map(config =>
          assertTrue(config.url.value.toString == "http://spicedb.svc.cluster.local:8080")
        )
      }
    },
    test("loads SpiceDbConfig with https URL") {
      withConfig(
        "register.spicedb.url"   -> "https://spicedb.example.com:443",
        "register.spicedb.token" -> "some-pre-shared-key"
      ) {
        ZIO.config(spiceDbConfig).map(config =>
          assertTrue(config.url.value.toString == "https://spicedb.example.com:443")
        )
      }
    },
    test("rejects invalid SpiceDb URL") {
      withConfig(
        "register.spicedb.url"   -> "not-a-url",
        "register.spicedb.token" -> "some-pre-shared-key"
      ) {
        ZIO.config(spiceDbConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("rejects blank SpiceDb token") {
      withConfig(
        "register.spicedb.url"   -> "http://spicedb.svc.cluster.local:8080",
        "register.spicedb.token" -> "   "
      ) {
        ZIO.config(spiceDbConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    }
  )
}
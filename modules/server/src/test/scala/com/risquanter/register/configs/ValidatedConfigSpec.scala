package com.risquanter.register.configs

import zio.*
import zio.config.magnolia.deriveConfig
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.domain.data.iron.BranchRef
import com.risquanter.register.domain.data.iron.SafeUrl.*

object ValidatedConfigSpec extends ZIOSpecDefault {

  private def withConfig[R, E, A](entries: (String, String)*)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    effect.withConfigProvider(
      ConfigProvider.fromMap(Map.from(entries))
    )

  private val telemetryConfig =
    deriveConfig[TelemetryConfig].nested("register", "telemetry")

  private val irminConfig =
    deriveConfig[IrminConfig].nested("register", "irmin")

  def spec = suite("ValidatedConfig")(
    test("loads TelemetryConfig with validated SafeUrl endpoint") {
      withConfig(
        "register.telemetry.serviceName" -> "risk-register",
        "register.telemetry.instrumentationScope" -> "com.risquanter.register",
        "register.telemetry.otlpEndpoint" -> "http://localhost:4317",
        "register.telemetry.devExportIntervalSeconds" -> "5",
        "register.telemetry.prodExportIntervalSeconds" -> "60"
      ) {
        ZIO.config(telemetryConfig).map(config => assertTrue(config.otlpEndpoint.asString == "http://localhost:4317"))
      }
    },
    test("rejects invalid telemetry OTLP endpoint") {
      withConfig(
        "register.telemetry.serviceName" -> "risk-register",
        "register.telemetry.instrumentationScope" -> "com.risquanter.register",
        "register.telemetry.otlpEndpoint" -> "not-a-url",
        "register.telemetry.devExportIntervalSeconds" -> "5",
        "register.telemetry.prodExportIntervalSeconds" -> "60"
      ) {
        ZIO.config(telemetryConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    },
    test("loads IrminConfig with BranchRef main") {
      withConfig(
        "register.irmin.url" -> "http://localhost:9080",
        "register.irmin.branch" -> "main",
        "register.irmin.timeoutSeconds" -> "30",
        "register.irmin.healthCheckTimeoutMillis" -> "5000",
        "register.irmin.healthCheckRetries" -> "0"
      ) {
        ZIO.config(irminConfig).map(config => assertTrue(config.branch == BranchRef.Main))
      }
    },
    test("rejects invalid Irmin branch") {
      withConfig(
        "register.irmin.url" -> "http://localhost:9080",
        "register.irmin.branch" -> "feature/x",
        "register.irmin.timeoutSeconds" -> "30",
        "register.irmin.healthCheckTimeoutMillis" -> "5000",
        "register.irmin.healthCheckRetries" -> "0"
      ) {
        ZIO.config(irminConfig).exit.map(exit => assert(exit)(fails(anything)))
      }
    }
  )
}
package com.risquanter.register.configs

import zio.*
import zio.config.magnolia.deriveConfig
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.domain.data.iron.Url.*

object TelemetryConfigSpec extends ZIOSpecDefault {

  // Use test config from TestConfigs instead of loading from file
  def spec = suite("TelemetryConfig")(
    
    test("TestConfigs provides valid TelemetryConfig") {
      for {
        config <- ZIO.service[TelemetryConfig]
      } yield assertTrue(
        config.serviceName == "risk-register-test",
        config.instrumentationScope == "com.risquanter.register.test",
        config.exporter == TelemetryExporter.Otlp,
        config.otlpEndpoint.value == "http://localhost:4317",
        config.devExportIntervalSeconds == 1,
        config.prodExportIntervalSeconds == 10
      )
    }.provide(TestConfigs.telemetryLayer),
    
    test("devExportInterval returns correct Duration") {
      for {
        config <- ZIO.service[TelemetryConfig]
      } yield assertTrue(
        config.devExportInterval.toSeconds == 1L
      )
    }.provide(TestConfigs.telemetryLayer),
    
    test("prodExportInterval returns correct Duration") {
      for {
        config <- ZIO.service[TelemetryConfig]
      } yield assertTrue(
        config.prodExportInterval.toSeconds == 10L
      )
    }.provide(TestConfigs.telemetryLayer),
    
    test("case class fields are accessible") {
      val config = TelemetryConfig(
        serviceName = "test-service",
        instrumentationScope = "test.scope",
        exporter = TelemetryExporter.Console,
        otlpEndpoint = TestSafeUrls.testOtlpEndpoint,
        devExportIntervalSeconds = 2,
        prodExportIntervalSeconds = 30
      )
      assertTrue(
        config.serviceName == "test-service",
        config.instrumentationScope == "test.scope",
        config.exporter == TelemetryExporter.Console,
        config.otlpEndpoint.value == "http://test:4317",
        config.devExportInterval.toSeconds == 2L,
        config.prodExportInterval.toSeconds == 30L
      )
    },

    test("exporter name parses, ignoring case and surrounding whitespace") {
      assertTrue(
        TelemetryExporter.fromString("otlp") == Right(TelemetryExporter.Otlp),
        TelemetryExporter.fromString("console") == Right(TelemetryExporter.Console),
        TelemetryExporter.fromString("  OTLP  ") == Right(TelemetryExporter.Otlp),
        TelemetryExporter.fromString("Console") == Right(TelemetryExporter.Console)
      )
    },

    test("an unknown exporter name is rejected rather than silently defaulted") {
      val result = TelemetryExporter.fromString("otel")
      assertTrue(
        result.isLeft,
        result.left.exists(_.contains("otel")),
        result.left.exists(_.contains("console")),
        result.left.exists(_.contains("otlp"))
      )
    },

    test("the exporter field is read from configuration") {
      val source = ConfigProvider.fromMap(
        Map(
          "serviceName"               -> "risk-register",
          "instrumentationScope"      -> "com.risquanter.register",
          "exporter"                  -> "console",
          "otlpEndpoint"              -> "http://localhost:4317",
          "devExportIntervalSeconds"  -> "5",
          "prodExportIntervalSeconds" -> "60"
        )
      )
      for {
        config <- source.load(deriveConfig[TelemetryConfig])
      } yield assertTrue(config.exporter == TelemetryExporter.Console)
    },

    test("a mistyped exporter name fails configuration loading") {
      val source = ConfigProvider.fromMap(
        Map(
          "serviceName"               -> "risk-register",
          "instrumentationScope"      -> "com.risquanter.register",
          "exporter"                  -> "not-an-exporter",
          "otlpEndpoint"              -> "http://localhost:4317",
          "devExportIntervalSeconds"  -> "5",
          "prodExportIntervalSeconds" -> "60"
        )
      )
      for {
        result <- source.load(deriveConfig[TelemetryConfig]).exit
      } yield assert(result)(fails(anything))
    }

  )
}

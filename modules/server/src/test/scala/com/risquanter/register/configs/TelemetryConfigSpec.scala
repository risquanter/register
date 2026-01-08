package com.risquanter.register.configs

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.config.typesafe.TypesafeConfigProvider

object TelemetryConfigSpec extends ZIOSpecDefault {

  // Use test config from TestConfigs instead of loading from file
  def spec = suite("TelemetryConfig")(
    
    test("TestConfigs provides valid TelemetryConfig") {
      for {
        config <- ZIO.service[TelemetryConfig]
      } yield assertTrue(
        config.serviceName == "risk-register-test",
        config.instrumentationScope == "com.risquanter.register.test",
        config.otlpEndpoint == "http://localhost:4317",
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
        otlpEndpoint = "http://test:4317",
        devExportIntervalSeconds = 2,
        prodExportIntervalSeconds = 30
      )
      assertTrue(
        config.serviceName == "test-service",
        config.instrumentationScope == "test.scope",
        config.otlpEndpoint == "http://test:4317",
        config.devExportInterval.toSeconds == 2L,
        config.prodExportInterval.toSeconds == 30L
      )
    }
    
  )
}

package com.risquanter.register.telemetry

import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.Instrument
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.exporter.logging.LoggingMetricExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api
import com.risquanter.register.configs.TelemetryConfig

/** OpenTelemetry metrics layer using ZIO Telemetry
  * 
  * Configuration-driven: all settings come from TelemetryConfig.
  * 
  * Pattern:
  * - Uses OpenTelemetry.custom() for SDK configuration
  * - Uses OpenTelemetry.metrics() for Meter layer
  * - Uses OpenTelemetry.contextZIO for fiber-based context storage
  * - Resource-safe with ZIO.fromAutoCloseable
  * 
  * Provides two configurations:
  * - `console`: Logging exporter for development (periodic console output)
  * - `otlp`: OTLP gRPC exporter for production (batched, async)
  */
object MetricsLive {
  
  /** Build OpenTelemetry resource with service name from config */
  private def otelResource(config: TelemetryConfig): Resource =
    Resource.create(
      Attributes.of(ServiceAttributes.SERVICE_NAME, config.serviceName)
    )
  
  /** Build SdkMeterProvider with logging exporter
    * 
    * Scoped resource - automatically closed when scope ends.
    * Uses PeriodicMetricReader for batch export.
    */
  private def stdoutMeterProvider(config: TelemetryConfig): RIO[Scope, SdkMeterProvider] =
    for {
      metricExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(LoggingMetricExporter.create())
      )
      metricReader <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          PeriodicMetricReader.builder(metricExporter)
            .setInterval(config.devExportInterval)
            .build()
        )
      )
      meterProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkMeterProvider.builder()
            .setResource(otelResource(config))
            .registerMetricReader(metricReader)
            .build()
        )
      )
    } yield meterProvider
  
  /** OpenTelemetry SDK layer with console/logging exporter
    * 
    * Requires TelemetryConfig for service name and export interval.
    */
  private def otelSdkConsoleLayer(config: TelemetryConfig): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        meterProvider <- stdoutMeterProvider(config)
        sdk <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            OpenTelemetrySdk.builder()
              .setMeterProvider(meterProvider)
              .build()
          )
        )
      } yield sdk
    )
  
  /** Complete metrics layer for development (requires TelemetryConfig)
    * 
    * Composes:
    * - otelSdkConsoleLayer: Provides configured OpenTelemetry SDK with metrics
    * - OpenTelemetry.metrics: Provides Meter service
    * - OpenTelemetry.contextZIO: Provides fiber-based ContextStorage
    * 
    * Usage:
    * {{{
    * myEffect.provide(
    *   MetricsLive.console,
    *   Configs.makeLayer[TelemetryConfig]("register.telemetry")
    * )
    * }}}
    */
  val console: ZLayer[TelemetryConfig, Throwable, Meter & Instrument.Builder] =
    ZLayer.service[TelemetryConfig].flatMap { configEnv =>
      val config = configEnv.get
      otelSdkConsoleLayer(config) ++ OpenTelemetry.contextZIO >>> 
        OpenTelemetry.metrics(config.instrumentationScope)
    }
  
  // ===== OTLP Configuration (Production) =====
  
  /** Build SdkMeterProvider with OTLP gRPC exporter */
  private def otlpMeterProvider(config: TelemetryConfig): RIO[Scope, SdkMeterProvider] =
    for {
      metricExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcMetricExporter.builder()
            .setEndpoint(config.otlpEndpoint)
            .build()
        )
      )
      metricReader <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          PeriodicMetricReader.builder(metricExporter)
            .setInterval(config.prodExportInterval)
            .build()
        )
      )
      meterProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkMeterProvider.builder()
            .setResource(otelResource(config))
            .registerMetricReader(metricReader)
            .build()
        )
      )
    } yield meterProvider
  
  /** OpenTelemetry SDK layer with OTLP exporter */
  private def otelSdkOtlpLayer(config: TelemetryConfig): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        meterProvider <- otlpMeterProvider(config)
        sdk <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            OpenTelemetrySdk.builder()
              .setMeterProvider(meterProvider)
              .build()
          )
        )
      } yield sdk
    )
  
  /** Complete metrics layer for production (OTLP export)
    * 
    * Uses OTLP gRPC exporter with periodic async metric export.
    * Endpoint from TelemetryConfig (default: http://localhost:4317)
    * Export interval from TelemetryConfig (default: 60 seconds)
    * 
    * Override endpoint via:
    * - application.conf: register.telemetry.otlpEndpoint
    * - Environment: OTEL_EXPORTER_OTLP_ENDPOINT
    * 
    * Usage:
    * {{{
    * myEffect.provide(
    *   MetricsLive.otlp,
    *   Configs.makeLayer[TelemetryConfig]("register.telemetry")
    * )
    * }}}
    */
  val otlp: ZLayer[TelemetryConfig, Throwable, Meter & Instrument.Builder] =
    ZLayer.service[TelemetryConfig].flatMap { configEnv =>
      val config = configEnv.get
      otelSdkOtlpLayer(config) ++ OpenTelemetry.contextZIO >>> 
        OpenTelemetry.metrics(config.instrumentationScope)
    }
}

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
import java.time.Duration

/** OpenTelemetry metrics layer using ZIO Telemetry
  * 
  * Follows the same idiomatic pattern as TracingLive:
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
  
  /** Service name for OpenTelemetry resource attributes */
  private val ServiceName = "risk-register"
  
  /** Instrumentation scope name for meter */
  private val InstrumentationScopeName = "com.risquanter.register"
  
  /** Metric export interval for development (5 seconds) */
  private val DevMetricExportInterval = Duration.ofSeconds(5)
  
  /** Metric export interval for production (60 seconds) */
  private val ProdMetricExportInterval = Duration.ofSeconds(60)
  
  /** Default OTLP endpoint (standard OpenTelemetry Collector port) */
  private val DefaultOtlpEndpoint = "http://localhost:4317"
  
  /** Build SdkMeterProvider with logging exporter
    * 
    * Scoped resource - automatically closed when scope ends.
    * Uses PeriodicMetricReader for batch export.
    */
  private val stdoutMeterProvider: RIO[Scope, SdkMeterProvider] =
    for {
      metricExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(LoggingMetricExporter.create())
      )
      metricReader <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          PeriodicMetricReader.builder(metricExporter)
            .setInterval(DevMetricExportInterval)
            .build()
        )
      )
      meterProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkMeterProvider.builder()
            .setResource(
              Resource.create(
                Attributes.of(ServiceAttributes.SERVICE_NAME, ServiceName)
              )
            )
            .registerMetricReader(metricReader)
            .build()
        )
      )
    } yield meterProvider
  
  /** OpenTelemetry SDK layer with metrics only (console/logging exporter)
    * 
    * This is the foundation layer that provides api.OpenTelemetry with metrics.
    */
  val otelSdkLayer: TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        meterProvider <- stdoutMeterProvider
        sdk <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            OpenTelemetrySdk.builder()
              .setMeterProvider(meterProvider)
              .build()
          )
        )
      } yield sdk
    )
  
  /** Complete metrics layer for development
    * 
    * Composes:
    * - otelSdkLayer: Provides configured OpenTelemetry SDK with metrics
    * - OpenTelemetry.metrics: Provides Meter service
    * - OpenTelemetry.contextZIO: Provides fiber-based ContextStorage
    * 
    * Usage:
    * {{{
    * myEffect.provide(MetricsLive.console)
    * }}}
    */
  val console: ZLayer[Any, Throwable, Meter & Instrument.Builder] =
    otelSdkLayer ++ OpenTelemetry.contextZIO >>> OpenTelemetry.metrics(InstrumentationScopeName)
  
  // ===== OTLP Configuration (Production) =====
  
  /** Build SdkMeterProvider with OTLP gRPC exporter */
  private def otlpMeterProvider(endpoint: String): RIO[Scope, SdkMeterProvider] =
    for {
      metricExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcMetricExporter.builder()
            .setEndpoint(endpoint)
            .build()
        )
      )
      metricReader <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          PeriodicMetricReader.builder(metricExporter)
            .setInterval(ProdMetricExportInterval)
            .build()
        )
      )
      meterProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkMeterProvider.builder()
            .setResource(
              Resource.create(
                Attributes.of(ServiceAttributes.SERVICE_NAME, ServiceName)
              )
            )
            .registerMetricReader(metricReader)
            .build()
        )
      )
    } yield meterProvider
  
  /** OpenTelemetry SDK layer with OTLP metrics exporter */
  private def otelSdkOtlpLayer(endpoint: String): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        meterProvider <- otlpMeterProvider(endpoint)
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
    * Default endpoint: http://localhost:4317 (OpenTelemetry Collector)
    * Export interval: 60 seconds (production)
    * 
    * Usage:
    * {{{
    * myEffect.provide(MetricsLive.otlp())
    * // or with custom endpoint
    * myEffect.provide(MetricsLive.otlp("http://collector:4317"))
    * }}}
    */
  def otlp(endpoint: String = DefaultOtlpEndpoint): ZLayer[Any, Throwable, Meter & Instrument.Builder] =
    otelSdkOtlpLayer(endpoint) ++ OpenTelemetry.contextZIO >>> OpenTelemetry.metrics(InstrumentationScopeName)
}

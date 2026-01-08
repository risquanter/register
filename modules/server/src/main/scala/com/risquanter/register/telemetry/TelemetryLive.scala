package com.risquanter.register.telemetry

import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.Instrument
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{SimpleSpanProcessor, BatchSpanProcessor}
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader
import io.opentelemetry.exporter.logging.{LoggingSpanExporter, LoggingMetricExporter}
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api
import com.risquanter.register.configs.TelemetryConfig

/** Combined OpenTelemetry layer providing both Tracing and Metrics
  * 
  * Configuration-driven: all settings come from TelemetryConfig.
  * 
  * Uses a single OpenTelemetry SDK instance with both providers,
  * which is more efficient than separate layers and ensures
  * consistent resource attributes across all signals.
  * 
  * Provides two configurations:
  * - `console`: Logging exporters for development
  * - `otlp`: OTLP gRPC exporters for production
  * 
  * Pattern follows official zio-telemetry documentation:
  * - OpenTelemetry.custom() for SDK configuration
  * - OpenTelemetry.tracing() + OpenTelemetry.metrics() for services
  * - OpenTelemetry.contextZIO for fiber-based context storage
  */
object TelemetryLive {
  
  /** Build OpenTelemetry resource with service name from config */
  private def otelResource(config: TelemetryConfig): Resource =
    Resource.create(
      Attributes.of(ServiceAttributes.SERVICE_NAME, config.serviceName)
    )
  
  /** Build combined SDK with both tracing and metrics (console exporters)
    * 
    * Creates a single OpenTelemetry SDK with:
    * - SdkTracerProvider with LoggingSpanExporter
    * - SdkMeterProvider with LoggingMetricExporter
    * 
    * All resources are scoped for proper cleanup.
    */
  private def consoleOtelSdk(config: TelemetryConfig): RIO[Scope, OpenTelemetrySdk] =
    for {
      // Tracing components
      spanExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(LoggingSpanExporter.create())
      )
      spanProcessor <- ZIO.fromAutoCloseable(
        ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
      )
      tracerProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkTracerProvider.builder()
            .setResource(otelResource(config))
            .addSpanProcessor(spanProcessor)
            .build()
        )
      )
      
      // Metrics components
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
      
      // Combined SDK
      sdk <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build()
        )
      )
    } yield sdk
  
  /** OpenTelemetry SDK layer with both tracing and metrics (console exporters) */
  private def otelSdkConsoleLayer(config: TelemetryConfig): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(consoleOtelSdk(config))
  
  /** Complete telemetry layer providing Tracing + Meter (console exporters)
    * 
    * Usage:
    * {{{
    * myEffect.provide(
    *   TelemetryLive.console,
    *   Configs.makeLayer[TelemetryConfig]("register.telemetry")
    * )
    * }}}
    * 
    * This is preferred over using TracingLive.console + MetricsLive.console
    * separately, as it uses a single SDK instance.
    */
  val console: ZLayer[TelemetryConfig, Throwable, Tracing & Meter & Instrument.Builder] =
    ZLayer.service[TelemetryConfig].flatMap { configEnv =>
      val config = configEnv.get
      val contextLayer = OpenTelemetry.contextZIO
      val tracingLayer = OpenTelemetry.tracing(config.instrumentationScope)
      val metricsLayer = OpenTelemetry.metrics(config.instrumentationScope)
      
      otelSdkConsoleLayer(config) ++ contextLayer >>> (tracingLayer ++ metricsLayer)
    }
  
  /** Tracing-only layer (console exporter) - for backward compatibility */
  val tracingConsole: ZLayer[TelemetryConfig, Throwable, Tracing] =
    ZLayer.service[TelemetryConfig].flatMap { configEnv =>
      val config = configEnv.get
      otelSdkConsoleLayer(config) ++ OpenTelemetry.contextZIO >>> 
        OpenTelemetry.tracing(config.instrumentationScope)
    }
  
  /** Metrics-only layer (console exporter) */
  val metricsConsole: ZLayer[TelemetryConfig, Throwable, Meter & Instrument.Builder] =
    ZLayer.service[TelemetryConfig].flatMap { configEnv =>
      val config = configEnv.get
      otelSdkConsoleLayer(config) ++ OpenTelemetry.contextZIO >>> 
        OpenTelemetry.metrics(config.instrumentationScope)
    }
  
  // ===== OTLP Configuration (Production) =====
  
  /** Build combined SDK with both tracing and metrics (OTLP exporters) */
  private def otlpOtelSdk(config: TelemetryConfig): RIO[Scope, OpenTelemetrySdk] =
    for {
      // Tracing components (batched for production)
      spanExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcSpanExporter.builder()
            .setEndpoint(config.otlpEndpoint)
            .build()
        )
      )
      spanProcessor <- ZIO.fromAutoCloseable(
        ZIO.succeed(BatchSpanProcessor.builder(spanExporter).build())
      )
      tracerProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkTracerProvider.builder()
            .setResource(otelResource(config))
            .addSpanProcessor(spanProcessor)
            .build()
        )
      )
      
      // Metrics components
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
      
      // Combined SDK
      sdk <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build()
        )
      )
    } yield sdk
  
  /** OpenTelemetry SDK layer with OTLP exporters */
  private def otelSdkOtlpLayer(config: TelemetryConfig): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(otlpOtelSdk(config))
  
  /** Complete telemetry layer for production (OTLP export)
    * 
    * Uses OTLP gRPC exporters for both traces and metrics.
    * Endpoint from TelemetryConfig (default: http://localhost:4317)
    * 
    * Override endpoint via:
    * - application.conf: register.telemetry.otlpEndpoint
    * - Environment: OTEL_EXPORTER_OTLP_ENDPOINT
    * 
    * Usage:
    * {{{
    * myEffect.provide(
    *   TelemetryLive.otlp,
    *   Configs.makeLayer[TelemetryConfig]("register.telemetry")
    * )
    * }}}
    */
  val otlp: ZLayer[TelemetryConfig, Throwable, Tracing & Meter & Instrument.Builder] =
    ZLayer.service[TelemetryConfig].flatMap { configEnv =>
      val config = configEnv.get
      val contextLayer = OpenTelemetry.contextZIO
      val tracingLayer = OpenTelemetry.tracing(config.instrumentationScope)
      val metricsLayer = OpenTelemetry.metrics(config.instrumentationScope)
      
      otelSdkOtlpLayer(config) ++ contextLayer >>> (tracingLayer ++ metricsLayer)
    }
}

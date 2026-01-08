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
import java.time.Duration

/** Combined OpenTelemetry layer providing both Tracing and Metrics
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
  
  /** Service name for OpenTelemetry resource attributes */
  private val ServiceName = "risk-register"
  
  /** Instrumentation scope name */
  private val InstrumentationScopeName = "com.risquanter.register"
  
  /** Metric export interval for development (5 seconds) */
  private val DevMetricExportInterval = Duration.ofSeconds(5)
  
  /** Metric export interval for production (60 seconds) */
  private val ProdMetricExportInterval = Duration.ofSeconds(60)
  
  /** Default OTLP endpoint */
  private val DefaultOtlpEndpoint = "http://localhost:4317"
  
  /** Shared resource for all providers */
  private val otelResource: Resource =
    Resource.create(
      Attributes.of(ServiceAttributes.SERVICE_NAME, ServiceName)
    )
  
  /** Build combined SDK with both tracing and metrics (console exporters)
    * 
    * Creates a single OpenTelemetry SDK with:
    * - SdkTracerProvider with LoggingSpanExporter
    * - SdkMeterProvider with LoggingMetricExporter
    * 
    * All resources are scoped for proper cleanup.
    */
  private val consoleOtelSdk: RIO[Scope, OpenTelemetrySdk] =
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
            .setResource(otelResource)
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
            .setInterval(DevMetricExportInterval)
            .build()
        )
      )
      meterProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkMeterProvider.builder()
            .setResource(otelResource)
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
  val otelSdkConsoleLayer: TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(consoleOtelSdk)
  
  /** Complete telemetry layer providing Tracing + Meter (console exporters)
    * 
    * Usage:
    * {{{
    * myEffect.provide(TelemetryLive.console)
    * }}}
    * 
    * This is preferred over using TracingLive.console + MetricsLive.console
    * separately, as it uses a single SDK instance.
    */
  val console: ZLayer[Any, Throwable, Tracing & Meter & Instrument.Builder] = {
    val contextLayer = OpenTelemetry.contextZIO
    val tracingLayer = OpenTelemetry.tracing(InstrumentationScopeName)
    val metricsLayer = OpenTelemetry.metrics(InstrumentationScopeName)
    
    otelSdkConsoleLayer ++ contextLayer >>> (tracingLayer ++ metricsLayer)
  }
  
  /** Tracing-only layer (console exporter) - for backward compatibility */
  val tracingConsole: ZLayer[Any, Throwable, Tracing] =
    otelSdkConsoleLayer ++ OpenTelemetry.contextZIO >>> OpenTelemetry.tracing(InstrumentationScopeName)
  
  /** Metrics-only layer (console exporter) */
  val metricsConsole: ZLayer[Any, Throwable, Meter & Instrument.Builder] =
    otelSdkConsoleLayer ++ OpenTelemetry.contextZIO >>> OpenTelemetry.metrics(InstrumentationScopeName)
  
  // ===== OTLP Configuration (Production) =====
  
  /** Build combined SDK with both tracing and metrics (OTLP exporters) */
  private def otlpOtelSdk(endpoint: String): RIO[Scope, OpenTelemetrySdk] =
    for {
      // Tracing components (batched for production)
      spanExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            .build()
        )
      )
      spanProcessor <- ZIO.fromAutoCloseable(
        ZIO.succeed(BatchSpanProcessor.builder(spanExporter).build())
      )
      tracerProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkTracerProvider.builder()
            .setResource(otelResource)
            .addSpanProcessor(spanProcessor)
            .build()
        )
      )
      
      // Metrics components
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
            .setResource(otelResource)
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
  private def otelSdkOtlpLayer(endpoint: String): TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(otlpOtelSdk(endpoint))
  
  /** Complete telemetry layer for production (OTLP export)
    * 
    * Uses OTLP gRPC exporters for both traces and metrics.
    * Default endpoint: http://localhost:4317 (OpenTelemetry Collector)
    * 
    * Usage:
    * {{{
    * myEffect.provide(TelemetryLive.otlp())
    * // or with custom endpoint
    * myEffect.provide(TelemetryLive.otlp("http://collector:4317"))
    * }}}
    */
  def otlp(endpoint: String = DefaultOtlpEndpoint): ZLayer[Any, Throwable, Tracing & Meter & Instrument.Builder] = {
    val contextLayer = OpenTelemetry.contextZIO
    val tracingLayer = OpenTelemetry.tracing(InstrumentationScopeName)
    val metricsLayer = OpenTelemetry.metrics(InstrumentationScopeName)
    
    otelSdkOtlpLayer(endpoint) ++ contextLayer >>> (tracingLayer ++ metricsLayer)
  }
}

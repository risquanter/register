package com.risquanter.register.telemetry

import java.time.Duration

import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import zio.telemetry.opentelemetry.metrics.internal.Instrument
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.{SdkTracerProvider, SpanProcessor}
import io.opentelemetry.sdk.trace.`export`.{SimpleSpanProcessor, BatchSpanProcessor, SpanExporter}
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.{PeriodicMetricReader, MetricExporter}
import io.opentelemetry.exporter.logging.{LoggingSpanExporter, LoggingMetricExporter}
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api
import com.risquanter.register.configs.{TelemetryConfig, TelemetryExporter}
import com.risquanter.register.domain.data.iron.Url.*

/** The one OpenTelemetry layer, providing tracing and metrics from a single SDK
  * instance so both signals carry the same resource attributes.
  *
  * `configured` is the only entry point. It reads `TelemetryConfig.exporter` and
  * builds the SDK for that exporter, so where spans and metrics go is decided by
  * configuration alone and nothing selects an exporter by naming a layer. A test
  * that needs a particular exporter sets the field in its own `TelemetryConfig`.
  *
  * The composition follows zio-telemetry's documented pattern:
  * `OpenTelemetry.custom` configures the SDK, `OpenTelemetry.tracing` and
  * `OpenTelemetry.metrics` expose the services, and `OpenTelemetry.contextZIO`
  * keeps the span context in a fibre reference.
  */
object TelemetryLive {

  /** The four pieces of the SDK that the exporter choice decides. They are
    * produced together because one configuration value fixes all of them, so
    * there is no state in which they can disagree about which exporter is in
    * use. `toSpanProcessor` is a function rather than a value because the
    * processor wraps the span exporter, and the exporter is registered for
    * release before the processor is built.
    */
  private final case class ExporterSetup(
    spanExporter: SpanExporter,
    toSpanProcessor: SpanExporter => SpanProcessor,
    metricExporter: MetricExporter,
    metricInterval: Duration
  )

  /** Console prints each span as it ends and flushes metrics on the development
    * interval. OTLP (OpenTelemetry Protocol) batches spans and sends both
    * signals over gRPC to the collector at the configured endpoint, on the
    * production interval.
    */
  private def exporterSetup(config: TelemetryConfig, exporter: TelemetryExporter): ExporterSetup =
    exporter match
      case TelemetryExporter.Console =>
        ExporterSetup(
          spanExporter    = LoggingSpanExporter.create(),
          toSpanProcessor = SimpleSpanProcessor.create(_),
          metricExporter  = LoggingMetricExporter.create(),
          metricInterval  = config.devExportInterval
        )
      case TelemetryExporter.Otlp =>
        ExporterSetup(
          spanExporter    = OtlpGrpcSpanExporter.builder()
                              .setEndpoint(config.otlpEndpoint.value)
                              .build(),
          toSpanProcessor = BatchSpanProcessor.builder(_).build(),
          metricExporter  = OtlpGrpcMetricExporter.builder()
                              .setEndpoint(config.otlpEndpoint.value)
                              .build(),
          metricInterval  = config.prodExportInterval
        )

  /** The resource attributes every span and metric carries, identifying which
    * service produced it. */
  private def otelResource(config: TelemetryConfig): Resource =
    Resource.create(
      Attributes.of(ServiceAttributes.SERVICE_NAME, config.serviceName)
    )

  /** One SDK carrying both a tracer provider and a meter provider. Every
    * component is registered for release with the scope, so shutting the scope
    * flushes and closes the exporters.
    */
  private def otelSdk(config: TelemetryConfig, exporter: TelemetryExporter): RIO[Scope, OpenTelemetrySdk] =
    for {
      setup <- ZIO.succeed(exporterSetup(config, exporter))

      spanExporter <- ZIO.fromAutoCloseable(ZIO.succeed(setup.spanExporter))
      spanProcessor <- ZIO.fromAutoCloseable(
        ZIO.succeed(setup.toSpanProcessor(spanExporter))
      )
      tracerProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkTracerProvider.builder()
            .setResource(otelResource(config))
            .addSpanProcessor(spanProcessor)
            .build()
        )
      )

      metricExporter <- ZIO.fromAutoCloseable(ZIO.succeed(setup.metricExporter))
      metricReader <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          PeriodicMetricReader.builder(metricExporter)
            .setInterval(setup.metricInterval)
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

      sdk <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build()
        )
      )
    } yield sdk

  /** Tracing and metrics over one SDK built for the given exporter. */
  private def telemetryLayer(
    config: TelemetryConfig,
    exporter: TelemetryExporter
  ): ZLayer[Any, Throwable, Tracing & Meter & Instrument.Builder] =
    val sdkLayer: TaskLayer[api.OpenTelemetry] = OpenTelemetry.custom(otelSdk(config, exporter))
    val tracingLayer = OpenTelemetry.tracing(config.instrumentationScope)
    val metricsLayer = OpenTelemetry.metrics(config.instrumentationScope)

    sdkLayer ++ OpenTelemetry.contextZIO >>> (tracingLayer ++ metricsLayer)

  /** The combined tracing and metrics layer for the configured exporter.
    *
    * Usage:
    * {{{
    * myEffect.provide(
    *   TelemetryLive.configured,
    *   Configs.makeLayer[TelemetryConfig]("register.telemetry")
    * )
    * }}}
    */
  val configured: ZLayer[TelemetryConfig, Throwable, Tracing & Meter & Instrument.Builder] =
    ZLayer.service[TelemetryConfig].flatMap { configEnv =>
      val config = configEnv.get
      telemetryLayer(config, config.exporter)
    }
}

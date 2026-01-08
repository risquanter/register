package com.risquanter.register.telemetry

import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api

/** OpenTelemetry tracing layer using ZIO Telemetry:
  * - Uses OpenTelemetry.custom() for SDK configuration
  * - Uses OpenTelemetry.tracing() for Tracing layer
  * - Uses OpenTelemetry.contextZIO for fiber-based context storage
  * - Resource-safe with ZIO.fromAutoCloseable
  *   * 
  * Uses logging exporter for development (console output).
  * Production should use OTLP exporter.
  */
object TracingLive {
  
  /** Service name for OpenTelemetry resource attributes */
  private val ServiceName = "risk-register"
  
  /** Instrumentation scope name for tracer */
  private val InstrumentationScopeName = "com.risquanter.register"
  
  /** Build SdkTracerProvider with logging exporter
    * 
    * Scoped resource - automatically closed when scope ends.
    * Uses SimpleSpanProcessor for immediate export (dev only).
    */
  private val stdoutTracerProvider: RIO[Scope, SdkTracerProvider] =
    for {
      spanExporter <- ZIO.fromAutoCloseable(
        ZIO.succeed(LoggingSpanExporter.create())
      )
      spanProcessor <- ZIO.fromAutoCloseable(
        ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
      )
      tracerProvider <- ZIO.fromAutoCloseable(
        ZIO.succeed(
          SdkTracerProvider.builder()
            .setResource(
              Resource.create(
                Attributes.of(ServiceAttributes.SERVICE_NAME, ServiceName)
              )
            )
            .addSpanProcessor(spanProcessor)
            .build()
        )
      )
    } yield tracerProvider
  
  /** OpenTelemetry SDK layer with console/logging exporter
    * 
    * This is the foundation layer that provides api.OpenTelemetry.
    * Uses OpenTelemetry.custom() as per official documentation.
    */
  val otelSdkLayer: TaskLayer[api.OpenTelemetry] =
    OpenTelemetry.custom(
      for {
        tracerProvider <- stdoutTracerProvider
        sdk <- ZIO.fromAutoCloseable(
          ZIO.succeed(
            OpenTelemetrySdk.builder()
              .setTracerProvider(tracerProvider)
              .build()
          )
        )
      } yield sdk
    )
  
  /** Complete tracing layer for development
    * 
    * Composes:
    * - otelSdkLayer: Provides configured OpenTelemetry SDK
    * - OpenTelemetry.tracing: Provides Tracing service
    * - OpenTelemetry.contextZIO: Provides fiber-based ContextStorage
    * 
    * Usage:
    * {{{
    * myEffect.provide(TracingLive.console)
    * }}}
    */
  val console: ZLayer[Any, Throwable, Tracing] =
    otelSdkLayer ++ OpenTelemetry.contextZIO >>> OpenTelemetry.tracing(InstrumentationScopeName)
  
  /** Create span with name and kind
    * 
    * Pure function - returns ZIO effect that creates span.
    * Span is automatically closed when effect completes.
    * 
    * @param name Span operation name
    * @param spanKind Span kind (SERVER, CLIENT, INTERNAL, etc.)
    * @param effect Effect to run within span
    * @return Result of effect with tracing context
    */
  def span[R, E, A](
    name: String,
    spanKind: SpanKind = SpanKind.INTERNAL
  )(effect: ZIO[R, E, A]): ZIO[R & Tracing, E, A] =
    ZIO.serviceWithZIO[Tracing] { tracing =>
      tracing.span(name, spanKind)(effect)
    }
  
  /** Set attribute on current span
    * 
    * Pure function - returns ZIO effect that sets attribute.
    * Type-safe attribute setting via OpenTelemetry semantic conventions.
    * 
    * @param key Attribute key
    * @param value Attribute value
    */
  def setAttribute(key: String, value: String): ZIO[Tracing, Nothing, Unit] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(key, value))
  
  /** Set attribute with long value */
  def setAttribute(key: String, value: Long): ZIO[Tracing, Nothing, Unit] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(key, value))
  
  /** Set attribute with boolean value */
  def setAttribute(key: String, value: Boolean): ZIO[Tracing, Nothing, Unit] =
    ZIO.serviceWithZIO[Tracing](_.setAttribute(key, value))
  
  /** Add event to current span
    * 
    * Events represent point-in-time occurrences within span.
    * Useful for marking significant moments in operation.
    * 
    * @param name Event name
    */
  def addEvent(name: String): ZIO[Tracing, Nothing, Unit] =
    ZIO.serviceWithZIO[Tracing](_.addEvent(name))
}

package com.risquanter.register.http.controllers

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.domain.data.iron.{TelemetryPlaceholders, TraceId}
import com.risquanter.register.domain.errors.AppError
import com.risquanter.register.http.errors.WireError

/** The one place a controller's failure becomes a wire failure.
  *
  * `serverLogic` wants a function from the input to an effect of
  * `Either[E, O]`, where `E` is the endpoint's declared error output. Every
  * controller method therefore ends in `.wireEither`, so the projection happens
  * once per method and in one expression.
  */
extension [R, A](effect: ZIO[R, AppError, A])
  /** Project the typed domain failure onto the wire type, naming the trace the
    * failing work ran under.
    *
    * This is where the trace is read, and it is the only place it can be: the
    * active span lives in a fibre reference, so reading it requires an effect,
    * and a failure value constructed in pure code deep in a service cannot
    * carry one. A trace identifier is constant across a request's whole span
    * tree, so the value read here is the same one the failing work ran under.
    */
  def wireEither(using tracing: Tracing): ZIO[R, Nothing, Either[WireError, A]] =
    effect.either.flatMap {
      case Right(value) => ZIO.succeed(Right(value))
      case Left(error)  => currentTraceId.map(trace => Left(WireError.fromAppError(error, trace)))
    }

/** The trace identifier of the active span.
  *
  * With no active span OpenTelemetry returns its invalid span context, whose
  * identifier is thirty-two zeros — the value `TelemetryPlaceholders`
  * recognises as unlinked. So a failure outside any span names an unlinked
  * trace rather than a plausible identifier that resolves to nothing.
  */
private def currentTraceId(using tracing: Tracing): UIO[TraceId] =
  tracing.getCurrentSpanContextUnsafe.map { ctx =>
    TraceId.fromString(ctx.getTraceId).toOption.getOrElse(TelemetryPlaceholders.UnlinkedTrace)
  }

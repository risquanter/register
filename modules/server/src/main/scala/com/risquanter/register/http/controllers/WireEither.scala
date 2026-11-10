package com.risquanter.register.http.controllers

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.domain.data.iron.TraceId
import com.risquanter.register.domain.errors.{AppError, Failure, FailureOrigin}
import com.risquanter.register.http.RequestSpanInterceptor
import com.risquanter.register.http.errors.{TreeScope, WireError}
import com.risquanter.register.telemetry.ActiveTrace

/** Write one log line for a failure that is about to become a response.
  *
  * The level comes from the failure's own `origin`, so a caller-caused failure
  * is recorded as ordinary traffic and a service-caused one is an operational
  * signal (ADR-002). `Caller` is `INFO` rather than `DEBUG` deliberately: the
  * deployment's log level defaults to `INFO`, and at `DEBUG` these lines would
  * be absent from a default deployment.
  *
  * Every line carries `trace_id`, which is the same identifier the response
  * body carries, so a failure a caller reports can be found in the log.
  */
private def logProjectedFailure(error: AppError | Failure, trace: TraceId): UIO[Unit] =
  val (origin, message, annotations) = error match
    case e: AppError => (e.origin, e.logMessage, e.logAnnotations)
    case f: Failure  => (f.origin, f.logMessage, f.logAnnotations)
  val line: UIO[Unit] = origin match
    case FailureOrigin.Caller  => ZIO.logInfo(message)
    case FailureOrigin.Service => ZIO.logWarning(message)
  (annotations + (RequestSpanInterceptor.TraceAnnotation -> trace.value)).foldRight(line) {
    case ((k, v), effect) => ZIO.logAnnotate(k, v)(effect)
  }

/** The one place a controller's failure becomes a wire failure, and the one
  * place a failure that becomes a response is logged.
  *
  * `serverLogic` wants a function from the input to an effect of
  * `Either[E, O]`, where `E` is the endpoint's declared error output. Every
  * controller method therefore ends in `.wireEither`, so the projection happens
  * once per method and in one expression.
  *
  * The error channel admits both failure types while the codebase moves from
  * `AppError` to `Failure`; each is projected by its own projection.
  */
extension [R, A](effect: ZIO[R, AppError | Failure, A])
  /** Project the typed domain failure onto the wire type, naming the trace the
    * failing work ran under.
    *
    * This is the only place a controller's failure can pick up a trace: the
    * active span lives in a fibre reference, so reading it requires an effect,
    * and a failure value constructed in pure code deep in a service cannot
    * carry one. A trace identifier is constant across a request's whole span
    * tree, so the value read here is the same one the failing work ran under.
    *
    * `scope` names the tree the failure is about, and the caller supplies it
    * for the same reason: a failure carries no tree, while the handler calling
    * this has one in hand whenever the route named it.
    */
  def wireEither(scope: TreeScope)(using tracing: Tracing): ZIO[R, Nothing, Either[WireError, A]] =
    effect
      .map(Right(_))
      .catchAll(error =>
        ActiveTrace.id.flatMap(trace =>
          logProjectedFailure(error, trace).as(Left(projectOntoWire(error, trace, scope)))
        )
      )

/** Each failure type by its own one-way projection. */
private def projectOntoWire(error: AppError | Failure, trace: TraceId, scope: TreeScope): WireError =
  error match
    case e: AppError => WireError.fromAppError(e, trace, scope)
    case f: Failure  => f.toWire(trace, scope)

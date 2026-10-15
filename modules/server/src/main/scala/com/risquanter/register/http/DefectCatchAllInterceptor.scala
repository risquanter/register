package com.risquanter.register.http

import sttp.capabilities.StreamMaxLengthExceededException
import sttp.monad.MonadError
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, RequestInterceptor, RequestResult, Responder, ResponseSource}
import sttp.tapir.server.interceptor.exception.{DefaultExceptionHandler, ExceptionContext, ExceptionHandler}
import sttp.tapir.server.model.{InvalidMultipartBodyException, ValuedEndpointOutput}
import zio.{Cause, Task, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.domain.data.iron.TraceId
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.telemetry.ActiveTrace

/** Tapir request interceptor that answers any failure reaching no endpoint's
  * declared output, so a failure leaving endpoint logic has a declared shape
  * whether it was typed or a defect.
  *
  * Without it the zio-http interpreter folds the cause itself and writes
  * `cause.squash.getMessage` into a plain-text body, which publishes an
  * exception message and a shape no client decodes.
  *
  * Two answers still come from Tapir rather than from here, and both carry a
  * constant body: a request matching no route answers `404`, and one whose
  * method does not match answers `405`. Neither reports a failure of this
  * service, so neither carries a trace worth delivering.
  *
  * It is connected with `appendInterceptor`, which makes it the innermost
  * request interceptor, and the position is load-bearing. Tapir enters the
  * first interceptor in the stack first and leaves it last, so a response is
  * decorated on the way out by every interceptor outside the one that built it.
  * The CORS interceptor adds its headers that way. Placed outside CORS this
  * interceptor would build a response CORS never sees, and a browser would
  * reject it on the cross-origin check before any script could read the body —
  * losing exactly the trace the response exists to deliver. Being innermost
  * also puts it inside `SecurityHeadersInterceptor`, so a defect response
  * carries the same headers as every other.
  *
  * `RequestSpanInterceptor` stays outside it and owns the span and the log
  * annotation. That ordering is what lets the cause logged here appear under
  * the trace the response carries.
  */
object DefectCatchAllInterceptor:

  /** Creates the interceptor, ready to pass to
    * `ZioHttpServerOptions.customiseInterceptors.appendInterceptor(…)`.
    *
    * `Tracing` is a parameter rather than an environment requirement because a
    * Tapir interceptor's effect type is `Task`, whose environment is `Any`.
    */
  def interceptor(tracing: Tracing): RequestInterceptor[Task] =
    new RequestInterceptor[Task]:
      override def apply[R, B](
        responder: Responder[Task, B],
        requestHandler: EndpointInterceptor[Task] => RequestHandler[Task, R, B]
      ): RequestHandler[Task, R, B] =
        new RequestHandler[Task, R, B]:
          override def apply(
            request: ServerRequest,
            endpoints: List[ServerEndpoint[R, Task]]
          )(implicit monad: MonadError[Task]): Task[RequestResult[B]] =
            val downstream = requestHandler(EndpointInterceptor.noop)(request, endpoints)
            ActiveTrace.id(using tracing).flatMap { trace =>
              downstream.catchAllCause { cause =>
                if cause.isInterruptedOnly then ZIO.refailCause(cause)
                else answerDefect(responder, request, trace, cause)
              }
            }

  /** The exception handler that lets a typed failure reach this interceptor.
    *
    * Tapir's own handler answers every exception itself, and it sits inside this
    * one, so without this a failure escaping endpoint logic would be answered as
    * plain text with no trace and this interceptor would see only a defect.
    * `ExceptionInterceptor` re-raises when a handler answers `None`, which is
    * what passes the failure outwards to the `catchAllCause` above.
    *
    * The two cases Tapir answers specially are delegated to it rather than
    * re-raised, because the status belongs to the request: a body over its
    * declared maximum is `413` and a malformed multipart body is `400`, and
    * answering either as `500` would be wrong. Neither is reachable while no
    * endpoint declares a stream or multipart body, which
    * `DecodeFailureStatusSurfaceSpec` is what keeps true. The list mirrors
    * `DefaultExceptionHandler`'s own special arms and is revisited when Tapir is
    * upgraded.
    */
  val exceptionHandler: ExceptionHandler[Task] =
    new ExceptionHandler[Task]:
      private val tapirDefault = DefaultExceptionHandler[Task]

      override def apply(ctx: ExceptionContext)(using
        monad: MonadError[Task]
      ): Task[Option[ValuedEndpointOutput[?]]] =
        (ctx.e, ctx.e.getCause) match
          case (StreamMaxLengthExceededException(_), _) => tapirDefault(ctx)
          case (_, StreamMaxLengthExceededException(_)) => tapirDefault(ctx)
          case (InvalidMultipartBodyException(_, _), _) => tapirDefault(ctx)
          case _                                        => ZIO.none

  /** Answer a failure that reached no endpoint's declared output, with the `500`
    * output the endpoints themselves declare.
    *
    * Nothing about the failure crosses: the cause is logged under the same trace
    * the response carries, and the body holds the identifier alone (ADR-035).
    * The response is built from `BaseEndpoint.internalFaultOut`, so a failure
    * answered here and one projected by `wireEither` are the same shape to a
    * client.
    *
    * An interruption is not answered here. The caller has gone, so there is
    * nothing to answer, and treating it as a fault would log a request the
    * client itself cancelled.
    */
  private def answerDefect[B](
    responder: Responder[Task, B],
    request:   ServerRequest,
    trace:     TraceId,
    cause:     Cause[Throwable]
  ): Task[RequestResult[B]] =
    ZIO.logErrorCause("request failed with no declared output", cause) *>
      responder(
        request,
        ValuedEndpointOutput(BaseEndpoint.internalFaultOut, WireError.InternalFault(trace))
      ).map(response => RequestResult.Response(response, ResponseSource.EndpointHandler))

package com.risquanter.register.http

import io.opentelemetry.api.trace.SpanKind
import sttp.monad.MonadError
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, RequestInterceptor, RequestResult, Responder, ResponseSource}
import sttp.tapir.server.model.ValuedEndpointOutput
import zio.{Cause, Task, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.domain.data.iron.TraceId
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.telemetry.ActiveTrace

/** Tapir request interceptor that opens one SERVER span per request, and answers
  * any failure that reaches no endpoint's declared output.
  *
  * The span is the root of the request's trace, so every span a service opens
  * below it is a child and carries the same trace identifier. That identifier is
  * what the boundary reads when it projects a failure onto the wire, and what a
  * user can quote from the screen.
  *
  * Every log line written while the request runs is annotated with the same
  * identifier, so a quoted value resolves to that request's own lines. The
  * annotation travels in a fibre reference, which is how it reaches code running
  * on forked fibres without being passed to it.
  *
  * The second responsibility is here rather than in a handler of its own because
  * this interceptor is the outermost one, so it is the only place that sees every
  * request whatever happened to it, and because answering such a failure needs
  * the trace this one already holds.
  *
  * This is the general `RequestInterceptor` form rather than `transformResult`,
  * which `SecurityHeadersInterceptor` uses. The span has to be open while
  * downstream handling runs; `transformResult` only sees the result after that
  * handling has finished, so it has nothing left to wrap.
  */
object RequestSpanInterceptor:

  /** The log annotation key carrying the trace identifier. Snake case matches
    * the annotation keys the workspace store already writes.
    */
  private val TraceAnnotation = "trace_id"

  /** Stands in for the path segment holding a workspace capability key. A span
    * name is exported to the collector, and the key is a bearer credential, so
    * the segment is never reproduced in one (ADR-021).
    */
  private val RedactedKeySegment = "{key}"

  /** Creates the interceptor, ready to pass to
    * `ZioHttpServerOptions.customiseInterceptors.prependInterceptor(…)`.
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
            tracing.span(spanName(request), SpanKind.SERVER) {
              ActiveTrace.id(using tracing).flatMap { trace =>
                ZIO.logAnnotate(TraceAnnotation, trace.value)(
                  downstream.foldCauseZIO(
                    cause =>
                      if cause.isInterruptedOnly then ZIO.refailCause(cause)
                      else answerDefect(responder, request, trace, cause),
                    success => ZIO.succeed(success)
                  )
                )
              }
            }

  /** The span's name: the HTTP method and the request's route family.
    *
    * Only the first path segment contributes, for two separate reasons. A
    * workspace-scoped path carries the capability key in its second segment, and
    * a span name is exported, so reproducing the path would publish the
    * credential. The later segments also hold per-resource identifiers, which
    * would make the name high-cardinality and useless for grouping.
    */
  private[http] def spanName(request: ServerRequest): String =
    val family = request.pathSegments match
      case "w" :: _     => s"/w/$RedactedKeySegment"
      case segment :: _ => s"/$segment"
      case Nil          => "/"
    s"${request.method.method} $family"

  /** Answer a failure that reached no endpoint's declared output, with the `500`
    * output the endpoints themselves declare.
    *
    * Nothing about the failure crosses: the cause is logged under the same trace
    * the response carries, and the body holds the identifier alone (ADR-035).
    * The response is built from `BaseEndpoint.internalFaultOut`, so a failure
    * answered here and one projected by `wireEither` are the same shape to a
    * client.
    *
    * Without this, the zio-http interpreter folds the cause itself and writes
    * `cause.squash.getMessage` into a plain-text body, which publishes an
    * exception message and a shape no client decodes.
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

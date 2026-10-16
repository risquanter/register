package com.risquanter.register.http

import io.opentelemetry.api.trace.SpanKind
import sttp.monad.MonadError
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, RequestInterceptor, RequestResult, Responder}
import zio.{Task, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.telemetry.ActiveTrace

/** Tapir request interceptor that opens one SERVER span per request.
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
  * Answering a failure that reaches no declared output belongs to
  * `DefectCatchAllInterceptor`, which is appended and therefore runs inside this
  * one. That nesting is what puts the cause it logs under this span's trace.
  *
  * This is the general `RequestInterceptor` form rather than `transformResult`,
  * which `SecurityHeadersInterceptor` uses. The span has to be open while
  * downstream handling runs; `transformResult` only sees the result after that
  * handling has finished, so it has nothing left to wrap.
  */
object RequestSpanInterceptor:

  /** The log annotation key carrying the trace identifier. Snake case matches
    * the annotation keys the workspace store already writes.
    *
    * Two places apply it: this interceptor, for every line written while the
    * request runs, and the projection boundary, for the line it writes about a
    * failure. One definition, so a line written by either is found by the same
    * query.
    */
  private[http] val TraceAnnotation = "trace_id"

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
                ZIO.logAnnotate(TraceAnnotation, trace.value)(downstream)
              }
            }

  /** The HTTP method and the request's route family, with no capability key.
    *
    * Only the first path segment contributes, for two separate reasons. A
    * workspace-scoped path carries the capability key in its second segment, and
    * a span name is exported, so reproducing the path would publish the
    * credential. The later segments also hold per-resource identifiers, which
    * would make the name high-cardinality and useless for grouping.
    *
    * Two sinks read it: the span this interceptor opens, and the server log,
    * whose default renders the whole request line and would otherwise write the
    * key to standard output. One definition, so neither sink can drift from the
    * other.
    */
  private[register] def spanName(request: ServerRequest): String =
    val family = request.pathSegments match
      case "w" :: _     => s"/w/$RedactedKeySegment"
      case segment :: _ => s"/$segment"
      case Nil          => "/"
    s"${request.method.method} $family"


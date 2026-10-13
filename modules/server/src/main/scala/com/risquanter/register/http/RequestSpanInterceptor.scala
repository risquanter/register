package com.risquanter.register.http

import io.opentelemetry.api.trace.SpanKind
import sttp.monad.MonadError
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, RequestInterceptor, RequestResult, Responder}
import zio.{Task, UIO, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.domain.data.iron.{TelemetryPlaceholders, TraceId}

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
              currentTrace(tracing).flatMap { trace =>
                ZIO.logAnnotate(TraceAnnotation, trace.value)(downstream)
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

  /** The active span's trace identifier.
    *
    * Reading it requires an effect because the OpenTelemetry context lives in a
    * fibre reference rather than a thread-local, which is what lets it survive
    * the async boundaries of a work-stealing executor. Inside this interceptor's
    * own span the read always succeeds; the placeholder is unreachable here and
    * is named rather than thrown so the function stays total.
    */
  private def currentTrace(tracing: Tracing): UIO[TraceId] =
    tracing.getCurrentSpanContextUnsafe.map { ctx =>
      TraceId.fromString(ctx.getTraceId).toOption.getOrElse(TelemetryPlaceholders.UnlinkedTrace)
    }

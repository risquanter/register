package com.risquanter.register.http

import sttp.monad.MonadError
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, RequestInterceptor, RequestResult, Responder, ResponseSource}
import sttp.tapir.server.model.ValuedEndpointOutput
import zio.Task
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.{NotFoundKind, WireError}
import com.risquanter.register.telemetry.ActiveTrace

/** Refuses a request whose path ends in a slash, which matches no route
  * (ADR-017 §7).
  *
  * A final slash leaves an empty last path segment. zio-http drops that segment
  * when it matches a route, so the slash form would otherwise reach the route
  * the same path without the slash names:
  * {{{
  * /workspaces    // segments: workspaces       -> passes on
  * /workspaces/   // segments: workspaces, ""   -> refused
  * }}}
  *
  * The answer is the one `HttpApi.noRouteEndpoint` sends for a path that fits no
  * route's pattern (ADR-035 §4).
  *
  * It is connected with `appendInterceptor`, which puts it inside CORS and the
  * security headers, so a browser can read the answer and the answer carries
  * those headers.
  */
object TrailingSlashInterceptor:

  /** Tracing is a parameter because a Tapir interceptor's effect type is `Task`,
    * whose environment is `Any`.
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
            request.uri.path.lastOption match
              case Some("") => answerNoRoute(responder, request, tracing)
              case _        => requestHandler(EndpointInterceptor.noop)(request, endpoints)

  private def answerNoRoute[B](
    responder: Responder[Task, B],
    request:   ServerRequest,
    tracing:   Tracing
  ): Task[RequestResult[B]] =
    ActiveTrace.id(using tracing).flatMap { trace =>
      responder(
        request,
        ValuedEndpointOutput(BaseEndpoint.notFoundOut, WireError.NotFound(NotFoundKind.Unknown(), trace))
      ).map(response => RequestResult.Response(response, ResponseSource.EndpointHandler))
    }

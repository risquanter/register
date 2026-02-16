package com.risquanter.register.http

import sttp.model.Header
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.{RequestInterceptor, RequestResult}
import zio.Task

/** Tapir request interceptor that appends security response headers.
  *
  * Two header sets are applied:
  *  - '''Global''' (A21–A23): `X-Frame-Options`, `X-XSS-Protection`,
  *    `Content-Security-Policy` — added to every response.
  *  - '''Workspace-specific''' (A1–A4): `Referrer-Policy`, `Cache-Control`,
  *    `X-Content-Type-Options`, `Strict-Transport-Security` — added only to
  *    responses for `/w/…` and `/workspaces` paths, to prevent workspace-key
  *    leakage.
  *
  * Implemented as a `RequestInterceptor.transformResult` that appends headers to
  * the `ServerResponse` after downstream processing. This mirrors the existing
  * CORS interceptor pattern — both are cross-cutting HTTP concerns configured in
  * `Application.startServer`.
  *
  * @see [[https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html OWASP HTTP Headers]]
  */
object SecurityHeadersInterceptor:

  // ── Header constants ───────────────────────────────────────────────

  /** Global security headers applied to every response (A21-A23). */
  private[http] val globalHeaders: Seq[Header] = Seq(
    Header("X-Frame-Options", "DENY"),
    Header("X-XSS-Protection", "0"),
    Header("Content-Security-Policy",
      "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'")
  )

  /** Workspace-specific headers for key leakage prevention (A1-A4). */
  private[http] val workspaceHeaders: Seq[Header] = Seq(
    Header("Referrer-Policy", "no-referrer"),
    Header("Cache-Control", "no-store"),
    Header("X-Content-Type-Options", "nosniff"),
    Header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
  )

  // ── Path matching ──────────────────────────────────────────────────

  /** Returns `true` for paths that carry a workspace key and therefore need
    * additional leakage-prevention headers.
    */
  private[http] def isWorkspacePath(segments: List[String]): Boolean =
    segments match
      case "w" :: _          => true   // /w/{key}/…
      case "workspaces" :: _ => true   // /workspaces (bootstrap)
      case _                 => false

  // ── Interceptor factory ────────────────────────────────────────────

  /** Creates the interceptor, ready to pass to
    * `ZioHttpServerOptions.customiseInterceptors.prependInterceptor(…)`.
    *
    * Prepend (not append) so this interceptor sees the response ''last'' — its
    * headers are added after all other interceptors have had their turn. Per
    * Tapir docs: "The first interceptor in the stack is called first on request,
    * and processes the resulting response as the last one."
    */
  val interceptor: RequestInterceptor[Task] =
    RequestInterceptor.transformResult[Task](
      new RequestInterceptor.RequestResultTransform[Task]:
        override def apply[B](request: ServerRequest, result: RequestResult[B]): Task[RequestResult[B]] =
          zio.ZIO.succeed {
            result match
              case RequestResult.Response(response, source) =>
                val extra =
                  if isWorkspacePath(request.pathSegments) then globalHeaders ++ workspaceHeaders
                  else globalHeaders
                RequestResult.Response(response.addHeaders(extra), source)
              case failure: RequestResult.Failure => failure
          }
    )

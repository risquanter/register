package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.test.Assertion.*

import sttp.model.{Header, StatusCode, Uri, Method, QueryParams}
import sttp.monad.{MonadError as SttpMonadError}
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.{EndpointInterceptor, RequestHandler, RequestResult, Responder, ResponseSource}
import sttp.tapir.server.model.{ServerResponse, ValuedEndpointOutput}
import sttp.tapir.ztapir.RIOMonadError
import sttp.tapir.AttributeKey
import sttp.tapir.model.ConnectionInfo

object SecurityHeadersInterceptorSpec extends ZIOSpecDefault:

  // ── Test helpers ─────────────────────────────────────────────────────

  /** Minimal `ServerRequest` stub that only exposes `pathSegments`. */
  private def stubRequest(segments: List[String]): ServerRequest =
    new ServerRequest:
      override def protocol: String                  = "HTTP/1.1"
      override def connectionInfo: ConnectionInfo    = ConnectionInfo(None, None, None)
      override def underlying: Any                   = ()
      override def pathSegments: List[String]         = segments
      override def queryParameters: QueryParams       = QueryParams()
      override def method: Method                     = Method.GET
      override def uri: Uri                           = Uri(segments.mkString("/", "/", ""))
      override def headers: Seq[Header]               = Nil
      override def attribute[T](k: AttributeKey[T]): Option[T] = None
      override def attribute[T](k: AttributeKey[T], v: T): ServerRequest = this
      override def withUnderlying(underlying: Any): ServerRequest = this

  /** Creates a bare 200 OK response with no headers. */
  private def bareResponse: ServerResponse[Unit] =
    ServerResponse(StatusCode.Ok, Nil, Some(()), None)

  /** Extracts header names from a `RequestResult.Response`. */
  private def headerNames[B](result: RequestResult[B]): Set[String] =
    result match
      case RequestResult.Response(resp, _) => resp.headers.map(_.name).toSet
      case _                               => Set.empty

  /** Extracts a specific header value from a `RequestResult.Response`. */
  private def headerValue[B](result: RequestResult[B], name: String): Option[String] =
    result match
      case RequestResult.Response(resp, _) => resp.headers.find(_.name == name).map(_.value)
      case _                               => None

  /** Runs a request through the actual `SecurityHeadersInterceptor.interceptor`,
    * returning the transformed `RequestResult`. Exercises the real Tapir
    * `RequestInterceptor.transformResult` wiring — not a re-implementation.
    */
  private def runInterceptor(
    segments: List[String],
    upstream: RequestResult[Unit] = RequestResult.Response(bareResponse, ResponseSource.EndpointHandler)
  ): Task[RequestResult[Unit]] =
    given SttpMonadError[Task] = new RIOMonadError[Any]

    val downstream: EndpointInterceptor[Task] => RequestHandler[Task, Any, Unit] =
      _ => new RequestHandler[Task, Any, Unit]:
        override def apply(req: ServerRequest, eps: List[ServerEndpoint[Any, Task]])(implicit
          m: SttpMonadError[Task]
        ): Task[RequestResult[Unit]] = ZIO.succeed(upstream)

    val responder = new Responder[Task, Unit]:
      override def apply[O](req: ServerRequest, out: ValuedEndpointOutput[O]): Task[ServerResponse[Unit]] =
        ZIO.succeed(bareResponse)

    SecurityHeadersInterceptor.interceptor
      .apply[Any, Unit](responder, downstream)
      .apply(stubRequest(segments), Nil)

  // ── isWorkspacePath predicate ────────────────────────────────────────

  private val isWorkspacePath = SecurityHeadersInterceptor.isWorkspacePath

  def spec = suite("SecurityHeadersInterceptor")(
    // ── Path matching ──────────────────────────────────────────────────

    suite("isWorkspacePath")(
      test("matches /w/{key}/risk-trees") {
        assertTrue(isWorkspacePath(List("w", "abc123", "risk-trees")))
      },
      test("matches /w/{key}") {
        assertTrue(isWorkspacePath(List("w", "abc123")))
      },
      test("matches /workspaces") {
        assertTrue(isWorkspacePath(List("workspaces")))
      },
      test("does not match /health") {
        assertTrue(!isWorkspacePath(List("health")))
      },
      test("does not match /risk-trees") {
        assertTrue(!isWorkspacePath(List("risk-trees")))
      },
      test("does not match /events/tree/{id}") {
        assertTrue(!isWorkspacePath(List("events", "tree", "abc")))
      },
      test("does not match /docs") {
        assertTrue(!isWorkspacePath(List("docs")))
      },
      test("does not match /admin/workspaces/expired") {
        assertTrue(!isWorkspacePath(List("admin", "workspaces", "expired")))
      },
      test("does not match empty path") {
        assertTrue(!isWorkspacePath(Nil))
      },
    ),

    // ── Header injection ───────────────────────────────────────────────

    suite("header injection")(
      test("non-workspace path gets only global headers") {
        for result <- runInterceptor(List("health"))
        yield
          val names = headerNames(result)
          assertTrue(
            names.contains("X-Frame-Options"),
            names.contains("X-XSS-Protection"),
            names.contains("Content-Security-Policy"),
            !names.contains("Referrer-Policy"),
            !names.contains("Cache-Control"),
            !names.contains("X-Content-Type-Options"),
            !names.contains("Strict-Transport-Security")
          )
      },
      test("workspace path /w/{key}/… gets global + workspace headers") {
        for result <- runInterceptor(List("w", "abc123", "risk-trees"))
        yield
          val names = headerNames(result)
          assertTrue(
            // Global
            names.contains("X-Frame-Options"),
            names.contains("X-XSS-Protection"),
            names.contains("Content-Security-Policy"),
            // Workspace-specific
            names.contains("Referrer-Policy"),
            names.contains("Cache-Control"),
            names.contains("X-Content-Type-Options"),
            names.contains("Strict-Transport-Security")
          )
      },
      test("/workspaces gets global + workspace headers") {
        for result <- runInterceptor(List("workspaces"))
        yield
          val names = headerNames(result)
          assertTrue(
            names.contains("Referrer-Policy"),
            names.contains("Cache-Control"),
            names.contains("Strict-Transport-Security")
          )
      },
      test("global header values are correct") {
        for result <- runInterceptor(List("health"))
        yield assertTrue(
          headerValue(result, "X-Frame-Options").contains("DENY"),
          headerValue(result, "X-XSS-Protection").contains("0"),
          headerValue(result, "Content-Security-Policy").exists(_.contains("default-src 'self'"))
        )
      },
      test("workspace header values are correct") {
        for result <- runInterceptor(List("w", "key123"))
        yield assertTrue(
          headerValue(result, "Referrer-Policy").contains("no-referrer"),
          headerValue(result, "Cache-Control").contains("no-store"),
          headerValue(result, "X-Content-Type-Options").contains("nosniff"),
          headerValue(result, "Strict-Transport-Security").contains("max-age=31536000; includeSubDomains")
        )
      },
      test("failure results pass through unchanged") {
        for result <- runInterceptor(List("w", "key"), RequestResult.Failure(Nil))
        yield assertTrue(result.isInstanceOf[RequestResult.Failure])
      }
    )
  )

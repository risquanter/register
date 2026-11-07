package com.risquanter.register.http

import sttp.tapir.*
import zio.*
import zio.http.{Method, Request, Response, Routes, Status, URL}
import zio.json.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*

import com.risquanter.register.Application
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.{NotFoundKind, WireError}
import com.risquanter.register.http.responses.LocationRoute
import com.risquanter.register.telemetry.TelemetryLive

/** A request matching no route gets one answer, and a real route keeps its own.
  *
  * This runs the production options and route assembly through zio-http's own
  * route patterns. A Tapir stub builds no patterns, so it cannot see a mistake in
  * which request reaches which route, and it is why a path fitting no pattern
  * needs an endpoint of its own to receive this answer.
  */
object TrailingSlashInterceptorSpec extends ZIOSpecDefault with BaseEndpoint:

  private val telemetry: ZLayer[Any, Throwable, Tracing] =
    TestConfigs.telemetryLayer >>> TelemetryLive.configured

  /** The origin the request claims and the CORS policy allows. Any value works;
    * it only has to match on both sides. */
  private val TestOrigin = "https://register.example"

  /** A path segment standing in for a workspace key. These routes read it as
    * plain text, because no request here reaches a real workspace. */
  private val Key = "AAAAAAAAAAAAAAAAAAAAAA"

  private val Served = "served"

  private val bootstrapPath = s"/${BaseEndpoint.BootstrapSegment}"
  private val treesPath     = s"/${LocationRoute.Workspace.segment}/$Key/${LocationRoute.Tree.segment}"

  private val routes =
    List(
      baseEndpoint.post.in(BaseEndpoint.BootstrapSegment).out(stringBody),
      baseEndpoint.get.in(LocationRoute.Workspace.segment / path[String]("key") / LocationRoute.Tree.segment).out(stringBody)
        .mapIn(_ => ())(_ => Key)
    ).map(_.serverLogicSuccess[Task](_ => ZIO.succeed(Served)))

  private val app: URIO[Tracing, Routes[Any, Response]] =
    ZIO.serviceWith[Tracing](tracing =>
      Application.httpRoutes(
        Application.serverOptions(tracing, Application.corsPolicy(List(TestOrigin))),
        routes,
        tracing
      )
    )

  private def send(method: Method, target: String): RIO[Tracing, Response] =
    app.flatMap(routes =>
      ZIO.scoped(routes.runZIO(
        Request(method = method, url = URL.decode(target).toOption.get).addHeader("Origin", TestOrigin)
      ))
    )

  private def isNoRouteAnswer(response: Response): Task[Boolean] =
    response.body.asString.map(body =>
      response.status == Status.NotFound &&
        body.fromJson[WireError.NotFound].exists(_.absence == NotFoundKind.Unknown())
    )

  private val noRouteRequests: List[(Method, String)] = List(
    Method.POST -> s"$bootstrapPath/",
    Method.GET  -> s"$treesPath/",
    Method.GET  -> s"$treesPath/one/extra",
    Method.GET  -> s"/${LocationRoute.Workspace.segment}/$Key/no-such",
    Method.GET  -> "/no-such-route"
  )

  override def spec = suite("the answer to a request matching no route")(
    test("a real route is served without a slash and with a query string") {
      for
        plain <- send(Method.POST, bootstrapPath)
        query <- send(Method.POST, s"$bootstrapPath?x=1")
        tree  <- send(Method.GET, treesPath)
      yield assertTrue(plain.status == Status.Ok, query.status == Status.Ok, tree.status == Status.Ok)
    },
    test("a slash form and every path fitting no route answer the declared NotFound") {
      for
        answers <- ZIO.foreach(noRouteRequests)((method, target) => send(method, target).flatMap(isNoRouteAnswer))
      yield assertTrue(answers.forall(identity))
    },
    test("a method a real route does not accept stays a 405") {
      for response <- send(Method.DELETE, bootstrapPath)
      yield assertTrue(response.status == Status.MethodNotAllowed)
    },
    test("every such answer on a path holding a key carries no-store") {
      for
        slash   <- send(Method.GET, s"$treesPath/")
        unknown <- send(Method.GET, s"/${LocationRoute.Workspace.segment}/$Key/no-such")
      yield assertTrue(
        slash.rawHeader("Cache-Control").contains("no-store"),
        unknown.rawHeader("Cache-Control").contains("no-store")
      )
    },
    test("every such answer carries the global security headers") {
      for responses <- ZIO.foreach(noRouteRequests)((method, target) => send(method, target))
      yield assertTrue(responses.forall(_.rawHeader("X-Frame-Options").contains("DENY")))
    },
    test("a browser can read every such answer across origins") {
      for responses <- ZIO.foreach(noRouteRequests)((method, target) => send(method, target))
      yield assertTrue(responses.forall(_.rawHeader("Access-Control-Allow-Origin").contains(TestOrigin)))
    }
  ).provideLayerShared(telemetry)

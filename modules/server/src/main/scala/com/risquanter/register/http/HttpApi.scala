package com.risquanter.register.http

import zio.*
import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.{endpoint, header, paths, statusCode, stringToPath}
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.server.ServerEndpoint
import zio.telemetry.opentelemetry.tracing.Tracing

import com.risquanter.register.http.controllers.{BaseController, SystemController, WorkspaceLifecycleController, WorkspaceTreeController, WorkspaceAnalysisController, QueryController, DistributionPreviewController, ScenarioController}
import com.risquanter.register.http.endpoints.BaseEndpoint
import com.risquanter.register.http.errors.{NotFoundKind, WireError}
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.telemetry.ActiveTrace

/** Aggregates all HTTP controllers and generates Swagger documentation
  */
object HttpApi extends BaseEndpoint {

  /** Answers a request whose path fits no route's pattern (ADR-035 §4).
    *
    * zio-http answers such a request before Tapir runs, so no interceptor would
    * see it. Being a Tapir endpoint is what passes it through the security
    * headers, CORS and the span. It matches every path and method, so it is
    * interpreted apart from the real routes, which zio-http prefers over it, and
    * `gatherRoutes` leaves it out, which keeps it out of the published document.
    * `TrailingSlashInterceptor` sends the same answer for a slash form.
    */
  def noRouteEndpoint(tracing: Tracing): ServerEndpoint[Any, Task] =
    baseEndpoint
      .in(paths)
      .serverLogic[Task](_ =>
        ActiveTrace.id(using tracing).map(trace => Left(WireError.NotFound(NotFoundKind.Unknown(), trace)))
      )

  /** The published document's title and version, chosen by this API. */
  private val ApiTitle   = "Risk Register API"
  private val ApiVersion = "0.1.0"

  /** The file the Swagger UI's jar opens on. Fixed by that jar. */
  private val DocsLandingFile = "index.html"

  /** Answers `/docs` with the page's own address.
    *
    * Tapir's redirect names `/docs/`, which `TrailingSlashInterceptor` refuses
    * (ADR-017 §7). This endpoint is listed ahead of Tapir's, which is what makes
    * it the one that answers, and being outside `logicRoutes` keeps it out of the
    * published document.
    */
  private val docsRedirectEndpoint: ServerEndpoint[Any, Task] =
    endpoint.get
      .in(BaseEndpoint.DocsSegment)
      .out(statusCode(StatusCode.PermanentRedirect).and(header[String](HeaderNames.Location)))
      .serverLogicSuccess[Task](_ => ZIO.succeed(s"./${BaseEndpoint.DocsSegment}/$DocsLandingFile"))

  /** Gathers routes from all controllers and adds Swagger documentation endpoints
    *
    * @param controllers List of controllers implementing BaseController
    * @return Combined list of application routes and Swagger UI routes
    */
  def gatherRoutes(controllers: List[BaseController]): List[ServerEndpoint[Any, Task]] = {
    val logicRoutes = controllers.flatMap(_.routes)
    val docRoutes = SwaggerInterpreter()
      .fromServerEndpoints(logicRoutes, ApiTitle, ApiVersion)
    logicRoutes ++ (docsRedirectEndpoint :: docRoutes)
  }

  /** Creates all application controllers with their dependencies
    * 
    * @return ZIO effect that provides list of all controllers
    */
  def makeControllers: ZIO[SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & QueryController & DistributionPreviewController & ScenarioController, Nothing, List[BaseController]] = for {
    system <- ZIO.service[SystemController]
    lifecycle <- ZIO.service[WorkspaceLifecycleController]
    trees <- ZIO.service[WorkspaceTreeController]
    analysis <- ZIO.service[WorkspaceAnalysisController]
    sse <- ZIO.service[SSEController]
    query <- ZIO.service[QueryController]
    distPreview <- ZIO.service[DistributionPreviewController]
    scenarios <- ZIO.service[ScenarioController]
  } yield List(system, lifecycle, trees, analysis, sse, query, distPreview, scenarios)

  /** Complete application endpoints including business logic and documentation
    * 
    * @return ZIO effect providing all HTTP endpoints
    */
  val endpointsZIO: ZIO[SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & QueryController & DistributionPreviewController & ScenarioController, Nothing, List[ServerEndpoint[Any, Task]]] =
    makeControllers.map(gatherRoutes)
}

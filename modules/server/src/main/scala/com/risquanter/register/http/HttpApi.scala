package com.risquanter.register.http

import zio.*
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.http.controllers.*
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.http.cache.CacheController

/** Aggregates all HTTP controllers and generates Swagger documentation
  */
object HttpApi {

  /** Gathers routes from all controllers and adds Swagger documentation endpoints
    * 
    * @param controllers List of controllers implementing BaseController
    * @return Combined list of application routes and Swagger UI routes
    */
  def gatherRoutes(controllers: List[BaseController]): List[ServerEndpoint[Any, Task]] = {
    val logicRoutes = controllers.flatMap(_.routes)
    val docRoutes = SwaggerInterpreter()
      .fromServerEndpoints(logicRoutes, "Risk Register API", "0.1.0")
    logicRoutes ++ docRoutes
  }

  /** Creates all application controllers with their dependencies
    * 
    * @return ZIO effect that provides list of all controllers
    */
  def makeControllers: ZIO[RiskTreeController & HealthController & SSEController & CacheController, Nothing, List[BaseController]] = for {
    riskTrees <- ZIO.service[RiskTreeController]
    health <- ZIO.service[HealthController]
    sse <- ZIO.service[SSEController]
    cache <- ZIO.service[CacheController]
  } yield List(health, riskTrees, sse, cache)

  /** Complete application endpoints including business logic and documentation
    * 
    * @return ZIO effect providing all HTTP endpoints
    */
  val endpointsZIO: ZIO[RiskTreeController & HealthController & SSEController & CacheController, Nothing, List[ServerEndpoint[Any, Task]]] =
    makeControllers.map(gatherRoutes)
}

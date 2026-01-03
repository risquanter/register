package com.risquanter.register

import zio.*
import zio.http.Server
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.http.Middleware.{cors, CorsConfig}
import zio.http.Header.{
  AccessControlAllowHeaders,
  AccessControlAllowOrigin,
  AccessControlExposeHeaders,
  Origin
}

import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory

/** Main application entry point
  * Sets up HTTP server with CORS, dependency injection, and routing
  */
object Application extends ZIOAppDefault {

  def startServer: ZIO[RiskTreeController & Server, Throwable, Unit] = for {
    endpoints <- HttpApi.endpointsZIO
    httpApp   = ZioHttpInterpreter().toHttp(endpoints)
    
    // CORS configuration - allow all origins for development
    corsConfig = CorsConfig(
      allowedOrigin = _ => Some(AccessControlAllowOrigin.All),
      allowedHeaders = AccessControlAllowHeaders.All,
      exposedHeaders = AccessControlExposeHeaders.All
    )
    
    corsApp = cors(corsConfig)(httpApp)
    _ <- ZIO.log("Starting Risk Register API server on http://localhost:8080")
    _ <- ZIO.log("Swagger UI available at http://localhost:8080/docs")
    _ <- Server.serve(corsApp)
  } yield ()

  def program: ZIO[RiskTreeController & Server, Throwable, Unit] = for {
    _ <- ZIO.log("Bootstrapping Risk Register application...")
    _ <- startServer
  } yield ()

  override def run: ZIO[Any, Any, Unit] = program.provide(
    Server.default,
    // Controllers
    ZLayer.fromZIO(RiskTreeController.makeZIO),
    // Services
    RiskTreeServiceLive.layer,
    com.risquanter.register.services.SimulationExecutionService.live,
    // Repositories
    RiskTreeRepositoryInMemory.layer
  )
}

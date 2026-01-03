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

  override def run = {
    val program = for {
      _          <- ZIO.logInfo("Bootstrapping Risk Register application...")
      endpoints  <- HttpApi.endpointsZIO
      _          <- ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")
      httpApp     = ZioHttpInterpreter().toHttp(endpoints)
      
      corsConfig  = CorsConfig(
        allowedOrigin = _ => Some(AccessControlAllowOrigin.All),
        allowedHeaders = AccessControlAllowHeaders.All,
        exposedHeaders = AccessControlExposeHeaders.All
      )
      _          <- ZIO.logInfo("CORS configured for all origins")
      corsApp     = cors(corsConfig)(httpApp)
      _          <- ZIO.logInfo("Starting HTTP server on port 8080...")
      _          <- Server.serve(corsApp)
    } yield ()

    program.provide(
      Server.default,
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.services.SimulationExecutionService.live,
      RiskTreeServiceLive.layer,
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    )
  }
}

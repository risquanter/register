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

  // Build layer dependency graph explicitly
  val appLayer = ZLayer.make[RiskTreeController & Server](
    Server.default,
    RiskTreeRepositoryInMemory.layer,
    com.risquanter.register.services.SimulationExecutionService.live,
    RiskTreeServiceLive.layer,
    ZLayer.fromZIO(RiskTreeController.makeZIO)
  )

  override def run = {
    val program = for {
      _          <- ZIO.logInfo("Bootstrapping Risk Register application...")
      endpoints  <- HttpApi.endpointsZIO
      _          <- ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")
      httpApp     = ZioHttpInterpreter().toHttp(endpoints)
      _          <- ZIO.logInfo("Starting HTTP server on port 8080...")
      _          <- Server.serve(httpApp)
    } yield ()

    program.provide(appLayer)
  }
}

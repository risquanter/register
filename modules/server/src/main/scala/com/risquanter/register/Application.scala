package com.risquanter.register

import zio.*
import zio.http.Server
import zio.config.typesafe.TypesafeConfigProvider
import sttp.tapir.server.ziohttp.ZioHttpInterpreter

import com.risquanter.register.configs.{Configs, ServerConfig, SimulationConfig, CorsConfig, TelemetryConfig}
import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}

/** Main application entry point
  * Sets up HTTP server with configuration management, dependency injection, and routing
  */
object Application extends ZIOAppDefault {

  // Bootstrap: Configure TypesafeConfigProvider to load from application.conf
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(
      TypesafeConfigProvider.fromResourcePath()
    )

  // Application layers (with config dependencies)
  val appLayer: ZLayer[Any, Throwable, RiskTreeController & Server & ServerConfig] =
    ZLayer.make[RiskTreeController & Server & ServerConfig](
      // Config layers
      Configs.makeLayer[ServerConfig]("register.server"),
      Configs.makeLayer[SimulationConfig]("register.simulation"),
      Configs.makeLayer[TelemetryConfig]("register.telemetry"),
      // Server layer uses ServerConfig
      ZLayer.fromZIO(
        ZIO.service[ServerConfig].map(cfg => 
          Server.Config.default.binding(cfg.host, cfg.port)
        )
      ) >>> Server.live,
      // Telemetry - provides Tracing + Meter for observability (requires TelemetryConfig)
      TracingLive.console,
      MetricsLive.console,
      // Concurrency control - limits concurrent simulations (requires SimulationConfig)
      com.risquanter.register.services.SimulationSemaphore.layer,
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.services.SimulationExecutionService.live,
      RiskTreeServiceLive.layer,  // Requires SimulationConfig + Tracing + SimulationSemaphore + Meter
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    )

  override def run = {
    val program = for {
      _          <- ZIO.logInfo("Bootstrapping Risk Register application...")
      cfg        <- ZIO.service[ServerConfig]
      _          <- ZIO.logInfo(s"Server config: host=${cfg.host}, port=${cfg.port}")
      endpoints  <- HttpApi.endpointsZIO
      _          <- ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")
      httpApp     = ZioHttpInterpreter().toHttp(endpoints)
      _          <- ZIO.logInfo(s"Starting HTTP server on ${cfg.host}:${cfg.port}...")
      _          <- Server.serve(httpApp)
    } yield ()

    program.provide(appLayer)
  }
}

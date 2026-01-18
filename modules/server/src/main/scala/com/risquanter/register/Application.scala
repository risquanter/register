package com.risquanter.register

import zio.*
import zio.http.Server
import zio.config.typesafe.TypesafeConfigProvider
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.http.{Middleware, Header}
import zio.http.Header.{AccessControlAllowHeaders, AccessControlAllowOrigin, AccessControlExposeHeaders, Origin}

import com.risquanter.register.configs.{Configs, ServerConfig, SimulationConfig, CorsConfig, TelemetryConfig}
import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.controllers.{RiskTreeController, HealthController}
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.http.cache.CacheController
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.cache.RiskResultCache
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.services.tree.TreeIndexService
import com.risquanter.register.domain.tree.TreeIndex
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

  // TreeIndex layer - provides empty index for LECCache (Phase 5: dormant infrastructure)
  // Future: TreeIndexService will manage per-tree indices
  val treeIndexLayer: ZLayer[Any, Nothing, TreeIndex] =
    ZLayer.succeed(TreeIndex.empty)

  // Application layers (with config dependencies)
  val appLayer: ZLayer[Any, Throwable, RiskTreeController & HealthController & SSEController & CacheController & Server & ServerConfig & CorsConfig] =
    ZLayer.make[RiskTreeController & HealthController & SSEController & CacheController & Server & ServerConfig & CorsConfig](
      // Config layers
      Configs.makeLayer[ServerConfig]("register.server"),
      Configs.makeLayer[SimulationConfig]("register.simulation"),
      Configs.makeLayer[TelemetryConfig]("register.telemetry"),
      Configs.makeLayer[CorsConfig]("register.cors"),
      // Server layer uses ServerConfig
      ZLayer.fromZIO(
        ZIO.service[ServerConfig].map(cfg => 
          Server.Config.default.binding(cfg.host, cfg.port)
        )
      ) >>> Server.live,
      // Telemetry - provides Tracing + Meter for observability (requires TelemetryConfig)
      // Current setup: LoggingSpanExporter & LoggingMetricExporter configured
      // NOTE: Console exporters produce no visible output in application logs
      // (likely log at DEBUG/FINE level filtered by default log config)
      // TODO: Configure log level or switch to TracingLive.otlp & MetricsLive.otlp
      // for actual telemetry export to otel-collector
      TracingLive.console,
      MetricsLive.console,
      // Concurrency control - limits concurrent simulations (requires SimulationConfig)
      com.risquanter.register.services.SimulationSemaphore.layer,
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.services.SimulationExecutionService.live,
      RiskTreeServiceLive.layer,  // Requires SimulationConfig + Tracing + SimulationSemaphore + Meter
      // Phase 5: Cache invalidation + SSE infrastructure
      treeIndexLayer,
      RiskResultCache.layer,
      SSEHub.live,
      InvalidationHandler.live,
      SSEController.layer,
      CacheController.layer,
      ZLayer.fromZIO(RiskTreeController.makeZIO),
      HealthController.live
    )

  def startServer = for {
    cfg        <- ZIO.service[ServerConfig]
    corsConfig <- ZIO.service[CorsConfig]
    _          <- ZIO.logInfo(s"Server config: host=${cfg.host}, port=${cfg.port}")
    _          <- ZIO.logInfo(s"CORS allowed origins: ${corsConfig.allowedOrigins.mkString(", ")}")
    endpoints  <- HttpApi.endpointsZIO
    _          <- ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")
    httpApp     = ZioHttpInterpreter().toHttp(endpoints)
    
    // Apply CORS middleware
    corsMiddleware = Middleware.cors(Middleware.CorsConfig(
      allowedOrigin = { origin =>
        if (corsConfig.allowedOrigins.exists(allowed => Origin.parse(allowed).toOption.contains(origin)))
          Some(AccessControlAllowOrigin.Specific(origin))
        else
          None
      },
      allowedHeaders = AccessControlAllowHeaders.All,
      exposedHeaders = AccessControlExposeHeaders.All
    ))
    corsApp = corsMiddleware(httpApp)
    
    _          <- ZIO.logInfo(s"Starting HTTP server on ${cfg.host}:${cfg.port}...")
    _          <- Server.serve(corsApp)
  } yield ()

  def program = for {
    _ <- ZIO.logInfo("Bootstrapping Risk Register application...")
    _ <- startServer
  } yield ()

  override def run = program.provide(appLayer)
}

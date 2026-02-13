package com.risquanter.register

import zio.*
import zio.http.Server
import zio.config.typesafe.TypesafeConfigProvider
import sttp.tapir.server.ziohttp.{ZioHttpInterpreter, ZioHttpServerOptions}
import sttp.tapir.server.interceptor.cors.{CORSInterceptor, CORSConfig as TapirCORSConfig}

import com.risquanter.register.configs.{Configs, ServerConfig, SimulationConfig, CorsConfig, TelemetryConfig, RepositoryConfig, IrminConfig}
import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.http.cache.CacheController
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.cache.{TreeCacheManager, RiskResultResolverLive}
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryInMemory, RiskTreeRepositoryIrmin}
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}

/** Main application entry point
  * Sets up HTTP server with configuration management, dependency injection, and routing
  */
object Application extends ZIOAppDefault {

  // Repo selection helper (config-driven) with Irmin fail-fast health check
  private val irminHealthCheck: ZLayer[IrminClient & IrminConfig, Throwable, IrminClient] =
    ZLayer.fromZIO {
      for {
        cfg    <- ZIO.service[IrminConfig]
        client <- ZIO.service[IrminClient]
        // Health check retries are bounded and default to zero per ADR-012 fail-fast guidance
        retry   = Schedule.recurs(math.max(0, cfg.healthCheckRetries))
        _ <- client.healthCheck
               .flatMap(ok => if ok then ZIO.unit else ZIO.fail(new RuntimeException("Irmin health check returned false")))
               .mapError(err => new RuntimeException(s"Irmin health check failed: $err"))
               .retry(retry)
               .timeoutFail(new RuntimeException(s"Irmin health check timed out after ${cfg.healthCheckTimeout.toMillis} ms"))(cfg.healthCheckTimeout)
               .tapError(e => ZIO.logError(s"Irmin health check failed: ${e.getMessage}"))
               .tap(_ => ZIO.logInfo(s"Irmin repository selected at ${cfg.url}"))
      } yield client
    }

  private val irminRepoLayer: ZLayer[Any, Throwable, RiskTreeRepository] =
    ZLayer.make[RiskTreeRepository](
      IrminConfig.layer,
      IrminClientLive.layer >>> irminHealthCheck,
      RiskTreeRepositoryIrmin.layer
    )

  private val inMemoryRepoLayer: ZLayer[Any, Nothing, RiskTreeRepository] =
    RiskTreeRepositoryInMemory.layer

  private val chooseRepo: ZLayer[RepositoryConfig, Throwable, RiskTreeRepository] =
    ZLayer.fromZIO {
      for {
        repoCfg <- ZIO.service[RepositoryConfig]
        repo <- repoCfg.normalizedType match {
          case "irmin" =>
            ZIO.logInfo("repository.type=irmin; attempting Irmin wiring with fail-fast health check") *>
              ZIO.scoped(irminRepoLayer.build.map(_.get[RiskTreeRepository]))
          case other =>
            ZIO.logWarning(s"repository.type='$other' not 'irmin'; defaulting to in-memory repository") *>
              ZIO.scoped(inMemoryRepoLayer.build.map(_.get[RiskTreeRepository]))
        }
      } yield repo
    }

  // Bootstrap: Configure TypesafeConfigProvider to load from application.conf
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(
      TypesafeConfigProvider.fromResourcePath()
    )

  // Application layers (with config dependencies)
  val appLayer: ZLayer[Any, Throwable, RiskTreeController & SSEController & CacheController & Server & ServerConfig & CorsConfig] =
    ZLayer.make[RiskTreeController & SSEController & CacheController & Server & ServerConfig & CorsConfig](
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
      RepositoryConfig.layer >>> chooseRepo,
      RiskTreeServiceLive.layer,  // Requires SimulationConfig + Tracing + SimulationSemaphore + Meter
      // Per-tree cache management (ADR-014)
      TreeCacheManager.layer,
      RiskResultResolverLive.layer,  // ADR-015: ensureCached primitive
      SSEHub.live,
      InvalidationHandler.live,
      SSEController.layer,
      CacheController.layer,
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    )

  def startServer = for {
    cfg        <- ZIO.service[ServerConfig]
    corsConfig <- ZIO.service[CorsConfig]
    _          <- ZIO.logInfo(s"Server config: host=${cfg.host}, port=${cfg.port}")
    _          <- ZIO.logInfo(s"CORS allowed origins: ${corsConfig.normalised.mkString(", ")}")
    endpoints  <- HttpApi.endpointsZIO
    _          <- ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")

    // CORS at the tapir interceptor layer — handles OPTIONS preflight before
    // route matching, avoiding the 405 that zio-http Middleware.cors produces.
    tapirCorsConfig = TapirCORSConfig.default
      .allowMatchingOrigins(origin => corsConfig.normalised.contains(origin))
      .reflectHeaders
      .exposeAllHeaders
    serverOptions = ZioHttpServerOptions
      .customiseInterceptors[Any]
      .corsInterceptor(CORSInterceptor.customOrThrow[Task](tapirCorsConfig))
      .options
    httpApp = ZioHttpInterpreter(serverOptions).toHttp(endpoints)

    _          <- ZIO.logInfo(s"Starting HTTP server on ${cfg.host}:${cfg.port}...")
    _          <- Server.serve(httpApp)
  } yield ()

  def program = for {
    _ <- ZIO.logInfo("Bootstrapping Risk Register application...")
    _ <- startServer
  } yield ()

  override def run = program.provide(appLayer)
}

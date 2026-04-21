package com.risquanter.register

import zio.*
import zio.http.Server
import zio.config.typesafe.TypesafeConfigProvider
import sttp.tapir.server.ziohttp.{ZioHttpInterpreter, ZioHttpServerOptions}
import sttp.tapir.server.interceptor.cors.{CORSInterceptor, CORSConfig as TapirCORSConfig}
import io.getquill.SnakeCase

import com.risquanter.register.configs.{AuthConfig, AuthMode, Configs, CorsConfig, FlywayConfig, IrminConfig, PostgresDataSourceConfig, RepositoryConfig, RepositoryType, ServerConfig, SimulationConfig, TelemetryConfig, WorkspaceConfig, WorkspaceStoreBackend, WorkspaceStoreConfig}
import com.risquanter.register.auth.{AuthorizationService, AuthorizationServiceNoOp, UserContextExtractor}
import com.risquanter.register.http.{HealthProbeServer, HttpApi, SecurityHeadersInterceptor}
import com.risquanter.register.http.controllers.{SystemController, WorkspaceLifecycleController, WorkspaceTreeController, WorkspaceAnalysisController, QueryController}
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.http.cache.CacheController
import com.risquanter.register.infra.persistence.{FlywayService, FlywayServiceLive, Repository}
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.services.QueryServiceLive
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.cache.{TreeCacheManager, RiskResultResolverLive}
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.services.workspace.{WorkspaceStore, WorkspaceStoreLive, WorkspaceStorePostgres, RateLimiterLive, WorkspaceReaper}
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
        repo <- repoCfg.repositoryType match {
          case RepositoryType.Irmin =>
            ZIO.logInfo("repository.type=irmin; attempting Irmin wiring with fail-fast health check") *>
              ZIO.scoped(irminRepoLayer.build.map(_.get[RiskTreeRepository]))
          case RepositoryType.InMemory =>
            ZIO.logInfo("repository.type=in-memory; using in-memory repository") *>
              ZIO.scoped(inMemoryRepoLayer.build.map(_.get[RiskTreeRepository]))
        }
      } yield repo
    }

  private val postgresWorkspaceStoreLayer: ZLayer[WorkspaceConfig & PostgresDataSourceConfig, Throwable, WorkspaceStore] =
    (ZLayer.service[WorkspaceConfig] ++ Repository.dataLayer) >>> WorkspaceStorePostgres.layer

  private val inMemoryWorkspaceStoreLayer: ZLayer[WorkspaceConfig, Nothing, WorkspaceStore] =
    ZLayer.service[WorkspaceConfig] >>> WorkspaceStoreLive.layer

  private val chooseWorkspaceStore: ZLayer[WorkspaceStoreConfig & WorkspaceConfig & PostgresDataSourceConfig, Throwable, WorkspaceStore] =
    ZLayer.fromZIO {
      for {
        cfg <- ZIO.service[WorkspaceStoreConfig]
        store <- cfg.backend match {
          case WorkspaceStoreBackend.Postgres =>
            ZIO.logInfo("workspaceStore.backend=postgres; using PostgreSQL-backed workspace store") *>
              ZIO.scoped(postgresWorkspaceStoreLayer.build.map(_.get[WorkspaceStore]))
          case WorkspaceStoreBackend.InMemory =>
            ZIO.logInfo("workspaceStore.backend=in-memory; using in-memory workspace store") *>
              ZIO.scoped(inMemoryWorkspaceStoreLayer.build.map(_.get[WorkspaceStore]))
        }
      } yield store
    }

  private val postgresFlywayLayer: ZLayer[Any, Throwable, FlywayService] =
    FlywayConfig.layer >>> FlywayServiceLive.layer

  private val chooseFlywayService: ZLayer[WorkspaceStoreConfig, Throwable, FlywayService] =
    ZLayer.fromZIO {
      for {
        cfg <- ZIO.service[WorkspaceStoreConfig]
        flyway <- cfg.backend match {
          case WorkspaceStoreBackend.Postgres =>
            ZIO.logInfo("workspaceStore.backend=postgres; enabling Flyway migrations") *>
              ZIO.scoped(postgresFlywayLayer.build.map(_.get[FlywayService]))
          case WorkspaceStoreBackend.InMemory =>
            ZIO.logInfo("workspaceStore.backend=in-memory; skipping Flyway migrations") *>
              ZIO.succeed(FlywayService.noOp)
        }
      } yield flyway
    }

  private val chooseAuthorizationService: ZLayer[AuthConfig, Throwable, AuthorizationService] =
    ZLayer.fromZIO {
      ZIO.service[AuthConfig].map {
        case AuthConfig(AuthMode.CapabilityOnly) => AuthorizationServiceNoOp
        case AuthConfig(AuthMode.Identity)       => AuthorizationServiceNoOp
        case AuthConfig(AuthMode.FineGrained)    => AuthorizationServiceNoOp
      }
    }

  private val chooseUserContextExtractor: ZLayer[AuthConfig, Nothing, UserContextExtractor] =
    ZLayer.fromZIO {
      ZIO.service[AuthConfig].map {
        case AuthConfig(AuthMode.CapabilityOnly) => UserContextExtractor.noOp
        case AuthConfig(AuthMode.Identity)       => UserContextExtractor.noOp
        case AuthConfig(AuthMode.FineGrained)    => UserContextExtractor.noOp
      }
    }

  // Bootstrap: Configure TypesafeConfigProvider to load from application.conf
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(
      TypesafeConfigProvider.fromResourcePath()
    )

  // Application layers (with config dependencies)
  val appLayer: ZLayer[Any, Throwable, SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & CacheController & QueryController & FlywayService & Server & ServerConfig & CorsConfig & WorkspaceReaper & UserContextExtractor & AuthConfig] =
    ZLayer.make[SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & CacheController & QueryController & FlywayService & Server & ServerConfig & CorsConfig & WorkspaceReaper & UserContextExtractor & AuthConfig](
      // Config layers
      Configs.makeLayer[ServerConfig]("register.server"),
      Configs.makeLayer[SimulationConfig]("register.simulation"),
      Configs.makeLayer[TelemetryConfig]("register.telemetry"),
      Configs.makeLayer[CorsConfig]("register.cors"),
      Configs.makeLayer[WorkspaceConfig]("register.workspace"),
      PostgresDataSourceConfig.layer,
      AuthConfig.layer,
      WorkspaceStoreConfig.layer,
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
      // Per-tree cache management (ADR-014)
      TreeCacheManager.layer,
      RiskResultResolverLive.layer,  // ADR-015: ensureCached primitive
      SSEHub.live,
      InvalidationHandler.live,     // Requires TreeCacheManager & SSEHub (no RiskTreeService dep)
      RiskTreeServiceLive.layer,    // Requires InvalidationHandler + SimulationConfig + Tracing + SimulationSemaphore + Meter
      QueryServiceLive.layer,       // Requires RiskTreeRepository + RiskResultResolver + Tracing
      chooseWorkspaceStore,
      chooseFlywayService,
      RateLimiterLive.layer,
      WorkspaceReaper.layer,
      chooseAuthorizationService,
      chooseUserContextExtractor,
      ZLayer.fromZIO(SystemController.makeZIO),
      ZLayer.fromZIO(WorkspaceLifecycleController.makeZIO),
      ZLayer.fromZIO(WorkspaceTreeController.makeZIO),
      ZLayer.fromZIO(WorkspaceAnalysisController.makeZIO),
      SSEController.layer,
      CacheController.layer,
      ZLayer.fromZIO(QueryController.makeZIO)
    )

  def startServer = for {
    cfg        <- ZIO.service[ServerConfig]
    corsConfig <- ZIO.service[CorsConfig]
    authConfig <- ZIO.service[AuthConfig]
    userCtx    <- ZIO.service[UserContextExtractor]
    _          <- ZIO.logInfo(s"Server config: host=${cfg.host}, port=${cfg.port}")
    _          <- ZIO.logInfo(s"CORS allowed origins: ${corsConfig.normalised.mkString(", ")}")
    // Auth mode startup log — operators should alert on capability-only in production namespaces.
    // @see ADR-012 §7 — Trust Assumption T3/Attack 3: silent mode mismatch detection
    _          <- UserContextExtractor.logStartupMode(
                    authConfig.mode match
                      case AuthMode.CapabilityOnly => "capability-only"
                      case AuthMode.Identity       => "identity"
                      case AuthMode.FineGrained    => "fine-grained",
                    userCtx
                  )
    endpoints  <- HttpApi.endpointsZIO
    _          <- ZIO.logInfo(s"Registered ${endpoints.length} HTTP endpoints")

    // CORS at the tapir interceptor layer — handles OPTIONS preflight before
    // route matching, avoiding the 405 that zio-http Middleware.cors produces.
    // A18: explicit header whitelist (no reflectHeaders)
    // A20: preflight caching (maxAge 1 hour)
    tapirCorsConfig = TapirCORSConfig.default
      .allowMatchingOrigins(origin => corsConfig.normalised.contains(origin))
      .allowHeaders("Content-Type", "Accept", "Authorization")
      .exposeHeaders("Content-Type")
      .maxAge(scala.concurrent.duration.Duration(3600, "s"))
    serverOptions = ZioHttpServerOptions
      .customiseInterceptors[Any]
      .prependInterceptor(SecurityHeadersInterceptor.interceptor)
      .corsInterceptor(CORSInterceptor.customOrThrow[Task](tapirCorsConfig))
      .options
    httpApp = ZioHttpInterpreter(serverOptions).toHttp(endpoints)

    _          <- ZIO.logInfo(s"Starting health probe server on ${cfg.host}:${cfg.healthPort}...")
    _          <- ZIO.logInfo(s"Starting API server on ${cfg.host}:${cfg.port}...")
    // Run both servers concurrently — shared lifecycle: if either fails or the
    // application shuts down, both are interrupted. @see FR-5 in spec.
    _          <- HealthProbeServer.serve(cfg.host, cfg.healthPort) <&> Server.serve(httpApp)
  } yield ()

  def program = for {
    _ <- ZIO.logInfo("Bootstrapping Risk Register application...")
    _ <- ZIO.logInfo("Running Flyway migrations...")
    _ <- ZIO.serviceWithZIO[FlywayService](_.runMigrations)
    _ <- ZIO.logInfo("Flyway migrations complete")
    _ <- startServer
  } yield ()

  override def run = program.provide(appLayer)
}

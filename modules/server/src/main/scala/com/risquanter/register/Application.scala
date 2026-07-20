package com.risquanter.register

import zio.*
import zio.http.Server
import zio.config.typesafe.TypesafeConfigProvider
import sttp.tapir.server.ziohttp.{ZioHttpInterpreter, ZioHttpServerOptions}
import sttp.tapir.server.interceptor.cors.{CORSInterceptor, CORSConfig as TapirCORSConfig}
import io.getquill.SnakeCase

import com.risquanter.register.configs.{AuthConfig, AuthMode, Configs, CorsConfig, FlywayConfig, IrminConfig, PostgresDataSourceConfig, RepositoryConfig, RepositoryType, ServerConfig, SimulationConfig, SpiceDbConfig, TelemetryConfig, WorkspaceConfig, WorkspaceStoreBackend, WorkspaceStoreConfig}
import com.risquanter.register.auth.{AuthorizationService, AuthorizationServiceNoOp, AuthorizationServiceSpiceDB, BootstrapProvisioner, BootstrapProvisionerNoOp, BootstrapProvisionerSpiceDB, UserContextExtractor}
import com.risquanter.register.http.{HealthProbeServer, HttpApi, SecurityHeadersInterceptor}
import com.risquanter.register.http.controllers.{SystemController, WorkspaceLifecycleController, WorkspaceTreeController, WorkspaceAnalysisController, QueryController, DistributionPreviewController, ScenarioController}
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.infra.StartupReadiness
import com.risquanter.register.infra.persistence.{FlywayService, FlywayServiceLive, Repository}
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.services.{ScenarioService, ScenarioServiceLive, ScenarioServiceNotSupported}
import com.risquanter.register.services.QueryServiceLive
import com.risquanter.register.services.DistributionPreviewService
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.cache.{CacheScope, RiskResultResolverLive}
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.services.workspace.{WorkspaceStore, WorkspaceStoreLive, WorkspaceStorePostgres, RateLimiterLive, WorkspaceReaper}
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryInMemory, RiskTreeRepositoryIrmin}
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.Meter
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}

/** Main application entry point
  * Sets up HTTP server with configuration management, dependency injection, and routing
  */
object Application extends ZIOAppDefault {

  // Startup readiness gate for the Irmin dependency (ADR-031): bounded, fail-closed
  // wait for irmin to become reachable. Request-path resilience remains the mesh's
  // responsibility (ADR-012 §4); this gate covers only the bootstrap window, which
  // the mesh cannot cover during its own policy reconciliation.
  private val irminHealthCheck: ZLayer[IrminClient & IrminConfig, Throwable, IrminClient] =
    ZLayer.fromZIO {
      for {
        cfg    <- ZIO.service[IrminConfig]
        client <- ZIO.service[IrminClient]
        _      <- StartupReadiness.awaitReady(
                    name           = s"irmin (${cfg.url})",
                    probe          = client.healthCheck,
                    attemptTimeout = cfg.healthCheckAttemptTimeout,
                    budget         = cfg.healthCheckBudget
                  )
        _      <- ZIO.logInfo(s"Irmin repository selected at ${cfg.url}")
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

  // Independent IrminClient + health check from irminRepoLayer's — a second
  // connection/health-check at startup when repository.type=irmin, rather than
  // sharing RiskTreeRepositoryIrmin's client. Simpler and scoped to only what's
  // new here; refactoring chooseRepo to expose a shared IrminClient would touch
  // already-working wiring beyond this item's scope (milestone-2b Phase B item 6).
  private val irminScenarioServiceLayer: ZLayer[Any, Throwable, ScenarioService] =
    ZLayer.make[ScenarioService](
      IrminConfig.layer,
      IrminClientLive.layer >>> irminHealthCheck,
      ScenarioServiceLive.layer
    )

  private val chooseScenarioService: ZLayer[RepositoryConfig, Throwable, ScenarioService] =
    ZLayer.fromZIO {
      for {
        repoCfg <- ZIO.service[RepositoryConfig]
        svc <- repoCfg.repositoryType match {
          case RepositoryType.Irmin =>
            ZIO.logInfo("repository.type=irmin; scenarios enabled") *>
              ZIO.scoped(irminScenarioServiceLayer.build.map(_.get[ScenarioService]))
          case RepositoryType.InMemory =>
            ZIO.logInfo("repository.type=in-memory; scenarios disabled (ScenarioServiceNotSupported, item 6)") *>
              ZIO.succeed(ScenarioServiceNotSupported: ScenarioService)
        }
      } yield svc
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

  // Requires Tracing & Meter because FineGrained mode creates AuthorizationServiceSpiceDB
  // with OTel instruments. Both are already provided in appLayer (TracingLive/MetricsLive).
  private val chooseAuthorizationService: ZLayer[AuthConfig & Tracing & Meter, Throwable, AuthorizationService] =
    ZLayer.scoped {
      for
        authConfig <- ZIO.service[AuthConfig]
        result     <- authConfig.mode match
          case AuthMode.CapabilityOnly =>
            ZIO.logInfo("auth.mode=capability-only; authorization NoOp (all checks pass)").as(
              AuthorizationServiceNoOp: AuthorizationService
            )
          case AuthMode.Identity =>
            ZIO.logInfo("auth.mode=identity; authorization NoOp (all SpiceDB checks pass)").as(
              AuthorizationServiceNoOp: AuthorizationService
            )
          case AuthMode.FineGrained =>
            ZIO.logInfo("auth.mode=fine-grained; activating AuthorizationServiceSpiceDB") *>
              (for
                tracing <- ZIO.service[Tracing]
                meter   <- ZIO.service[Meter]
                svc     <- ZLayer.make[AuthorizationService](
                  AuthorizationServiceSpiceDB.liveLayer,
                  Configs.makeLayer[SpiceDbConfig]("register.spicedb"),
                  ZLayer.succeed(tracing),
                  ZLayer.succeed(meter)
                ).build.map(_.get[AuthorizationService])
              yield svc)
      yield result
    }

  private val chooseBootstrapProvisioner: ZLayer[AuthConfig, Throwable, BootstrapProvisioner] =
    ZLayer.scoped {
      for
        authConfig <- ZIO.service[AuthConfig]
        result     <- authConfig.mode match
          case AuthMode.CapabilityOnly =>
            ZIO.logInfo("auth.mode=capability-only; BootstrapProvisioner NoOp (ownership via owner_id column only)").as(
              BootstrapProvisionerNoOp: BootstrapProvisioner
            )
          case AuthMode.Identity =>
            ZIO.logInfo("auth.mode=identity; BootstrapProvisioner NoOp (ownership via owner_id column only)").as(
              BootstrapProvisionerNoOp: BootstrapProvisioner
            )
          case AuthMode.FineGrained =>
            ZIO.logInfo("auth.mode=fine-grained; activating BootstrapProvisionerSpiceDB") *>
              ZLayer.make[BootstrapProvisioner](
                BootstrapProvisionerSpiceDB.liveLayer,
                Configs.makeLayer[SpiceDbConfig]("register.spicedb")
              ).build.map(_.get[BootstrapProvisioner])
      yield result
    }

  private val chooseUserContextExtractor: ZLayer[AuthConfig, Nothing, UserContextExtractor] =
    ZLayer.fromZIO {
      ZIO.service[AuthConfig].map {
        case AuthConfig(AuthMode.CapabilityOnly) => UserContextExtractor.noOp
        case AuthConfig(AuthMode.Identity)       => UserContextExtractor.requirePresent
        case AuthConfig(AuthMode.FineGrained)    => UserContextExtractor.requirePresent
      }
    }

  // Bootstrap: Configure TypesafeConfigProvider to load from application.conf
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(
      TypesafeConfigProvider.fromResourcePath()
    )

  // Application layers (with config dependencies)
  val appLayer: ZLayer[Any, Throwable, SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & QueryController & DistributionPreviewController & ScenarioController & FlywayService & Server & ServerConfig & CorsConfig & WorkspaceReaper & UserContextExtractor & AuthConfig] =
    ZLayer.make[SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & QueryController & DistributionPreviewController & ScenarioController & FlywayService & Server & ServerConfig & CorsConfig & WorkspaceReaper & UserContextExtractor & AuthConfig](
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
      RepositoryConfig.layer >>> chooseScenarioService,
      // Per-workspace content-addressed cache (milestone 2b Phase A, DD-17)
      CacheScope.layer,
      RiskResultResolverLive.layer,  // ADR-015: ensureCached primitive
      SSEHub.live,
      InvalidationHandler.live,     // SSE-only mutation notifications (requires SSEHub)
      RiskTreeServiceLive.layer,    // Requires InvalidationHandler + SimulationConfig + Tracing + SimulationSemaphore + Meter
      QueryServiceLive.layer,       // Requires RiskTreeRepository + RiskResultResolver + Tracing
      chooseWorkspaceStore,
      chooseFlywayService,
      RateLimiterLive.layer,
      WorkspaceReaper.layer,
      chooseAuthorizationService,
      chooseBootstrapProvisioner,
      chooseUserContextExtractor,
      ZLayer.fromZIO(SystemController.makeZIO),
      ZLayer.fromZIO(WorkspaceLifecycleController.makeZIO),
      ZLayer.fromZIO(WorkspaceTreeController.makeZIO),
      ZLayer.fromZIO(WorkspaceAnalysisController.makeZIO),
      ZLayer.fromZIO(ScenarioController.makeZIO),
      SSEController.layer,
      ZLayer.fromZIO(QueryController.makeZIO),
      DistributionPreviewService.layer,
      ZLayer.fromZIO(DistributionPreviewController.makeZIO)
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
    _          <- UserContextExtractor.logStartupMode(authConfig.mode, userCtx)
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

package com.risquanter.register.http

import java.net.ServerSocket
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.time.Duration

import zio.*
import zio.http.*
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import io.github.iltotore.iron.*

import com.risquanter.register.configs.*
import com.risquanter.register.http.controllers.{SystemController, WorkspaceLifecycleController, WorkspaceTreeController, WorkspaceAnalysisController, QueryController, DistributionPreviewController}
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.http.support.TestSafeUrls
import com.risquanter.register.infra.StartupReadiness
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryInMemory, RiskTreeRepositoryIrmin}
import com.risquanter.register.services.{RiskTreeServiceLive, SimulationSemaphore}
import com.risquanter.register.services.QueryServiceLive
import com.risquanter.register.services.DistributionPreviewService
import com.risquanter.register.services.cache.{RiskResultResolverLive, CacheScope}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.workspace.{WorkspaceStoreLive, RateLimiterLive}
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.telemetry.{MetricsLive, TracingLive}
import com.risquanter.register.auth.{AuthorizationServiceNoOp, BootstrapProvisionerNoOp, UserContextExtractor}

/** Test-only harness to start the HTTP server on a random port with selectable repo wiring. */
object HttpTestHarness:

  final case class RunningServer(baseUrl: String, port: Int)

  private val defaultSimulationConfig = SimulationConfig(
    defaultNTrials = 10.refineUnsafe,
    maxTreeDepth = 5.refineUnsafe,
    defaultTrialParallelism = 2.refineUnsafe,
    maxConcurrentSimulations = 2.refineUnsafe,
    maxNTrials = 100.refineUnsafe,
    maxParallelism = 2.refineUnsafe,
    defaultSeed3 = 0L,
    defaultSeed4 = 0L
  )

  private val defaultTelemetryConfig = TelemetryConfig(
    serviceName = "register-it",
    instrumentationScope = "com.risquanter.register",
    otlpEndpoint = TestSafeUrls.localhostOtlpEndpoint,
    devExportIntervalSeconds = 5,
    prodExportIntervalSeconds = 60
  )

  private val defaultCorsConfig = CorsConfig(allowedOrigins = List("*"))

  private val defaultWorkspaceConfig = WorkspaceConfig()

  final case class HarnessConfig(
    simulation: SimulationConfig = defaultSimulationConfig,
    telemetry: TelemetryConfig = defaultTelemetryConfig,
    cors: CorsConfig = defaultCorsConfig,
    workspace: WorkspaceConfig = defaultWorkspaceConfig
  )

  /** In-memory HTTP server (useful for lightweight component tests). */
  def inMemoryServer(cfg: HarnessConfig = HarnessConfig()): ZLayer[Scope, Throwable, RunningServer] =
    serverWithRepo(RiskTreeRepositoryInMemory.layer, cfg)

  /** Irmin-backed HTTP server (default for integration tests). */
  def irminServer(
      irminConfigLayer: ZLayer[Any, Throwable, IrminConfig],
      cfg: HarnessConfig = HarnessConfig()
  ): ZLayer[Scope, Throwable, RunningServer] =
    serverWithRepo(irminRepoLayer(irminConfigLayer), cfg)

  private def serverWithRepo(
      repoLayer: ZLayer[Any, Throwable, RiskTreeRepository],
      cfg: HarnessConfig
  ): ZLayer[Scope, Throwable, RunningServer] =
    ZLayer.scoped {
      for
        port <- randomPort
        envLayer = applicationLayer(port, repoLayer, cfg)
        env     <- envLayer.build
        corsCfg  = env.get[CorsConfig]
        endpoints <- HttpApi.endpointsZIO.provideEnvironment(env)

        corsMiddleware = Middleware.cors(Middleware.CorsConfig(
          allowedOrigin = { origin =>
            if (corsCfg.allowedOrigins.exists { allowed => Header.Origin.parse(allowed).toOption.contains(origin) })
              Some(Header.AccessControlAllowOrigin.Specific(origin))
            else None
          },
          allowedHeaders = Header.AccessControlAllowHeaders.All,
          exposedHeaders = Header.AccessControlExposeHeaders.All
        ))
        httpApp = corsMiddleware(ZioHttpInterpreter().toHttp(endpoints))

        serverFiber <- Server.serve(httpApp).provideEnvironment(env).forkScoped
        _           <- ZIO.addFinalizer(serverFiber.interrupt)
        baseUrl = s"http://127.0.0.1:$port"
        _ <- waitForHealth(baseUrl)
      yield RunningServer(baseUrl = baseUrl, port = port)
    }

  private def randomPort: Task[Int] =
    ZIO.attempt {
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    }

  private def applicationLayer(
      port: Int,
      repoLayer: ZLayer[Any, Throwable, RiskTreeRepository],
      cfg: HarnessConfig
  ): ZLayer[Any, Throwable, Server & CorsConfig & SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & QueryController & DistributionPreviewController] =
    ZLayer.make[
      Server & CorsConfig & SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & QueryController & DistributionPreviewController
    ](
      ZLayer.succeed(ServerConfig(host = "127.0.0.1", port = port, healthPort = port + 1)),
      ZLayer.succeed(cfg.simulation),
      ZLayer.succeed(cfg.telemetry),
      ZLayer.succeed(cfg.cors),
      ZLayer.succeed(cfg.workspace),
      ZLayer.fromZIO(
        ZIO.service[ServerConfig].map(sc => Server.Config.default.binding(sc.host, sc.port))
      ) >>> Server.live,
      TracingLive.console,
      MetricsLive.console,
      SimulationSemaphore.layer,
      repoLayer,
      RiskTreeServiceLive.layer,
      CacheScope.layer,
      RiskResultResolverLive.layer,
      SSEHub.live,
      InvalidationHandler.live,
      WorkspaceStoreLive.layer,
      RateLimiterLive.layer,
      AuthorizationServiceNoOp.layer,
      BootstrapProvisionerNoOp.layer,
      ZLayer.succeed(UserContextExtractor.noOp),
      ZLayer.fromZIO(SystemController.makeZIO),
      ZLayer.fromZIO(WorkspaceLifecycleController.makeZIO),
      ZLayer.fromZIO(WorkspaceTreeController.makeZIO),
      ZLayer.fromZIO(WorkspaceAnalysisController.makeZIO),
      SSEController.layer,
      QueryServiceLive.layer,
      ZLayer.fromZIO(QueryController.makeZIO),
      DistributionPreviewService.layer,
      ZLayer.fromZIO(DistributionPreviewController.makeZIO)
    )

  private def waitForHealth(baseUrl: String): Task[Unit] =
    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"$baseUrl/health"))
      .timeout(Duration.ofSeconds(2))
      .GET()
      .build()

    val probe = ZIO.attempt {
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode() == 200
    }.mapError(e => new RuntimeException(s"health probe failed: ${e.getMessage}", e))
      .flatMap {
        case true  => ZIO.unit
        case false => ZIO.fail(new RuntimeException("health probe returned non-200"))
      }

    probe.retry(Schedule.recurs(10) && Schedule.spaced(200.millis))

  private def irminRepoLayer(
      irminConfigLayer: ZLayer[Any, Throwable, IrminConfig]
  ): ZLayer[Any, Throwable, RiskTreeRepository] =
    ZLayer.make[RiskTreeRepository](
      irminConfigLayer,
      IrminClientLive.layer >>> irminHealthCheck,
      RiskTreeRepositoryIrmin.layer
    )

  // Same startup readiness gate as Application (ADR-031) — no duplicated retry
  // logic. Runs on the live clock: the harness executes under zio-test, where
  // the default TestClock would never advance the gate's backoff sleeps.
  private val irminHealthCheck: ZLayer[IrminClient & IrminConfig, Throwable, IrminClient] =
    ZLayer.fromZIO {
      for
        cfg    <- ZIO.service[IrminConfig]
        client <- ZIO.service[IrminClient]
        _      <- ZIO.withClock(Clock.ClockLive) {
                    StartupReadiness.awaitReady(
                      name           = s"irmin (${cfg.url})",
                      probe          = client.healthCheck,
                      attemptTimeout = cfg.healthCheckAttemptTimeout,
                      budget         = cfg.healthCheckBudget
                    )
                  }
        _      <- ZIO.logInfo(s"Irmin repository selected at ${cfg.url}")
      yield client
    }


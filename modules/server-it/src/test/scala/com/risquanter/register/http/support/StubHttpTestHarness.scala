package com.risquanter.register.http.support

import zio.*
import sttp.client3.*
import sttp.monad.MonadError
import sttp.client3.testing.SttpBackendStub
import sttp.tapir.server.stub.*
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.configs.{IrminConfig, SimulationConfig, TelemetryConfig, WorkspaceConfig}
import com.risquanter.register.domain.data.iron.Url
import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.controllers.{SystemController, WorkspaceLifecycleController, WorkspaceTreeController, WorkspaceAnalysisController, QueryController, DistributionPreviewController, ScenarioController}
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryInMemory, RiskTreeRepositoryIrmin}
import com.risquanter.register.services.{RiskTreeServiceLive, ScenarioDiffServiceLive, ScenarioServiceNotSupported, ScenarioMergeServiceNotSupported}
import com.risquanter.register.services.SimulationSemaphore
import com.risquanter.register.services.cache.{RiskResultResolverLive, CacheScope}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.QueryServiceLive
import com.risquanter.register.services.DistributionPreviewService
import com.risquanter.register.services.workspace.{WorkspaceStoreLive, RateLimiterLive}
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.telemetry.{MetricsLive, TracingLive}
import com.risquanter.register.infra.irmin.IrminClientLive
import com.risquanter.register.auth.{AuthorizationServiceNoOp, BootstrapProvisionerNoOp, UserContextExtractor}
import io.github.iltotore.iron.*

object StubHttpTestHarness {

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
    serviceName = "register-test",
    instrumentationScope = "com.risquanter.register",
    otlpEndpoint = TestSafeUrls.localhostOtlpEndpoint,
    devExportIntervalSeconds = 5,
    prodExportIntervalSeconds = 60
  )

  private given MonadError[Task] = new RIOMonadError[Any]

  /** Build stub backend wired to provided repository layer. */
  def backendFor(
      repoLayer: ZLayer[Any, Throwable, RiskTreeRepository],
      simConfig: SimulationConfig = defaultSimulationConfig
  ): ZIO[Any, Throwable, SttpBackend[Task, Any]] =
    val controllersLayer: ZLayer[Any, Throwable, SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & QueryController & DistributionPreviewController & ScenarioController] =
      ZLayer.make[SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController & SSEController & QueryController & DistributionPreviewController & ScenarioController](
        ZLayer.succeed(simConfig),
        ZLayer.succeed(defaultTelemetryConfig),
        ZLayer.succeed(WorkspaceConfig()),
        TracingLive.console,
        MetricsLive.console,
        SimulationSemaphore.layer,
        repoLayer,
        CacheScope.layer,
        RiskResultResolverLive.layer,
        RiskTreeServiceLive.layer,
        ScenarioDiffServiceLive.layer,
        SSEHub.live,
        InvalidationHandler.live,
        WorkspaceStoreLive.layer,
        RateLimiterLive.layer,
        AuthorizationServiceNoOp.layer,
        ZLayer.succeed(UserContextExtractor.noOp),
        ZLayer.succeed(BootstrapProvisionerNoOp),
        // Stub only — this harness doesn't wire ScenarioController/IrminClient,
        // so a test exercising X-Active-Branch against an Irmin-backed repoLayer
        // would get 501 here rather than a working scenario. Fine for today's
        // suites (none exercise that path); revisit if/when one does.
        ScenarioServiceNotSupported.layer,
        ScenarioMergeServiceNotSupported.layer,
        ZLayer.fromZIO(SystemController.makeZIO),
        ZLayer.fromZIO(WorkspaceLifecycleController.makeZIO),
        ZLayer.fromZIO(WorkspaceTreeController.makeZIO),
        ZLayer.fromZIO(WorkspaceAnalysisController.makeZIO),
        ZLayer.fromZIO(ScenarioController.makeZIO),
        SSEController.layer,
          QueryServiceLive.layer,
        ZLayer.fromZIO(QueryController.makeZIO),
        DistributionPreviewService.layer,
        ZLayer.fromZIO(DistributionPreviewController.makeZIO)
      )

    for
      endpoints <- HttpApi.endpointsZIO.provideLayer(controllersLayer)
      backend = TapirStubInterpreter(SttpBackendStub[Task, Any](summon[MonadError[Task]]))
        .whenServerEndpointsRunLogic(endpoints)
        .backend()
    yield backend

  /** In-memory backend (no external dependencies). */
  def inMemoryBackend(simConfig: SimulationConfig = defaultSimulationConfig): ZIO[Any, Throwable, SttpBackend[Task, Any]] =
    backendFor(RiskTreeRepositoryInMemory.layer, simConfig)

  /** Irmin-backed backend if IRMIN_URL is set; otherwise returns None. */
  def irminBackendFromEnv(simConfig: SimulationConfig = defaultSimulationConfig): ZIO[Any, Throwable, Option[SttpBackend[Task, Any]]] =
    sys.env.get("IRMIN_URL") match
      case None => ZIO.succeed(None)
      case Some(urlStr) =>
        for
          url <- ZIO.fromEither(Url.fromString(urlStr).left.map(errs => new RuntimeException(errs.map(_.message).mkString("; "))))
          cfg  = IrminConfig(url = url)
          backend <- backendFor(ZLayer.succeed(cfg) >>> IrminClientLive.layer >>> RiskTreeRepositoryIrmin.layer, simConfig)
        yield Some(backend)
}

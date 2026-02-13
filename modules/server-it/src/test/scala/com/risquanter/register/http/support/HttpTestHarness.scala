package com.risquanter.register.http.support

import zio.*
import sttp.client3.*
import sttp.monad.MonadError
import sttp.client3.testing.SttpBackendStub
import sttp.tapir.server.stub.*
import sttp.tapir.ztapir.RIOMonadError
import com.risquanter.register.configs.{IrminConfig, SimulationConfig, TelemetryConfig}
import com.risquanter.register.domain.data.iron.SafeUrl
import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.cache.CacheController
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryInMemory, RiskTreeRepositoryIrmin}
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.services.SimulationSemaphore
import com.risquanter.register.services.cache.{RiskResultResolverLive, TreeCacheManager}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.telemetry.{MetricsLive, TracingLive}
import com.risquanter.register.infra.irmin.IrminClientLive
import io.github.iltotore.iron.*

object HttpTestHarness {

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
    otlpEndpoint = "http://localhost:4317",
    devExportIntervalSeconds = 5,
    prodExportIntervalSeconds = 60
  )

  private given MonadError[Task] = new RIOMonadError[Any]

  /** Build stub backend wired to provided repository layer. */
  def backendFor(
      repoLayer: ZLayer[Any, Throwable, RiskTreeRepository],
      simConfig: SimulationConfig = defaultSimulationConfig
  ): ZIO[Any, Throwable, SttpBackend[Task, Any]] =
    val controllersLayer: ZLayer[Any, Throwable, RiskTreeController & SSEController & CacheController] =
      ZLayer.make[RiskTreeController & SSEController & CacheController](
        ZLayer.succeed(simConfig),
        ZLayer.succeed(defaultTelemetryConfig),
        TracingLive.console,
        MetricsLive.console,
        SimulationSemaphore.layer,
        repoLayer,
        TreeCacheManager.layer,
        RiskResultResolverLive.layer,
        RiskTreeServiceLive.layer,
        SSEHub.live,
        InvalidationHandler.live,
        SSEController.layer,
        CacheController.layer,
        ZLayer.fromZIO(RiskTreeController.makeZIO)
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
          url <- ZIO.fromEither(SafeUrl.fromString(urlStr).left.map(errs => new RuntimeException(errs.map(_.message).mkString("; "))))
          cfg  = IrminConfig(url = url)
          backend <- backendFor(ZLayer.succeed(cfg) >>> IrminClientLive.layer >>> RiskTreeRepositoryIrmin.layer, simConfig)
        yield Some(backend)
}

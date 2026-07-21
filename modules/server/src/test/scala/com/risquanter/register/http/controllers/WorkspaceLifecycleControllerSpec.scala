package com.risquanter.register.http.controllers

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.stub.*
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.auth.{
  AuthorizationServiceNoOp, BootstrapProvisioner, BootstrapProvisionerNoOp,
  BootstrapProvisionerStub, UserContextExtractor
}
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, BranchRef}
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.http.requests.{DistributionShapeRequest, RiskLeafDefinitionRequest, RiskPortfolioDefinitionRequest, RiskTreeDefinitionRequest}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.services.{RiskTreeService, RiskTreeServiceLive, ScenarioService, ScenarioServiceNotSupported}
import com.risquanter.register.services.cache.{RiskResultResolverLive, CacheScope}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.services.workspace.{RateLimiterLive, WorkspaceStoreLive}
import com.risquanter.register.services.SimulationSemaphore
import com.risquanter.register.telemetry.{MetricsLive, TracingLive}

/** Unit tests for [[WorkspaceLifecycleController]] bootstrap endpoint (Wave 6).
  *
  * Verifies that:
  *  - [[BootstrapProvisionerStub]] records one `recordOwnership` call after successful bootstrap
  *    (fine-grained mode — requirePresent extractor + x-user-id header)
  *  - `recordOwnership` failure propagates as an HTTP error response
  *  - Bootstrap succeeds in capability-only mode (noOp extractor, NoOp provisioner)
  *  - Absent `x-user-id` header in identity/fine-grained mode is rejected with 403
  *
  * @see AUTHORIZATION-IMPLEMENTATION-PLAN.md §Wave 6 — Updated tests
  */
object WorkspaceLifecycleControllerSpec extends ZIOSpecDefault:

  private given MonadError[Task] = new RIOMonadError[Any]

  // ── Stub repository ───────────────────────────────────────────────────────────
  // `def` (not `val`) so each buildBackend call gets a fresh, isolated map.
  // A shared mutable.Map across ZIO Test's parallel test execution is a data race.

  private def stubRepo: RiskTreeRepository = new RiskTreeRepository:
    private val db = collection.mutable.Map[(WorkspaceId, TreeId), RiskTree]()
    override def create(wsId: WorkspaceId, t: RiskTree, branch: Option[BranchRef] = None): Task[RiskTree] =
      com.risquanter.register.util.IdGenerators.nextTreeId.map { id =>
        val tree = t.copy(id = id); db += ((wsId, id) -> tree); tree
      }
    override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: Option[BranchRef] = None): Task[RiskTree] =
      ZIO.attempt { val t = db((wsId, id)); val u = op(t); db += ((wsId, id) -> u); u }
    override def delete(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None): Task[RiskTree] =
      ZIO.attempt { val t = db((wsId, id)); db -= ((wsId, id)); t }
    override def getById(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None): Task[Option[RiskTree]] =
      ZIO.succeed(db.get((wsId, id)))
    override def getAllForWorkspace(wsId: WorkspaceId, branch: Option[BranchRef] = None): Task[List[Either[RepositoryFailure, RiskTree]]] =
      ZIO.succeed(db.collect { case ((wid, _), t) if wid == wsId => Right(t) }.toList)

  // ── Controller factory ───────────────────────────────────────────────────────

  private def buildBackend(
    extractor:   UserContextExtractor,
    provisioner: BootstrapProvisioner
  ): ZIO[Any, Nothing, SttpBackend[Task, Any]] =
    WorkspaceLifecycleController.makeZIO
      .provide(
        ZLayer.succeed(stubRepo),
        TestConfigs.simulationLayer,
        TestConfigs.workspaceLayer,
        TestConfigs.telemetryLayer >>> TracingLive.console,
        TestConfigs.telemetryLayer >>> MetricsLive.console,
        SimulationSemaphore.layer,
        RiskTreeServiceLive.layer,
        CacheScope.layer,
        RiskResultResolverLive.layer,
        SSEHub.live,
        InvalidationHandler.live,
        WorkspaceStoreLive.layer,
        RateLimiterLive.layer,
        AuthorizationServiceNoOp.layer,
        ZLayer.succeed(extractor),
        ZLayer.succeed(provisioner),
        // Bootstrap never sets X-Active-Branch — the not-supported stub is
        // adequate here, this suite doesn't exercise scenario branches.
        ScenarioServiceNotSupported.layer
      )
      .orDie
      .map { ctrl =>
        TapirStubInterpreter(SttpBackendStub[Task, Any](summon[MonadError[Task]]))
          .whenServerEndpointsRunLogic(ctrl.routes)
          .backend()
      }

  // ── Request helpers ──────────────────────────────────────────────────────────

  private val validRequest = RiskTreeDefinitionRequest(
    name = "Bootstrap Test Tree",
    portfolios = Seq(RiskPortfolioDefinitionRequest("Root", None)),
    leaves = Seq(
      RiskLeafDefinitionRequest(
        name = "Test Leaf",
        parentName = Some("Root"),
        probability = 0.1,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          percentiles = None, quantiles = None, terms = None,
          minLoss = Some(1000L), maxLoss = Some(50000L)
        )
      )
    )
  )

  private val validUuid = "550e8400-e29b-41d4-a716-446655440000"

  private def bootstrapRequest(
    backend: SttpBackend[Task, Any],
    userId:  Option[String] = None
  ): Task[Response[Either[String, String]]] =
    val base = basicRequest
      .post(uri"http://localhost/workspaces")
      .body(validRequest.toJson)
      .contentType("application/json")
    userId.fold(base)(id => base.header("x-user-id", id)).send(backend)

  // ── Spec ─────────────────────────────────────────────────────────────────────

  override def spec = suite("WorkspaceLifecycleController — bootstrapWorkspace (Wave 6)")(

    test("capability-only mode: bootstrap succeeds without x-user-id header") {
      for
        backend  <- buildBackend(UserContextExtractor.noOp, BootstrapProvisionerNoOp)
        response <- bootstrapRequest(backend, userId = None)
      yield assertTrue(response.code.code == 200)
    },

    test("fine-grained mode: bootstrap records one recordOwnership call with correct userId") {
      for
        stub     <- BootstrapProvisionerStub.make
        backend  <- buildBackend(UserContextExtractor.requirePresent, stub)
        response <- bootstrapRequest(backend, userId = Some(validUuid))
        calls    <- stub.calls
      yield assertTrue(
        response.code.code == 200,
        calls.size == 1,
        calls.head._1.value == validUuid
      )
    },

    test("fine-grained mode: absent x-user-id header is rejected with 403") {
      for
        stub     <- BootstrapProvisionerStub.make
        backend  <- buildBackend(UserContextExtractor.requirePresent, stub)
        response <- bootstrapRequest(backend, userId = None)
        calls    <- stub.calls
      yield assertTrue(
        response.code.code == 403,
        calls.isEmpty  // recordOwnership never reached
      )
    },

    test("recordOwnership failure propagates as 403") {
      for
        stub     <- BootstrapProvisionerStub.makeFailing
        backend  <- buildBackend(UserContextExtractor.requirePresent, stub)
        response <- bootstrapRequest(backend, userId = Some(validUuid))
      yield assertTrue(response.code.code == 403)
    }
  )

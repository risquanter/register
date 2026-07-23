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
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, BranchRef, ScenarioName, CommitHash}
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.http.requests.{DistributionShapeRequest, RiskLeafDefinitionRequest, RiskPortfolioDefinitionRequest, RiskTreeDefinitionRequest}
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.services.{CascadeTestStubs, RiskTreeService, RiskTreeServiceLive, ScenarioService, ScenarioServiceNotSupported, ScenarioSummary}
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

  // Keyed by (workspace, branch, tree) — mirrors the real Irmin-backed
  // repository's branch isolation (RiskTreeRepository doc: "None" targets
  // main; a tree written on one branch does not exist on another until
  // forked). Branch-blind keying would silently pass any branch-aware
  // controller test regardless of whether the branch was actually threaded.
  private def stubRepo: RiskTreeRepository = new RiskTreeRepository:
    private val db = collection.mutable.Map[(WorkspaceId, BranchRef, TreeId), RiskTree]()
    override def create(wsId: WorkspaceId, t: RiskTree, branch: BranchRef = BranchRef.Main): Task[RiskTree] =
      com.risquanter.register.util.IdGenerators.nextTreeId.map { id =>
        val tree = t.copy(id = id); db += ((wsId, branch, id) -> tree); tree
      }
    override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef = BranchRef.Main): Task[RiskTree] =
      ZIO.attempt { val t = db((wsId, branch, id)); val u = op(t); db += ((wsId, branch, id) -> u); u }
    override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef = BranchRef.Main): Task[RiskTree] =
      ZIO.attempt { val t = db((wsId, branch, id)); db -= ((wsId, branch, id)); t }
    override def getById(wsId: WorkspaceId, id: TreeId, branch: BranchRef = BranchRef.Main): Task[Option[RiskTree]] =
      ZIO.succeed(db.get((wsId, branch, id)))
    override def getAllForWorkspace(wsId: WorkspaceId, branch: BranchRef = BranchRef.Main): Task[List[Either[RepositoryFailure, RiskTree]]] =
      ZIO.succeed(db.collect { case ((wid, b, _), t) if wid == wsId && b == branch => Right(t) }.toList)

  // ── Controller factory ───────────────────────────────────────────────────────

  private def buildBackend(
    extractor:       UserContextExtractor,
    provisioner:     BootstrapProvisioner,
    // Bootstrap itself never sets X-Active-Branch — the not-supported stub is
    // adequate for the bootstrap-only tests below. The branch-scoped listing
    // test overrides this with a stub that knows about one scenario.
    scenarioService: ScenarioService = ScenarioServiceNotSupported
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
        ZLayer.succeed(scenarioService)
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

  private def scenarioBranch(name: String): ScenarioName.ScenarioName = ScenarioName.fromString(name).toOption.get

  private def createTreeRequest(
    backend: SttpBackend[Task, Any],
    key:     String,
    name:    String,
    branch:  Option[String]
  ): Task[Response[Either[String, String]]] =
    val base = basicRequest
      .post(uri"http://localhost/w/$key/risk-trees")
      .body(validRequest.copy(name = name).toJson)
      .contentType("application/json")
    branch.fold(base)(b => base.header("X-Active-Branch", b)).send(backend)

  private def listTreesRequest(
    backend: SttpBackend[Task, Any],
    key:     String,
    branch:  Option[String]
  ): Task[Response[Either[String, String]]] =
    val base = basicRequest.get(uri"http://localhost/w/$key/risk-trees")
    branch.fold(base)(b => base.header("X-Active-Branch", b)).send(backend)

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
    },

    test("listWorkspaceTrees is branch-scoped: X-Active-Branch selects which trees are visible") {
      val branchName = scenarioBranch("stress-2026")
      val scenarioSvc = CascadeTestStubs.scenarioService(
        onList   = _ => ZIO.succeed(List(ScenarioSummary(branchName, CommitHash.fromString("a" * 40).toOption.get))),
        onDelete = (_, _, _) => ZIO.die(new UnsupportedOperationException)
      )
      for
        backend      <- buildBackend(UserContextExtractor.noOp, BootstrapProvisionerNoOp, scenarioSvc)
        bootstrapResp <- bootstrapRequest(backend, userId = None)
        bootstrap     = bootstrapResp.body.toOption.get.fromJson[WorkspaceBootstrapResponse].toOption.get
        key           = bootstrap.workspaceKey.reveal
        createResp   <- createTreeRequest(backend, key, "Scenario Tree", branch = Some("stress-2026"))
        mainListResp <- listTreesRequest(backend, key, branch = None)
        mainTrees     = mainListResp.body.toOption.get.fromJson[List[SimulationResponse]].toOption.get
        branchListResp <- listTreesRequest(backend, key, branch = Some("stress-2026"))
        branchTrees   = branchListResp.body.toOption.get.fromJson[List[SimulationResponse]].toOption.get
      yield assertTrue(
        createResp.code.code == 200,
        mainTrees.map(_.name) == List("Bootstrap Test Tree"),
        branchTrees.map(_.name) == List("Scenario Tree")
      )
      // IdGenerators.nextTreeId needs a real Clock/Random to produce distinct
      // IDs across the two `create` calls in this test — ZIO Test's default
      // fiber environment is a deterministic TestClock/TestRandom, which makes
      // a freshly-provisioned ULIDGen emit the same first ULID every time
      // (documented gotcha, IdGenerators.scala:13-18). Every other test in
      // this file creates at most one tree, so this is the first to need it.
    } @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom
  )

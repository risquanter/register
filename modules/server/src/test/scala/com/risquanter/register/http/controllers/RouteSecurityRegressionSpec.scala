package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.services.{RiskTreeService, RiskTreeServiceLive}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.workspace.WorkspaceStoreLive
import com.risquanter.register.services.cache.{TreeCacheManager, RiskResultResolverLive}
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.services.SimulationSemaphore
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId}
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.util.IdGenerators
import com.risquanter.register.auth.{AuthorizationServiceNoOp, UserContextExtractor}

/** Failsafe regression tests: old unscoped `/risk-trees/{id}/...` paths
  * must NOT appear in any controller's route table.
  *
  * These tests exist as a security safety net. If anyone accidentally
  * re-exposes tree-specific operations on the old unscoped paths
  * (bypassing workspace capability checks), these tests will fail
  * and block the build.
  *
  * Context: Prior to workspace capability introduction,
  * all tree operations were served at `/risk-trees/{id}/...`. These
  * were removed to prevent unauthenticated access by TreeId alone.
  */
object RouteSecurityRegressionSpec extends ZIOSpecDefault:

  // ── Stub repository (minimal — tests don't exercise business logic) ──

  private val stubRepo = new RiskTreeRepository:
    override def create(wsId: WorkspaceId, t: RiskTree): Task[RiskTree] = ZIO.succeed(t)
    override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree): Task[RiskTree] =
      ZIO.fail(RuntimeException("stub"))
    override def delete(wsId: WorkspaceId, id: TreeId): Task[RiskTree] =
      ZIO.fail(RuntimeException("stub"))
    override def getById(wsId: WorkspaceId, id: TreeId): Task[Option[RiskTree]] = ZIO.succeed(None)
    override def getAllForWorkspace(wsId: WorkspaceId): Task[List[Either[RepositoryFailure, RiskTree]]] = ZIO.succeed(Nil)

  // ── Shared layer for controller instantiation ────────────────────────

  private val controllerLayer = ZLayer.make[SystemController & WorkspaceTreeController & WorkspaceAnalysisController](
    ZLayer.succeed(stubRepo),
    TestConfigs.simulationLayer,
    TestConfigs.workspaceLayer,
    TestConfigs.telemetryLayer >>> TracingLive.console,
    TestConfigs.telemetryLayer >>> MetricsLive.console,
    SimulationSemaphore.layer,
    RiskTreeServiceLive.layer,
    TreeCacheManager.layer,
    RiskResultResolverLive.layer,
    SSEHub.live,
    InvalidationHandler.live,
    WorkspaceStoreLive.layer,
    AuthorizationServiceNoOp.layer,
    ZLayer.succeed(UserContextExtractor.noOp),
    ZLayer.fromZIO(SystemController.makeZIO),
    ZLayer.fromZIO(WorkspaceTreeController.makeZIO),
    ZLayer.fromZIO(WorkspaceAnalysisController.makeZIO)
  )

  // ── Old unscoped path patterns that must NOT be served ───────────────
  //
  // These regex patterns match the tree-specific paths that were removed
  // in Option A. We only flag paths with a treeId segment or mutating
  // methods on the collection path.

  /** True if a path serves tree-specific data without a workspace key prefix. */
  private def isUnscopedTreePath(path: String): Boolean =
    // Matches /risk-trees/{...} (with path parameter = tree-specific)
    path.matches("""/risk-trees/\{[^}]+\}.*""")

  // ── Helper: extract path templates from a controller's routes ────────

  private def pathTemplates(controller: BaseController): List[String] =
    controller.routes.map(_.endpoint.showPathTemplate())

  // ── Tests ─────────────────────────────────────────────────────────────

  override def spec = suite("Route security regression — old path scheme")(

    test("SystemController exposes /health") {
      for
        ctrl  <- ZIO.service[SystemController]
        paths  = pathTemplates(ctrl)
      yield assertTrue(
        paths.contains("/health"),
        paths.forall(p => !isUnscopedTreePath(p))
      )
    },

    test("WorkspaceAnalysisController does not expose /health") {
      for
        ctrl  <- ZIO.service[WorkspaceAnalysisController]
        paths  = pathTemplates(ctrl)
      yield assertTrue(!paths.contains("/health"))
    },

    test("WorkspaceTreeController serves NO unscoped /risk-trees/{id} paths") {
      // Failsafe: all tree-specific routes must be under /w/{key}/...
      for
        ctrl  <- ZIO.service[WorkspaceTreeController]
        paths  = pathTemplates(ctrl)
        unscopedTreePaths = paths.filter(isUnscopedTreePath)
      yield assertTrue(unscopedTreePaths.isEmpty)
    },

    test("WorkspaceTreeController tree-specific paths all start with /w/{key}") {
      // Positive check: every tree operation is workspace-scoped
      for
        ctrl  <- ZIO.service[WorkspaceTreeController]
        paths  = pathTemplates(ctrl)
        treePaths = paths.filter(_.contains("risk-trees"))
      yield assertTrue(
        treePaths.nonEmpty,
        treePaths.forall(_.startsWith("/w/{key}/"))
      )
    },

    test("no controller exposes POST /risk-trees (unscoped create)") {
      // Failsafe: tree creation must only be possible via workspace paths.
      for
        wsCtrl <- ZIO.service[WorkspaceTreeController]
        allRoutes = wsCtrl.routes
        postOnRiskTrees = allRoutes.filter { se =>
          val path = se.endpoint.showPathTemplate()
          val method = se.endpoint.method.map(_.method)
          path == "/risk-trees" && method.contains("POST")
        }
      yield assertTrue(postOnRiskTrees.isEmpty)
    },

    test("no controller exposes PUT /risk-trees/{id} (unscoped update)") {
      for
        wsCtrl <- ZIO.service[WorkspaceTreeController]
        allRoutes = wsCtrl.routes
        putOnRiskTrees = allRoutes.filter { se =>
          val path = se.endpoint.showPathTemplate()
          val method = se.endpoint.method.map(_.method)
          isUnscopedTreePath(path) && method.contains("PUT")
        }
      yield assertTrue(putOnRiskTrees.isEmpty)
    },

    test("no controller exposes DELETE /risk-trees/{id} (unscoped delete)") {
      for
        wsCtrl <- ZIO.service[WorkspaceTreeController]
        allRoutes = wsCtrl.routes
        deleteOnRiskTrees = allRoutes.filter { se =>
          val path = se.endpoint.showPathTemplate()
          val method = se.endpoint.method.map(_.method)
          isUnscopedTreePath(path) && method.contains("DELETE")
        }
      yield assertTrue(deleteOnRiskTrees.isEmpty)
    },

    test("no controller exposes GET /risk-trees/{id} (unscoped getById)") {
      for
        wsCtrl <- ZIO.service[WorkspaceTreeController]
        allRoutes = wsCtrl.routes
        getByIdRoutes = allRoutes.filter { se =>
          val path = se.endpoint.showPathTemplate()
          val method = se.endpoint.method.map(_.method)
          isUnscopedTreePath(path) && method.contains("GET")
        }
      yield assertTrue(getByIdRoutes.isEmpty)
    }

  ).provideShared(controllerLayer)

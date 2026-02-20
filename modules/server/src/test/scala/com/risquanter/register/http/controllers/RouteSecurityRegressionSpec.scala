package com.risquanter.register.http.controllers

import zio.*
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.configs.{ApiConfig, TestConfigs}
import com.risquanter.register.services.{RiskTreeService, RiskTreeServiceLive}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.workspace.{WorkspaceStoreLive, RateLimiterLive}
import com.risquanter.register.services.cache.{TreeCacheManager, RiskResultResolverLive}
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.services.SimulationSemaphore
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.TreeId
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
  * Context: Prior to workspace capability introduction (Tier 1.5),
  * all tree operations were served at `/risk-trees/{id}/...`. These
  * were removed (Option A) to prevent unauthenticated access by
  * TreeId alone. Only `/health` and the config-gated `GET /risk-trees`
  * remain on unscoped paths.
  */
object RouteSecurityRegressionSpec extends ZIOSpecDefault:

  // ── Stub repository (minimal — tests don't exercise business logic) ──

  private val stubRepo = new RiskTreeRepository:
    override def create(t: RiskTree): Task[RiskTree] = ZIO.succeed(t)
    override def update(id: TreeId, op: RiskTree => RiskTree): Task[RiskTree] =
      ZIO.fail(RuntimeException("stub"))
    override def delete(id: TreeId): Task[RiskTree] =
      ZIO.fail(RuntimeException("stub"))
    override def getById(id: TreeId): Task[Option[RiskTree]] = ZIO.succeed(None)
    override def getAll: Task[List[Either[RepositoryFailure, RiskTree]]] = ZIO.succeed(Nil)

  // ── Shared layer for controller instantiation ────────────────────────

  private val controllerLayer = ZLayer.make[RiskTreeController & WorkspaceController](
    ZLayer.succeed(stubRepo),
    ZLayer.succeed(ApiConfig()),
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
    RateLimiterLive.layer,
    AuthorizationServiceNoOp.layer,
    ZLayer.succeed(UserContextExtractor.noOp),
    ZLayer.fromZIO(RiskTreeController.makeZIO),
    ZLayer.fromZIO(WorkspaceController.makeZIO)
  )

  // ── Old unscoped path patterns that must NOT be served ───────────────
  //
  // These regex patterns match the tree-specific paths that were removed
  // in Option A. The `/risk-trees` list endpoint (GET) is intentionally
  // kept (config-gated via A17), so we only flag paths with a treeId
  // segment or POST/PUT/DELETE on the collection path.

  /** True if a path serves tree-specific data without a workspace key prefix. */
  private def isUnscopedTreePath(path: String): Boolean =
    // Matches /risk-trees/{...} (with path parameter = tree-specific)
    // but NOT /risk-trees (the list-all endpoint)
    path.matches("""/risk-trees/\{[^}]+\}.*""")

  // ── Helper: extract path templates from a controller's routes ────────

  private def pathTemplates(controller: BaseController): List[String] =
    controller.routes.map(_.endpoint.showPathTemplate())

  // ── Tests ─────────────────────────────────────────────────────────────

  override def spec = suite("Route security regression — old path scheme")(

    test("RiskTreeController exposes only /health and /risk-trees") {
      // Failsafe: RiskTreeController must NOT serve any tree-specific paths.
      // Only /health and /risk-trees (list-all, config-gated) are allowed.
      for
        ctrl  <- ZIO.service[RiskTreeController]
        paths  = pathTemplates(ctrl)
      yield assertTrue(
        paths.length == 2,
        paths.contains("/health"),
        paths.contains("/risk-trees"),
        paths.forall(p => !isUnscopedTreePath(p))
      )
    },

    test("WorkspaceController serves NO unscoped /risk-trees/{id} paths") {
      // Failsafe: all tree-specific routes must be under /w/{key}/...
      for
        ctrl  <- ZIO.service[WorkspaceController]
        paths  = pathTemplates(ctrl)
        unscopedTreePaths = paths.filter(isUnscopedTreePath)
      yield assertTrue(unscopedTreePaths.isEmpty)
    },

    test("WorkspaceController tree-specific paths all start with /w/{key}") {
      // Positive check: every tree operation is workspace-scoped
      for
        ctrl  <- ZIO.service[WorkspaceController]
        paths  = pathTemplates(ctrl)
        treePaths = paths.filter(_.contains("risk-trees"))
      yield assertTrue(
        treePaths.nonEmpty,
        treePaths.forall(_.startsWith("/w/{key}/"))
      )
    },

    test("no controller exposes POST /risk-trees (unscoped create)") {
      // Failsafe: tree creation must only be possible via workspace paths.
      // GET /risk-trees is allowed (config-gated list), but POST is not.
      for
        rtCtrl <- ZIO.service[RiskTreeController]
        wsCtrl <- ZIO.service[WorkspaceController]
        allRoutes = rtCtrl.routes ++ wsCtrl.routes
        postOnRiskTrees = allRoutes.filter { se =>
          val path = se.endpoint.showPathTemplate()
          val method = se.endpoint.method.map(_.method)
          path == "/risk-trees" && method.contains("POST")
        }
      yield assertTrue(postOnRiskTrees.isEmpty)
    },

    test("no controller exposes PUT /risk-trees/{id} (unscoped update)") {
      for
        rtCtrl <- ZIO.service[RiskTreeController]
        wsCtrl <- ZIO.service[WorkspaceController]
        allRoutes = rtCtrl.routes ++ wsCtrl.routes
        putOnRiskTrees = allRoutes.filter { se =>
          val path = se.endpoint.showPathTemplate()
          val method = se.endpoint.method.map(_.method)
          isUnscopedTreePath(path) && method.contains("PUT")
        }
      yield assertTrue(putOnRiskTrees.isEmpty)
    },

    test("no controller exposes DELETE /risk-trees/{id} (unscoped delete)") {
      for
        rtCtrl <- ZIO.service[RiskTreeController]
        wsCtrl <- ZIO.service[WorkspaceController]
        allRoutes = rtCtrl.routes ++ wsCtrl.routes
        deleteOnRiskTrees = allRoutes.filter { se =>
          val path = se.endpoint.showPathTemplate()
          val method = se.endpoint.method.map(_.method)
          isUnscopedTreePath(path) && method.contains("DELETE")
        }
      yield assertTrue(deleteOnRiskTrees.isEmpty)
    },

    test("no controller exposes GET /risk-trees/{id} (unscoped getById)") {
      for
        rtCtrl <- ZIO.service[RiskTreeController]
        wsCtrl <- ZIO.service[WorkspaceController]
        allRoutes = rtCtrl.routes ++ wsCtrl.routes
        getByIdRoutes = allRoutes.filter { se =>
          val path = se.endpoint.showPathTemplate()
          val method = se.endpoint.method.map(_.method)
          isUnscopedTreePath(path) && method.contains("GET")
        }
      yield assertTrue(getByIdRoutes.isEmpty)
    }

  ).provideShared(controllerLayer)

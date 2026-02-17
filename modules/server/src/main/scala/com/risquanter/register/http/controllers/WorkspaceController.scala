package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.http.endpoints.WorkspaceEndpoints
import com.risquanter.register.http.endpoints.InvalidationResponse
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, WorkspaceRotateResponse, SimulationResponse}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.workspace.{WorkspaceStore, RateLimiter}
import com.risquanter.register.domain.data.iron.{WorkspaceKeySecret, TreeId, NodeId}
import com.risquanter.register.domain.errors.TreeNotInWorkspace

/** Workspace controller.
  *
  * All tree-specific operations are served exclusively under
  * `/w/{key}/...`. The old unscoped `/risk-trees/` paths were removed
  * (Option A) to prevent unauthenticated access by TreeId alone.
  *
  * Security features:
  *   - A6:  DELETE /w/{key} cascade hard-delete
  *   - A13: Workspace errors mapped to opaque 404 via ErrorResponse.encode
  *   - A27: Bootstrap endpoint uses IP-based rate limiting
  *   - All tree operations validate workspace ownership via `resolveTree`
  */
class WorkspaceController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  rateLimiter: RateLimiter,
  invalidationHandler: InvalidationHandler
) extends BaseController
    with WorkspaceEndpoints:

  // ── Helpers ───────────────────────────────────────────────────────

  private def normaliseIp(xff: Option[String]): String =
    xff.flatMap(_.split(",").headOption).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")

  /** Resolve workspace + verify tree ownership.
    *
    * Shared by all tree-scoped endpoints under `/w/{key}/risk-trees/{treeId}/`.
    * Fails with TreeNotInWorkspace (opaque 404 via A13) if the tree
    * does not belong to the workspace.
    */
  private def resolveTree(key: WorkspaceKeySecret, treeId: TreeId): IO[Throwable, Unit] =
    for
      _       <- workspaceStore.resolve(key)
      belongs <- workspaceStore.belongsTo(key, treeId)
      _       <- ZIO.unless(belongs)(ZIO.fail(TreeNotInWorkspace(key, treeId)))
    yield ()

  // ── Workspace lifecycle ───────────────────────────────────────────

  val bootstrapWorkspace: ServerEndpoint[Any, Task] = bootstrapWorkspaceEndpoint.serverLogic {
    case (xff, req) =>
      val ip = normaliseIp(xff)
      (for
        _    <- rateLimiter.checkCreate(ip)
        key  <- workspaceStore.create()
        tree <- riskTreeService.create(req)
        _    <- workspaceStore.addTree(key, tree.id)
        ws   <- workspaceStore.resolve(key)
      yield WorkspaceBootstrapResponse(
        workspaceKey = key,
        tree = SimulationResponse.fromRiskTree(tree),
        expiresAt = ws.expiresAt
      )).either
  }

  val listWorkspaceTrees: ServerEndpoint[Any, Task] = listWorkspaceTreesEndpoint.serverLogic { key =>
    (for
      ids <- workspaceStore.listTrees(key)
      trees <- ZIO.foreach(ids)(riskTreeService.getById)
      existing = trees.collect { case Some(t) => SimulationResponse.fromRiskTree(t) }
    yield existing).either
  }

  val createWorkspaceTree: ServerEndpoint[Any, Task] = createWorkspaceTreeEndpoint.serverLogic {
    case (key, req) =>
      (for
        _    <- workspaceStore.resolve(key)
        tree <- riskTreeService.create(req)
        _    <- workspaceStore.addTree(key, tree.id)
      yield SimulationResponse.fromRiskTree(tree)).either
  }

  val rotateWorkspace: ServerEndpoint[Any, Task] = rotateWorkspaceKeySecretEndpoint.serverLogic { key =>
    (for
      newKey <- workspaceStore.rotate(key)
      ws     <- workspaceStore.resolve(newKey)
    yield WorkspaceRotateResponse(newKey, ws.expiresAt)).either
  }

  val deleteWorkspace: ServerEndpoint[Any, Task] = deleteWorkspaceEndpoint.serverLogic { key =>
    (for
      ids <- workspaceStore.listTrees(key)
      // Best-effort cascade: ignore individual tree deletion failures
      // (tree may already be gone; cascade should not abort)
      _   <- ZIO.foreachDiscard(ids)(id => riskTreeService.delete(id).ignore)
      _   <- workspaceStore.delete(key)
    yield ()).either
  }

  val evictExpired: ServerEndpoint[Any, Task] = evictExpiredEndpoint.serverLogicSuccess { _ =>
    workspaceStore.evictExpired.map(n => Map("evicted" -> n))
  }

  // ── Workspace-scoped tree operations ──────────────────────────────

  val getTreeById: ServerEndpoint[Any, Task] = getWorkspaceTreeByIdEndpoint.serverLogic {
    case (key, treeId) =>
      (resolveTree(key, treeId) *>
        riskTreeService.getById(treeId).map(_.map(SimulationResponse.fromRiskTree))
      ).either
  }

  val getTreeStructure: ServerEndpoint[Any, Task] = getWorkspaceTreeStructureEndpoint.serverLogic {
    case (key, treeId) =>
      (resolveTree(key, treeId) *> riskTreeService.getById(treeId)).either
  }

  val updateTree: ServerEndpoint[Any, Task] = updateWorkspaceTreeEndpoint.serverLogic {
    case (key, treeId, req) =>
      (resolveTree(key, treeId) *>
        riskTreeService.update(treeId, req).map(SimulationResponse.fromRiskTree)
      ).either
  }

  val deleteTree: ServerEndpoint[Any, Task] = deleteWorkspaceTreeEndpoint.serverLogic {
    case (key, treeId) =>
      (resolveTree(key, treeId) *>
        riskTreeService.delete(treeId)
          .tap(_ => workspaceStore.removeTree(key, treeId))
          .map(SimulationResponse.fromRiskTree)
      ).either
  }

  val invalidateCache: ServerEndpoint[Any, Task] = invalidateWorkspaceCacheEndpoint.serverLogic {
    case (key, treeId, nodeId) =>
      (for
        _    <- resolveTree(key, treeId)
        tree <- riskTreeService.getById(treeId).someOrFail(TreeNotInWorkspace(key, treeId))
        r    <- invalidationHandler.handleNodeChange(nodeId, tree)
      yield InvalidationResponse(
        invalidatedNodes = r.invalidatedNodes.map(_.value.toString),
        subscribersNotified = r.subscribersNotified
      )).either
  }

  // ── Workspace-scoped LEC queries ──────────────────────────────────

  val getLECCurve: ServerEndpoint[Any, Task] = getWorkspaceLECCurveEndpoint.serverLogic {
    case (key, treeId, nodeId, includeProvenance) =>
      (resolveTree(key, treeId) *>
        riskTreeService.getLECCurve(treeId, nodeId, includeProvenance)
      ).either
  }

  val probOfExceedance: ServerEndpoint[Any, Task] = getWorkspaceProbOfExceedanceEndpoint.serverLogic {
    case (key, treeId, nodeId, threshold, includeProvenance) =>
      (resolveTree(key, treeId) *>
        riskTreeService.probOfExceedance(treeId, nodeId, threshold, includeProvenance)
      ).either
  }

  val getLECCurvesMulti: ServerEndpoint[Any, Task] = getWorkspaceLECCurvesMultiEndpoint.serverLogic {
    case (key, treeId, includeProvenance, nodeIds) =>
      (resolveTree(key, treeId) *>
        riskTreeService.getLECCurvesMulti(treeId, nodeIds.toSet, includeProvenance)
          .map(_.map { case (nodeId, nodeCurve) => (nodeId.value, nodeCurve) })
      ).either
  }

  val getLECChart: ServerEndpoint[Any, Task] = getWorkspaceLECChartEndpoint.serverLogic {
    case (key, treeId, nodeIds) =>
      (resolveTree(key, treeId) *>
        riskTreeService.getLECChart(treeId, nodeIds.toSet)
      ).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      // Workspace lifecycle
      bootstrapWorkspace,
      listWorkspaceTrees,
      createWorkspaceTree,
      rotateWorkspace,
      deleteWorkspace,
      evictExpired,
      // Tree operations (workspace-scoped)
      getTreeById,
      getTreeStructure,
      updateTree,
      deleteTree,
      invalidateCache,
      // LEC queries (workspace-scoped)
      getLECCurve,
      probOfExceedance,
      getLECCurvesMulti,
      getLECChart
    )

object WorkspaceController:
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & RateLimiter & InvalidationHandler, Nothing, WorkspaceController] =
    for
      riskTreeService      <- ZIO.service[RiskTreeService]
      workspaceStore       <- ZIO.service[WorkspaceStore]
      rateLimiter          <- ZIO.service[RateLimiter]
      invalidationHandler  <- ZIO.service[InvalidationHandler]
    yield WorkspaceController(riskTreeService, workspaceStore, rateLimiter, invalidationHandler)

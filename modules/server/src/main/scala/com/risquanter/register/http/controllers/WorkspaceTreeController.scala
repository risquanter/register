package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Checked, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.http.endpoints.WorkspaceTreeEndpoints
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.workspace.WorkspaceStore

/** Workspace tree controller.
  *
  * Owns workspace-scoped CRUD for trees. (The manual cache-invalidation
  * endpoint was retired with the content-addressed cache — DD-20: under
  * content addressing there is nothing NodeId-keyed to invalidate.)
  *
  * Authorization layers:
  *  - Layer 0: [[WorkspaceStore.resolveTreeWorkspace]] validates the workspace key
  *    and asserts the tree belongs to that workspace.
  *  - Layer 1: [[UserContextExtractor.extract]] fails closed when `requirePresent`
  *    is injected via `register.auth.mode=identity`. The `UserId` is bound as
  *    `userId` because it is immediately consumed by the Layer 2 check.
  *  - Layer 2: `authzService.check(userId, Permission.*, ResourceRef(RiskTree, treeId))`
  *    calls are present in this controller, but the SpiceDB backend is not yet
  *    deployed (Phase K). Currently resolved by [[AuthorizationServiceNoOp]]
  *    (always-permit). Full enforcement activates when Phase K infra is provisioned.
  *
  * @see AUTHORIZATION-PLAN.md — Layered Model
  * @see ADR-024 — Application as Pure PEP
  */
class WorkspaceTreeController private (
  riskTreeService: RiskTreeService,
  workspaceStore: WorkspaceStore,
  authzService: AuthorizationService,
  userCtx: UserContextExtractor
) extends BaseController
    with WorkspaceTreeEndpoints:

  val getTreeById: ServerEndpoint[Any, Task] = getWorkspaceTreeByIdEndpoint.serverLogic {
    case (maybeUserId, key, treeId, activeBranch) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(key, ws.id, activeBranch)
        result <- riskTreeService.getById(ws.id, treeId, branch).map(_.map(SimulationResponse.fromRiskTree))
      yield result).either
  }

  val getTreeStructure: ServerEndpoint[Any, Task] = getWorkspaceTreeStructureEndpoint.serverLogic {
    case (maybeUserId, key, treeId, activeBranch) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(key, ws.id, activeBranch)
        result <- riskTreeService.getById(ws.id, treeId, branch)
      yield result).either
  }

  val updateTree: ServerEndpoint[Any, Task] = updateWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, req, activeBranch) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(key, ws.id, activeBranch)
        result <- riskTreeService.update(ws.id, treeId, req, branch).map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  val deleteTree: ServerEndpoint[Any, Task] = deleteWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, activeBranch) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(key, ws.id, activeBranch)
        // removeTree disassociates the tree from the workspace as a whole
        // (WorkspaceRecord.trees spans every branch — reaper cascade-delete,
        // listTrees) — only correct when the delete targets `main`. Deleting
        // from a scenario branch removes it from that branch alone; the tree
        // still exists on `main` and any other scenario, so the workspace
        // must keep tracking it.
        result <- riskTreeService.delete(ws.id, treeId, branch)
                      .tap(_ => ZIO.when(branch.isEmpty)(workspaceStore.removeTree(key, treeId)))
                      .map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      getTreeById,
      getTreeStructure,
      updateTree,
      deleteTree
    )

object WorkspaceTreeController:
  val makeZIO: ZIO[RiskTreeService & WorkspaceStore & AuthorizationService & UserContextExtractor, Nothing, WorkspaceTreeController] =
    for
      riskTreeService      <- ZIO.service[RiskTreeService]
      workspaceStore       <- ZIO.service[WorkspaceStore]
      authzService         <- ZIO.service[AuthorizationService]
      userCtx              <- ZIO.service[UserContextExtractor]
    yield WorkspaceTreeController(riskTreeService, workspaceStore, authzService, userCtx)
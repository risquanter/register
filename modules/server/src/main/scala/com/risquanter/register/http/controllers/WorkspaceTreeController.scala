package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{AuthorizationService, Checked, Permission, ResourceRef, ResourceType, UserContextExtractor}
import com.risquanter.register.http.endpoints.WorkspaceTreeEndpoints
import com.risquanter.register.domain.data.iron.{BranchRef, Revision, ValidationUtil}
import com.risquanter.register.domain.errors.ValidationFailed
import com.risquanter.register.http.responses.{SimulationResponse, ChangedNodesResponse, NodeChangeEntry, TreeHistoryResponse}
import com.risquanter.register.services.{RiskTreeService, ChangedNodesService, ChangedNodesResult, TreeHistoryService}
import com.risquanter.register.services.workspace.WorkspaceStore

/** Workspace tree controller.
  *
  * Owns workspace-scoped CRUD for trees, plus point-in-time reads, per-tree
  * history, changed-nodes comparison, and revert.
  *
  * Authorization layers:
  *  - Layer 0: [[WorkspaceStore.resolveTreeWorkspace]] validates the workspace key
  *    and asserts the tree belongs to that workspace.
  *  - Layer 1: [[UserContextExtractor.extract]] fails closed when `requirePresent`
  *    is injected via `register.auth.mode=identity`.
  *  - Layer 2: `authzService.check(userId, Permission.*, ResourceRef(RiskTree, treeId))`.
  *
  * @see AUTHORIZATION-PLAN.md — Layered Model
  * @see ADR-024 — Application as Pure PEP
  */
class WorkspaceTreeController private (
  riskTreeService: RiskTreeService,
  changedNodesService: ChangedNodesService,
  treeHistoryService: TreeHistoryService,
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
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        result <- riskTreeService.getById(ws.id, treeId, Revision.Head(branch)).map(_.map(SimulationResponse.fromRiskTree))
      yield result).either
  }

  val getTreeStructure: ServerEndpoint[Any, Task] = getWorkspaceTreeStructureEndpoint.serverLogic {
    case (maybeUserId, key, treeId, activeBranch, at) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        rev     = at.fold[Revision](Revision.Head(branch))(Revision.At(_))
        result <- riskTreeService.getById(ws.id, treeId, rev)
      yield result).either
  }

  val getChangedNodes: ServerEndpoint[Any, Task] = getChangedNodesEndpoint.serverLogic {
    case (maybeUserId, key, treeId, a, aAt, b, bAt) =>
      (for
        userId  <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws      <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branchA <- ActiveBranch.resolve(ws.id, a)
        branchB <- ActiveBranch.resolve(ws.id, b)
        revA     = aAt.fold[Revision](Revision.Head(branchA))(Revision.At(_))
        revB     = bAt.fold[Revision](Revision.Head(branchB))(Revision.At(_))
        result  <- changedNodesService.changedNodes(ws.id, treeId, revA, revB)
      yield result match
        case ChangedNodesResult.Changes(entries) =>
          ChangedNodesResponse("ok", entries.map(c => NodeChangeEntry(c.nodeId.value, c.status.toWire)))
        case ChangedNodesResult.MissingOnA    => ChangedNodesResponse("missing-on-a", Nil)
        case ChangedNodesResult.MissingOnB    => ChangedNodesResponse("missing-on-b", Nil)
        case ChangedNodesResult.MissingOnBoth => ChangedNodesResponse("missing-on-both", Nil)
      ).either
  }

  val getTreeHistory: ServerEndpoint[Any, Task] = getTreeHistoryEndpoint.serverLogic {
    case (maybeUserId, key, treeId, activeBranch, n) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.ViewTree, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        limit  <- ZIO.fromEither(ValidationUtil.refinePositiveInt(n, "n")).mapError(ValidationFailed(_))
        entries <- treeHistoryService.history(ws.id, treeId, branch, limit)
      yield TreeHistoryResponse(entries)).either
  }

  val updateTree: ServerEndpoint[Any, Task] = updateWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, req, activeBranch) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        result <- riskTreeService.update(ws.id, treeId, req, branch).map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  val revertTree: ServerEndpoint[Any, Task] = revertTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, activeBranch, req) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        result <- riskTreeService.revertTree(ws.id, treeId, req.toCommit, branch).map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  val deleteTree: ServerEndpoint[Any, Task] = deleteWorkspaceTreeEndpoint.serverLogic {
    case (maybeUserId, key, treeId, activeBranch) =>
      (for
        userId <- userCtx.requireAuthenticated(maybeUserId)
        given Checked[Permission] <- authzService.check(userId, Permission.DesignWrite, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
        ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
        branch <- ActiveBranch.resolve(ws.id, activeBranch)
        // removeTree disassociates the tree from the workspace as a whole
        // (WorkspaceRecord.trees spans every branch — reaper cascade-delete,
        // listTrees) — only correct when the delete targets `main`. Deleting
        // from a scenario branch removes it from that branch alone; the tree
        // still exists on `main` and any other scenario, so the workspace
        // must keep tracking it.
        result <- riskTreeService.delete(ws.id, treeId, branch)
                      .tap(_ => ZIO.when(branch == BranchRef.Main)(workspaceStore.removeTree(key, treeId)))
                      .map(SimulationResponse.fromRiskTree)
      yield result).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(
      getTreeById,
      getTreeStructure,
      getChangedNodes,
      getTreeHistory,
      updateTree,
      revertTree,
      deleteTree
    )

object WorkspaceTreeController:
  val makeZIO: ZIO[RiskTreeService & ChangedNodesService & TreeHistoryService & WorkspaceStore & AuthorizationService & UserContextExtractor, Nothing, WorkspaceTreeController] =
    for
      riskTreeService      <- ZIO.service[RiskTreeService]
      changedNodesService  <- ZIO.service[ChangedNodesService]
      treeHistoryService   <- ZIO.service[TreeHistoryService]
      workspaceStore       <- ZIO.service[WorkspaceStore]
      authzService         <- ZIO.service[AuthorizationService]
      userCtx              <- ZIO.service[UserContextExtractor]
    yield WorkspaceTreeController(riskTreeService, changedNodesService, treeHistoryService, workspaceStore, authzService, userCtx)

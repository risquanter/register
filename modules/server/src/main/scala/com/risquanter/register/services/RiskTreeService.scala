package com.risquanter.register.services

import zio.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.domain.data.{RiskTree, LECPoint, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, WorkspaceId, SeedEntityId, BranchRef, CommitHash, Revision}

/** Service layer for RiskTree business logic.
  * 
  * Separation of Concerns:
  * - Config CRUD: Persists risk tree structure only (no execution)
  * - LEC Query APIs: Node-based queries composing on RiskResultResolver.ensureCached
  * 
  * Design Pattern (ADR-015):
  * - Tree Manipulation: POST/PUT/DELETE → synchronous, fast (config only)
  * - LEC Query: Node-based APIs that use cache-aside pattern
  * - Simulation parameters come from SimulationConfig (not API parameters)
  *
  * Every method takes an explicit `wsId: WorkspaceId` as its first parameter
  * so that workspace scoping is visible and compile-time enforced at every call site.
  */
trait RiskTreeService {
  
  // ========================================
  // Config CRUD (NO execution)
  // ========================================
  
  /** Create risk tree definition - persists tree structure only
    * @param wsId Workspace that owns the tree
    * @param req Request containing tree definition
    * @param branch Target branch. The controller must have already verified
    *   `branch` belongs to `wsId` — see `ActiveBranch.resolve`; this method trusts it.
    * @return Persisted risk tree metadata (no LEC data)
    */
  def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[RiskTree]

  /** Update risk tree definition - modifies tree structure only
    * @param wsId Workspace that owns the tree
    * @param id Risk tree ID
    * @param req Updated tree definition
    * @param branch Target branch. The controller must have already verified
    *   `branch` belongs to `wsId` — see `ActiveBranch.resolve`; this method trusts it.
    * @return Updated risk tree metadata
    */
  def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[RiskTree]

  /** Delete risk tree configuration
    * @param wsId Workspace that owns the tree
    * @param id Risk tree ID
    * @param branch Target branch.
    * @return Deleted risk tree metadata
    */
  def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[RiskTree]

  /** Revert a tree to `toCommit` as one forward commit (E3/E4/E8). Reads the
    * tree state at `toCommit` and writes it forward with a `:revert` message;
    * no precondition (last write wins). Absent target → NotFound.
    * @param wsId Workspace that owns the tree
    * @param id Risk tree ID
    * @param toCommit Commit whose tree state is restored
    * @param branch Target branch the revert commit lands on.
    * @return The reverted (now current) risk tree metadata
    */
  def revertTree(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[RiskTree]

  /** Retrieve single risk tree configuration by ID (no LEC data)
    * @param wsId Workspace that owns the tree
    * @param id Risk tree ID
    * @param rev Read coordinate — branch head or a pinned commit.
    * @return Optional risk tree metadata
    */
  def getById(wsId: WorkspaceId, id: TreeId, rev: Revision)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[Option[RiskTree]]
  
  // ========================================
  // LEC Query APIs (ADR-015: compose on ensureCached)
  // ========================================
  
  /** Get exceedance probability at a threshold for a node.
    *
    * @param wsId Workspace that owns the tree
    * @param nodeId Node identifier
    * @param threshold Loss threshold to compute P(Loss >= threshold)
    * @param seedEntityId Owning workspace's stochastic identity (from the controller's resolved workspace)
    * @param includeProvenance Whether to include provenance metadata (currently unused for this endpoint)
    * @param rev Read coordinate — branch head or a pinned commit.
    * @return Probability as Double (empirical frequency ratio: exceedingCount / nTrials)
    */
  def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision): Task[Double]

  /** Get LEC curves for multiple nodes with shared tick domain.
    *
    * Used for multi-curve overlay (e.g., split pane comparison).
    * All curves share the same loss ticks for aligned rendering.
    * Returns LECNodeCurve (id + name + curve + quantiles) per node.
    *
    * @param wsId Workspace that owns the tree
    * @param nodeIds Set of node identifiers
    * @param includeProvenance Whether to include provenance metadata for reproducibility
    * @param rev Read coordinate — branch head or a pinned commit.
    * @param omitAbsent When true, node IDs absent from the tree at `rev` are
    *                   omitted from the result instead of failing the request —
    *                   the point-in-time read path (a node may not exist yet at
    *                   an earlier commit).
    * @return Map from nodeId to LECNodeCurve (id, name, curve points, quantiles)
    */
  def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision, omitAbsent: Boolean = false): Task[Map[NodeId, LECNodeCurve]]
}

object RiskTreeService:

  /** Best-effort cascade deletion — deletes each tree, swallowing individual failures.
    * Each failure is logged as a warning before being swallowed, so an orphaned
    * tree (delete failed, not just "already gone") is observable instead of silent.
    * Deletes are independent (no shared CAS target, no ordering requirement), so
    * they run with bounded concurrency rather than one at a time.
    *
    * Used via `CascadeDelete.workspace` by `WorkspaceLifecycleController.deleteWorkspace`
    * (explicit delete), `WorkspaceLifecycleController.evictExpired` (admin sweep), and
    * `WorkspaceReaper` (TTL expiry). Extracted here as the single source of truth for the
    * cascade-delete semantic. Mirrored by `ScenarioService.cascadeDeleteScenarios`
    * for scenario branches.
    */
  extension (self: RiskTreeService)
    def cascadeDeleteTrees(wsId: WorkspaceId, ids: Iterable[TreeId])(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): UIO[Unit] =
      ZIO.withParallelism(8) {
        ZIO.foreachParDiscard(ids)(id =>
          // Workspace teardown removes the workspace-level (main) tree set.
          self.delete(wsId, id, BranchRef.Main)
            .tapError(e => ZIO.logWarning(s"cascadeDeleteTrees: failed to delete tree ${id.value} in workspace ${wsId.value}: ${e.getMessage}"))
            .ignore)
      }

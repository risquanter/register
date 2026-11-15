package com.risquanter.register.services

import zio.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.domain.data.{MitigationSelection, RiskTree}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, MitigationId, NonNegativeLong, PositiveInt, WorkspaceId, SeedEntityId, BranchRef, CommitHash, Revision}
import com.risquanter.register.http.responses.{ExceedanceSeries, LECNodeSeries}
import com.risquanter.register.domain.errors.Failure
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.services.cache.ResolvedScopes

/** Service layer for RiskTree business logic.
  * 
  * Separation of Concerns:
  * - Config CRUD: Persists risk tree structure only (no execution)
  * - LEC Query APIs: Node-based queries composing on CachedResultResolver.ensureCached
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
  def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree]

  /** Update risk tree definition - modifies tree structure only
    * @param wsId Workspace that owns the tree
    * @param id Risk tree ID
    * @param req Updated tree definition
    * @param branch Target branch. The controller must have already verified
    *   `branch` belongs to `wsId` — see `ActiveBranch.resolve`; this method trusts it.
    * @return Updated risk tree metadata
    */
  def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree]

  /** Delete risk tree configuration
    * @param wsId Workspace that owns the tree
    * @param id Risk tree ID
    * @param branch Target branch.
    * @return Deleted risk tree metadata
    */
  def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree]

  /** Revert a tree to `toCommit` as one forward commit. Reads the
    * tree state at `toCommit` and writes it forward with a `:revert` message;
    * no precondition (last write wins). Absent target → NotFound.
    * @param wsId Workspace that owns the tree
    * @param id Risk tree ID
    * @param toCommit Commit whose tree state is restored
    * @param branch Target branch the revert commit lands on.
    * @return The reverted (now current) risk tree metadata
    */
  def revertTree(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree]

  /** Retrieve single risk tree configuration by ID (no LEC data), together with
    * the concrete commit it was read at.
    *
    * `rev` names a read coordinate that may still be ambiguous — `Revision.Head`
    * resolves to whatever the branch head is at the moment of the read. The
    * returned `CommitHash` is the Irmin commit that read actually landed on: a
    * whole-tree, byte-level revision, never a domain content hash. A caller that
    * needs to key or memoize on the tree version therefore gets it from the same
    * read, rather than from a second call that could resolve to a different
    * head. Callers with no such need discard it at their own boundary.
    *
    * @param wsId Workspace that owns the tree
    * @param id Risk tree ID
    * @param rev Read coordinate — branch head or a pinned commit.
    * @return The tree and the commit it resolved to, or `None` when absent
    */
  def getById(wsId: WorkspaceId, id: TreeId, rev: Revision)(using Checked[Authority]): IO[Failure, Option[(RiskTree, CommitHash)]]

  /** The tree at this read coordinate, together with each mitigation's resolved
    * scope for that version and the ids of overrides whose base stamp is stale.
    * Fails with `RiskTreeRepository.treeAbsent` when the revision lacks the
    * tree.
    *
    * Separate from `getById` because resolving scopes builds a knowledge base
    * and evaluates every predicate. `getById` is on the update path's pre-read
    * and on the cascade paths, none of which consume a resolution, and none of
    * which should pay for one.
    */
  def getStructure(wsId: WorkspaceId, id: TreeId, branch: BranchRef, at: Option[CommitHash])(
    using Checked[Authority]
  ): IO[Failure, (RiskTree, ResolvedScopes, Set[MitigationId])]

  // ========================================
  // LEC Query APIs (ADR-015: compose on ensureCached)
  // ========================================
  
  /** Exceedance probability at a threshold for one node, under one mitigation
    * selection.
    *
    * One reading, computed under the selection the caller passed.
    * `withMitigations` names the mitigations whose resolved scope reached this
    * node or any node below it, and is empty exactly when none did — which is
    * also the whole answer for the mitigation-free selection. A caller wanting
    * the mitigation-free figure alongside a mitigated one asks twice; the two
    * probabilities share no tick domain and need no shared request.
    *
    * `branch` and `at` are taken separately rather than as a `Revision`
    * because the scope resolver memoizes per (tree, branch) and `Revision.At`
    * carries a commit with no branch. The method composes the `Revision` the
    * repository reads at.
    */
  def probOfExceedance(
    wsId: WorkspaceId,
    treeId: TreeId,
    nodeId: NodeId,
    threshold: NonNegativeLong,
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean,
    branch: BranchRef,
    at: Option[CommitHash],
    selection: MitigationSelection = MitigationSelection.Inherent
  )(using Checked[Authority]): IO[Failure, ExceedanceSeries]

  /** LEC curves for several nodes on one shared tick domain, under one
    * mitigation selection.
    *
    * Each node carries the mitigation-free curve first, and a second curve when
    * the selection reaches it. Every curve in the answer — both valuations of
    * every node — is evaluated against one tick domain, so any two of them can
    * be read against each other.
    */
  def getLECCurvesMulti(
    wsId: WorkspaceId,
    treeId: TreeId,
    nodeIds: Set[NodeId],
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean,
    branch: BranchRef,
    at: Option[CommitHash],
    omitAbsent: Boolean = false,
    selection: MitigationSelection = MitigationSelection.Inherent
  )(using Checked[Authority]): IO[Failure, Map[NodeId, List[LECNodeSeries]]]
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
    def cascadeDeleteTrees(wsId: WorkspaceId, ids: Iterable[TreeId], parallelism: PositiveInt)(using Checked[Authority]): UIO[Unit] =
      ZIO.withParallelism(parallelism) {
        ZIO.foreachParDiscard(ids)(id =>
          // Workspace teardown removes the workspace-level (main) tree set.
          self.delete(wsId, id, BranchRef.Main)
            .tapError(error =>
              ZIO.logWarning(s"cascadeDeleteTrees: failed to delete tree ${id.value} in workspace ${wsId.value}: ${error.logMessage}"))
            .ignore)
      }

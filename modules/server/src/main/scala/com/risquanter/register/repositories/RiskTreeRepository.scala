package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, BranchRef, CommitHash, Revision}

/** Repository for RiskTree persistence operations.
  *
  * Every method takes an explicit `wsId: WorkspaceId` as its first parameter
  * so that workspace scoping is visible and compile-time enforced at every call site.
  *
  * Revision model (E2/E7): writes target a `branch: BranchRef`; reads take a
  * `Revision` — `Head(branch)` resolves the branch head once, `At(commit)`
  * pins a specific commit for point-in-time access. There is no default: every
  * call names its target explicitly. Only the Irmin backend supports commit
  * pins and non-main branches; the in-memory backend rejects both with a typed
  * failure rather than silently serving main-branch/head data.
  */
trait RiskTreeRepository {
  def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef): Task[RiskTree]
  def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): Task[RiskTree]
  def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef): Task[RiskTree]

  /** Revert a tree to `toCommit` as one forward `set_tree` commit (E3/E4/E8).
    * Reads the tree state at `toCommit` and writes it forward with a `:revert`
    * message; no precondition. Absent target (commit or path) → NotFound. */
  def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): Task[RiskTree]

  /** Loads a tree and reports the concrete commit it was read at. The
    * `CommitHash` is the storage-relation revision (ADR-032 §3) that scope
    * resolution memoizes on — one honest read that names the head it resolved,
    * so no second call and no resolve-then-reload race (OD-5=D). */
  def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): Task[Option[(RiskTree, CommitHash)]]
  def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): Task[List[Either[RepositoryFailure, RiskTree]]]
}

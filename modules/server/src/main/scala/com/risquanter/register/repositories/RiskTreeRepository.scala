package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, BranchRef}

/** Repository for RiskTree persistence operations.
  *
  * Every method takes an explicit `wsId: WorkspaceId` as its first parameter
  * so that workspace scoping is visible and compile-time enforced at every call site.
  *
  * Branch threading (milestone 2b, DD-4): every method takes an optional
  * `branch`; `None` targets the main branch — fully backward compatible.
  * Explicit branch args (rather than ambient state) let the comparison
  * workflow read two branches in one effect. Only the Irmin backend supports
  * branches; the in-memory backend rejects non-main branch requests with a
  * typed failure rather than silently serving main-branch data.
  */
trait RiskTreeRepository {
  def create(wsId: WorkspaceId, riskTree: RiskTree, branch: Option[BranchRef] = None): Task[RiskTree]
  def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: Option[BranchRef] = None): Task[RiskTree]
  def delete(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None): Task[RiskTree]
  def getById(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None): Task[Option[RiskTree]]
  def getAllForWorkspace(wsId: WorkspaceId, branch: Option[BranchRef] = None): Task[List[Either[RepositoryFailure, RiskTree]]]
}

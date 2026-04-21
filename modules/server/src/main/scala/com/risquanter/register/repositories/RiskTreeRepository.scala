package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId}

/** Repository for RiskTree persistence operations.
  *
  * Every method takes an explicit `wsId: WorkspaceId` as its first parameter
  * so that workspace scoping is visible and compile-time enforced at every call site.
  */
trait RiskTreeRepository {
  def create(wsId: WorkspaceId, riskTree: RiskTree): Task[RiskTree]
  def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree): Task[RiskTree]
  def delete(wsId: WorkspaceId, id: TreeId): Task[RiskTree]
  def getById(wsId: WorkspaceId, id: TreeId): Task[Option[RiskTree]]
  def getAllForWorkspace(wsId: WorkspaceId): Task[List[Either[RepositoryFailure, RiskTree]]]
}

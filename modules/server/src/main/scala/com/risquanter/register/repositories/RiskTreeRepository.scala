package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.domain.data.iron.TreeId

/** Repository for RiskTree persistence operations
  */
trait RiskTreeRepository {
  def create(riskTree: RiskTree): Task[RiskTree]
  def update(id: TreeId, op: RiskTree => RiskTree): Task[RiskTree]
  def delete(id: TreeId): Task[RiskTree]
  def getById(id: TreeId): Task[Option[RiskTree]]
  def getAll: Task[List[Either[RepositoryFailure, RiskTree]]]
}

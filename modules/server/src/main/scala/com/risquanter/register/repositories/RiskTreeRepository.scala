package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree

/** Repository for RiskTree persistence operations
  */
trait RiskTreeRepository {
  def create(riskTree: RiskTree): Task[RiskTree]
  def update(id: Long, op: RiskTree => RiskTree): Task[RiskTree]
  def delete(id: Long): Task[RiskTree]
  def getById(id: Long): Task[Option[RiskTree]]
  def getAll: Task[List[RiskTree]]
}

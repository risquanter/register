package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.NonNegativeLong

/** Repository for RiskTree persistence operations
  */
trait RiskTreeRepository {
  def create(riskTree: RiskTree): Task[RiskTree]
  def update(id: NonNegativeLong, op: RiskTree => RiskTree): Task[RiskTree]
  def delete(id: NonNegativeLong): Task[RiskTree]
  def getById(id: NonNegativeLong): Task[Option[RiskTree]]
  def getAll: Task[List[RiskTree]]
}

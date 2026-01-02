package com.risquanter.register.services

import zio.*
import com.risquanter.register.http.requests.CreateSimulationRequest
import com.risquanter.register.domain.data.Simulation

/** Service layer for Simulation business logic
  * Handles validation, domain transformations, and repository orchestration
  */
trait SimulationService {
  def create(req: CreateSimulationRequest): Task[Simulation]
  def getAll: Task[List[Simulation]]
  def getById(id: Long): Task[Option[Simulation]]
}

package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.Simulation

/** Repository for Simulation persistence operations
  */
trait SimulationRepository {
  def create(simulation: Simulation): Task[Simulation]
  def update(id: Long, op: Simulation => Simulation): Task[Simulation]
  def delete(id: Long): Task[Simulation]
  def getById(id: Long): Task[Option[Simulation]]
  def getAll: Task[List[Simulation]]
}

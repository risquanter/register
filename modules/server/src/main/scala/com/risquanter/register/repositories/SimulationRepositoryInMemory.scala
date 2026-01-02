package com.risquanter.register.repositories

import zio.*
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.Simulation

/** In-memory implementation of SimulationRepository for testing and development
  * Uses a mutable map to store simulations
  */
class SimulationRepositoryInMemory private () extends SimulationRepository {
  private val db = collection.concurrent.TrieMap[Long, Simulation]()
  private val idCounter = new java.util.concurrent.atomic.AtomicLong(0L)

  override def create(simulation: Simulation): Task[Simulation] = ZIO.attempt {
    val nextId = idCounter.incrementAndGet()
    val newSimulation = simulation.copy(id = nextId.refineUnsafe)
    db += (nextId -> newSimulation)
    newSimulation
  }

  override def update(id: Long, op: Simulation => Simulation): Task[Simulation] = ZIO.attempt {
    val simulation = db.getOrElse(id, throw new NoSuchElementException(s"Simulation with id $id not found"))
    val updated = op(simulation)
    db += (id -> updated)
    updated
  }

  override def delete(id: Long): Task[Simulation] = ZIO.attempt {
    val simulation = db.getOrElse(id, throw new NoSuchElementException(s"Simulation with id $id not found"))
    db -= id
    simulation
  }

  override def getById(id: Long): Task[Option[Simulation]] =
    ZIO.succeed(db.get(id))

  override def getAll: Task[List[Simulation]] =
    ZIO.succeed(db.values.toList)
}

object SimulationRepositoryInMemory {
  val layer: ZLayer[Any, Nothing, SimulationRepository] = ZLayer {
    ZIO.succeed(new SimulationRepositoryInMemory())
  }
}

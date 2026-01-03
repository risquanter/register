package com.risquanter.register.repositories

import zio.*
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.RiskTree

/** In-memory implementation of RiskTreeRepository for testing and development
  * Uses a mutable map to store risk trees
  */
class RiskTreeRepositoryInMemory private () extends RiskTreeRepository {
  private val db = collection.concurrent.TrieMap[Long, RiskTree]()
  private val idCounter = new java.util.concurrent.atomic.AtomicLong(0L)

  override def create(riskTree: RiskTree): Task[RiskTree] = ZIO.attempt {
    val nextId = idCounter.incrementAndGet()
    val newRiskTree = riskTree.copy(id = nextId.refineUnsafe)
    db += (nextId -> newRiskTree)
    newRiskTree
  }

  override def update(id: Long, op: RiskTree => RiskTree): Task[RiskTree] = ZIO.attempt {
    val riskTree = db.getOrElse(id, throw new NoSuchElementException(s"RiskTree with id $id not found"))
    val updated = op(riskTree)
    db += (id -> updated)
    updated
  }

  override def delete(id: Long): Task[RiskTree] = ZIO.attempt {
    val riskTree = db.getOrElse(id, throw new NoSuchElementException(s"RiskTree with id $id not found"))
    db -= id
    riskTree
  }

  override def getById(id: Long): Task[Option[RiskTree]] =
    ZIO.succeed(db.get(id))

  override def getAll: Task[List[RiskTree]] =
    ZIO.succeed(db.values.toList)
}

object RiskTreeRepositoryInMemory {
  val layer: ZLayer[Any, Nothing, RiskTreeRepository] = ZLayer {
    ZIO.succeed(new RiskTreeRepositoryInMemory())
  }
}

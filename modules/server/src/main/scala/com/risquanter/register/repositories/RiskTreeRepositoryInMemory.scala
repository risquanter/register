package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.TreeId
import com.risquanter.register.domain.errors.RepositoryFailure

/** In-memory implementation of RiskTreeRepository for testing and development
  * Uses a mutable map to store risk trees
  */
class RiskTreeRepositoryInMemory private () extends RiskTreeRepository {
  private val db = collection.concurrent.TrieMap[TreeId, RiskTree]()

  override def create(riskTree: RiskTree): Task[RiskTree] = ZIO.attempt {
    val id = riskTree.id
    if db.contains(id) then throw new IllegalStateException(s"RiskTree with id $id already exists")
    db += (id -> riskTree)
    riskTree
  }

  override def update(id: TreeId, op: RiskTree => RiskTree): Task[RiskTree] = ZIO.attempt {
    val riskTree = db.getOrElse(id, throw new NoSuchElementException(s"RiskTree with id $id not found"))
    val updated = op(riskTree)
    db += (id -> updated)
    updated
  }

  override def delete(id: TreeId): Task[RiskTree] = ZIO.attempt {
    val riskTree = db.getOrElse(id, throw new NoSuchElementException(s"RiskTree with id $id not found"))
    db -= id
    riskTree
  }

  override def getById(id: TreeId): Task[Option[RiskTree]] =
    ZIO.succeed(db.get(id))

  override def getAll: Task[List[Either[RepositoryFailure, RiskTree]]] =
    ZIO.succeed(db.values.toList.map(Right(_)))
}

object RiskTreeRepositoryInMemory {
  val layer: ZLayer[Any, Nothing, RiskTreeRepository] = ZLayer {
    ZIO.succeed(new RiskTreeRepositoryInMemory())
  }
}

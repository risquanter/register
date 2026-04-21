package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId}
import com.risquanter.register.domain.errors.RepositoryFailure

/** In-memory implementation of RiskTreeRepository for testing and development.
  *
  * The TrieMap is keyed by (WorkspaceId, TreeId) so that workspace isolation is
  * enforced at the storage level. A wrong or missing WorkspaceId will yield None /
  * NoSuchElementException rather than silently crossing workspace boundaries.
  */
class RiskTreeRepositoryInMemory private () extends RiskTreeRepository {
  private val db = collection.concurrent.TrieMap[(WorkspaceId, TreeId), RiskTree]()

  override def create(wsId: WorkspaceId, riskTree: RiskTree): Task[RiskTree] = ZIO.attempt {
    val key = (wsId, riskTree.id)
    if db.contains(key) then throw new IllegalStateException(s"RiskTree with id ${riskTree.id} already exists in workspace $wsId")
    db += (key -> riskTree)
    riskTree
  }

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree): Task[RiskTree] = ZIO.attempt {
    val key = (wsId, id)
    val riskTree = db.getOrElse(key, throw new NoSuchElementException(s"RiskTree with id $id not found in workspace $wsId"))
    val updated = op(riskTree)
    db += (key -> updated)
    updated
  }

  override def delete(wsId: WorkspaceId, id: TreeId): Task[RiskTree] = ZIO.attempt {
    val key = (wsId, id)
    val riskTree = db.getOrElse(key, throw new NoSuchElementException(s"RiskTree with id $id not found in workspace $wsId"))
    db -= key
    riskTree
  }

  override def getById(wsId: WorkspaceId, id: TreeId): Task[Option[RiskTree]] =
    ZIO.succeed(db.get((wsId, id)))

  override def getAllForWorkspace(wsId: WorkspaceId): Task[List[Either[RepositoryFailure, RiskTree]]] =
    ZIO.succeed(db.collect { case ((wid, _), tree) if wid == wsId => Right(tree) }.toList)
}

object RiskTreeRepositoryInMemory {
  val layer: ZLayer[Any, Nothing, RiskTreeRepository] = ZLayer {
    ZIO.succeed(new RiskTreeRepositoryInMemory())
  }
}

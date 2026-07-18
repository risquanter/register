package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, BranchRef}
import com.risquanter.register.domain.errors.RepositoryFailure

/** In-memory implementation of RiskTreeRepository for testing and development.
  *
  * The TrieMap is keyed by (WorkspaceId, TreeId) so that workspace isolation is
  * enforced at the storage level. A wrong or missing WorkspaceId will yield None /
  * NoSuchElementException rather than silently crossing workspace boundaries.
  *
  * Branches are an Irmin capability: this backend serves only the main
  * branch. A request for any other branch fails with a typed
  * RepositoryFailure rather than silently answering with main-branch data.
  */
class RiskTreeRepositoryInMemory private () extends RiskTreeRepository {
  private val db = collection.concurrent.TrieMap[(WorkspaceId, TreeId), RiskTree]()

  private def requireMain(branch: Option[BranchRef]): Task[Unit] =
    branch match {
      case None | Some(BranchRef.Main) => ZIO.unit
      case Some(other) =>
        ZIO.fail(RepositoryFailure(
          s"In-memory repository has no branches: requested '${other.toBranchRef}' (use the Irmin backend for scenario branches)"
        ))
    }

  override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: Option[BranchRef] = None): Task[RiskTree] =
    requireMain(branch) *> ZIO.attempt {
      val key = (wsId, riskTree.id)
      if db.contains(key) then throw new IllegalStateException(s"RiskTree with id ${riskTree.id} already exists in workspace $wsId")
      db += (key -> riskTree)
      riskTree
    }

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: Option[BranchRef] = None): Task[RiskTree] =
    requireMain(branch) *> ZIO.attempt {
      val key = (wsId, id)
      val riskTree = db.getOrElse(key, throw new NoSuchElementException(s"RiskTree with id $id not found in workspace $wsId"))
      val updated = op(riskTree)
      db += (key -> updated)
      updated
    }

  override def delete(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None): Task[RiskTree] =
    requireMain(branch) *> ZIO.attempt {
      val key = (wsId, id)
      val riskTree = db.getOrElse(key, throw new NoSuchElementException(s"RiskTree with id $id not found in workspace $wsId"))
      db -= key
      riskTree
    }

  override def getById(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None): Task[Option[RiskTree]] =
    requireMain(branch) *> ZIO.succeed(db.get((wsId, id)))

  override def getAllForWorkspace(wsId: WorkspaceId, branch: Option[BranchRef] = None): Task[List[Either[RepositoryFailure, RiskTree]]] =
    requireMain(branch) *> ZIO.succeed(db.collect { case ((wid, _), tree) if wid == wsId => Right(tree) }.toList)
}

object RiskTreeRepositoryInMemory {
  val layer: ZLayer[Any, Nothing, RiskTreeRepository] = ZLayer {
    ZIO.succeed(new RiskTreeRepositoryInMemory())
  }
}

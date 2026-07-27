package com.risquanter.register.repositories

import zio.*
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, BranchRef, CommitHash, Revision}
import com.risquanter.register.domain.errors.{RepositoryFailure, ValidationFailed, ValidationError, ValidationErrorCode}

/** In-memory implementation of RiskTreeRepository for testing and development.
  *
  * The TrieMap is keyed by (WorkspaceId, TreeId) so that workspace isolation is
  * enforced at the storage level. A wrong or missing WorkspaceId will yield None /
  * NoSuchElementException rather than silently crossing workspace boundaries.
  *
  * Branches and commit pins are an Irmin capability: this backend serves only
  * the main branch at its head. A non-main branch fails with a typed
  * RepositoryFailure; a commit pin (`Revision.At`) fails with a typed
  * ValidationFailed — point-in-time reads require the Irmin backend. Neither is
  * reachable in normal operation (scenario/history UI is disabled on the
  * in-memory backend, DD-9).
  */
class RiskTreeRepositoryInMemory private () extends RiskTreeRepository {
  private val db = collection.concurrent.TrieMap[(WorkspaceId, TreeId), RiskTree]()

  // Single-spelling check since the BranchChoice consolidation (TODO item
  // 22): main has exactly one representation, so this is a plain equality —
  // the old None-or-Some(BranchRef.Main) double match is gone by construction.
  private def requireMain(branch: BranchRef): Task[Unit] =
    if branch == BranchRef.Main then ZIO.unit
    else ZIO.fail(RepositoryFailure(
      s"In-memory repository has no branches: requested '${branch.toBranchRef}' (use the Irmin backend for scenario branches)"
    ))

  // A read Revision resolves to "main head" only; a scenario branch or a commit
  // pin is rejected with a typed failure rather than silently served from main.
  private def requireMainRevision(rev: Revision): Task[Unit] =
    rev match
      case Revision.Head(branch) => requireMain(branch)
      case Revision.At(_) =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          field = "at",
          code = ValidationErrorCode.NOT_SUPPORTED,
          message = "point-in-time reads require the Irmin backend"
        ))))

  override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef): Task[RiskTree] =
    requireMain(branch) *> ZIO.attempt {
      val key = (wsId, riskTree.id)
      if db.contains(key) then throw new IllegalStateException(s"RiskTree with id ${riskTree.id} already exists in workspace $wsId")
      db += (key -> riskTree)
      riskTree
    }

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): Task[RiskTree] =
    requireMain(branch) *> ZIO.attempt {
      val key = (wsId, id)
      val riskTree = db.getOrElse(key, throw new NoSuchElementException(s"RiskTree with id $id not found in workspace $wsId"))
      val updated = op(riskTree)
      db += (key -> updated)
      updated
    }

  override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef): Task[RiskTree] =
    requireMain(branch) *> ZIO.attempt {
      val key = (wsId, id)
      val riskTree = db.getOrElse(key, throw new NoSuchElementException(s"RiskTree with id $id not found in workspace $wsId"))
      db -= key
      riskTree
    }

  override def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): Task[RiskTree] =
    ZIO.fail(ValidationFailed(List(ValidationError(
      field = "toCommit",
      code = ValidationErrorCode.NOT_SUPPORTED,
      message = "revert requires the Irmin backend (point-in-time reads unavailable in memory)"
    ))))

  override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): Task[Option[RiskTree]] =
    requireMainRevision(rev) *> ZIO.succeed(db.get((wsId, id)))

  override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): Task[List[Either[RepositoryFailure, RiskTree]]] =
    requireMainRevision(rev) *> ZIO.succeed(db.collect { case ((wid, _), tree) if wid == wsId => Right(tree) }.toList)
}

object RiskTreeRepositoryInMemory {
  val layer: ZLayer[Any, Nothing, RiskTreeRepository] = ZLayer {
    ZIO.succeed(new RiskTreeRepositoryInMemory())
  }
}

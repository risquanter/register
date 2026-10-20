package com.risquanter.register.repositories

import zio.*
import zio.json.EncoderOps
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, WorkspaceId, BranchRef, CommitHash, Revision}
import com.risquanter.register.domain.errors.{AppError, ErrorLocation, FieldName, RepositoryFailure, TreeLoadFailure, ValidationFailed, ValidationError, ValidationErrorCode}

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
  * reachable in normal operation: the scenario and history interface is
  * disabled on the in-memory backend.
  */
class RiskTreeRepositoryInMemory private () extends RiskTreeRepository {
  private val db = collection.concurrent.TrieMap[(WorkspaceId, TreeId), RiskTree]()

  /** Runs `effect` for the main branch. This backend has no branches, so any
    * other branch fails with a typed RepositoryFailure and `effect` is never
    * evaluated.
    */
  private def requireMain[A](branch: BranchRef)(effect: => IO[AppError, A]): IO[AppError, A] =
    branch match
      case BranchRef.Main => effect
      case _              => ZIO.fail(RepositoryFailure(
        s"In-memory repository has no branches: requested '${branch.toBranchRef}' (use the Irmin backend for scenario branches)"
      ))

  /** Runs `effect` for the main branch at its head. A commit pin fails with a
    * typed ValidationFailed rather than being silently served from main:
    * point-in-time reads require the Irmin backend.
    */
  private def requireMainRevision[A](rev: Revision)(effect: => IO[AppError, A]): IO[AppError, A] =
    rev match
      case Revision.Head(branch) => requireMain(branch)(effect)
      case Revision.At(_) =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          location = ErrorLocation.requestField(FieldName.At),
          code = ValidationErrorCode.NOT_SUPPORTED,
          message = "point-in-time reads require the Irmin backend"
        ))))

  override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef): IO[AppError, RiskTree] =
    requireMain(branch) {
      // putIfAbsent reports the collision and inserts in one step, so two
      // concurrent creates of the same identifier cannot both succeed.
      ZIO.succeed(db.putIfAbsent((wsId, riskTree.id), riskTree)).flatMap {
        case Some(_) => ZIO.fail(RepositoryFailure(
          s"RiskTree ${riskTree.id} already exists in workspace ${wsId.value}"
        ))
        case None    => ZIO.succeed(riskTree)
      }
    }

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): IO[AppError, RiskTree] =
    requireMain(branch) {
      // Suspended, so the lookup and `op` run when the effect runs rather than
      // when it is constructed; a throwing `op` is then this effect's defect
      // and not the caller's.
      ZIO.suspendSucceed {
        val key = (wsId, id)
        db.get(key) match
          case None           => ZIO.fail(RepositoryFailure(s"RiskTree $id not found in workspace ${wsId.value}"))
          case Some(riskTree) =>
            val updated = op(riskTree)
            ZIO.succeed {
              db += (key -> updated)
              updated
            }
      }
    }

  override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[AppError, RiskTree] =
    requireMain(branch) {
      // Suspended for the same reason as `update`: the lookup belongs to the
      // effect's execution, not to its construction.
      ZIO.suspendSucceed {
        val key = (wsId, id)
        db.get(key) match
          case None           => ZIO.fail(RepositoryFailure(s"RiskTree $id not found in workspace ${wsId.value}"))
          case Some(riskTree) =>
            ZIO.succeed {
              db -= key
              riskTree
            }
      }
    }

  override def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): IO[AppError, RiskTree] =
    ZIO.fail(ValidationFailed(List(ValidationError(
      location = ErrorLocation.requestField(FieldName.ToCommit),
      code = ValidationErrorCode.NOT_SUPPORTED,
      message = "revert requires the Irmin backend (point-in-time reads unavailable in memory)"
    ))))

  override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): IO[AppError, Option[(RiskTree, CommitHash)]] =
    requireMainRevision(rev) { ZIO.succeed(db.get((wsId, id)).map(t => (t, syntheticHash(t)))) }

  /** A deterministic, content-sensitive stand-in for a real Irmin commit hash.
    * This backend has no commit graph, so the scope-resolution memo key
    * (`ScopeResolutionContext`) is derived from the tree's serialized content:
    * SHA-1 of the tree JSON rendered as 40 lowercase hex, matching the Irmin
    * `CommitHash` format. Content-sensitive, so a mutated tree yields a distinct
    * key and never serves a stale resolved scope. SHA-1 always produces 20 bytes
    * → 40 masked hex chars, so the refinement holds by construction. */
  private def syntheticHash(tree: RiskTree): CommitHash =
    val digest = java.security.MessageDigest.getInstance("SHA-1")
      .digest(tree.toJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val hex = digest.map(b => f"${b & 0xff}%02x").mkString
    CommitHash.fromString(hex).toOption.getOrElse(
      throw new IllegalStateException(s"synthetic commit hash not 40 hex: '$hex'"))

  override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): IO[AppError, List[Either[TreeLoadFailure, RiskTree]]] =
    requireMainRevision(rev) { ZIO.succeed(db.collect { case ((wid, _), tree) if wid == wsId => Right(tree) }.toList) }
}

object RiskTreeRepositoryInMemory {
  val layer: ZLayer[Any, Nothing, RiskTreeRepository] = ZLayer {
    ZIO.succeed(new RiskTreeRepositoryInMemory())
  }
}

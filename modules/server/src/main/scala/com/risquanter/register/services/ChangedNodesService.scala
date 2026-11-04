package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, NodeId, ContentHash, Revision}
import com.risquanter.register.domain.errors.AppError
import com.risquanter.register.http.responses.{ChangedNodesStatus, NodeChangeEntry, NodeChangeStatus}
import com.risquanter.register.services.cache.ContentHashIndex

/** Content-hash changed-nodes comparison of a tree between two revisions —
  * no value-level comparison, only whether each node's content hash matches.
  *
  * The hashes compared are the *domain* content hashes (`ContentHashIndex`): a
  * leaf's hash covers only its simulation-relevant projection
  * (`LeafSimContent`) — not `name` or `parentId` — so a renamed or moved node
  * reports `Identical` here even though its persisted JSON, and therefore its
  * Irmin blob hash, changed. That is the intended semantics (ADR-032): this
  * comparison answers "did the risk content change", not "did the stored bytes
  * change". Irmin is itself a content-addressed Merkle store, so this service
  * is not duplicating Irmin's hashing — it hashes a different projection to
  * answer a different question.
  *
  * Consequently this comparison must NOT be used to predict Irmin merge
  * outcomes: Irmin merges per node file on byte equality of the full persisted
  * JSON, so a node reported `Identical` (or changed on one side only) here can
  * still merge-conflict — e.g. renamed on one revision while its probability
  * changed on the other. Merge-conflict prediction needs the storage-level
  * relation; see ADR-032 for both relations and where each applies.
  */
trait ChangedNodesService:

  /** Changed nodes of `treeId` between revisions `a` and `b`. Deliberately
    * symmetric — neither side is a baseline; each is a branch head or a
    * pinned commit.
    *
    * @return the wire outcome `ChangedNodesStatus`: `MissingOnA`, `MissingOnB`
    *         or `MissingOnBoth` if the tree does not exist on one or both sides,
    *         e.g. it was deleted from a scenario branch while surviving on
    *         `main`. Not an error condition — mirrors the `Option` in
    *         `RiskTreeService.getById`'s return, distinguishing which side is
    *         missing instead of collapsing both to one empty value.
    */
  def changedNodes(
    wsId:   WorkspaceId,
    treeId: TreeId,
    a:      Revision,
    b:      Revision
  )(using Checked[Authority]): IO[AppError, ChangedNodesStatus]

final case class ChangedNodesServiceLive(riskTreeService: RiskTreeService) extends ChangedNodesService:

  override def changedNodes(
    wsId:   WorkspaceId,
    treeId: TreeId,
    a:      Revision,
    b:      Revision
  )(using Checked[Authority]): IO[AppError, ChangedNodesStatus] =
    // The two sides' trees are independent fetches (no shared state) — zipPar
    // avoids paying getById's Irmin round-trip cost twice, sequentially.
    riskTreeService.getById(wsId, treeId, a)
      .zipPar(riskTreeService.getById(wsId, treeId, b))
      .map {
        case (Some((treeA, _)), Some((treeB, _))) =>
          ChangedNodesStatus.Ok(changedHashes(ContentHashIndex.build(treeA), ContentHashIndex.build(treeB)))
        case (None, Some(_)) => ChangedNodesStatus.MissingOnA()
        case (Some(_), None) => ChangedNodesStatus.MissingOnB()
        case (None, None)    => ChangedNodesStatus.MissingOnBoth()
      }

  /** Keys present only in `hashesA` are `Removed`, only in `hashesB` are
    * `Added`, and shared keys compare hashes directly — three disjoint set
    * operations, no `Option`-pair match with an unreachable case needed.
    * Sorted by `NodeId.value` so the response has a stable order across
    * identical requests (a `Set`'s iteration order is not guaranteed).
    */
  private def changedHashes(hashesA: Map[NodeId, ContentHash], hashesB: Map[NodeId, ContentHash]): List[NodeChangeEntry] =
    val onlyA = (hashesA.keySet -- hashesB.keySet).map(NodeChangeEntry(_, NodeChangeStatus.Removed))
    val onlyB = (hashesB.keySet -- hashesA.keySet).map(NodeChangeEntry(_, NodeChangeStatus.Added))
    val both  = (hashesA.keySet & hashesB.keySet).map { id =>
      NodeChangeEntry(id, if hashesA(id) == hashesB(id) then NodeChangeStatus.Identical else NodeChangeStatus.Changed)
    }
    (onlyA ++ onlyB ++ both).toList.sortBy(_.nodeId.value)

object ChangedNodesServiceLive:
  val layer: ZLayer[RiskTreeService, Nothing, ChangedNodesService] =
    ZLayer.fromFunction(ChangedNodesServiceLive.apply)

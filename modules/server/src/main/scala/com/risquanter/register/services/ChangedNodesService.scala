package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, NodeId, ContentHash, Revision}
import com.risquanter.register.services.cache.ContentHashIndex

/** Per-node content-hash comparison result between two revisions (UC5). */
enum NodeChangeStatus:
  case Identical, Changed, Added, Removed

  /** Wire form for `NodeChangeEntry.status` — colocated with the case list so a
    * new case and its wire string are added in the same place (mirrors
    * `DistributionMode.toApiString`).
    */
  def toWire: String = this match
    case NodeChangeStatus.Identical => "identical"
    case NodeChangeStatus.Changed   => "changed"
    case NodeChangeStatus.Added     => "added"
    case NodeChangeStatus.Removed   => "removed"

/** One node's change outcome. */
final case class NodeChange(nodeId: NodeId, status: NodeChangeStatus)

/** Outcome of a `ChangedNodesService.changedNodes` call. A plain multi-case
  * enum, not `Either`/`Option` — a tree missing on one or both sides is a
  * distinct, non-error outcome, not a failure to collapse into a generic empty
  * value. Mirrors the existing `TreeLoadDecision` convention (a plain enum for
  * "one of several non-error outcomes") rather than introducing `Either`'s
  * success/failure connotation for something that isn't a failure.
  */
enum ChangedNodesResult:
  case Changes(entries: List[NodeChange])
  case MissingOnA
  case MissingOnB
  case MissingOnBoth

/** Content-hash changed-nodes comparison (UC5) of a tree between two revisions
  * — no value-level comparison (DD-6), only whether each node's content hash
  * matches.
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

  /** Changed nodes of `treeId` between revisions `a` and `b` (E2/E6).
    * Deliberately symmetric (no baseline/comparand asymmetry,
    * PLAN-UI-MILESTONE-2B.md §0/§6); each side is a branch head or a pinned
    * commit.
    *
    * @return `ChangedNodesResult.MissingOnA`/`MissingOnB`/`MissingOnBoth` if
    *         the tree does not exist on one or both sides — e.g. it was deleted
    *         from a scenario branch while surviving on `main`. Not an error
    *         condition — mirrors `RiskTreeService.getById`'s `Option[RiskTree]`
    *         return, just distinguishing which side is missing instead of
    *         collapsing both to one empty value.
    */
  def changedNodes(
    wsId:   WorkspaceId,
    treeId: TreeId,
    a:      Revision,
    b:      Revision
  )(using Checked[Permission]): Task[ChangedNodesResult]

final case class ChangedNodesServiceLive(riskTreeService: RiskTreeService) extends ChangedNodesService:

  override def changedNodes(
    wsId:   WorkspaceId,
    treeId: TreeId,
    a:      Revision,
    b:      Revision
  )(using Checked[Permission]): Task[ChangedNodesResult] =
    // The two sides' trees are independent fetches (no shared state) — zipPar
    // avoids paying getById's Irmin round-trip cost twice, sequentially.
    riskTreeService.getById(wsId, treeId, a)
      .zipPar(riskTreeService.getById(wsId, treeId, b))
      .map {
        case (Some(treeA), Some(treeB)) =>
          ChangedNodesResult.Changes(changedHashes(ContentHashIndex.build(treeA), ContentHashIndex.build(treeB)))
        case (None, Some(_)) => ChangedNodesResult.MissingOnA
        case (Some(_), None) => ChangedNodesResult.MissingOnB
        case (None, None)    => ChangedNodesResult.MissingOnBoth
      }

  /** Keys present only in `hashesA` are `Removed`, only in `hashesB` are
    * `Added`, and shared keys compare hashes directly — three disjoint set
    * operations, no `Option`-pair match with an unreachable case needed.
    * Sorted by `NodeId.value` so the response has a stable order across
    * identical requests (a `Set`'s iteration order is not guaranteed).
    */
  private def changedHashes(hashesA: Map[NodeId, ContentHash], hashesB: Map[NodeId, ContentHash]): List[NodeChange] =
    val onlyA = (hashesA.keySet -- hashesB.keySet).map(NodeChange(_, NodeChangeStatus.Removed))
    val onlyB = (hashesB.keySet -- hashesA.keySet).map(NodeChange(_, NodeChangeStatus.Added))
    val both  = (hashesA.keySet & hashesB.keySet).map { id =>
      NodeChange(id, if hashesA(id) == hashesB(id) then NodeChangeStatus.Identical else NodeChangeStatus.Changed)
    }
    (onlyA ++ onlyB ++ both).toList.sortBy(_.nodeId.value)

object ChangedNodesServiceLive:
  val layer: ZLayer[RiskTreeService, Nothing, ChangedNodesService] =
    ZLayer.fromFunction(ChangedNodesServiceLive.apply)

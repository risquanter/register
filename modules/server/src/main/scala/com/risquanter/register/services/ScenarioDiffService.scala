package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, TreeId, NodeId, ContentHash, BranchRef}
import com.risquanter.register.services.cache.ContentHashIndex

/** Per-node content-hash comparison result (UC5) between two branches. */
enum NodeDiffStatus:
  case Identical, Changed, Added, Removed

  /** Wire form for `NodeDiffEntry.status` — colocated with the case list so a
    * new case and its wire string are added in the same place (mirrors
    * `DistributionMode.toApiString`).
    */
  def toWire: String = this match
    case NodeDiffStatus.Identical => "identical"
    case NodeDiffStatus.Changed   => "changed"
    case NodeDiffStatus.Added     => "added"
    case NodeDiffStatus.Removed   => "removed"

/** One node's diff outcome. */
final case class NodeDiff(nodeId: NodeId, status: NodeDiffStatus)

/** Outcome of a `ScenarioDiffService.diff` call. A plain multi-case enum,
  * not `Either`/`Option` — a tree missing on one or both branches is a
  * distinct, non-error outcome, not a failure to collapse into a generic
  * empty value. Mirrors the existing `TreeLoadDecision` convention (a plain
  * enum for "one of several non-error outcomes") rather than introducing
  * `Either`'s success/failure connotation for something that isn't a
  * failure.
  */
enum ScenarioDiffResult:
  case Diff(entries: List[NodeDiff])
  case MissingOnA
  case MissingOnB
  case MissingOnBoth

/** Content-hash diff (UC5, milestone-2b Phase C) of a tree between two
  * branches — no value-level comparison (DD-6), only whether each node's
  * content hash matches.
  *
  * The hashes compared are the *domain* content hashes (`ContentHashIndex`):
  * a leaf's hash covers only its simulation-relevant projection
  * (`LeafSimContent`) — not `name` or `parentId` — so a renamed or moved
  * node reports `Identical` here even though its persisted JSON, and
  * therefore its Irmin blob hash, changed. That is the intended semantics
  * (ADR-032): this diff answers "did the risk content change", not "did the
  * stored bytes change". Irmin is itself a content-addressed Merkle store,
  * so this service is not duplicating Irmin's hashing — it hashes a
  * different projection to answer a different question.
  *
  * Consequently this diff must NOT be used to predict Irmin merge outcomes:
  * Irmin merges per node file on byte equality of the full persisted JSON,
  * so a node reported `Identical` (or changed on one side only) here can
  * still merge-conflict — e.g. renamed on one branch while its probability
  * changed on the other. Merge-conflict prediction needs the storage-level
  * relation; see ADR-032 for both relations and where each applies.
  */
trait ScenarioDiffService:

  /** Diff `treeId` between `branchA` and `branchB`. Deliberately symmetric
    * (no baseline/comparand asymmetry, PLAN-UI-MILESTONE-2B.md §0/§6).
    *
    * @return `ScenarioDiffResult.MissingOnA`/`MissingOnB`/`MissingOnBoth` if
    *         the tree does not exist on one or both branches — e.g. it was
    *         deleted from a scenario branch while surviving on `main`
    *         (`WorkspaceTreeController.deleteTree`'s own documented
    *         behaviour). Not an error condition — mirrors
    *         `RiskTreeService.getById`'s `Option[RiskTree]` return, just
    *         distinguishing which side is missing instead of collapsing
    *         both to one empty value.
    */
  def diff(
    wsId:    WorkspaceId,
    treeId:  TreeId,
    branchA: BranchRef,
    branchB: BranchRef
  )(using Checked[Permission]): Task[ScenarioDiffResult]

final case class ScenarioDiffServiceLive(riskTreeService: RiskTreeService) extends ScenarioDiffService:

  override def diff(
    wsId:    WorkspaceId,
    treeId:  TreeId,
    branchA: BranchRef,
    branchB: BranchRef
  )(using Checked[Permission]): Task[ScenarioDiffResult] =
    // The two branches' trees are independent fetches (no shared state) —
    // zipPar avoids paying getById's Irmin round-trip cost twice, sequentially.
    riskTreeService.getById(wsId, treeId, branchA)
      .zipPar(riskTreeService.getById(wsId, treeId, branchB))
      .map {
        case (Some(treeA), Some(treeB)) =>
          ScenarioDiffResult.Diff(diffHashes(ContentHashIndex.build(treeA), ContentHashIndex.build(treeB)))
        case (None, Some(_)) => ScenarioDiffResult.MissingOnA
        case (Some(_), None) => ScenarioDiffResult.MissingOnB
        case (None, None)    => ScenarioDiffResult.MissingOnBoth
      }

  /** Keys present only in `hashesA` are `Removed`, only in `hashesB` are
    * `Added`, and shared keys compare hashes directly — three disjoint set
    * operations, no `Option`-pair match with an unreachable case needed.
    * Sorted by `NodeId.value` so the response has a stable order across
    * identical requests (a `Set`'s iteration order is not guaranteed).
    */
  private def diffHashes(hashesA: Map[NodeId, ContentHash], hashesB: Map[NodeId, ContentHash]): List[NodeDiff] =
    val onlyA = (hashesA.keySet -- hashesB.keySet).map(NodeDiff(_, NodeDiffStatus.Removed))
    val onlyB = (hashesB.keySet -- hashesA.keySet).map(NodeDiff(_, NodeDiffStatus.Added))
    val both  = (hashesA.keySet & hashesB.keySet).map { id =>
      NodeDiff(id, if hashesA(id) == hashesB(id) then NodeDiffStatus.Identical else NodeDiffStatus.Changed)
    }
    (onlyA ++ onlyB ++ both).toList.sortBy(_.nodeId.value)

object ScenarioDiffServiceLive:
  val layer: ZLayer[RiskTreeService, Nothing, ScenarioDiffService] =
    ZLayer.fromFunction(ScenarioDiffServiceLive.apply)

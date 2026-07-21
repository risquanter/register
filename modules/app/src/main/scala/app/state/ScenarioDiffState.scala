package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, UserId, WorkspaceKeySecret, ScenarioName}
import com.risquanter.register.http.endpoints.{WorkspaceTreeEndpoints, WorkspaceAnalysisEndpoints}
import com.risquanter.register.http.responses.ScenarioDiffResponse

/** Reactive state for the Analyze Overlay comparison mode (milestone-2b
  * Phase C) — everything scoped to "the branch being compared against":
  * the content-hash diff (UC5, for the ✎ markers in `TreeDetailView`) and
  * that branch's own LEC curves for whatever nodes are currently visible
  * (for the Overlay chart — paired against the tab's own already-cached
  * curves via `CompareColorAssigner.pairForOverlay`).
  *
  * Mirrors `TreeViewState`'s existing endpoint-calling pattern.
  */
final class ScenarioDiffState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends WorkspaceTreeEndpoints
  with WorkspaceAnalysisEndpoints:

  val diffResult: Var[LoadState[ScenarioDiffResponse]] = Var(LoadState.Idle)
  val compareCurves: Var[LoadState[Map[NodeId, LECNodeCurve]]] = Var(LoadState.Idle)

  /** Fetch the diff for `treeId` between `activeBranch` and `compareBranch`. */
  def loadDiff(
    treeId: TreeId,
    activeBranch: Option[ScenarioName.ScenarioName],
    compareBranch: Option[ScenarioName.ScenarioName]
  ): Unit =
    keySignal.now() match
      case Some(key) =>
        getScenarioDiffEndpoint((userIdAccessor(), key, treeId, activeBranch, compareBranch)).loadInto(diffResult)
      case None => ()

  /** Fetch `compareBranch`'s own curves for `nodeIds` — the same set the tab's
    * own `LECChartState` is already showing, just on the other branch. */
  def loadCompareCurves(
    treeId: TreeId,
    nodeIds: List[NodeId],
    compareBranch: Option[ScenarioName.ScenarioName]
  ): Unit =
    keySignal.now() match
      case Some(key) if nodeIds.nonEmpty =>
        getWorkspaceLECCurvesMultiEndpoint((userIdAccessor(), key, treeId, false, nodeIds, compareBranch)).loadInto(compareCurves)
      case _ =>
        compareCurves.set(LoadState.Idle)

  def reset(): Unit =
    diffResult.set(LoadState.Idle)
    compareCurves.set(LoadState.Idle)

  /** Nodes with a non-`"identical"` status — empty (not an error) for any
    * non-`"ok"` `status`, mirroring the service's own missing-tree semantics
    * (a tree missing on one or both branches has nothing to mark as changed).
    */
  val changedNodeIds: Signal[Set[NodeId]] = diffResult.signal.map(ScenarioDiffState.deriveChangedNodeIds)

object ScenarioDiffState:
  /** Pure derivation extracted from `changedNodeIds` so it's testable without
    * a Laminar `Var`/`Signal` harness. */
  def deriveChangedNodeIds(result: LoadState[ScenarioDiffResponse]): Set[NodeId] = result match
    case LoadState.Loaded(resp) if resp.status == "ok" =>
      resp.entries.filter(_.status != "identical").flatMap(e => NodeId.fromString(e.nodeId).toOption).toSet
    case _ => Set.empty

package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import app.core.*
import com.risquanter.register.domain.data.iron.{BranchChoice, NodeId, TreeId, UserId, WorkspaceKeySecret}
import com.risquanter.register.http.endpoints.WorkspaceTreeEndpoints
import com.risquanter.register.http.responses.ScenarioDiffResponse

/** Reactive state for the content-hash diff between the tab's active branch
  * and the compared branch — feeds the ✎ markers on the compare card's tree
  * view. The compared branch's own LEC curves are NOT here: the compare
  * card's own `TreeViewState`/`LECChartState` instance fetches them, exactly
  * like the tab's own chart state does for the active branch.
  */
final class ScenarioDiffState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends WorkspaceTreeEndpoints:

  // Not written directly by `loadDiff`/`reset`: driven from the EventBus-fed
  // `flatMapSwitch` pipeline below (`ZJS.loadStatePipeline`). A new call to
  // either method drops the subscription to whatever the previous request
  // was still doing, so a stale response can never land after a newer
  // request has started, regardless of which round trip finishes first.
  // Same mechanism as `AnalyzeQueryState`'s outcome pipeline.
  val diffResult: Var[LoadState[ScenarioDiffResponse]] = Var(LoadState.Idle)

  private val diffTrigger = new EventBus[Option[() => EventStream[Either[Throwable, ScenarioDiffResponse]]]]

  // Idempotency guard: callers fire on every tick of a combined signal, not
  // just on a real transition, and `Var.set` doesn't dedupe by value — an
  // unguarded write here would force `AnalyzeView`'s combined chart-spec
  // signal to recompute, tearing down and rebuilding the Vega chart for a
  // "change" that never happened.
  ZJS.loadStatePipeline(diffTrigger.events).foreach { v =>
    if diffResult.now() != v then diffResult.set(v)
  }(using unsafeWindowOwner)

  /** Fetch the diff for `treeId` between `activeBranch` and `compareBranch`. */
  def loadDiff(
    treeId: TreeId,
    activeBranch: BranchChoice,
    compareBranch: BranchChoice
  ): Unit =
    keySignal.now() match
      case Some(key) =>
        diffTrigger.emit(Some(() =>
          getScenarioDiffEndpoint((userIdAccessor(), key, treeId, activeBranch, compareBranch)).toOutcomeEventStream
        ))
      case None => reset()

  /** Also supersedes an in-flight diff fetch, if one was still running. */
  def reset(): Unit =
    diffTrigger.emit(None)

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

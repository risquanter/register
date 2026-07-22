package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import app.core.*
import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, UserId, WorkspaceKeySecret, ScenarioName}
import com.risquanter.register.domain.errors.RepositoryFailure
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

  // `diffResult`/`compareCurves` stay public `Var`s (unchanged type and
  // idempotent-reset behavior — see the doc comment each one carried before)
  // — every existing reader is unaffected. Neither is written directly by
  // `loadDiff`/`loadCompareCurves`/`reset`/`clearCompareCurves` any more:
  // each is driven from its own `EventBus`-fed `flatMapSwitch` pipeline
  // (`ScenarioDiffState.loadStatePipeline`, shared since both fetches have
  // the exact same "one request, or reset, supersedes whatever the previous
  // one was still doing" shape). A new call to either method makes
  // `flatMapSwitch` drop the subscription to whatever the *previous*
  // request for that same Var was still doing — a response for a tree/branch
  // combination the caller has already moved on from can't land after a
  // newer request has started, regardless of which round trip finishes
  // first. Same mechanism, same rationale, as `AnalyzeQueryState`'s own
  // `outcome` pipeline.
  val diffResult: Var[LoadState[ScenarioDiffResponse]] = Var(LoadState.Idle)
  val compareCurves: Var[LoadState[Map[NodeId, LECNodeCurve]]] = Var(LoadState.Idle)

  private val diffTrigger = new EventBus[Option[() => EventStream[Either[Throwable, ScenarioDiffResponse]]]]
  private val curvesTrigger = new EventBus[Option[() => EventStream[Either[Throwable, Map[NodeId, LECNodeCurve]]]]]

  // Idempotency guard (kept from before this pipeline existed): callers fire
  // on every tick of a combined signal, not just on a real transition, and
  // `Var.set` doesn't dedupe by value — an unguarded write here would still
  // force `AnalyzeView`'s combined chart-spec signal to recompute, tearing
  // down and rebuilding the Vega chart for a "change" that never happened.
  ScenarioDiffState.loadStatePipeline(diffTrigger.events).foreach { v =>
    if diffResult.now() != v then diffResult.set(v)
  }(using unsafeWindowOwner)
  ScenarioDiffState.loadStatePipeline(curvesTrigger.events).foreach { v =>
    if compareCurves.now() != v then compareCurves.set(v)
  }(using unsafeWindowOwner)

  /** Fetch the diff for `treeId` between `activeBranch` and `compareBranch`. */
  def loadDiff(
    treeId: TreeId,
    activeBranch: Option[ScenarioName.ScenarioName],
    compareBranch: Option[ScenarioName.ScenarioName]
  ): Unit =
    keySignal.now() match
      case Some(key) =>
        diffTrigger.emit(Some(() =>
          getScenarioDiffEndpoint((userIdAccessor(), key, treeId, activeBranch, compareBranch)).toOutcomeEventStream
        ))
      case None => reset()

  /** Fetch `compareBranch`'s own curves for `nodeIds` — the same set the tab's
    * own `LECChartState` is already showing, just on the other branch. */
  def loadCompareCurves(
    treeId: TreeId,
    nodeIds: List[NodeId],
    compareBranch: Option[ScenarioName.ScenarioName]
  ): Unit =
    keySignal.now() match
      case Some(key) if nodeIds.nonEmpty =>
        curvesTrigger.emit(Some(() =>
          getWorkspaceLECCurvesMultiEndpoint((userIdAccessor(), key, treeId, false, nodeIds, compareBranch)).toOutcomeEventStream
        ))
      case _ =>
        clearCompareCurves()

  /** Also supersedes an in-flight diff fetch, if one was still running. */
  def reset(): Unit =
    diffTrigger.emit(None)
    clearCompareCurves()

  /** Also supersedes an in-flight curve fetch, if one was still running. */
  def clearCompareCurves(): Unit =
    curvesTrigger.emit(None)

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

  /** `flatMapSwitch` over a stream of "what should I be loading right now"
    * triggers — `None` resets to `Idle`, `Some(request)` runs `request()`
    * and tracks its `Loading`/`Loaded`/`Failed` lifecycle. Whenever the
    * trigger stream emits again (a new request, or a reset), Airstream drops
    * the subscription to whatever the *previous* trigger's request stream
    * was still doing, so a stale response can never overwrite a newer one —
    * shared by `loadDiff`/`diffResult` and `loadCompareCurves`/`compareCurves`,
    * which otherwise differ only in the endpoint and payload type.
    *
    * Mirrors `ZJS.loadInto`'s error routing exactly: a workspace-sentinel
    * failure (A13 — the global banner's own domain) resolves to `Idle`,
    * any other failure resolves to `Failed(message)`. `toOutcomeEventStream`
    * (unlike `loadInto`'s plain `forkProvided`) already notified the global
    * `ErrorObserver` for either case before this pipeline ever sees the
    * outcome — this only decides what `diffResult`/`compareCurves` display.
    */
  private def loadStatePipeline[A](
    triggers: EventStream[Option[() => EventStream[Either[Throwable, A]]]]
  ): EventStream[LoadState[A]] =
    triggers.flatMapSwitch[LoadState[A], EventStream, EventStream] {
      case None => EventStream.fromValue(LoadState.Idle, emitOnce = true)
      case Some(request) =>
        val loading = EventStream.fromValue(LoadState.Loading, emitOnce = true)
        val settled = request().map {
          case Right(a) => LoadState.Loaded(a)
          case Left(e) if RepositoryFailure.isWorkspaceSentinel(e) => LoadState.Idle
          case Left(e) => LoadState.Failed(e.safeMessage)
        }
        loading.mergeWith(settled)
    }

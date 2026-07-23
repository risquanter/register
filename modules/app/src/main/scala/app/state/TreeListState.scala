package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.iron.{BranchChoice, UserId, WorkspaceKeySecret}
import com.risquanter.register.http.endpoints.WorkspaceLifecycleEndpoints
import com.risquanter.register.http.responses.{ScenarioSummaryResponse, SimulationResponse}

/** The workspace's tree lists, one per branch — genuinely shared server
  * state, the same ownership argument as `ScenarioListState`: which trees
  * exist on a branch doesn't change meaning depending on which view is
  * looking. A single instance is constructed once (`Main`) and consumed by
  * every `TreeViewState`, so a mutation through Design's builder refreshes
  * the one list Analyze is also reading — the stale-Analyze-list bug this
  * class exists to close.
  *
  * Keyed by `BranchChoice` (the single internal branch spelling, TODO item
  * 22): each branch's list has its own entry and its own request pipeline,
  * so a fetch for one branch can never overwrite another branch's list, and
  * a newer fetch for the same branch supersedes the older one
  * (`ZJS.loadStatePipeline` per branch — same shape as `ScenarioListState`).
  *
  * The map is exposed only as per-branch read-only slices (`listFor`); all
  * writes go through `refresh`/`ensureLoaded`/`seedMain` and the internal
  * subscriptions below.
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param scenarios      The shared scenario list (`ScenarioListState.scenarios`)
  *                       — drives pruning of entries for deleted scenarios.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class TreeListState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  scenarios: StrictSignal[LoadState[List[ScenarioSummaryResponse]]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends WorkspaceLifecycleEndpoints:

  private val byBranchVar: Var[Map[BranchChoice, LoadState[List[SimulationResponse]]]] =
    Var(Map.empty)

  /** One branch's list as a signal — a branch with no entry has simply never
    * been fetched and reads as `Idle`. */
  def listFor(branch: Signal[BranchChoice]): Signal[LoadState[List[SimulationResponse]]] =
    branch.combineWith(byBranchVar.signal).map((b, m) => m.getOrElse(b, LoadState.Idle))

  // One lazily-created trigger bus + pipeline per branch. The pipeline is the
  // per-branch supersede boundary: emitting a new request (or a reset) makes
  // `flatMapSwitch` drop whatever the previous request for THAT branch was
  // still doing, while other branches' in-flight fetches are untouched.
  // Single-threaded JS — a plain mutable map is safe here.
  private val triggers = collection.mutable.Map.empty[
    BranchChoice,
    EventBus[Option[() => EventStream[Either[Throwable, List[SimulationResponse]]]]]
  ]

  private def busFor(branch: BranchChoice) =
    triggers.getOrElseUpdate(branch, {
      val bus = new EventBus[Option[() => EventStream[Either[Throwable, List[SimulationResponse]]]]]
      loadStatePipeline(bus.events).foreach(storeResult(branch))(using unsafeWindowOwner)
      bus
    })

  /** One pipeline emission → the map. Same-value guard (mirrors
    * ScenarioListState): Var writes emit even when unchanged, and downstream
    * views react to `.changes`. `Idle` (a reset, or the workspace-sentinel
    * failure) removes the entry instead of storing it — "missing = Idle" is
    * the map's invariant, and storing Idle would resurrect entries the
    * pruning/clear subscriptions below just removed.
    */
  private def storeResult(branch: BranchChoice)(v: LoadState[List[SimulationResponse]]): Unit =
    val current = byBranchVar.now().get(branch)
    v match
      case LoadState.Idle => if current.nonEmpty then byBranchVar.update(_ - branch)
      case _              => if !current.contains(v) then byBranchVar.update(_.updated(branch, v))

  /** Fetch `branch`'s tree list unconditionally, superseding any still-running
    * fetch for the same branch. No-op if no workspace is active. Call after a
    * mutation on `branch`, or for an explicit user refresh. */
  def refresh(branch: BranchChoice): Unit =
    keySignal.now() match
      case Some(key) =>
        busFor(branch).emit(Some(() =>
          listWorkspaceTreesEndpoint((userIdAccessor(), key, branch)).toOutcomeEventStream
        ))
      case None => ()

  /** Fetch `branch`'s list only if it isn't already Loaded or in flight —
    * the mount/branch-switch entry point. Several consumers asking for the
    * same branch at startup (both views' TreeListView mounts, plus each
    * TreeViewState's own key subscription) collapse to one request instead
    * of the historical three; switching back to an already-fetched branch
    * shows its cached list instantly. */
  def ensureLoaded(branch: BranchChoice): Unit =
    byBranchVar.now().get(branch) match
      case Some(LoadState.Loaded(_)) | Some(LoadState.Loading) => ()
      case _                                                   => refresh(branch)

  /** Seed main's list from data already fetched elsewhere
    * (`WorkspaceState.preValidate`'s startup listing) so nobody re-requests
    * what that call just returned. */
  def seedMain(trees: List[SimulationResponse]): Unit =
    val v = LoadState.Loaded(trees)
    if !byBranchVar.now().get(BranchChoice.Main).contains(v) then
      byBranchVar.update(_.updated(BranchChoice.Main, v))

  // A workspace key change invalidates every cached list (they belong to the
  // old workspace). Resets are emitted into every existing bus FIRST so any
  // in-flight fetch for the old workspace is dropped by its pipeline and can
  // never land its stale list into the fresh map; then the map is cleared.
  // Main is re-fetched eagerly — every tab starts on main (ScenarioState) and
  // needs it immediately; scenario branches refill lazily via ensureLoaded.
  keySignal.changes.foreach { key =>
    triggers.values.foreach(_.emit(None))
    byBranchVar.set(Map.empty)
    key.foreach(_ => refresh(BranchChoice.Main))
  }(using unsafeWindowOwner)

  // Prune entries for scenarios that no longer exist — reacts to the shared
  // scenario list, reads its own state via now() (ADR-019 Pattern 6). Only a
  // Loaded list is trusted as evidence a branch is gone (Idle/Loading/Failed
  // are not), mirroring ScenarioState's branch-fallback subscription. The
  // dropped branch's bus also gets a reset so an in-flight fetch for it dies
  // rather than resurrecting the entry. Removing (not just emptying) the
  // entry means a later re-created scenario with the same name starts from
  // Idle and fetches fresh.
  scenarios.changes.foreach {
    case LoadState.Loaded(list) =>
      val names = list.map(_.name).toSet
      val stale = byBranchVar.now().keys.collect {
        case b @ BranchChoice.Scenario(name) if !names.contains(name) => b
      }.toSet
      if stale.nonEmpty then
        stale.foreach(b => triggers.get(b).foreach(_.emit(None)))
        byBranchVar.update(_.removedAll(stale))
    case _ => ()
  }(using unsafeWindowOwner)

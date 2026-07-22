package app.state

import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceKeySecret}
import com.risquanter.register.http.endpoints.ScenarioEndpoints
import com.risquanter.register.http.responses.ScenarioSummaryResponse

/** The workspace's actual scenario list — genuinely shared server state, not
  * per-view, the same way `WorkspaceState`'s key isn't per-view: it doesn't
  * change meaning depending on which view is looking at it. A single
  * `ScenarioListState` is constructed once (`Main`) and passed to every
  * `ScenarioState` instance, so a scenario created or deleted through any
  * one view's `ScenarioState.create`/`delete` is immediately visible through
  * every other instance's `scenarios` too — there is one list, not a copy
  * per view that could fall out of sync.
  *
  * `scenarios` is exposed read-only (`StrictSignal`, not `Var`) — the only
  * way to change it is `refresh()`, called internally by this class and by
  * `ScenarioState.create`/`delete` after their own mutation completes. There
  * is no public `Var` a holder could write to directly and silently
  * desync every other instance from what the server actually has.
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class ScenarioListState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends ScenarioEndpoints:

  private val scenariosVar: Var[LoadState[List[ScenarioSummaryResponse]]] = Var(LoadState.Idle)

  val scenarios: StrictSignal[LoadState[List[ScenarioSummaryResponse]]] = scenariosVar.signal

  /** Fetch the workspace's scenario list. No-op if no workspace is active. */
  def refresh(): Unit =
    keySignal.now() match
      case Some(key) => listScenariosEndpoint((userIdAccessor(), key)).loadInto(scenariosVar)
      case None      => ()

  // A workspace key appearing for the first time (bootstrap via Create, or a
  // returning session's URL key) means `scenarios` needs its first real
  // fetch — a caller's own onMountCallback-driven `refresh()` (e.g.
  // BranchBar.toolbar, BranchBar.picker) races the key not existing yet at
  // that mount and silently no-ops (the `None` case above).
  keySignal.changes.collect { case Some(_) => () }.foreach { _ => refresh() }(using unsafeWindowOwner)

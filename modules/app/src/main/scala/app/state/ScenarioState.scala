package app.state

import zio.ZIO
import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import app.core.safeMessage
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceKeySecret, ScenarioName}
import com.risquanter.register.http.endpoints.ScenarioEndpoints
import com.risquanter.register.http.requests.CreateScenarioRequest
import com.risquanter.register.http.responses.ScenarioSummaryResponse

/** Per-view active-branch selection (milestone-2b Phase B — BranchBar,
  * DD-5/DD-8/DD-9). Design and Analyze each construct their own instance —
  * "which branch is this view's own context" is exactly the part that must
  * differ between them.
  *
  * The workspace's actual scenario *list* is not this class's own state —
  * it lives in `listState: ScenarioListState`, a single instance shared by
  * every `ScenarioState` (see that class's doc for why the list isn't
  * per-view the way `activeBranch` is). `create`/`delete` here still perform
  * the mutation (they need this instance's own `keySignal`/`activeBranch`),
  * but hand the resulting refresh to `listState` — the shared list is never
  * written to directly by more than one code path.
  *
  * `activeBranch` is per-tab, in-memory only — `None` means main (mirrors
  * the server default, DD-8) and is never persisted to the URL or storage,
  * the same choice already made for `TreeViewState.selectedTreeId`: a page
  * refresh or a link opened in a new tab starts back on main, which is the
  * safe default, not data loss (nothing server-side depends on this value).
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param listState      Shared scenario list — see `ScenarioListState`.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class ScenarioState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  listState: ScenarioListState,
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends ScenarioEndpoints:

  /** `None` = main. */
  val activeBranch: Var[Option[ScenarioName.ScenarioName]] = Var(None)

  /** Read-only view of the shared scenario list — see `ScenarioListState`. */
  def scenarios: StrictSignal[LoadState[List[ScenarioSummaryResponse]]] = listState.scenarios

  /** Fetch the workspace's scenario list. No-op if no workspace is active. */
  def refresh(): Unit = listState.refresh()

  /** Switch this view's active branch. `None` switches back to main. */
  def switchTo(name: Option[ScenarioName.ScenarioName]): Unit =
    activeBranch.set(name)

  // Falls back to main if this view's active branch disappears from the
  // shared list — whether deleted through this view or any other. Reacts
  // only to `listState.scenarios`, the external signal that actually
  // invalidates the value, never to `activeBranch`'s own changes — so this
  // can't race a caller's own `switchTo` (ADR-019 Pattern 6 and the
  // "Self-Correcting Reactive Var" code smell it documents). `Loaded` only:
  // Idle/Loading/Failed aren't confirmation the branch is actually gone.
  listState.scenarios.changes.foreach {
    case LoadState.Loaded(list) =>
      val names = list.map(_.name).toSet
      activeBranch.now().foreach { current =>
        if !names.contains(current) then activeBranch.set(None)
      }
    case _ => ()
  }(using unsafeWindowOwner)

  /** Create a scenario, forked from `forkOf` (`None` = main's current head).
    * On success: refreshes the shared list and switches this view to the
    * new branch. Every other `ScenarioState` instance sees the new scenario
    * via the same shared list, independent of this switch.
    */
  def create(
    name: ScenarioName.ScenarioName,
    forkOf: Option[ScenarioName.ScenarioName],
    submitState: Var[ScenarioSubmitState]
  ): Unit =
    keySignal.now() match
      case None =>
        submitState.set(ScenarioSubmitState.Failed("Cannot create a scenario — no active workspace"))
      case Some(key) =>
        submitState.set(ScenarioSubmitState.Submitting)
        createScenarioEndpoint((userIdAccessor(), key, CreateScenarioRequest(name, forkOf)))
          .tap(response => ZIO.succeed {
            submitState.set(ScenarioSubmitState.Success(response))
            listState.refresh()
            switchTo(Some(response.name))
          })
          .tapError(e => ZIO.succeed(submitState.set(ScenarioSubmitState.Failed(e.safeMessage))))
          .runJs

  /** Delete a scenario via its CAS precondition (DD-5 Option A). Fetches the
    * head fresh right before deleting — rather than trusting the shared
    * list's last loaded value — so a delete clicked immediately after
    * `create()` (before that create's own refresh has resolved) still finds
    * the just-created scenario and its real head, instead of silently
    * no-op'ing against a stale or still-loading list. No-op if the scenario
    * is already gone by the time the fresh fetch resolves.
    *
    * Resets this instance's own `activeBranch` synchronously, in the same
    * success callback, if it was pointing at the branch just deleted — this
    * instance knows for certain the branch is gone; it doesn't need to wait
    * on the separate list refresh below to find out. The `listState.refresh()`
    * call is still what lets *every other* `ScenarioState` instance's own
    * branch-fallback subscription (above) notice the deletion — that path is
    * necessarily async (another instance only learns about it by observing
    * the shared list), but this instance's own knowledge shouldn't depend on
    * that refresh succeeding. Errors surface via the global error banner
    * (`forkProvided`'s default observer), matching other destructive actions.
    */
  def delete(name: ScenarioName.ScenarioName): Unit =
    keySignal.now() match
      case None => ()
      case Some(key) =>
        listScenariosEndpoint((userIdAccessor(), key))
          .flatMap { fresh =>
            fresh.find(_.name == name) match
              case Some(s) =>
                deleteScenarioEndpoint((userIdAccessor(), key, name, s.head))
                  .tap(_ => ZIO.succeed {
                    if activeBranch.now().contains(name) then activeBranch.set(None)
                    listState.refresh()
                  })
              case None => ZIO.unit
          }
          .runJs

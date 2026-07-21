package app.state

import zio.ZIO
import com.raquo.laminar.api.L.{*, given}

import app.core.ZJS.*
import app.core.safeMessage
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceKeySecret, ScenarioName}
import com.risquanter.register.http.endpoints.ScenarioEndpoints
import com.risquanter.register.http.requests.CreateScenarioRequest
import com.risquanter.register.http.responses.ScenarioSummaryResponse

/** Scenario (branch) lifecycle + the active-branch selection for this tab
  * (milestone-2b Phase B — BranchBar, DD-5/DD-8/DD-9).
  *
  * `activeBranch` is per-tab, in-memory only — `None` means main (mirrors
  * the server default, DD-8) and is never persisted to the URL or storage,
  * the same choice already made for `TreeViewState.selectedTreeId`: a page
  * refresh or a link opened in a new tab starts back on main, which is the
  * safe default, not data loss (nothing server-side depends on this value).
  *
  * @param keySignal      Read-only signal providing the active workspace key.
  * @param userIdAccessor Returns the current user identity (None in capability-only mode).
  */
final class ScenarioState(
  keySignal: StrictSignal[Option[WorkspaceKeySecret]],
  userIdAccessor: () => Option[UserId.Authenticated] = () => None
) extends ScenarioEndpoints:

  /** `None` = main. */
  val activeBranch: Var[Option[ScenarioName.ScenarioName]] = Var(None)

  val scenarios: Var[LoadState[List[ScenarioSummaryResponse]]] = Var(LoadState.Idle)

  /** Fetch the workspace's scenario list. No-op if no workspace is active. */
  def refresh(): Unit =
    keySignal.now() match
      case Some(key) => listScenariosEndpoint((userIdAccessor(), key)).loadInto(scenarios)
      case None      => ()

  /** Switch this tab's active branch. `None` switches back to main. */
  def switchTo(name: Option[ScenarioName.ScenarioName]): Unit =
    activeBranch.set(name)

  /** Create a scenario, forked from `forkOf` (`None` = main's current head).
    * On success: refreshes the list and switches this tab to the new branch.
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
            refresh()
            switchTo(Some(response.name))
          })
          .tapError(e => ZIO.succeed(submitState.set(ScenarioSubmitState.Failed(e.safeMessage))))
          .runJs

  /** Delete a scenario via its CAS precondition (DD-5 Option A). Fetches the
    * head fresh right before deleting — rather than trusting `scenarios.now()`
    * — so a delete clicked immediately after `create()` (before that create's
    * own `refresh()` has resolved) still finds the just-created scenario and
    * its real head, instead of silently no-op'ing against a stale or
    * still-loading list. No-op if the scenario is already gone by the time
    * the fresh fetch resolves. Switches this tab back to main first if it
    * was on the branch being deleted. Errors surface via the global error
    * banner (`forkProvided`'s default observer), matching other destructive
    * actions.
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
                    refresh()
                  })
              case None => ZIO.unit
          }
          .runJs

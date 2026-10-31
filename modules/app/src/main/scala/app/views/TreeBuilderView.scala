package app.views

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.Validation
import app.state.{TreeBuilderState, TreeBuilderField, TreeViewState, SubmitState, WorkspaceState, DistributionChartState, ScenarioState}
import app.components.{FailureNotice, FormInputs}
import app.components.ConfirmGuard.proceedOrConfirm
import app.core.LocalProblem
import app.core.ZJS.*
import com.risquanter.register.http.endpoints.{WorkspaceLifecycleEndpoints, WorkspaceTreeEndpoints}
import com.risquanter.register.http.responses.SimulationResponse

/**
 * Orchestrates portfolio + leaf forms, preview, and tree submission
 * using TreeBuilderState.
 *
 * Submission uses the `submitInto` ZJS extension (see `ZJS.scala`),
 * which drives a `Var[SubmitState]` through its lifecycle:
 * Submitting → Success (via callback) or Failed, carrying why it failed.
 * The sentence for a failure is composed here, where it is rendered.
 */
object TreeBuilderView extends WorkspaceLifecycleEndpoints
  with WorkspaceTreeEndpoints:
  def apply(state: TreeBuilderState, treeViewState: TreeViewState, wsState: WorkspaceState, chartState: DistributionChartState, scenarioState: ScenarioState, debugUi: Signal[Boolean]): HtmlElement =
    val submitState: Var[SubmitState] = Var(SubmitState.Idle)

    // A successful Create has nothing left to keep editing — matches
    // "+ New Tree"'s own reset (DesignView.onNewTree): blank builder, ready
    // for the next tree.
    //
    // `wasCreate` is passed in by each call site below, fixed at the moment
    // the request is fired (each site already statically knows which case
    // it's in — `handleSubmit` branched on `editingTreeId` to get here) —
    // not re-derived from `state.editingTreeId.now()` inside this callback.
    // Reading it live here would race: this callback only runs after the
    // async round trip completes, and if the user navigates to a different
    // tree while a Create is still in flight, `editingTreeId` would already
    // reflect that other tree by the time the response lands, misclassifying
    // a genuine Create as an Update and re-selecting the
    // just-created tree instead of leaving the user where they navigated to.
    //
    // Deliberately skips `treeViewState.selectTree(response.id)` on the
    // Create path — that call is what makes DesignView's own reload
    // subscription re-populate the builder from the server (`TreeLoadPolicy`'s
    // `SameContext` case, needed after Update to pick up anything the server
    // round-trip changed) — running it here would immediately undo the
    // blank reset.
    def onSuccess(wasCreate: Boolean)(response: SimulationResponse): Unit =
      submitState.set(SubmitState.Success(response))
      if wasCreate then
        state.startNewTree()
        treeViewState.selectedTreeId.set(None)
        treeViewState.loadTreeList()
      else
        state.editingTreeId.set(Some(response.id))
        state.markJustSaved()
        treeViewState.loadTreeList()
        treeViewState.selectTree(response.id)

    // A local refusal is not stored: `triggerValidation` has already run, so
    // the tree-name input and the topology line show what the submit refused,
    // each derived from the builder's current state.
    def validationFailed(): Unit =
      submitState.set(SubmitState.Idle)

    // The builder's line: the tree's current topology problems, then a failed
    // request's lines. Rendered only while there is at least one.
    val lineErrors: Signal[List[String]] =
      state.topologyError.combineWith(submitState.signal).map {
        case (topology, SubmitState.Failed(failure)) => topology ++ ErrorMessages.viewFailedLines(failure)
        case (topology, _)                           => topology
      }

    // `toRequest`/`toUpdateRequest` only ever read `portfoliosVar`/`leavesVar` —
    // a leaf or portfolio currently open mid-add/mid-edit (isEditDirtyVar) was
    // never committed into those lists, so submitting the tree now would
    // silently drop it. Same discard-confirmation the tree-switch/new-tree
    // guards in DesignView already use, extended to this action too.
    //
    // triggerValidation() runs only once the user has confirmed, so declining
    // the confirm dialog leaves no "required field" errors on screen for a
    // submission that never happened.
    def handleSubmit(): Unit =
      proceedOrConfirm(state.isEditDirtyVar.now(), "You have an unsaved leaf or portfolio draft that will not be included. Continue?") { () =>
        state.triggerValidation()
        state.editingTreeId.now() match
          // ── Update mode (workspace must already exist) ──
          case Some(treeId) =>
            wsState.currentKey match
              case None =>
                submitState.set(SubmitState.Failed(LocalProblem.NoActiveWorkspace))
              case Some(key) =>
                state.toUpdateRequest() match
                  case Validation.Success(_, request) =>
                    updateWorkspaceTreeEndpoint((wsState.currentUserId, key, treeId, request, scenarioState.activeBranch.now())).submitInto(submitState)(onSuccess(wasCreate = false))
                  case Validation.Failure(_, _) => validationFailed()

          // ── Create mode ──
          case None =>
            state.toRequest() match
              case Validation.Success(_, request) =>
                wsState.currentKey match
                  case Some(key) =>
                    createWorkspaceTreeEndpoint((wsState.currentUserId, key, request, scenarioState.activeBranch.now())).submitInto(submitState)(onSuccess(wasCreate = true))
                  case None =>
                    submitState.set(SubmitState.Submitting)
                    wsState.bootstrap(request, onSuccess(wasCreate = true), failure => submitState.set(SubmitState.Failed(failure)))
              case Validation.Failure(_, _) => validationFailed()
      }

    div(
      cls := "tree-builder",
      h1("Risk Tree Builder"),

      // Debug-only: makes the dirty-tracking flags that gate the unsaved-work
      // confirm dialogs directly visible, instead of having to infer their
      // state from behavior. Reads existing signals only — does not affect
      // any dirty-tracking logic. Gated behind the `debugUi` config flag
      // (`REGISTER_DEBUG_UI` / config.json) via `child.maybe`, so when the
      // flag is off the bar is absent from the DOM and its per-keystroke
      // dirty recomputation never subscribes at all.
      child.maybe <-- debugUi.map {
        case false => None
        case true => Some(div(
          cls := "dirty-debug-bar",
          cls("dirty-debug-bar--active") <-- state.isDirtySignal.combineWith(state.isEditDirtyVar.signal).map(_ || _),
          child.text <-- state.isDirtySignal
            .combineWith(state.isEditDirtyVar.signal, state.portfolioFormDirtyVar.signal, state.leafFormDirtyVar.signal)
            .map { case (treeDirty, editDirty, pDirty, lDirty) =>
              s"[debug] tree-dirty=$treeDirty  edit-dirty=$editDirty  (portfolio=$pDirty, leaf=$lDirty)"
            }
        ))
      },

      // A failed request goes stale when the user edits the tree's draft: its
      // name or its nodes. Only a failure is cleared. A Success stays until the
      // next submit, because selectTree() writes the draft right after onSuccess
      // sets it, and an in-flight submit keeps its spinner.
      state.draftEdits.sample(submitState.signal) --> {
        case SubmitState.Failed(_) => submitState.set(SubmitState.Idle)
        case _                     => ()
      },

      FormInputs.textInput(
        labelText = "Tree Name",
        valueVar = state.treeNameVar,
        errorSignal = state.treeNameError.map(_.toList),
        onBlurCallback = () => state.markTouched(TreeBuilderField.TreeName),
        placeholderText = "e.g., Enterprise Risk Tree"
      ),
      div(
        cls := "forms-stack",
        PortfolioFormView(state),
        RiskLeafFormView(state, chartState)
      ),
      FormInputs.submitButton(
        state.isUpdateMode.map(if _ then "Update Risk Tree" else "Create Risk Tree"),
        blockedBy = submitState.signal.map {
          case SubmitState.Submitting => List(BlockedMessages.TreeSaving)
          case _                      => Nil
        },
        onClickCallback = () => handleSubmit()
      ),
      child.maybe <-- submitState.signal.combineWith(state.isUpdateMode).map {
        case (SubmitState.Idle, _)        => None
        case (SubmitState.Submitting, isUpdate)  =>
          val verb = if isUpdate then "Updating" else "Creating"
          Some(div(cls := "submit-feedback submit-spinner", s"$verb risk tree…"))
        case (SubmitState.Success(r), _)  =>
          Some(div(cls := "submit-feedback submit-success",
            s"""Tree "${r.name}" saved with ID: ${r.id.value}"""
          ))
        case (SubmitState.Failed(_), _)   => None
      },
      child.maybe <-- lineErrors.map {
        case Nil   => None
        case lines => Some(FailureNotice.lines(lines, None))
      }
    )


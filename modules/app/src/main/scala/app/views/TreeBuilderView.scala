package app.views

import com.raquo.laminar.api.L.{*, given}
import zio.*
import zio.prelude.Validation
import app.state.{TreeBuilderState, TreeBuilderField, TreeViewState, SubmitState, WorkspaceState, DistributionChartState, ScenarioState}
import app.components.FormInputs
import app.components.ConfirmGuard.proceedOrConfirm
import app.core.ZJS.*
import com.risquanter.register.http.endpoints.{WorkspaceLifecycleEndpoints, WorkspaceTreeEndpoints}
import com.risquanter.register.http.responses.SimulationResponse

/**
 * Orchestrates portfolio + leaf forms, preview, and tree submission
 * using TreeBuilderState.
 *
 * Submission uses the `submitInto` ZJS extension (see `ZJS.scala`),
 * which drives a `Var[SubmitState]` through its lifecycle:
 * Submitting → Success (via callback) or Failed(msg).
 */
object TreeBuilderView extends WorkspaceLifecycleEndpoints
  with WorkspaceTreeEndpoints:
  def apply(state: TreeBuilderState, treeViewState: TreeViewState, wsState: WorkspaceState, chartState: DistributionChartState, scenarioState: ScenarioState): HtmlElement =
    val submitState: Var[SubmitState] = Var(SubmitState.Idle)

    def onSuccess(response: SimulationResponse): Unit =
      submitState.set(SubmitState.Success(response))
      state.editingTreeId.set(Some(response.id))
      state.markJustSaved()
      treeViewState.loadTreeList()
      treeViewState.selectTree(response.id)

    def validationFailed(errors: zio.NonEmptyChunk[com.risquanter.register.domain.errors.ValidationError]): Unit =
      submitState.set(SubmitState.Failed(errors.map(_.message).toList.mkString("; ")))

    // `toRequest`/`toUpdateRequest` only ever read `portfoliosVar`/`leavesVar` —
    // a leaf or portfolio currently open mid-add/mid-edit (isEditDirtyVar) was
    // never committed into those lists, so submitting the tree now would
    // silently drop it. Same discard-confirmation the tree-switch/new-tree
    // guards in DesignView already use, extended to this action too.
    //
    // triggerValidation() only runs once we know we're proceeding — it used
    // to run unconditionally up front, so declining the confirm dialog still
    // left "required field" errors painted on screen for a submission that
    // never happened.
    def handleSubmit(): Unit =
      proceedOrConfirm(state.isEditDirtyVar.now(), "You have an unsaved leaf or portfolio draft that will not be included. Continue?") { () =>
        state.triggerValidation()
        state.editingTreeId.now() match
          // ── Update mode (workspace must already exist) ──
          case Some(treeId) =>
            wsState.currentKey match
              case None =>
                submitState.set(SubmitState.Failed("Cannot update — no active workspace"))
              case Some(key) =>
                state.toUpdateRequest() match
                  case Validation.Success(_, request) =>
                    updateWorkspaceTreeEndpoint((wsState.currentUserId, key, treeId, request, scenarioState.activeBranch.now())).submitInto(submitState)(onSuccess)
                  case Validation.Failure(_, errors) => validationFailed(errors)

          // ── Create mode ──
          case None =>
            state.toRequest() match
              case Validation.Success(_, request) =>
                wsState.currentKey match
                  case Some(key) =>
                    createWorkspaceTreeEndpoint((wsState.currentUserId, key, request, scenarioState.activeBranch.now())).submitInto(submitState)(onSuccess)
                  case None =>
                    submitState.set(SubmitState.Submitting)
                    wsState.bootstrap(request, onSuccess, msg => submitState.set(SubmitState.Failed(msg)))
              case Validation.Failure(_, errors) => validationFailed(errors)
      }

    div(
      cls := "tree-builder",
      h1("Risk Tree Builder"),

      // Clear stale submit feedback when the user edits the tree name.
      // Does NOT clear a Success notification — Success transitions to Idle only via
      // the next explicit submit action. This prevents the programmatic-write trampoline
      // where selectTree() writes treeNameVar immediately after onSuccess sets Success,
      // causing the notification to disappear within milliseconds.
      state.treeNameVar.signal.changes
        .withCurrentValueOf(submitState.signal)
        --> { case (_, current) =>
          current match
            case SubmitState.Success(_) => ()
            case _                      => submitState.set(SubmitState.Idle)
        },

      FormInputs.textInput(
        labelText = "Tree Name",
        valueVar = state.treeNameVar,
        errorSignal = state.treeNameError,
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
        isDisabled = submitState.signal.map(_ == SubmitState.Submitting),
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
        case (SubmitState.Failed(msg), _) =>
          Some(div(cls := "submit-feedback submit-error", msg))
      }
    )


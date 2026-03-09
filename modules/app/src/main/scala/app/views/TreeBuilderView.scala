package app.views

import com.raquo.laminar.api.L.{*, given}
import zio.*
import zio.prelude.Validation
import app.state.{TreeBuilderState, TreeBuilderField, TreeViewState, SubmitState, WorkspaceState}
import app.components.FormInputs
import app.core.ZJS.*
import com.risquanter.register.http.endpoints.WorkspaceEndpoints
import com.risquanter.register.http.responses.SimulationResponse

/**
 * Orchestrates portfolio + leaf forms, preview, and tree submission
 * using TreeBuilderState.
 *
 * Submission uses the `submitInto` ZJS extension (see `ZJS.scala`),
 * which drives a `Var[SubmitState]` through its lifecycle:
 * Submitting → Success (via callback) or Failed(msg).
 */
object TreeBuilderView extends WorkspaceEndpoints:
  def apply(state: TreeBuilderState, treeViewState: TreeViewState, wsState: WorkspaceState): HtmlElement =
    val submitState: Var[SubmitState] = Var(SubmitState.Idle)

    def onSuccess(response: SimulationResponse): Unit =
      submitState.set(SubmitState.Success(response))
      state.editingTreeId.set(Some(response.id))
      treeViewState.loadTreeList()
      treeViewState.selectTree(response.id)

    def validationFailed(errors: zio.NonEmptyChunk[com.risquanter.register.domain.errors.ValidationError]): Unit =
      submitState.set(SubmitState.Failed(errors.map(_.message).toList.mkString("; ")))

    def handleSubmit(): Unit =
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
                  updateWorkspaceTreeEndpoint((wsState.currentUserId, key, treeId, request)).submitInto(submitState)(onSuccess)
                case Validation.Failure(_, errors) => validationFailed(errors)

        // ── Create mode ──
        case None =>
          state.toRequest() match
            case Validation.Success(_, request) =>
              wsState.currentKey match
                case Some(key) =>
                  createWorkspaceTreeEndpoint((wsState.currentUserId, key, request)).submitInto(submitState)(onSuccess)
                case None =>
                  submitState.set(SubmitState.Submitting)
                  wsState.bootstrap(request, onSuccess, msg => submitState.set(SubmitState.Failed(msg)))
            case Validation.Failure(_, errors) => validationFailed(errors)

    div(
      cls := "tree-builder",
      h1("Risk Tree Builder"),

      // Clear stale submit feedback when the user edits the tree name
      state.treeNameVar.signal.changes --> { _ => submitState.set(SubmitState.Idle) },

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
        RiskLeafFormView(state)
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


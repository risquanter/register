package app.views

import com.raquo.laminar.api.L.{*, given}
import zio.*
import zio.prelude.Validation
import app.state.{TreeBuilderState, TreeViewState, SubmitState}
import app.components.FormInputs
import app.core.ZJS.*
import com.risquanter.register.http.endpoints.RiskTreeEndpoints

/**
 * Orchestrates portfolio + leaf forms, preview, and tree submission
 * using TreeBuilderState.
 *
 * Submission uses the cheleb tapError→Var pattern: success and failure
 * are pushed directly into `submitState: Var[SubmitState]`.
 */
object TreeBuilderView extends RiskTreeEndpoints:
  def apply(state: TreeBuilderState, treeViewState: TreeViewState): HtmlElement =
    val submitState: Var[SubmitState] = Var(SubmitState.Idle)

    def onSuccess(response: com.risquanter.register.http.responses.SimulationResponse): Unit =
      submitState.set(SubmitState.Success(response))
      state.editingTreeId.set(Some(response.id))
      treeViewState.loadTreeList()
      // Auto-select the saved tree in the right panel so changes are visible
      treeViewState.selectTree(response.id)

    def handleSubmit(): Unit =
      state.triggerValidation()
      state.editingTreeId.now() match
        // ── Update mode ──
        case Some(treeId) =>
          state.toUpdateRequest() match
            case Validation.Success(_, request) =>
              submitState.set(SubmitState.Submitting)
              updateEndpoint((treeId, request))
                .tap(r => ZIO.succeed(onSuccess(r)))
                .tapError(e => ZIO.succeed(submitState.set(SubmitState.Failed(e.getMessage()))))
                .runJs
            case Validation.Failure(_, errors) =>
              submitState.set(SubmitState.Failed(errors.map(_.message).toList.mkString("; ")))
        // ── Create mode ──
        case None =>
          state.toRequest() match
            case Validation.Success(_, request) =>
              submitState.set(SubmitState.Submitting)
              createEndpoint(request)
                .tap(r => ZIO.succeed(onSuccess(r)))
                .tapError(e => ZIO.succeed(submitState.set(SubmitState.Failed(e.getMessage()))))
                .runJs
            case Validation.Failure(_, errors) =>
              submitState.set(SubmitState.Failed(errors.map(_.message).toList.mkString("; ")))

    div(
      cls := "tree-builder",
      h1("Risk Tree Builder"),

      // Clear stale submit feedback when the user edits the tree name
      state.treeNameVar.signal.changes --> { _ => submitState.set(SubmitState.Idle) },

      FormInputs.textInput(
        labelText = "Tree Name",
        valueVar = state.treeNameVar,
        errorSignal = state.treeNameError,
        onBlurCallback = () => state.markTouched("treeName"),
        placeholderText = "e.g., Enterprise Risk Tree"
      ),
      div(
        cls := "forms-grid",
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


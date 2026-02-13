package app.views

import com.raquo.laminar.api.L.{*, given}
import zio.*
import zio.prelude.Validation
import app.state.{TreeBuilderState, SubmitState}
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
  def apply(state: TreeBuilderState): HtmlElement =
    val submitState: Var[SubmitState] = Var(SubmitState.Idle)

    def handleSubmit(): Unit =
      state.triggerTreeNameValidation()
      state.toRequest() match
        case Validation.Success(_, request) =>
          submitState.set(SubmitState.Submitting)
          createEndpoint(request)
            .tap(response => ZIO.succeed(submitState.set(SubmitState.Success(response))))
            .tapError(e => ZIO.succeed(submitState.set(SubmitState.Failed(e.getMessage()))))
            .runJs
        case Validation.Failure(_, errors) =>
          submitState.set(SubmitState.Failed(
            errors.map(_.message).toList.mkString("; ")
          ))

    div(
      cls := "tree-builder",
      h1("Risk Tree Builder"),

      // Clear stale submit feedback when the user edits the tree name
      state.treeNameVar.signal.changes --> { _ => submitState.set(SubmitState.Idle) },

      FormInputs.textInput(
        labelText = "Tree Name",
        valueVar = state.treeNameVar,
        errorSignal = state.treeNameError,
        onBlurCallback = () => state.markTreeNameTouched(),
        placeholderText = "e.g., Enterprise Risk Tree"
      ),
      div(
        cls := "forms-grid",
        PortfolioFormView(state),
        RiskLeafFormView(state)
      ),
      FormInputs.submitButton(
        "Create Risk Tree",
        isDisabled = submitState.signal.map(_ == SubmitState.Submitting),
        onClickCallback = () => handleSubmit()
      ),
      child.maybe <-- submitState.signal.map {
        case SubmitState.Idle        => None
        case SubmitState.Submitting  =>
          Some(div(cls := "submit-feedback submit-spinner", "Creating risk tree…"))
        case SubmitState.Success(r)  =>
          Some(div(cls := "submit-feedback submit-success",
            s"""Tree "${r.name}" created with ID: ${r.id.value}"""
          ))
        case SubmitState.Failed(msg) =>
          Some(div(cls := "submit-feedback submit-error", msg))
      }
    )


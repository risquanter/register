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
  def apply(): HtmlElement =
    val state = new TreeBuilderState
    val submitState: Var[SubmitState] = Var(SubmitState.Idle)

    def handleSubmit(): Unit =
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
      div(
        cls := "tree-name-field",
        label(cls := "form-label", "Tree Name"),
        input(
          typ := "text",
          cls := "form-input",
          placeholder := "e.g., Enterprise Risk Tree",
          controlled(
            value <-- state.treeNameVar.signal,
            onInput.mapToValue --> state.treeNameVar
          )
        )
      ),
      TreePreview(state),
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


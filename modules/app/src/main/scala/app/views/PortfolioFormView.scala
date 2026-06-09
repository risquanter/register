package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.{PortfolioFormState, PortfolioField, TreeBuilderState}
import app.components.FormInputs
import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.SafeName

/**
 * Portfolio sub-form wired to TreeBuilderState.
 * Validates name locally (Iron SafeName) and delegates topology checks to
 * TreeBuilderState.addPortfolio / updatePortfolio.
 *
 * Mode is derived from `builderState.selectedPortfolioName.signal`:
 *  - None  → Add Portfolio mode
 *  - Some  → Edit Portfolio mode (submit calls updatePortfolio)
 */
object PortfolioFormView:

  def apply(builderState: TreeBuilderState): HtmlElement =
    val form = new PortfolioFormState
    val submitError: Var[Option[String]] = Var(None)
    val isEditMode: Signal[Boolean] = builderState.selectedPortfolioName.signal.map(_.isDefined)

    div(
      cls := "portfolio-form",
      h2(child.text <-- isEditMode.map(if _ then "Edit Portfolio" else "Add Portfolio")),

      // When a portfolio is selected, populate the form from its draft.
      builderState.selectedPortfolioName.signal.changes.collect { case Some(name) => name } --> { name =>
        builderState.portfoliosVar.now().find(_.name == name).foreach { portfolio =>
          builderState.populatePortfolioForm(form, portfolio)
        }
      },

      // Clear stale submit error whenever the user edits a field
      form.nameVar.signal.changes --> { _ =>
        submitError.set(None)
        form.clearSubmitFieldError(PortfolioField.Name)
      },
      form.parentVar.signal.changes --> { _ =>
        submitError.set(None)
        form.clearSubmitFieldError(PortfolioField.Parent)
      },

      FormInputs.textInput(
        labelText = "Portfolio Name",
        valueVar = form.nameVar,
        errorSignal = form.nameError,
        onBlurCallback = () => form.markTouched(PortfolioField.Name),
        placeholderText = "e.g., Operations",
        filter = _ => true
      ),
      FormInputs.parentSelect(form.parentVar, builderState.parentOptions, builderState.rootLabel),
      FormInputs.submitButton(
        textSignal = isEditMode.map(if _ then "Update Portfolio" else "Add Portfolio"),
        isDisabled = form.hasErrors,
        onClickCallback = () =>
          builderState.selectedPortfolioName.now() match
            case Some(originalName) => handleUpdate(form, originalName, builderState, submitError)
            case None               => handleSubmit(form, builderState, submitError)
      ),
      // Builder-level error (e.g., duplicate name or root constraint)
      child.maybe <-- submitError.signal.map(_.map(msg => div(cls := "form-error", msg)))
    )

  private def handleSubmit(
    form: PortfolioFormState,
    builderState: TreeBuilderState,
    submitError: Var[Option[String]]
  ): Unit =
    form.triggerValidation()
    form.toDraft match
      case Validation.Success(_, (name, parent)) =>
        builderState.addPortfolio(name, parent) match
          case Validation.Success(_, _) =>
            submitError.set(None)
            form.reset()
          case Validation.Failure(_, errs) =>
            FormSubmitUtil.routeTopologyErrors(form, errs.toList, submitError, {
              case "name"   => Some(PortfolioField.Name)
              case "parent" => Some(PortfolioField.Parent)
              case _        => None
            })
      case Validation.Failure(_, errs) =>
        submitError.set(Some(errs.head.message))

  private def handleUpdate(
    form: PortfolioFormState,
    originalName: SafeName.SafeName,
    builderState: TreeBuilderState,
    submitError: Var[Option[String]]
  ): Unit =
    form.triggerValidation()
    form.toDraft match
      case Validation.Success(_, (newName, newParent)) =>
        builderState.updatePortfolio(originalName, newName, newParent) match
          case Validation.Success(_, _) =>
            submitError.set(None)
            form.reset()
          case Validation.Failure(_, errs) =>
            FormSubmitUtil.routeTopologyErrors(form, errs.toList, submitError, {
              case "name"   => Some(PortfolioField.Name)
              case "parent" => Some(PortfolioField.Parent)
              case _        => None
            })
      case Validation.Failure(_, errs) =>
        submitError.set(Some(errs.head.message))

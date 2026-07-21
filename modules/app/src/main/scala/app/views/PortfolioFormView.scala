package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.{PortfolioFormState, PortfolioField, TreeBuilderState, FormMode, FormTarget, FieldSnapshot}
import app.components.FormInputs
import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.ValidationError

/**
 * Portfolio sub-form wired to TreeBuilderState.
 * Validates name locally (Iron SafeName) and delegates topology checks to
 * TreeBuilderState.addPortfolio / updatePortfolio.
 *
 * Mode is derived from `builderState.activeForm.signal`, filtered to the
 * `Portfolio` target — a `Leaf` target (or no target) is irrelevant to this
 * form and collapses to `Blank`, mirroring [[RiskLeafFormView]].
 */
object PortfolioFormView:

  def apply(builderState: TreeBuilderState): HtmlElement =
    val form = new PortfolioFormState
    val submitError: Var[Option[String]] = Var(None)

    val portfolioMode: Signal[FormMode] = builderState.activeForm.signal.map {
      case m @ FormMode.Locked(_: FormTarget.Portfolio)     => m
      case m @ FormMode.Editing(_: FormTarget.Portfolio)    => m
      case m @ FormMode.Templating(_: FormTarget.Portfolio) => m
      case _                                                => FormMode.Blank
    }
    val isLocked: Signal[Boolean] = portfolioMode.map { case FormMode.Locked(_) => true; case _ => false }

    val currentSnapshot: Signal[FieldSnapshot.PortfolioFields] =
      form.nameVar.signal.combineWith(form.parentVar.signal).map { (name, parent) =>
        FieldSnapshot.PortfolioFields(name, parent)
      }
    val isDirty: Signal[Boolean] = portfolioMode.combineWith(currentSnapshot).map { (mode, snapshot) =>
      FormMode.isFormDirty(mode, snapshot, builderState.leavesVar.now(), builderState.portfoliosVar.now())
    }

    val addSubmitLabel: Signal[String] = portfolioMode.map {
      case FormMode.Templating(_) => "Submit"
      case _                      => "Add Portfolio"
    }
    val addSubmitDisabled: Signal[Boolean] = portfolioMode.combineWith(form.hasErrors).map {
      case (FormMode.Editing(_), _) => true
      case (FormMode.Locked(_), _)  => false
      case (_, hasErrors)           => hasErrors
    }
    val editSaveLabel: Signal[String] = portfolioMode.map {
      case FormMode.Editing(_) => "Save"
      case _                   => "Edit"
    }
    val editSaveDisabled: Signal[Boolean] = portfolioMode.combineWith(form.hasErrors).map {
      case (FormMode.Blank, _)         => true
      case (FormMode.Templating(_), _) => true
      case (FormMode.Locked(_), _)     => false
      case (_, hasErrors)              => hasErrors
    }
    val clearFormDisabled: Signal[Boolean] = portfolioMode.map {
      case FormMode.Locked(_) => true
      case _                  => false
    }

    def onAddSubmitClick(): Unit =
      builderState.activeForm.now() match
        case FormMode.Blank                          => handleSubmit(form, builderState, submitError)
        case FormMode.Locked(t: FormTarget.Portfolio) => builderState.activeForm.set(FormMode.Templating(t))
        case FormMode.Templating(_: FormTarget.Portfolio) => handleSubmit(form, builderState, submitError)
        case _ => ()

    def onEditSaveClick(): Unit =
      builderState.activeForm.now() match
        case FormMode.Locked(t: FormTarget.Portfolio)      => builderState.activeForm.set(FormMode.Editing(t))
        case FormMode.Editing(FormTarget.Portfolio(name))  => handleUpdate(form, name, builderState, submitError)
        case _ => ()

    // Reverting to a saved portfolio's own values is exactly what populating
    // from it already does — the reactive populate subscription below fires
    // again as soon as `activeForm` moves back to `Locked(t)`.
    def onClearFormClick(): Unit =
      builderState.activeForm.now() match
        case FormMode.Blank =>
          form.reset()
          form.parentVar.set(None)
        case FormMode.Editing(t: FormTarget.Portfolio)    => builderState.activeForm.set(FormMode.Locked(t))
        case FormMode.Templating(t: FormTarget.Portfolio) => builderState.activeForm.set(FormMode.Locked(t))
        case _ => ()
      submitError.set(None)

    div(
      cls := "portfolio-form",
      h2(child.text <-- portfolioMode.map {
        case FormMode.Blank         => "Add Portfolio"
        case FormMode.Locked(_)     => "Portfolio Details"
        case FormMode.Editing(_)    => "Edit Portfolio"
        case FormMode.Templating(_) => "Add Portfolio"
      }),

      // When the active target is this portfolio, populate the form from its
      // saved draft. Any other target (Blank, or a leaf) clears the fields.
      builderState.activeForm.signal.changes --> { mode =>
        val portfolioName = mode match
          case FormMode.Locked(FormTarget.Portfolio(n))     => Some(n)
          case FormMode.Editing(FormTarget.Portfolio(n))    => Some(n)
          case FormMode.Templating(FormTarget.Portfolio(n)) => Some(n)
          case _                                            => None
        portfolioName match
          case Some(name) => builderState.portfoliosVar.now().find(_.name == name).foreach(builderState.populatePortfolioForm(form, _))
          case None       => form.reset(); form.parentVar.set(None)
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

      // Keep the shared dirty flag in sync — harmless when this form isn't the
      // active target, since `portfolioMode` collapses to `Blank` and `isDirty`
      // is pinned to false in that case (see RiskLeafFormView for the mirror).
      isDirty --> builderState.isEditDirtyVar.writer,

      FormInputs.textInput(
        labelText = "Portfolio Name",
        valueVar = form.nameVar,
        errorSignal = form.nameError,
        onBlurCallback = () => form.markTouched(PortfolioField.Name),
        placeholderText = "e.g., Operations",
        filter = _ => true,
        disabledSignal = isLocked
      ),
      FormInputs.parentSelect(form.parentVar, builderState.parentOptions, builderState.rootLabel, isLocked),

      // Add/Submit, Clear Form, Edit/Save — right-aligned as a group, Clear Form
      // immediately right of Add/Submit.
      div(
        cls := "form-actions",
        FormInputs.submitButton(
          textSignal = addSubmitLabel,
          isDisabled = addSubmitDisabled,
          onClickCallback = () => onAddSubmitClick()
        ),
        button(
          typ := "button",
          cls := "form-clear",
          "Clear Form",
          disabled <-- clearFormDisabled,
          onClick --> (_ => onClearFormClick())
        ),
        FormInputs.submitButton(
          textSignal = editSaveLabel,
          isDisabled = editSaveDisabled,
          onClickCallback = () => onEditSaveClick()
        )
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
            // Fields are left as-is — TreeBuilderState.addPortfolio already moved
            // `activeForm` to `Locked(newTarget)`, locking these same, just-submitted
            // values for viewing.
            submitError.set(None)
          case Validation.Failure(_, errs) =>
            FormSubmitUtil.routeTopologyErrors(form, errs.toList, submitError, {
              case "name"   => Some(PortfolioField.Name)
              case "parent" => Some(PortfolioField.Parent)
              case _        => None
            })
      case Validation.Failure(_, errs) =>
        routePortfolioDraftErrors(form, errs.toList, submitError)

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
            // TreeBuilderState.updatePortfolio already moved `activeForm` back to
            // `Locked(t)`, locking these same, just-saved values for viewing.
            submitError.set(None)
          case Validation.Failure(_, errs) =>
            FormSubmitUtil.routeTopologyErrors(form, errs.toList, submitError, {
              case "name"   => Some(PortfolioField.Name)
              case "parent" => Some(PortfolioField.Parent)
              case _        => None
            })
      case Validation.Failure(_, errs) =>
        routePortfolioDraftErrors(form, errs.toList, submitError)

  /** Route toDraft validation errors to their per-field inline slots.
    * Errors with no matching field fall back to the banner with their real message. */
  private def routePortfolioDraftErrors(
    form: PortfolioFormState,
    errors: List[ValidationError],
    submitError: Var[Option[String]]
  ): Unit =
    val unrouted = errors.filterNot { err =>
      fieldForDraftError(err.field) match
        case Some(field) => form.setSubmitFieldError(field, err.message); true
        case None        => false
    }
    submitError.set(unrouted.headOption.map(_.message))

  private def fieldForDraftError(field: String): Option[PortfolioField] =
    if field.contains("name") then Some(PortfolioField.Name)
    else if field.contains("parent") then Some(PortfolioField.Parent)
    else None

package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.{PortfolioFormState, TreeBuilderState}
import app.components.FormInputs
import zio.prelude.Validation

/**
 * Portfolio sub-form wired to TreeBuilderState.
 * Validates name locally (Iron SafeName) and delegates topology checks to TreeBuilderState.addPortfolio.
 */
object PortfolioFormView:

  def apply(builderState: TreeBuilderState): HtmlElement =
    val form = new PortfolioFormState
    val submitError: Var[Option[String]] = Var(None)

    div(
      cls := "portfolio-form",
      h2("Add Portfolio"),
      FormInputs.textInput(
        labelText = "Portfolio Name",
        valueVar = form.nameVar,
        errorSignal = form.nameError,
        onBlurCallback = () => if form.nameVar.now().nonEmpty then form.markTouched("name"),
        placeholderText = "e.g., Operations",
        filter = _ => true
      ),
      parentSelect(form, builderState),
      FormInputs.submitButton(
        text = "Add Portfolio",
        isDisabled = form.hasErrors,
        onClickCallback = () => handleSubmit(form, builderState, submitError)
      ),
      // Builder-level error (e.g., duplicate name or root constraint)
      child.maybe <-- submitError.signal.map(_.map(msg => div(cls := "form-error", msg)))
    )

  private def parentSelect(form: PortfolioFormState, builderState: TreeBuilderState): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", "Parent Portfolio"),
      select(
        cls := "form-input",
        controlled(
          value <-- form.parentVar.signal.combineWith(builderState.parentOptions).map { (sel, opts) =>
            val display = sel.getOrElse(builderState.rootLabel)
            if opts.contains(display) then display else opts.headOption.getOrElse(builderState.rootLabel)
          },
          onChange.mapToValue.map { v => if v == builderState.rootLabel then None else Some(v) } --> form.parentVar
        ),
        // Auto-sync parentVar when options change and current selection becomes invalid
        builderState.parentOptions --> { opts =>
          val current = form.parentVar.now().getOrElse(builderState.rootLabel)
          if !opts.contains(current) then
            opts.headOption match
              case Some(v) if v == builderState.rootLabel => form.parentVar.set(None)
              case Some(v) => form.parentVar.set(Some(v))
              case None => ()
        },
        children <-- builderState.parentOptions.map { opts =>
          opts.map { opt =>
            option(
              value := opt,
              opt
            )
          }
        }
      )
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
            submitError.set(Some(errs.head.message))
      case Validation.Failure(_, errs) =>
        submitError.set(Some(errs.head.message))

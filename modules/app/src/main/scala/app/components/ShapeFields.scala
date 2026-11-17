package app.components

import com.raquo.laminar.api.L.*

import app.components.FormInputs.textInput
import app.state.{ShapeFieldsForm, ShapeInput}

/** The inputs a loss distribution is entered in, one group per mode, for any
  * form holding them. Stateless (ADR-019 Pattern 5): the form owns every
  * value, and leaving an input reports it through the form.
  *
  * @param isDisabled   True while the inputs may not be edited.
  * @param showLockIcon True while the values shown are saved ones.
  */
object ShapeFields:

  /** The metalog inputs: percentiles and their losses, the implied-ratio
    * advisory, then the optional term count. */
  def metalog(form: ShapeFieldsForm, isDisabled: Signal[Boolean], showLockIcon: Signal[Boolean]): HtmlElement =
    div(
      cls := "mode-fields",
      div(cls := "mode-fields-title", "Metalog Parameters"),
      input(form, ShapeInput.Percentiles, form.percentilesVar, form.percentilesError, isDisabled, showLockIcon),
      input(form, ShapeInput.Quantiles, form.quantilesVar, form.quantilesError, isDisabled, showLockIcon),
      child.maybe <-- form.impliedRatioWarning.map(_.map(msg => div(cls := "form-warning", msg))),
      input(form, ShapeInput.Terms, form.termsVar, form.termsError, isDisabled, showLockIcon)
    )

  /** The lognormal inputs: the two loss bounds of the 90% interval. */
  def lognormal(form: ShapeFieldsForm, isDisabled: Signal[Boolean], showLockIcon: Signal[Boolean]): HtmlElement =
    div(
      cls := "mode-fields",
      div(cls := "mode-fields-title", "Lognormal Parameters (90% CI)"),
      input(form, ShapeInput.MinLoss, form.minLossVar, form.minLossError, isDisabled, showLockIcon),
      input(form, ShapeInput.MaxLoss, form.maxLossVar, form.maxLossError, isDisabled, showLockIcon)
    )

  private def input(
    form:         ShapeFieldsForm,
    shapeInput:   ShapeInput,
    valueVar:     Var[String],
    errorSignal:  Signal[List[String]],
    isDisabled:   Signal[Boolean],
    showLockIcon: Signal[Boolean]
  ): HtmlElement =
    textInput(
      labelText = ShapeInput.label(shapeInput),
      inputId = form.inputIdOf(shapeInput),
      valueVar = valueVar,
      errorSignal = errorSignal,
      filter = ShapeInput.filter(shapeInput),
      onBlurCallback = () => form.markShapeTouched(shapeInput),
      placeholderText = ShapeInput.placeholder(shapeInput),
      inputModeAttr = ShapeInput.inputMode(shapeInput),
      disabledSignal = isDisabled,
      lockedSignal = showLockIcon
    )

package app.views

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.Validation
import app.state.{RiskLeafFormState, DistributionMode, TreeBuilderState}
import app.components.FormInputs.*

/**
 * Complete form for creating a RiskLeaf.
 * Uses RiskLeafFormState for reactive state and validation.
 */
object RiskLeafFormView:

  def apply(builderState: TreeBuilderState): HtmlElement =
    val state = new RiskLeafFormState
    val parentVar: Var[Option[String]] = Var(None)
    val submitError: Var[Option[String]] = Var(None)
    
    div(
      cls := "risk-leaf-form",
      h2("Add Risk Leaf"),
      p(
        cls := "form-description",
        "Configure a risk leaf node with either expert distribution or lognormal distribution."
      ),
      
      // Common Fields
      textInput(
        labelText = "Name",
        valueVar = state.nameVar,
        errorSignal = state.nameError,
        filter = state.nameFilter,
        onBlurCallback = () => if state.nameVar.now().nonEmpty then state.markTouched("name"),
        placeholderText = "e.g., Cyber Attack Risk"
      ),
      
      textInput(
        labelText = "Probability",
        valueVar = state.probabilityVar,
        errorSignal = state.probabilityError,
        filter = state.probabilityFilter,
        onBlurCallback = () => state.markTouched("probability"),
        placeholderText = "e.g., 0.25",
        inputModeAttr = "decimal"
      ),
      
      // Distribution Mode Toggle
      radioGroup(
        labelText = "Distribution Type",
        options = List(
          (DistributionMode.Expert, "Expert Opinion"),
          (DistributionMode.Lognormal, "Lognormal (BCG)")
        ),
        selectedVar = state.distributionModeVar
      ),
      
      // Parent selection
      parentSelect(parentVar, builderState),
      
      // Conditional Fields based on mode
      child <-- state.distributionModeVar.signal.map {
        case DistributionMode.Expert => expertFields(state)
        case DistributionMode.Lognormal => lognormalFields(state)
      },
      
      // Submit Button
      submitButton(
        text = "Add Leaf",
        isDisabled = state.hasErrors,
        onClickCallback = () => handleSubmit(state, parentVar, builderState, submitError)
      ),
      child.maybe <-- submitError.signal.map(_.map(msg => div(cls := "form-error", msg)))
    )
  
  /** Expert mode specific fields */
  private def expertFields(state: RiskLeafFormState): HtmlElement =
    div(
      cls := "mode-fields",
      div(cls := "mode-fields-title", "Expert Opinion Parameters"),
      
      textInput(
        labelText = "Percentiles",
        valueVar = state.percentilesVar,
        errorSignal = state.percentilesError,
        filter = state.arrayFilter,
        onBlurCallback = () => state.markTouched("percentiles"),
        placeholderText = "e.g., 10, 50, 90",
        inputModeAttr = "decimal"
      ),
      
      textInput(
        labelText = "Quantiles (loss values)",
        valueVar = state.quantilesVar,
        errorSignal = state.quantilesError,
        filter = state.arrayFilter,
        onBlurCallback = () => state.markTouched("quantiles"),
        placeholderText = "e.g., 100, 500, 2000",
        inputModeAttr = "decimal"
      ),
      
      // Cross-field error
      crossFieldError(state.expertCrossFieldError)
    )
  
  /** Lognormal mode specific fields */
  private def lognormalFields(state: RiskLeafFormState): HtmlElement =
    div(
      cls := "mode-fields",
      div(cls := "mode-fields-title", "Lognormal Parameters (80% CI)"),
      
      textInput(
        labelText = "Minimum Loss",
        valueVar = state.minLossVar,
        errorSignal = state.minLossError,
        filter = state.lossFilter,
        onBlurCallback = () => state.markTouched("minLoss"),
        placeholderText = "e.g., 1000",
        inputModeAttr = "numeric"
      ),
      
      textInput(
        labelText = "Maximum Loss",
        valueVar = state.maxLossVar,
        errorSignal = state.maxLossError,
        filter = state.lossFilter,
        onBlurCallback = () => state.markTouched("maxLoss"),
        placeholderText = "e.g., 50000",
        inputModeAttr = "numeric"
      ),
      
      // Cross-field error
      crossFieldError(state.lognormalCrossFieldError)
    )
  
  private def parentSelect(parentVar: Var[Option[String]], builderState: TreeBuilderState): HtmlElement =
    div(
      cls := "form-field",
      label(cls := "form-label", "Parent Portfolio"),
      select(
        cls := "form-input",
        controlled(
          value <-- parentVar.signal.combineWith(builderState.parentOptions).map { (sel, opts) =>
            val display = sel.getOrElse(builderState.rootLabel)
            if opts.contains(display) then display else opts.headOption.getOrElse(builderState.rootLabel)
          },
          onChange.mapToValue.map { v => if v == builderState.rootLabel then None else Some(v) } --> parentVar
        ),
        // Auto-sync parentVar when options change and current selection becomes invalid
        builderState.parentOptions --> { opts =>
          val current = parentVar.now().getOrElse(builderState.rootLabel)
          if !opts.contains(current) then
            opts.headOption match
              case Some(v) if v == builderState.rootLabel => parentVar.set(None)
              case Some(v) => parentVar.set(Some(v))
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

  /** Handle form submission */
  private def handleSubmit(
    state: RiskLeafFormState,
    parentVar: Var[Option[String]],
    builderState: TreeBuilderState,
    submitError: Var[Option[String]]
  ): Unit =
    state.triggerValidation()
    state.toDistributionDraft match
      case Validation.Success(_, dist) =>
        builderState.addLeaf(state.nameVar.now(), parentVar.now(), dist) match
          case Validation.Success(_, _) =>
            submitError.set(None)
            parentVar.set(None)
            state.showErrorsVar.set(false)
            state.touchedFields.set(Set.empty)
            // keep leaf field values? For now leave as-is so user can add variants
          case Validation.Failure(_, errs) => submitError.set(Some(errs.head.message))
      case Validation.Failure(_, errs) => submitError.set(Some(errs.head.message))

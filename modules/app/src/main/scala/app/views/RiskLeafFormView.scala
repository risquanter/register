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

      // Clear stale submit error whenever the user edits any field
      EventStream.merge(
        state.nameVar.signal.changes.mapTo(()),
        state.probabilityVar.signal.changes.mapTo(()),
        state.distributionModeVar.signal.changes.mapTo(()),
        state.percentilesVar.signal.changes.mapTo(()),
        state.quantilesVar.signal.changes.mapTo(()),
        state.minLossVar.signal.changes.mapTo(()),
        state.maxLossVar.signal.changes.mapTo(()),
        parentVar.signal.changes.mapTo(())
      ) --> { _ => submitError.set(None) },

      // Common Fields
      textInput(
        labelText = "Name",
        valueVar = state.nameVar,
        errorSignal = state.nameError,
        filter = state.nameFilter,
        onBlurCallback = () => state.markTouched("name"),
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
      parentSelect(parentVar, builderState.parentOptions, builderState.rootLabel),
      
      // Conditional Fields based on mode
      child <-- state.distributionModeVar.signal.map {
        case DistributionMode.Expert => expertFields(state)
        case DistributionMode.Lognormal => lognormalFields(state)
      },
      
      // Submit / Clear Buttons
      div(
        cls := "form-actions",
        submitButton(
          text = "Add Leaf",
          isDisabled = state.hasErrors,
          onClickCallback = () => handleSubmit(state, parentVar, builderState, submitError)
        ),
        button(
          typ := "button",
          cls := "form-clear",
          "Clear Form",
          onClick --> { _ =>
            state.resetFields()
            parentVar.set(None)
            submitError.set(None)
          }
        )
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
        labelText = "Quantiles (loss in $M)",
        valueVar = state.quantilesVar,
        errorSignal = state.quantilesError,
        filter = state.arrayFilter,
        onBlurCallback = () => state.markTouched("quantiles"),
        placeholderText = "e.g., 50, 200, 1000 ($50M, $200M, $1B)",
        inputModeAttr = "decimal"
      )
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
            // Intentional: parentVar is NOT reset — it is auto-synced by
            // FormInputs.parentSelect based on available options.  Resetting
            // it to None races with auto-sync and causes the displayed value
            // ("Root") to diverge from the Var (None), making subsequent
            // leaf additions fail with "must select a parent portfolio".
            // Intentional: resetTouched() (not resetFields()) preserves form values
            // so the user can quickly add successive leaves with similar parameters,
            // only changing name/parent between submits.
            state.resetTouched()
          case Validation.Failure(_, errs) => submitError.set(Some(errs.head.message))
      case Validation.Failure(_, errs) => submitError.set(Some(errs.head.message))

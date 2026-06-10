package app.views

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.Validation
import app.state.{RiskLeafFormState, RiskLeafField, DistributionMode, TreeBuilderState, DistributionChartState}
import app.components.FormInputs.*
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.ValidationError

/**
 * Complete form for creating a RiskLeaf.
 * Uses RiskLeafFormState for reactive state and validation.
 */
object RiskLeafFormView:

  def apply(builderState: TreeBuilderState, chartState: DistributionChartState): HtmlElement =
    val state = new RiskLeafFormState
    val submitError: Var[Option[String]] = Var(None)
    val isEditMode: Signal[Boolean] = builderState.selectedLeafName.signal.map(_.isDefined)
    
    div(
      cls := "risk-leaf-form",
      h2(child.text <-- isEditMode.map(if _ then "Edit Risk Leaf" else "Add Risk Leaf")),
      p(
        cls := "form-description",
        "Configure a risk leaf node with either expert distribution or lognormal distribution."
      ),

      // When a leaf is selected, populate the form from its draft.
      builderState.selectedLeafName.signal.changes.collect { case Some(name) => name } --> { name =>
        builderState.leavesVar.now().find(_.name == name).foreach { leaf =>
          builderState.populateLeafForm(state, leaf)
        }
      },

      // Clear stale submit + per-field errors whenever the user edits a field
      state.nameVar.signal.changes --> { _ =>
        submitError.set(None)
        state.clearSubmitFieldError(RiskLeafField.Name)
      },
      state.probabilityVar.signal.changes --> { _ =>
        submitError.set(None)
        state.clearSubmitFieldError(RiskLeafField.Probability)
      },
      state.percentilesVar.signal.changes --> { _ =>
        submitError.set(None)
        state.clearSubmitFieldError(RiskLeafField.Percentiles)
      },
      state.quantilesVar.signal.changes --> { _ =>
        submitError.set(None)
        state.clearSubmitFieldError(RiskLeafField.Quantiles)
      },
      state.minLossVar.signal.changes --> { _ =>
        submitError.set(None)
        state.clearSubmitFieldError(RiskLeafField.MinLoss)
      },
      state.maxLossVar.signal.changes --> { _ =>
        submitError.set(None)
        state.clearSubmitFieldError(RiskLeafField.MaxLoss)
      },
      state.distributionModeVar.signal.changes --> { _ => submitError.set(None) },
      state.parentVar.signal.changes --> { _ => submitError.set(None) },

      // Push reactive draft up to TreeBuilderState for DistributionChartState to observe.
      // draftSignal already returns None when distribution fields are invalid (via
      // Distribution.create) — no additional hasErrors gate needed here. Removing the
      // gate allows the preview to render when distribution params are valid even if
      // name/probability are still empty (submit validation is separate and unchanged).
      // Cleared on unmount so the chart returns to Idle when the leaf form is not active.
      state.draftSignal --> builderState.currentDraftVar.writer,
      onUnmountCallback { _ =>
        builderState.currentDraftVar.set(None)
        builderState.selectedLeafName.set(None)
      },

      // Common Fields
      textInput(
        labelText = "Name",
        valueVar = state.nameVar,
        errorSignal = state.nameError,
        filter = state.nameFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.Name),
        placeholderText = "e.g., Cyber Attack Risk"
      ),
      
      textInput(
        labelText = "Probability",
        valueVar = state.probabilityVar,
        errorSignal = state.probabilityError,
        filter = state.probabilityFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.Probability),
        placeholderText = "e.g., 40 (= 40%)",
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
      parentSelect(state.parentVar, builderState.parentOptions, builderState.rootLabel),
      
      // Conditional Fields based on mode
      child <-- state.distributionModeVar.signal.map {
        case DistributionMode.Expert => expertFields(state)
        case DistributionMode.Lognormal => lognormalFields(state)
      },
      
      // Submit / Clear Buttons + preview toggle
      div(
        cls := "form-actions",
        button(
          typ := "button",
          cls := "form-clear",
          "Clear Form",
          onClick --> { _ =>
            state.resetFields()
            state.parentVar.set(None)
            builderState.selectedLeafName.set(None)
            submitError.set(None)
          }
        ),
        // Preview toggle — always enabled; endpoint is public (no workspace key required)
        label(
          cls := "form-preview-toggle",
          input(
            typ := "checkbox",
            checked <-- chartState.previewEnabledVar.signal,
            onChange.mapToChecked --> chartState.previewEnabledVar.writer
          ),
          span("Show preview")
        ),
        submitButton(
          textSignal = isEditMode.map(if _ then "Update Leaf" else "Add Leaf"),
          isDisabled = state.hasErrors,
          onClickCallback = () =>
            builderState.selectedLeafName.now() match
              case Some(originalName) => handleUpdate(state, originalName, builderState, submitError)
              case None               => handleSubmit(state, builderState, submitError)
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
        filter = state.percentilesFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.Percentiles),
        placeholderText = "e.g., 10, 50, 90",
        inputModeAttr = "decimal"
      ),
      
      textInput(
        labelText = "Quantiles (loss in $M)",
        valueVar = state.quantilesVar,
        errorSignal = state.quantilesError,
        filter = state.quantilesFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.Quantiles),
        placeholderText = "e.g., 50, 200, 1000 ($50M, $200M, $1B)",
        inputModeAttr = "decimal"
      ),

      // Implied ratio warning: shown when P90/P10 ratio exceeds 100×
      child.maybe <-- state.impliedRatioWarning.map(_.map { msg =>
        div(cls := "form-warning", msg)
      }),

      textInput(
        labelText = "Terms (optional)",
        valueVar = state.termsVar,
        errorSignal = state.termsError,
        filter = state.lossFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.Terms),
        placeholderText = "e.g. 3",
        inputModeAttr = "numeric"
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
        onBlurCallback = () => state.markTouched(RiskLeafField.MinLoss),
        placeholderText = "e.g., 1000",
        inputModeAttr = "numeric"
      ),
      
      textInput(
        labelText = "Maximum Loss",
        valueVar = state.maxLossVar,
        errorSignal = state.maxLossError,
        filter = state.lossFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.MaxLoss),
        placeholderText = "e.g., 50000",
        inputModeAttr = "numeric"
      )
    )
  
  /** Handle add-leaf submission. */
  private def handleSubmit(
    state: RiskLeafFormState,
    builderState: TreeBuilderState,
    submitError: Var[Option[String]]
  ): Unit =
    state.triggerValidation()
    (state.currentShapeValidation(), state.refinedProbability) match
      case (Validation.Success(_, shape), Some(prob)) =>
        builderState.addLeaf(state.nameVar.now(), state.parentVar.now(), shape, prob) match
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
          case Validation.Failure(_, errs) =>
            FormSubmitUtil.routeTopologyErrors(state, errs.toList, submitError, {
              case "name"   => Some(RiskLeafField.Name)
              case "parent" => None  // leaf form has no parent text input to highlight
              case _        => None
            })
      case (shapeV, _) =>
        // Shape/probability invalid. Surface the SPECIFIC, field-routed reasons
        // inline below each field (probability is already covered reactively).
        routeLeafShapeErrors(state, shapeErrorsOf(shapeV), submitError)

  /** Handle update-leaf submission (edit mode). */
  private def handleUpdate(
    state: RiskLeafFormState,
    originalName: SafeName.SafeName,
    builderState: TreeBuilderState,
    submitError: Var[Option[String]]
  ): Unit =
    state.triggerValidation()
    (state.currentShapeValidation(), state.refinedProbability) match
      case (Validation.Success(_, shape), Some(prob)) =>
        builderState.updateLeaf(originalName, state.nameVar.now(), state.parentVar.now(), shape, prob) match
          case Validation.Success(_, _) =>
            submitError.set(None)
            state.resetTouched()
          case Validation.Failure(_, errs) =>
            FormSubmitUtil.routeTopologyErrors(state, errs.toList, submitError, {
              case "name"   => Some(RiskLeafField.Name)
              case "parent" => None
              case _        => None
            })
      case (shapeV, _) =>
        routeLeafShapeErrors(state, shapeErrorsOf(shapeV), submitError)

  /** Extract the validation errors from a shape result (empty when it succeeded). */
  private def shapeErrorsOf(shapeV: Validation[ValidationError, ?]): List[ValidationError] =
    shapeV match
      case Validation.Failure(_, errs) => errs.toList
      case _                           => Nil

  /** Route distribution-shape validation errors to their per-field inline slots
    * (same visual channel as reactive errors, via `setSubmitFieldError`). Errors
    * with no matching form field fall back to the submit-error banner carrying
    * their real message — never a generic constant. */
  private def routeLeafShapeErrors(
    state: RiskLeafFormState,
    errors: List[ValidationError],
    submitError: Var[Option[String]]
  ): Unit =
    val unrouted = errors.filterNot { err =>
      fieldForShapeError(err.field) match
        case Some(field) => state.setSubmitFieldError(field, err.message); true
        case None        => false
    }
    submitError.set(unrouted.headOption.map(_.message))

  /** Map a `Distribution.create` field path (e.g. `leaf.percentiles[1]`) to the
    * form field whose inline error slot should display it. */
  private def fieldForShapeError(field: String): Option[RiskLeafField] =
    if field.contains("percentiles") then Some(RiskLeafField.Percentiles)
    else if field.contains("quantiles") then Some(RiskLeafField.Quantiles)
    else if field.contains("minLoss") then Some(RiskLeafField.MinLoss)
    else if field.contains("maxLoss") then Some(RiskLeafField.MaxLoss)
    else if field.contains("terms") then Some(RiskLeafField.Terms)
    else None

package app.views

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.Validation
import app.state.{RiskLeafFormState, RiskLeafField, DistributionMode, TreeBuilderState, DistributionChartState, FormMode, FormKind, FormTarget, FieldSnapshot, ParentSelection, forLeaf}
import app.components.FormInputs.*
import app.components.ConfirmGuard.proceedOrConfirm
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

    // This form's view of `activeForm`, via `forLeaf`: a Portfolio target
    // collapses to `Inactive` (fields shown empty and disabled, no lock
    // icon); no target at all collapses to `Blank`.
    // `.distinct` — see the matching comment in `PortfolioFormView`: without
    // it, the portfolio form moving between two of its own non-Blank states
    // re-fires this form's populate/reset subscription and wipes an unrelated,
    // unsaved leaf draft. `Blank` and `Inactive` being distinct values (not
    // both collapsing to `Blank`) is what fixed the "dirty after submitting
    // the other form" bug — see `FormMode.Inactive`'s own doc comment.
    val leafMode: Signal[FormMode] = builderState.activeForm.signal.map(_.forLeaf).distinct
    // Fields are disabled while genuinely Locked (viewing a saved leaf),
    // while Inactive (a saved portfolio is selected instead), and while the
    // *portfolio* is being freshly drafted (`Drafting(Portfolio)` — passed
    // through unchanged by `forLeaf` rather than folded away, see its doc
    // comment) — but the lock glyph next to each label means something more
    // specific ("this is a saved value"), so it's kept to Locked only.
    val isDisabled: Signal[Boolean] = leafMode.map { case FormMode.Locked(_) | FormMode.Inactive | FormMode.Drafting(_) => true; case _ => false }
    val showLockIcon: Signal[Boolean] = leafMode.map { case FormMode.Locked(_) => true; case _ => false }

    /** The leaf whose own occupancy of root must not count against itself in
      * `parentOptions` — Locked/Editing only (mirrors
      * `PortfolioFormView.selfExcludeName`; needed for leaves too, since a
      * lone leaf at root is a valid topology). Templating is deliberately
      * excluded from this — see that same doc comment for why.
      */
    val selfExcludeName: Signal[Option[String]] = leafMode.map {
      case FormMode.Locked(FormTarget.Leaf(n))  => Some(n.value)
      case FormMode.Editing(FormTarget.Leaf(n)) => Some(n.value)
      case _                                     => None
    }

    // Same combine-as-trigger, read-via-.now()-inside-map idiom as `draftSignal`
    // above: the combined signals only decide *when* to recompute, the actual
    // values are read fresh from the Vars at that moment.
    val currentSnapshot: Signal[FieldSnapshot.LeafFields] =
      state.nameVar.signal.combineWith(state.parentVar.signal, state.probabilityVar.signal, state.distributionModeVar.signal)
        .combineWith(state.percentilesVar.signal, state.quantilesVar.signal, state.termsVar.signal, state.minLossVar.signal, state.maxLossVar.signal)
        .map { _ =>
          FieldSnapshot.LeafFields(
            name        = state.nameVar.now(),
            parent      = state.parentVar.now(),
            probability = state.probabilityVar.now(),
            mode        = state.distributionModeVar.now(),
            percentiles = state.percentilesVar.now(),
            quantiles   = state.quantilesVar.now(),
            terms       = state.termsVar.now(),
            minLoss     = state.minLossVar.now(),
            maxLoss     = state.maxLossVar.now()
          )
        }
    val isDirty: Signal[Boolean] = leafMode.combineWith(currentSnapshot).map { (mode, snapshot) =>
      FormMode.isFormDirty(mode, snapshot, builderState.leavesVar.now(), builderState.portfoliosVar.now())
    }

    // Mode-dependent, per the state machine — see the matching comment in
    // PortfolioFormView: Blank and Templating both submit on click and share
    // a label; Locked unlocks a copy for editing — a new, uncommitted draft —
    // and gets the "Draft New" label instead.
    val addSubmitLabel: Signal[String] = leafMode.map {
      case FormMode.Blank | FormMode.Templating(_) => "Submit Leaf"
      case _                                        => "Draft New Leaf"
    }
    val addSubmitDisabled: Signal[Boolean] = leafMode.combineWith(state.hasErrors).map {
      case (FormMode.Editing(_), _)  => true  // mid-edit of an existing leaf; finish or Clear Form first
      case (FormMode.Locked(_), _)   => false // just unlocks a template copy, nothing to validate yet
      case (FormMode.Inactive, _)    => false // just wakes the form into Blank, nothing to validate yet
      case (FormMode.Drafting(_), _) => true  // a portfolio draft is in progress elsewhere; don't invite discarding it
      case (_, hasErrors)            => hasErrors
    }
    val editSaveLabel: Signal[String] = leafMode.map {
      case FormMode.Editing(_) => "Save"
      case _                   => "Edit"
    }
    val editSaveDisabled: Signal[Boolean] = leafMode.combineWith(state.hasErrors).map {
      case (FormMode.Blank, _)         => true
      case (FormMode.Templating(_), _) => true
      case (FormMode.Inactive, _)      => true // no leaf is selected — nothing to edit
      case (FormMode.Drafting(_), _)   => true // no leaf is selected — nothing to edit
      case (FormMode.Locked(_), _)     => false // just unlocks in place, nothing to validate yet
      case (_, hasErrors)              => hasErrors
    }
    val clearFormDisabled: Signal[Boolean] = leafMode.map {
      case FormMode.Locked(_) | FormMode.Inactive | FormMode.Drafting(_) => true
      case _                                                              => false
    }

    def onAddSubmitClick(): Unit =
      val raw = builderState.activeForm.now()
      raw.forLeaf match
        // Blank here can mean genuinely nothing selected, OR the portfolio
        // form is mid-Editing/Templating an unsaved draft — see the matching
        // comment in PortfolioFormView.
        case FormMode.Blank =>
          val isDiscardingPortfolioDraft = raw match
            case FormMode.Editing(_) | FormMode.Templating(_) => true
            case _                                            => false
          proceedOrConfirm(isDiscardingPortfolioDraft, "This will discard the portfolio you're currently editing. Continue?") { () =>
            handleSubmit(state, builderState, submitError)
          }
        // A portfolio is currently selected instead — this click means "start
        // a fresh leaf draft," which first has to reclaim `activeForm` from
        // the portfolio (there is only one shared target at a time). Goes to
        // `Drafting(Leaf)`, not straight to submitting (the form was
        // empty/disabled a moment ago, there's nothing valid to submit yet)
        // and not to plain `Blank` either — `Drafting` is what tells the
        // portfolio form "a fresh, unsaved leaf draft is now in progress,"
        // so it locks its own Add/Edit buttons instead of quietly offering
        // to discard this the moment it's clicked.
        case FormMode.Inactive => builderState.activeForm.set(FormMode.Drafting(FormKind.Leaf))
        case FormMode.Locked(t: FormTarget.Leaf) => builderState.activeForm.set(FormMode.Templating(t))
        case FormMode.Templating(_: FormTarget.Leaf) => handleSubmit(state, builderState, submitError)
        case _ => ()

    def onEditSaveClick(): Unit =
      builderState.activeForm.now().forLeaf match
        case FormMode.Locked(t: FormTarget.Leaf)      => builderState.activeForm.set(FormMode.Editing(t))
        case FormMode.Editing(FormTarget.Leaf(name))  => handleUpdate(state, name, builderState, submitError)
        case _ => ()

    // Reverting to a saved node's own values is exactly what populating from it
    // already does — the reactive populate subscription below fires again as soon
    // as `activeForm` moves back to `Locked(t)`, so there is nothing else to do here.
    def onClearFormClick(): Unit =
      builderState.activeForm.now().forLeaf match
        case FormMode.Blank =>
          state.resetFields()
          state.parentVar.set(ParentSelection.Unset)
        case FormMode.Editing(t: FormTarget.Leaf)     => builderState.activeForm.set(FormMode.Locked(t))
        case FormMode.Templating(t: FormTarget.Leaf)  => builderState.activeForm.set(FormMode.Locked(t))
        case _ => ()
      submitError.set(None)

    div(
      cls := "risk-leaf-form",
      h2(child.text <-- leafMode.map {
        case FormMode.Blank         => "Add Risk Leaf"
        case FormMode.Inactive      => "Add Risk Leaf"
        case FormMode.Drafting(_)   => "Add Risk Leaf"
        case FormMode.Locked(_)     => "Risk Leaf Details"
        case FormMode.Editing(_)    => "Edit Risk Leaf"
        case FormMode.Templating(_) => "Add Risk Leaf"
      }),
      p(
        cls := "form-description",
        "Configure a risk leaf node with either expert distribution or lognormal distribution."
      ),

      // When the active target is this leaf, populate the form from its saved
      // draft (covers viewing, editing, and templating alike — all three show
      // the target's values, just with different lock/edit semantics on top).
      // Any other target (Blank, or a portfolio) clears the fields. Templating
      // gets the same extra step as PortfolioFormView: if the source it's
      // copied from holds root, root is still genuinely taken (by the
      // untouched source) — reset the copy to Unset instead of silently
      // carrying over a "(root)" selection guaranteed to fail on submit.
      leafMode.changes --> {
        case FormMode.Locked(FormTarget.Leaf(n)) =>
          builderState.leavesVar.now().find(_.name == n).foreach(builderState.populateLeafForm(state, _))
        case FormMode.Editing(FormTarget.Leaf(n)) =>
          builderState.leavesVar.now().find(_.name == n).foreach(builderState.populateLeafForm(state, _))
        case FormMode.Templating(FormTarget.Leaf(n)) =>
          builderState.leavesVar.now().find(_.name == n).foreach { source =>
            builderState.populateLeafForm(state, source)
            if source.parent.isEmpty then state.parentVar.set(ParentSelection.Unset)
          }
        case _ =>
          // parentVar reset here too, not just resetFields() — otherwise a
          // stale parent left over from a previously-locked leaf survives
          // (resetFields() never touches it). Set explicitly to Unset, not a
          // computed guess — see `ParentSelection`'s own doc.
          state.resetFields()
          state.parentVar.set(ParentSelection.Unset)
      },
      // Explicit "clear regardless of mode" signal from startNewTree/loadFromTree
      // — see the matching comment in PortfolioFormView / resetFormFieldsBus's
      // own doc comment.
      builderState.resetFormFieldsBus.events --> { _ =>
        state.resetFields()
        state.parentVar.set(ParentSelection.Unset)
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
      state.parentVar.signal.changes --> { _ =>
        submitError.set(None)
        state.clearSubmitFieldError(RiskLeafField.Parent)
      },

      // Push reactive draft up to TreeBuilderState for DistributionChartState to observe.
      // draftSignal already returns None when distribution fields are invalid (via
      // Distribution.create) — no additional hasErrors gate needed here. Removing the
      // gate allows the preview to render when distribution params are valid even if
      // name/probability are still empty (submit validation is separate and unchanged).
      // Cleared on unmount so the chart returns to Idle when the leaf form is not active.
      state.draftSignal --> builderState.currentDraftVar.writer,
      // Written to this form's own dirty flag, not the shared isEditDirtyVar
      // directly — see the matching comment in PortfolioFormView. isEditDirtyVar
      // itself is the OR of both forms' flags (see TreeBuilderState).
      isDirty --> builderState.leafFormDirtyVar.writer,
      onUnmountCallback { _ =>
        builderState.currentDraftVar.set(None)
        builderState.activeForm.set(FormMode.Blank)
      },

      // Common Fields
      textInput(
        labelText = "Name",
        valueVar = state.nameVar,
        errorSignal = state.nameError,
        filter = state.nameFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.Name),
        placeholderText = "e.g., Cyber Attack Risk",
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
      ),

      textInput(
        labelText = "Probability",
        valueVar = state.probabilityVar,
        errorSignal = state.probabilityError,
        filter = state.probabilityFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.Probability),
        placeholderText = "e.g., 40 (= 40%)",
        inputModeAttr = "decimal",
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
      ),

      // Distribution Mode Toggle
      radioGroup(
        labelText = "Distribution Type",
        options = List(
          (DistributionMode.Expert, "Expert Opinion"),
          (DistributionMode.Lognormal, "Lognormal (BCG)")
        ),
        selectedVar = state.distributionModeVar,
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
      ),

      // Parent selection
      parentSelect(state.parentVar, builderState.parentOptions(selfExcludeName), builderState.rootLabel, builderState.allPortfolioNames, isDisabled, state.parentError, showLockIcon),

      // Conditional Fields based on mode. Only `distributionModeVar` triggers a
      // subtree rebuild here — `isDisabled`/`showLockIcon` are threaded through
      // as Signals so locking/unlocking toggles the `disabled` attribute on the
      // already-mounted inputs instead of rebuilding them (Signal granularity, ADR-019).
      child <-- state.distributionModeVar.signal.map {
        case DistributionMode.Expert    => expertFields(state, isDisabled, showLockIcon)
        case DistributionMode.Lognormal => lognormalFields(state, isDisabled, showLockIcon)
      },

      // Add/Submit, Edit/Save, Clear Form — right-aligned as a group, Clear Form
      // immediately right of Add/Submit.
      div(
        cls := "form-actions",
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
        submitButton(
          textSignal = editSaveLabel,
          isDisabled = editSaveDisabled,
          onClickCallback = () => onEditSaveClick()
        )
      ),
      child.maybe <-- submitError.signal.map(_.map(msg => div(cls := "form-error", msg)))
    )
  
  /** Expert mode specific fields */
  private def expertFields(state: RiskLeafFormState, isDisabled: Signal[Boolean], showLockIcon: Signal[Boolean]): HtmlElement =
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
        inputModeAttr = "decimal",
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
      ),

      textInput(
        labelText = "Quantiles (loss in $M)",
        valueVar = state.quantilesVar,
        errorSignal = state.quantilesError,
        filter = state.quantilesFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.Quantiles),
        placeholderText = "e.g., 50, 200, 1000 ($50M, $200M, $1B)",
        inputModeAttr = "decimal",
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
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
        inputModeAttr = "numeric",
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
      )
    )

  /** Lognormal mode specific fields */
  private def lognormalFields(state: RiskLeafFormState, isDisabled: Signal[Boolean], showLockIcon: Signal[Boolean]): HtmlElement =
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
        inputModeAttr = "numeric",
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
      ),

      textInput(
        labelText = "Maximum Loss",
        valueVar = state.maxLossVar,
        errorSignal = state.maxLossError,
        filter = state.lossFilter,
        onBlurCallback = () => state.markTouched(RiskLeafField.MaxLoss),
        placeholderText = "e.g., 50000",
        inputModeAttr = "numeric",
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
      )
    )
  
  /** Handle add-leaf submission. */
  private def handleSubmit(
    state: RiskLeafFormState,
    builderState: TreeBuilderState,
    submitError: Var[Option[String]]
  ): Unit =
    state.triggerValidation()
    (state.currentShapeValidation(), state.refinedProbability, state.parentDraft) match
      case (Validation.Success(_, shape), Some(prob), Some(parent)) =>
        builderState.addLeaf(state.nameVar.now(), parent, shape, prob) match
          case Validation.Success(_, _) =>
            // Fields are left as-is — TreeBuilderState.addLeaf already moved
            // `activeForm` to `Locked(newTarget)`, which locks these same,
            // just-submitted values for viewing. A fast successive add is the
            // Templating flow now (click Add again to unlock a copy), not a
            // touched-state reset here.
            submitError.set(None)
          case Validation.Failure(_, errs) =>
            FormSubmitUtil.routeTopologyErrors(state, errs.toList, submitError, {
              case "name"   => Some(RiskLeafField.Name)
              case "parent" => Some(RiskLeafField.Parent)
              case _        => None
            })
      case (shapeV, _, parentOpt) =>
        // Shape/probability invalid, and/or parent still Unset. Surface the
        // SPECIFIC, field-routed reasons inline below each field (probability
        // is already covered reactively).
        if parentOpt.isEmpty then state.setSubmitFieldError(RiskLeafField.Parent, "Parent is required")
        routeLeafShapeErrors(state, shapeErrorsOf(shapeV), submitError)

  /** Handle update-leaf submission (edit mode). */
  private def handleUpdate(
    state: RiskLeafFormState,
    originalName: SafeName.SafeName,
    builderState: TreeBuilderState,
    submitError: Var[Option[String]]
  ): Unit =
    state.triggerValidation()
    (state.currentShapeValidation(), state.refinedProbability, state.parentDraft) match
      case (Validation.Success(_, shape), Some(prob), Some(parent)) =>
        builderState.updateLeaf(originalName, state.nameVar.now(), parent, shape, prob) match
          case Validation.Success(_, _) =>
            // TreeBuilderState.updateLeaf already moved `activeForm` back to
            // `Locked(t)`, locking these same, just-saved values for viewing.
            submitError.set(None)
          case Validation.Failure(_, errs) =>
            FormSubmitUtil.routeTopologyErrors(state, errs.toList, submitError, {
              case "name"   => Some(RiskLeafField.Name)
              case "parent" => Some(RiskLeafField.Parent)
              case _        => None
            })
      case (shapeV, _, parentOpt) =>
        if parentOpt.isEmpty then state.setSubmitFieldError(RiskLeafField.Parent, "Parent is required")
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

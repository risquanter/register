package app.views

import com.raquo.laminar.api.L.*
import zio.prelude.Validation
import app.state.{RiskLeafFormState, RiskLeafField, DistributionMode, TreeBuilderState, DistributionChartState, FormMode, FormKind, FormTarget, FieldSnapshot, ParentSelection, forLeaf, name}
import app.components.FormInputs.*
import app.components.FormCard
import app.components.ConfirmGuard.proceedOrConfirm
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.ErrorSubject
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef

/**
 * Complete form for creating a RiskLeaf.
 * Uses RiskLeafFormState for reactive state and validation.
 */
object RiskLeafFormView:

  def apply(builderState: TreeBuilderState, chartState: DistributionChartState): HtmlElement =
    val state = new RiskLeafFormState

    // This form's view of `activeForm`, via `forLeaf`: a Portfolio target
    // collapses to `Inactive` (fields shown empty and disabled, no lock
    // icon); no target at all collapses to `Blank`.
    // `.distinct` — see the matching comment in `PortfolioFormView`: without
    // it, the portfolio form moving between two of its own non-Blank states
    // re-fires this form's populate/reset subscription and wipes an unrelated,
    // unsaved leaf draft. `Blank` and `Inactive` are distinct values, so
    // submitting the other form does not leave this one reading as dirty —
    // see `FormMode.Inactive`'s own doc comment.
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

    val currentSnapshot: Signal[FieldSnapshot.LeafFields] =
      state.nameVar.signal.combineWith(state.parentVar.signal, state.probabilityVar.signal, state.distributionModeVar.signal)
        .combineWith(state.percentilesVar.signal, state.quantilesVar.signal, state.termsVar.signal, state.minLossVar.signal, state.maxLossVar.signal)
        .map { (name, parent, probability, mode, percentiles, quantiles, terms, minLoss, maxLoss) =>
          FieldSnapshot.LeafFields(
            name        = name,
            parent      = parent,
            probability = probability,
            mode        = mode,
            percentiles = percentiles,
            quantiles   = quantiles,
            terms       = terms,
            minLoss     = minLoss,
            maxLoss     = maxLoss
          )
        }
    val isDirty: Signal[Boolean] =
      leafMode.combineWith(currentSnapshot, builderState.leavesVar.signal, builderState.portfoliosVar.signal)
        .map((mode, snapshot, leaves, portfolios) => FormMode.isFormDirty(mode, snapshot, leaves, portfolios))

    // Mode-dependent, per the state machine — see the matching comment in
    // PortfolioFormView: Blank and Templating both submit on click and share
    // a label; Locked unlocks a copy for editing — a new, uncommitted draft —
    // and gets the "Draft New" label instead.
    val addSubmitLabel: Signal[String] = leafMode.map {
      case FormMode.Blank | FormMode.Templating(_) => "Submit Leaf"
      case _                                        => "Draft New Leaf"
    }
    val addSubmitBlockedBy: Signal[List[String]] = leafMode.combineWith(state.blockingReasons).map {
      case (FormMode.Editing(_), _)      => List(BlockedMessages.editInProgress(FormKind.Leaf))
      case (FormMode.Locked(_), _)       => Nil // just unlocks a template copy, nothing to validate yet
      case (FormMode.Inactive, _)        => Nil // just wakes the form into Blank, nothing to validate yet
      case (FormMode.Drafting(other), _) => List(BlockedMessages.otherDraftInProgress(other)) // don't invite discarding it
      case (_, reasons)                  => reasons
    }
    val editSaveLabel: Signal[String] = leafMode.map {
      case FormMode.Editing(_) => "Save"
      case _                   => "Edit"
    }
    val editSaveBlockedBy: Signal[List[String]] = leafMode.combineWith(state.blockingReasons).map {
      case (FormMode.Blank, _)           => List(BlockedMessages.nothingSelected(FormKind.Leaf))
      case (FormMode.Templating(_), _)   => List(BlockedMessages.copyNotEditable(FormKind.Leaf))
      case (FormMode.Inactive, _)        => List(BlockedMessages.nothingSelected(FormKind.Leaf))
      case (FormMode.Drafting(other), _) => List(BlockedMessages.otherDraftInProgress(other))
      case (FormMode.Locked(_), _)       => Nil // just unlocks in place, nothing to validate yet
      case (_, reasons)                  => reasons
    }
    val clearFormBlockedBy: Signal[List[String]] = leafMode.map {
      case FormMode.Locked(_)       => List(BlockedMessages.savedNothingToClear(FormKind.Leaf))
      case FormMode.Inactive        => List(BlockedMessages.NothingToClear)
      case FormMode.Drafting(other) => List(BlockedMessages.otherDraftInProgress(other))
      case _                        => Nil
    }

    // Card focus: expanded when this form is the active one; collapsed when a
    // portfolio is active (`Inactive`) or a fresh portfolio draft is in progress
    // (`Drafting`, passed through by `forLeaf`).
    val expanded: Signal[Boolean] = leafMode.map {
      case FormMode.Inactive | FormMode.Drafting(_) => false
      case _                                        => true
    }
    val headerText: Signal[String] = leafMode.map {
      case FormMode.Locked(t)  => s"Risk Leaf: ${t.name.value}"
      case FormMode.Editing(t) => s"Edit Risk Leaf: ${t.name.value}"
      case _                   => "Add Risk Leaf"
    }

    // Activating a collapsed leaf form = the same Inactive→Drafting transition
    // the "Add Leaf" button runs from Inactive (reclaims `activeForm` from a
    // portfolio, discarding an unsaved portfolio draft only after confirming).
    def activateForm(): Unit =
      proceedOrConfirm(builderState.portfolioFormDirtyVar.now(), "This will discard the portfolio you're currently editing. Continue?") { () =>
        builderState.activeForm.set(FormMode.Drafting(FormKind.Leaf))
      }

    def onAddSubmitClick(): Unit =
      val raw = builderState.activeForm.now()
      raw.forLeaf match
        // Blank here means raw is Blank or this form's own Drafting — a
        // Portfolio target folds to Inactive, never Blank — so there is no
        // other-form draft to protect on this branch; submit directly.
        case FormMode.Blank =>
          handleSubmit(state, builderState)
        // A portfolio is currently selected instead — this click means "start
        // a fresh leaf draft," which first has to reclaim `activeForm` from
        // the portfolio (there is only one shared target at a time). If the
        // portfolio form holds unsaved changes (mid-Editing/Templating),
        // reclaiming discards them — confirm first, matching how discarding
        // unsaved work is gated everywhere else in this builder. Goes to
        // `Drafting(Leaf)`, not straight to submitting (the form was
        // empty/disabled a moment ago, there's nothing valid to submit yet)
        // and not to plain `Blank` either — `Drafting` is what tells the
        // portfolio form "a fresh, unsaved leaf draft is now in progress,"
        // so it locks its own Add/Edit buttons instead of quietly offering
        // to discard this the moment it's clicked.
        case FormMode.Inactive =>
          activateForm()
        case FormMode.Locked(t: FormTarget.Leaf) => builderState.activeForm.set(FormMode.Templating(t))
        case FormMode.Templating(_: FormTarget.Leaf) => handleSubmit(state, builderState)
        case _ => ()

    def onEditSaveClick(): Unit =
      builderState.activeForm.now().forLeaf match
        case FormMode.Locked(t: FormTarget.Leaf)      => builderState.activeForm.set(FormMode.Editing(t))
        case FormMode.Editing(FormTarget.Leaf(name))  => handleUpdate(state, name, builderState)
        case _ => ()

    // Reverting to a saved node's own values is exactly what populating from it
    // already does — the reactive populate subscription below fires again as soon
    // as `activeForm` moves back to `Locked(t)`, so there is nothing else to do here.
    def onClearFormClick(): Unit =
      builderState.activeForm.now().forLeaf match
        case FormMode.Blank =>
          state.resetFields()
          state.parentVar.set(ParentSelection.Unset)
          state.subjectVar.set(ErrorSubject.DraftNode)
          // The folded Blank can be this form's own Drafting(Leaf) — clearing
          // it must also end the draft, otherwise activeForm stays Drafting
          // and the portfolio form's controls remain disabled with no draft
          // actually in progress. A raw Blank re-set is a no-op.
          builderState.activeForm.set(FormMode.Blank)
        case FormMode.Editing(t: FormTarget.Leaf)     => builderState.activeForm.set(FormMode.Locked(t))
        case FormMode.Templating(t: FormTarget.Leaf)  => builderState.activeForm.set(FormMode.Locked(t))
        case _ => ()
      state.setSubmitErrors(Nil)

    FormCard(
      header = div(cls := "form-card-title", child.text <-- headerText),
      expanded = expanded,
      onHeaderActivate = () => activateForm(),
      body = div(
      cls := "risk-leaf-form",
      p(
        cls := "form-description",
        "Configure a risk leaf node with either a metalog or a lognormal distribution."
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
            // A template is a new node, so its refusals name the draft rather
            // than the source it was copied from.
            state.subjectVar.set(ErrorSubject.DraftNode)
            source.parent match
              case ParentRef.Root     => state.parentVar.set(ParentSelection.Unset)
              case ParentRef.Under(_) => ()
          }
        case _ =>
          // parentVar and subjectVar reset here too, not just resetFields() —
          // otherwise a stale value left over from a previously-locked leaf
          // survives (resetFields() never touches either). Set explicitly to
          // Unset, not a computed guess — see `ParentSelection`'s own doc.
          state.resetFields()
          state.parentVar.set(ParentSelection.Unset)
          state.subjectVar.set(ErrorSubject.DraftNode)
      },
      // Explicit "clear regardless of mode" signal from startNewTree/loadFromTree
      // — see the matching comment in PortfolioFormView / resetFormFieldsBus's
      // own doc comment.
      builderState.resetFormFieldsBus.events --> { _ =>
        state.resetFields()
        state.parentVar.set(ParentSelection.Unset)
        state.subjectVar.set(ErrorSubject.DraftNode)
      },

      // Drop the stored submit refusals an edit makes stale: those naming the
      // edited input, and those on the form's own line. Changing the mode
      // edits no single input, so it drops only the line's.
      state.nameVar.signal.changes --> (_ => state.clearFor(RiskLeafField.Name)),
      state.probabilityVar.signal.changes --> (_ => state.clearFor(RiskLeafField.Probability)),
      state.percentilesVar.signal.changes --> (_ => state.clearFor(RiskLeafField.Percentiles)),
      state.quantilesVar.signal.changes --> (_ => state.clearFor(RiskLeafField.Quantiles)),
      state.minLossVar.signal.changes --> (_ => state.clearFor(RiskLeafField.MinLoss)),
      state.maxLossVar.signal.changes --> (_ => state.clearFor(RiskLeafField.MaxLoss)),
      state.termsVar.signal.changes --> (_ => state.clearFor(RiskLeafField.Terms)),
      state.distributionModeVar.signal.changes --> (_ => state.clearFor(RiskLeafField.FormLevel)),
      state.parentVar.signal.changes --> (_ => state.clearFor(RiskLeafField.Parent)),

      // Push the reactive draft up to TreeBuilderState for DistributionChartState
      // to observe. draftSignal is None while the distribution fields do not form
      // a shape, so the preview renders as soon as they do, whether or not the
      // name and probability are filled. Cleared on unmount so the chart returns
      // to Idle when the leaf form is not active.
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
          (DistributionMode.Metalog, "Metalog (expert estimate)"),
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
        case DistributionMode.Metalog   => metalogFields(state, isDisabled, showLockIcon)
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
          blockedBy = addSubmitBlockedBy,
          onClickCallback = () => onAddSubmitClick()
        ),
        blockable(
          button(
            typ := "button",
            cls := "form-clear",
            "Clear Form",
            onClick --> (_ => onClearFormClick())
          ),
          clearFormBlockedBy
        ),
        submitButton(
          textSignal = editSaveLabel,
          blockedBy = editSaveBlockedBy,
          onClickCallback = () => onEditSaveClick()
        )
      ),
      // The form's own line: the builder's topology check of this submit, then
      // any refusal no input of this form claims.
      crossFieldError(state.formLevelError)
      )
    )

  /** Metalog mode specific fields */
  private def metalogFields(state: RiskLeafFormState, isDisabled: Signal[Boolean], showLockIcon: Signal[Boolean]): HtmlElement =
    div(
      cls := "mode-fields",
      div(cls := "mode-fields-title", "Metalog Parameters"),

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
      div(cls := "mode-fields-title", "Lognormal Parameters (90% CI)"),

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
  
  /** Handle add-leaf submission. Every refusal is stored at once, whichever
    * check produced it — this form's own field refinement or the tree-wide
    * topology rules — and each input selects its own. */
  private def handleSubmit(
    state: RiskLeafFormState,
    builderState: TreeBuilderState
  ): Unit =
    state.triggerValidation()
    state.toDraft.flatMap((name, parent, prob, shape) => builderState.addLeaf(name, parent, shape, prob)) match
      case Validation.Success(_, _) =>
        // Fields are left as-is — TreeBuilderState.addLeaf already moved
        // `activeForm` to `Locked(newTarget)`, which locks these same,
        // just-submitted values for viewing. A fast successive add is the
        // Templating flow (click Add again to unlock a copy), not a
        // touched-state reset here.
        state.setSubmitErrors(Nil)
      case Validation.Failure(_, errs) =>
        state.setSubmitErrors(errs.toList)

  /** Handle update-leaf submission (edit mode). */
  private def handleUpdate(
    state: RiskLeafFormState,
    originalName: SafeName.SafeName,
    builderState: TreeBuilderState
  ): Unit =
    state.triggerValidation()
    state.toDraft.flatMap((name, parent, prob, shape) => builderState.updateLeaf(originalName, name, parent, shape, prob)) match
      case Validation.Success(_, _) =>
        // TreeBuilderState.updateLeaf already moved `activeForm` back to
        // `Locked(t)`, locking these same, just-saved values for viewing.
        state.setSubmitErrors(Nil)
      case Validation.Failure(_, errs) =>
        state.setSubmitErrors(errs.toList)

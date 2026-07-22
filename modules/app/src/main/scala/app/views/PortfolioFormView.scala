package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.{PortfolioFormState, PortfolioField, TreeBuilderState, FormMode, FormKind, FormTarget, FieldSnapshot, ParentSelection, forPortfolio}
import app.components.FormInputs
import app.components.ConfirmGuard.proceedOrConfirm
import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.ValidationError

/**
 * Portfolio sub-form wired to TreeBuilderState.
 * Validates name locally (Iron SafeName) and delegates topology checks to
 * TreeBuilderState.addPortfolio / updatePortfolio.
 *
 * Mode is derived from `builderState.activeForm.signal`, filtered to the
 * `Portfolio` target via `forPortfolio` — a `Leaf` target collapses to
 * `Inactive` (fields shown empty and disabled, no lock icon); no target at
 * all collapses to `Blank`. Mirrors [[RiskLeafFormView]].
 */
object PortfolioFormView:

  def apply(builderState: TreeBuilderState): HtmlElement =
    val form = new PortfolioFormState
    val submitError: Var[Option[String]] = Var(None)

    // `.distinct` — a leaf occupying `activeForm` always collapses to the
    // same `Inactive` value regardless of which of its own states it's in,
    // so hand-offs between two non-portfolio states (e.g. the leaf form
    // moving Templating → Locked) don't re-fire the populate/reset
    // subscription below and wipe this form's own unrelated draft. A
    // same-target `Templating` → `Locked` transition (this form's own "Clear
    // Form" revert) still fires, since those are different values — and so
    // does the transition into `Inactive` from `Blank`, or from this form's
    // own target, since those are different values too (this is what fixed
    // the "dirty after submitting the other form" bug: `Blank` and
    // `Inactive` no longer collapse into the same value the way `Blank` and
    // the old undifferentiated "not mine" case used to).
    val portfolioMode: Signal[FormMode] = builderState.activeForm.signal.map(_.forPortfolio).distinct
    // Fields are disabled while genuinely Locked (viewing a saved portfolio),
    // while Inactive (a saved leaf is selected instead), and while the
    // *leaf* is being freshly drafted (`Drafting(Leaf)` — passed through
    // unchanged by `forPortfolio` rather than folded away, see its doc
    // comment) — but the lock glyph next to each label means something more
    // specific ("this is a saved value"), so it's kept to Locked only.
    val isDisabled: Signal[Boolean] = portfolioMode.map { case FormMode.Locked(_) | FormMode.Inactive | FormMode.Drafting(_) => true; case _ => false }
    val showLockIcon: Signal[Boolean] = portfolioMode.map { case FormMode.Locked(_) => true; case _ => false }

    /** The portfolio whose own occupancy of root must not count against
      * itself in `parentOptions` (see `TreeBuilderState.parentOptions`) —
      * Locked/Editing only, since those are the SAME node being viewed or
      * edited in place. Templating's new draft is a genuinely different,
      * not-yet-existing entity: the source it was templated from is
      * untouched and still really holds whatever it holds, so excluding it
      * here would offer "(root)" as if the source no longer held root — a
      * choice guaranteed to fail validation the moment it's submitted. Blank
      * has no identity of its own either way.
      */
    val selfExcludeName: Signal[Option[String]] = portfolioMode.map {
      case FormMode.Locked(FormTarget.Portfolio(n))  => Some(n.value)
      case FormMode.Editing(FormTarget.Portfolio(n)) => Some(n.value)
      case _                                          => None
    }

    val currentSnapshot: Signal[FieldSnapshot.PortfolioFields] =
      form.nameVar.signal.combineWith(form.parentVar.signal).map { (name, parent) =>
        FieldSnapshot.PortfolioFields(name, parent)
      }
    val isDirty: Signal[Boolean] = portfolioMode.combineWith(currentSnapshot).map { (mode, snapshot) =>
      FormMode.isFormDirty(mode, snapshot, builderState.leavesVar.now(), builderState.portfoliosVar.now())
    }

    // Mode-dependent, per the state machine: Blank and Templating are both
    // "unlocked, click submits what's typed" states, so they share one
    // label. Locked is different in kind — clicking there doesn't submit
    // anything, it unlocks a copy for editing — a new, uncommitted draft —
    // which is the actual "start a new one" action, so that's where "Draft
    // New" belongs.
    val addSubmitLabel: Signal[String] = portfolioMode.map {
      case FormMode.Blank | FormMode.Templating(_) => "Submit Portfolio"
      case _                                        => "Draft New Portfolio"
    }
    val addSubmitDisabled: Signal[Boolean] = portfolioMode.combineWith(form.hasErrors).map {
      case (FormMode.Editing(_), _) => true
      case (FormMode.Locked(_), _)  => false
      case (FormMode.Inactive, _)   => false // just wakes the form into Blank, nothing to validate yet
      case (FormMode.Drafting(_), _) => true // a leaf draft is in progress elsewhere; don't invite discarding it
      case (_, hasErrors)           => hasErrors
    }
    val editSaveLabel: Signal[String] = portfolioMode.map {
      case FormMode.Editing(_) => "Save"
      case _                   => "Edit"
    }
    val editSaveDisabled: Signal[Boolean] = portfolioMode.combineWith(form.hasErrors).map {
      case (FormMode.Blank, _)         => true
      case (FormMode.Templating(_), _) => true
      case (FormMode.Inactive, _)      => true // no portfolio is selected — nothing to edit
      case (FormMode.Drafting(_), _)   => true // no portfolio is selected — nothing to edit
      case (FormMode.Locked(_), _)     => false
      case (_, hasErrors)              => hasErrors
    }
    val clearFormDisabled: Signal[Boolean] = portfolioMode.map {
      case FormMode.Locked(_) | FormMode.Inactive | FormMode.Drafting(_) => true
      case _                                                              => false
    }

    def onAddSubmitClick(): Unit =
      val raw = builderState.activeForm.now()
      raw.forPortfolio match
        // Blank here can mean genuinely nothing selected, OR the leaf form
        // is mid-Editing/Templating an unsaved draft (raw activeForm holds a
        // Leaf target, which forPortfolio collapses to Blank). Submitting
        // would overwrite the shared activeForm and silently discard that
        // draft — confirm first, matching how discarding unsaved work is
        // gated everywhere else in this builder.
        case FormMode.Blank =>
          val isDiscardingLeafDraft = raw match
            case FormMode.Editing(_) | FormMode.Templating(_) => true
            case _                                            => false
          proceedOrConfirm(isDiscardingLeafDraft, "This will discard the leaf you're currently editing. Continue?") { () =>
            handleSubmit(form, builderState, submitError)
          }
        // A leaf is currently selected instead — this click means "start a
        // fresh portfolio draft," which first has to reclaim `activeForm`
        // from the leaf (there is only one shared target at a time). Goes to
        // `Drafting(Portfolio)`, not straight to submitting (the form was
        // empty/disabled a moment ago, there's nothing valid to submit yet)
        // and not to plain `Blank` either — `Drafting` is what tells the leaf
        // form "a fresh, unsaved portfolio draft is now in progress," so it
        // locks its own Add/Edit buttons instead of quietly offering to
        // discard this the moment it's clicked.
        case FormMode.Inactive => builderState.activeForm.set(FormMode.Drafting(FormKind.Portfolio))
        case FormMode.Locked(t: FormTarget.Portfolio) => builderState.activeForm.set(FormMode.Templating(t))
        case FormMode.Templating(_: FormTarget.Portfolio) => handleSubmit(form, builderState, submitError)
        case _ => ()

    def onEditSaveClick(): Unit =
      builderState.activeForm.now().forPortfolio match
        case FormMode.Locked(t: FormTarget.Portfolio)      => builderState.activeForm.set(FormMode.Editing(t))
        case FormMode.Editing(FormTarget.Portfolio(name))  => handleUpdate(form, name, builderState, submitError)
        case _ => ()

    // Reverting to a saved portfolio's own values is exactly what populating
    // from it already does — the reactive populate subscription below fires
    // again as soon as `activeForm` moves back to `Locked(t)`.
    def onClearFormClick(): Unit =
      builderState.activeForm.now().forPortfolio match
        case FormMode.Blank =>
          form.reset()
          form.parentVar.set(ParentSelection.Unset)
        case FormMode.Editing(t: FormTarget.Portfolio)    => builderState.activeForm.set(FormMode.Locked(t))
        case FormMode.Templating(t: FormTarget.Portfolio) => builderState.activeForm.set(FormMode.Locked(t))
        case _ => ()
      submitError.set(None)

    div(
      cls := "portfolio-form",
      h2(child.text <-- portfolioMode.map {
        case FormMode.Blank         => "Add Portfolio"
        case FormMode.Inactive      => "Add Portfolio"
        case FormMode.Drafting(_)   => "Add Portfolio"
        case FormMode.Locked(_)     => "Portfolio Details"
        case FormMode.Editing(_)    => "Edit Portfolio"
        case FormMode.Templating(_) => "Add Portfolio"
      }),

      // When the active target is this portfolio, populate the form from its
      // saved draft. Any other target (Blank, or a leaf) clears the fields.
      // Templating gets one extra step after the normal populate: a template
      // is a new, not-yet-existing draft, so if the source it's copied from
      // holds root, root is still genuinely taken (by the untouched source)
      // — reset the copy to Unset instead of silently carrying over a
      // "(root)" selection that's guaranteed to fail validation on submit.
      portfolioMode.changes --> {
        case FormMode.Locked(FormTarget.Portfolio(n)) =>
          builderState.portfoliosVar.now().find(_.name == n).foreach(builderState.populatePortfolioForm(form, _))
        case FormMode.Editing(FormTarget.Portfolio(n)) =>
          builderState.portfoliosVar.now().find(_.name == n).foreach(builderState.populatePortfolioForm(form, _))
        case FormMode.Templating(FormTarget.Portfolio(n)) =>
          builderState.portfoliosVar.now().find(_.name == n).foreach { source =>
            builderState.populatePortfolioForm(form, source)
            if source.parent.isEmpty then form.parentVar.set(ParentSelection.Unset)
          }
        case _ =>
          form.reset()
          form.parentVar.set(ParentSelection.Unset)
      },
      // Explicit "clear regardless of mode" signal from startNewTree/loadFromTree
      // — see resetFormFieldsBus's doc comment. Needed because a same-to-same
      // Blank transition (this form was already Blank, with typed content)
      // produces no emission on the .distinct-filtered portfolioMode above.
      builderState.resetFormFieldsBus.events --> { _ =>
        form.reset()
        form.parentVar.set(ParentSelection.Unset)
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

      // Written to this form's own dirty flag, not the shared isEditDirtyVar
      // directly — writing there directly let this form's "not dirty" (when
      // portfolioMode collapses to Blank while the leaf form is active)
      // clobber a genuinely dirty leaf draft. isEditDirtyVar itself is the OR
      // of both forms' flags (see TreeBuilderState).
      isDirty --> builderState.portfolioFormDirtyVar.writer,

      FormInputs.textInput(
        labelText = "Portfolio Name",
        valueVar = form.nameVar,
        errorSignal = form.nameError,
        onBlurCallback = () => form.markTouched(PortfolioField.Name),
        placeholderText = "e.g., Operations",
        filter = _ => true,
        disabledSignal = isDisabled,
        lockedSignal = showLockIcon
      ),
      FormInputs.parentSelect(form.parentVar, builderState.parentOptions(selfExcludeName), builderState.rootLabel, builderState.allPortfolioNames, isDisabled, form.parentError, showLockIcon),

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

package app.state

import com.risquanter.register.domain.data.iron.{SafeName, IronConstants}

/** Identifies which saved node (leaf or portfolio) a [[FormMode]] is pointing at. */
enum FormTarget:
  case Leaf(name: SafeName.SafeName)
  case Portfolio(name: SafeName.SafeName)

/** Which of the two node-editing forms — used by [[FormMode.Drafting]] to say
  * which one is actively being drafted, without reusing [[FormTarget]] (which
  * always names a *saved* node; a fresh draft has none yet).
  */
enum FormKind:
  case Leaf, Portfolio

/** The node-editing form's state machine. Replaces the independently-mutated
  * `selectedLeafName`/`selectedPortfolioName` pair in [[TreeBuilderState]] —
  * folding target + mode into one sum type makes "only one target selected,
  * and it's read-only / mid-edit / templating a new node" structural rather
  * than a manually-maintained invariant.
  */
enum FormMode:
  case Blank
  /** The *other* node type currently occupies `activeForm` (any of
    * `Locked`/`Editing`/`Templating` on the other type) — as opposed to
    * `Blank`, where nothing at all is selected anywhere. Distinct from
    * `Blank` specifically so `forPortfolio`/`forLeaf` stop conflating the two:
    * before this case existed, both collapsed to the same `Blank` value, so a
    * plain submit that moved the *other* form from nothing-selected straight
    * to its own freshly-`Locked` node was invisible to `.distinct` (`Blank ->
    * Blank`, no change) — the reset/populate subscription keyed on that
    * collapsed value never fired, leaving this form's fields (notably
    * `parentVar`) holding whatever they held before, undetected because the
    * dropdown's own display falls back to its placeholder for any value no
    * longer among the current options (see `FormInputs.parentSelect`'s
    * `correctedDisplay`) — so the stale value looked cleared even though
    * `isFormDirty` correctly read it as dirty. See docs/dev/TODO.md.
    */
  case Inactive
  /** `kind`'s form is actively being drafted — fresh and unsaved, entered
    * from `Inactive` by clicking its Add button (see both FormViews'
    * `onAddSubmitClick`). From `kind`'s own form's perspective this is
    * identical to `Blank` (freely editable) — `forPortfolio`/`forLeaf` fold
    * it there directly. From the *other* form's perspective it is passed
    * through unchanged (not folded to `Inactive`): unlike `Inactive` (where
    * the other side is a *saved* node, so starting a fresh draft here too is
    * harmless), the other side right now is unsaved and would be silently
    * discarded by any action here — so that other form disables its own
    * Add/Edit buttons too, not just its fields (see the `Drafting` match arm
    * in both FormViews' derived signals).
    */
  case Drafting(kind: FormKind)
  case Locked(target: FormTarget)
  case Editing(target: FormTarget)
  case Templating(source: FormTarget)

extension (mode: FormMode)
  /** The node this mode is pointing at, if any — the shared extraction every
    * caller that needs "what's currently selected" (highlighting, cleanup on
    * delete, per-view lock/disabled derivation) would otherwise repeat as its
    * own match on `FormMode`.
    */
  def currentTarget: Option[FormTarget] = mode match
    case FormMode.Blank         => None
    case FormMode.Inactive      => None
    case FormMode.Drafting(_)   => None
    case FormMode.Locked(t)     => Some(t)
    case FormMode.Editing(t)    => Some(t)
    case FormMode.Templating(t) => Some(t)

  /** This mode viewed from the portfolio form's perspective: unchanged if its
    * target is a `Portfolio`; `Blank` stays `Blank` (nothing selected
    * anywhere); anything else (a `Leaf` occupies `activeForm`) becomes
    * `Inactive` — see `Inactive`'s own doc comment for why that's a distinct
    * case rather than also collapsing to `Blank`.
    *
    * `PortfolioFormView`'s single view of `activeForm`, used both for its
    * reactive display signal and for reading the current mode inside click
    * handlers (`builderState.activeForm.now().forPortfolio`), so the two can
    * never disagree about what "my form's current mode" means. Before this,
    * a click handler that matched on the raw, unfiltered `activeForm` would
    * silently no-op whenever the *other* form's target was active — e.g. a
    * portfolio submission left stuck in `Templating` made the leaf form's
    * "Add Leaf" button (enabled, since leaf's own filtered view read `Blank`)
    * do nothing when clicked.
    */
  def forPortfolio: FormMode = mode match
    case m @ FormMode.Locked(_: FormTarget.Portfolio)     => m
    case m @ FormMode.Editing(_: FormTarget.Portfolio)    => m
    case m @ FormMode.Templating(_: FormTarget.Portfolio) => m
    case FormMode.Blank                                    => FormMode.Blank
    case FormMode.Drafting(FormKind.Portfolio)             => FormMode.Blank
    case m @ FormMode.Drafting(FormKind.Leaf)              => m
    case _                                                  => FormMode.Inactive

  /** Mirror of `forPortfolio` for `RiskLeafFormView`. */
  def forLeaf: FormMode = mode match
    case m @ FormMode.Locked(_: FormTarget.Leaf)     => m
    case m @ FormMode.Editing(_: FormTarget.Leaf)    => m
    case m @ FormMode.Templating(_: FormTarget.Leaf) => m
    case FormMode.Blank                               => FormMode.Blank
    case FormMode.Drafting(FormKind.Leaf)             => FormMode.Blank
    case m @ FormMode.Drafting(FormKind.Portfolio)    => m
    case _                                             => FormMode.Inactive

extension (target: FormTarget)
  def name: SafeName.SafeName = target match
    case FormTarget.Leaf(n)      => n
    case FormTarget.Portfolio(n) => n

/** A pre-validation snapshot of a form's raw field content — the same
  * `String`/`ParentSelection` shape the underlying `Var`s already hold (see
  * [[RiskLeafFormState]] / [[PortfolioFormState]]). Deliberately not
  * Iron-refined: the dirty check this exists for has to fire on partially-typed
  * or currently-invalid input, which a refined type cannot represent.
  */
enum FieldSnapshot:
  case LeafFields(
    name:        String,
    parent:      ParentSelection,
    probability: String,
    mode:        DistributionMode,
    percentiles: String,
    quantiles:   String,
    terms:       String,
    minLoss:     String,
    maxLoss:     String
  )
  case PortfolioFields(name: String, parent: ParentSelection)

object FormMode:

  /** The one place a saved [[LeafDraft]] is converted to raw display-field
    * values — reused by both `populateLeafForm` (writes the values into Vars)
    * and `isFormDirty` (compares them against the live form), so there is
    * exactly one domain-to-display mapping, not two.
    */
  def leafDraftToFieldSnapshot(leaf: LeafDraft): FieldSnapshot.LeafFields =
    val mode = leaf.distribution.distributionType match
      case IronConstants.Expert    => DistributionMode.Expert
      case IronConstants.Lognormal => DistributionMode.Lognormal
      case other => throw new IllegalStateException(s"Unrecognised DistributionType: $other — update leafDraftToFieldSnapshot")
    FieldSnapshot.LeafFields(
      name        = leaf.name.value,
      parent      = ParentSelection.fromSaved(leaf.parent.map(_.value)),
      probability = RiskLeafFormState.domainToDisplayPct(leaf.probability, 2),
      mode        = mode,
      percentiles = leaf.distribution.percentiles.fold("")(arr => arr.map(RiskLeafFormState.domainToDisplayPct(_, 0)).mkString(", ")),
      quantiles   = leaf.distribution.quantiles.fold("")(arr => arr.map(_.toString).mkString(", ")),
      terms       = leaf.distribution.terms.fold("")(_.toString),
      minLoss     = leaf.distribution.minLoss.fold("")(_.toString),
      maxLoss     = leaf.distribution.maxLoss.fold("")(_.toString)
    )

  /** The one place a saved [[PortfolioDraft]] is converted to raw display-field
    * values — reused by both `populatePortfolioForm` and `isFormDirty`.
    */
  def portfolioDraftToFieldSnapshot(portfolio: PortfolioDraft): FieldSnapshot.PortfolioFields =
    FieldSnapshot.PortfolioFields(portfolio.name.value, ParentSelection.fromSaved(portfolio.parent.map(_.value)))

  /** Whether the currently-active form has unsaved content — i.e. whether
    * Clear Form has anything to revert, and whether navigating away should
    * be confirm-gated. One definition of "dirty", reused for both.
    */
  def isFormDirty(
    mode:       FormMode,
    current:    FieldSnapshot,
    leaves:     List[LeafDraft],
    portfolios: List[PortfolioDraft]
  ): Boolean =
    mode match
      case FormMode.Blank =>
        // A freshly-cleared/templated Blank form's parent starts at
        // `ParentSelection.Unset` (see PortfolioFormView/RiskLeafFormView's
        // clear/reset call sites) — any move away from `Unset`, deliberate
        // or system-corrected (FormInputs.parentSelect's own auto-correct),
        // is real content to lose, so a plain equality check against `Unset`
        // is both correct and sufficient — no computed default needed.
        current match
          case FieldSnapshot.LeafFields(name, parent, probability, _, percentiles, quantiles, terms, minLoss, maxLoss) =>
            name.nonEmpty || parent != ParentSelection.Unset || probability.nonEmpty ||
              percentiles.nonEmpty || quantiles.nonEmpty || terms.nonEmpty || minLoss.nonEmpty || maxLoss.nonEmpty
          case FieldSnapshot.PortfolioFields(name, parent) =>
            name.nonEmpty || parent != ParentSelection.Unset
      case FormMode.Inactive =>
        // Fields are forced disabled while Inactive (see the two FormViews) —
        // nothing the user can type into, so nothing to ever call dirty.
        false
      case FormMode.Drafting(_) =>
        // Only ever reached here as the *other* form's mode (the owning
        // form's own view folds this to `Blank` — see `forPortfolio`/
        // `forLeaf`) — that other form's fields are forced disabled/blank
        // too, same reasoning as `Inactive`.
        false
      case FormMode.Locked(_) =>
        false
      case FormMode.Editing(target) => differsFromSaved(target, current, leaves, portfolios)
      case FormMode.Templating(source) => differsFromSaved(source, current, leaves, portfolios)

  private def differsFromSaved(
    target:     FormTarget,
    current:    FieldSnapshot,
    leaves:     List[LeafDraft],
    portfolios: List[PortfolioDraft]
  ): Boolean =
    // Straight equality — a saved node's parent is always a definite
    // ParentSelection (never Unset, see fromSaved), so there is no longer a
    // "was this the system's own forced default, not a real edit" case to
    // special-case around: `Unset` showing up here means the option the
    // node was attached to genuinely no longer exists, which really is
    // something the user needs to resolve before resaving, not a false alarm.
    (target, current) match
      case (FormTarget.Leaf(name), leafCurrent: FieldSnapshot.LeafFields) =>
        leaves.find(_.name == name).exists { l =>
          val saved = leafDraftToFieldSnapshot(l)
          saved.copy(parent = leafCurrent.parent) != leafCurrent || leafCurrent.parent != saved.parent
        }
      case (FormTarget.Portfolio(name), portfolioCurrent: FieldSnapshot.PortfolioFields) =>
        portfolios.find(_.name == name).exists { p =>
          val saved = portfolioDraftToFieldSnapshot(p)
          saved.copy(parent = portfolioCurrent.parent) != portfolioCurrent || portfolioCurrent.parent != saved.parent
        }
      case _ => false

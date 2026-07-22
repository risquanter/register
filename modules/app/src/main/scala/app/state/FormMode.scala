package app.state

import com.risquanter.register.domain.data.iron.{SafeName, IronConstants}

/** Identifies which saved node (leaf or portfolio) a [[FormMode]] is pointing at. */
enum FormTarget:
  case Leaf(name: SafeName.SafeName)
  case Portfolio(name: SafeName.SafeName)

/** The node-editing form's state machine. Replaces the independently-mutated
  * `selectedLeafName`/`selectedPortfolioName` pair in [[TreeBuilderState]] —
  * folding target + mode into one sum type makes "only one target selected,
  * and it's read-only / mid-edit / templating a new node" structural rather
  * than a manually-maintained invariant.
  */
enum FormMode:
  case Blank
  case Locked(target: FormTarget)
  case Editing(target: FormTarget)
  case Templating(source: FormTarget)

extension (mode: FormMode)
  /** The node this mode is pointing at, if any — the shared extraction every
    * caller that needs "what's currently selected" (highlighting, cleanup on
    * delete, per-view lock/disabled derivation) would otherwise repeat as its
    * own 4-way match on `FormMode`.
    */
  def currentTarget: Option[FormTarget] = mode match
    case FormMode.Blank         => None
    case FormMode.Locked(t)     => Some(t)
    case FormMode.Editing(t)    => Some(t)
    case FormMode.Templating(t) => Some(t)

  /** This mode collapsed to `Blank` unless its target is a `Portfolio` —
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
    case _                                                 => FormMode.Blank

  /** Mirror of `forPortfolio` for `RiskLeafFormView`. */
  def forLeaf: FormMode = mode match
    case m @ FormMode.Locked(_: FormTarget.Leaf)     => m
    case m @ FormMode.Editing(_: FormTarget.Leaf)    => m
    case m @ FormMode.Templating(_: FormTarget.Leaf) => m
    case _                                            => FormMode.Blank

extension (target: FormTarget)
  def name: SafeName.SafeName = target match
    case FormTarget.Leaf(n)      => n
    case FormTarget.Portfolio(n) => n

/** A pre-validation snapshot of a form's raw field content — the same
  * `String`/`Option[String]` shape the underlying `Var`s already hold (see
  * [[RiskLeafFormState]] / [[PortfolioFormState]]). Deliberately not
  * Iron-refined: the dirty check this exists for has to fire on partially-typed
  * or currently-invalid input, which a refined type cannot represent.
  */
enum FieldSnapshot:
  case LeafFields(
    name:        String,
    parent:      Option[String],
    probability: String,
    mode:        DistributionMode,
    percentiles: String,
    quantiles:   String,
    terms:       String,
    minLoss:     String,
    maxLoss:     String
  )
  case PortfolioFields(name: String, parent: Option[String])

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
      parent      = leaf.parent.map(_.value),
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
    FieldSnapshot.PortfolioFields(portfolio.name.value, portfolio.parent.map(_.value))

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
        // `parent` is checked against `defaultParent`, not excluded outright:
        // once any portfolio already exists, the parent dropdown has no
        // blank/unset state to offer — `FormInputs.parentSelect` auto-selects
        // the first available portfolio the moment the form mounts, before
        // the user has touched anything. Counting that forced default as "the
        // user entered something" made a genuinely untouched form register as
        // dirty on every tree switch / new tree. But a *deliberate* parent
        // change (to something other than that forced default) must still
        // count — dropping the check entirely would silently discard it.
        val default = defaultParent(portfolios, leaves)
        current match
          case FieldSnapshot.LeafFields(name, parent, probability, _, percentiles, quantiles, terms, minLoss, maxLoss) =>
            name.nonEmpty || (parent != default) || probability.nonEmpty ||
              percentiles.nonEmpty || quantiles.nonEmpty || terms.nonEmpty || minLoss.nonEmpty || maxLoss.nonEmpty
          case FieldSnapshot.PortfolioFields(name, parent) =>
            name.nonEmpty || (parent != default)
      case FormMode.Locked(_) =>
        false
      case FormMode.Editing(target) => differsFromSaved(target, current, leaves, portfolios)
      case FormMode.Templating(source) => differsFromSaved(source, current, leaves, portfolios)

  /** The parent value a freshly-cleared Blank form is explicitly initialized
    * to (see `PortfolioFormView`/`RiskLeafFormView`'s clear/reset call
    * sites) — mirroring `TreeBuilderState.parentOptions`' own fallback rule
    * (excluding whichever node, if any, the caller is currently viewing):
    * `None` (root) while root is unclaimed, otherwise the first existing
    * portfolio. Public so both the views (to compute what to set) and the
    * checks below (to compute what "unchanged" means) share one
    * authoritative rule instead of two that have to be kept in sync by hand.
    */
  def defaultParent(portfolios: List[PortfolioDraft], leaves: List[LeafDraft]): Option[String] =
    val rootTaken = portfolios.exists(_.parent.isEmpty) || leaves.exists(_.parent.isEmpty)
    if rootTaken then portfolios.headOption.map(_.name.value) else None

  private def differsFromSaved(
    target:     FormTarget,
    current:    FieldSnapshot,
    leaves:     List[LeafDraft],
    portfolios: List[PortfolioDraft]
  ): Boolean =
    // Parent counts as changed only if it differs from BOTH the saved node's
    // own parent AND the current default — normally redundant (a populated
    // Locked/Editing/Templating form's parent already equals the saved
    // node's own, so the first clause alone decides it), but kept as a
    // fallback for the same reason `defaultParent` exists at all: an
    // unmodified field should never read as a deliberate edit.
    def parentChanged(currentParent: Option[String], savedParent: Option[String]): Boolean =
      currentParent != savedParent && currentParent != defaultParent(portfolios, leaves)

    (target, current) match
      case (FormTarget.Leaf(name), leafCurrent: FieldSnapshot.LeafFields) =>
        leaves.find(_.name == name).exists { l =>
          val saved = leafDraftToFieldSnapshot(l)
          saved.copy(parent = leafCurrent.parent) != leafCurrent || parentChanged(leafCurrent.parent, saved.parent)
        }
      case (FormTarget.Portfolio(name), portfolioCurrent: FieldSnapshot.PortfolioFields) =>
        portfolios.find(_.name == name).exists { p =>
          val saved = portfolioDraftToFieldSnapshot(p)
          saved.copy(parent = portfolioCurrent.parent) != portfolioCurrent || parentChanged(portfolioCurrent.parent, saved.parent)
        }
      case _ => false

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
        current match
          case FieldSnapshot.LeafFields(name, parent, probability, _, percentiles, quantiles, terms, minLoss, maxLoss) =>
            name.nonEmpty || parent.exists(_.nonEmpty) || probability.nonEmpty ||
              percentiles.nonEmpty || quantiles.nonEmpty || terms.nonEmpty || minLoss.nonEmpty || maxLoss.nonEmpty
          case FieldSnapshot.PortfolioFields(name, parent) =>
            name.nonEmpty || parent.exists(_.nonEmpty)
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
    (target, current) match
      case (FormTarget.Leaf(name), leafCurrent: FieldSnapshot.LeafFields) =>
        leaves.find(_.name == name).exists(leafDraftToFieldSnapshot(_) != leafCurrent)
      case (FormTarget.Portfolio(name), portfolioCurrent: FieldSnapshot.PortfolioFields) =>
        portfolios.find(_.name == name).exists(portfolioDraftToFieldSnapshot(_) != portfolioCurrent)
      case _ => false

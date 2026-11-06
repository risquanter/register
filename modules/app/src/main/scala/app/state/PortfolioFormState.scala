package app.state

import com.raquo.laminar.api.L.*
import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.{SafeName, ValidationMessages, ValidationUtil}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef
import app.state.PortfolioField.*

/** Type-safe field identifiers for the portfolio form. */
enum PortfolioField:
  case Name, Parent

  /** The form's own line, for a refusal about a combination of its inputs
    * rather than about one of them. */
  case FormLevel

object PortfolioField:
  /** The label an input carries, and the name a summary entry gives it. */
  def label(field: PortfolioField): String = field match
    case Name      => "Portfolio Name"
    case Parent    => ParentSelection.Label
    case FormLevel => "This portfolio"

  /** The element id of the input, unique on the page. */
  def inputId(field: PortfolioField): String = s"portfolio-${field.toString.toLowerCase}"

/**
 * Reactive form state for creating a portfolio.
 * Validates name eagerly (Iron SafeName) and exposes parent selection as a ParentSelection.
 */
final class PortfolioFormState extends SubmitRefusals[PortfolioField]:

  // Fields
  val nameVar: Var[String] = Var("")
  val parentVar: Var[ParentSelection] = Var(ParentSelection.Unset)

  /** The node this form's refusals are about. Set when the form is opened:
    * `DraftNode` for a new portfolio, `ExistingNode(id)` for one the server
    * has stored. */
  val subjectVar: Var[ErrorSubject] = Var(ErrorSubject.DraftNode)
  private def subject: StrictSignal[ErrorSubject] = subjectVar.signal

  /** The fields a refusal about each input names. Declared once; the located
    * readers below refer to them and no other site in this form names a field. */
  private val nameField: FieldName   = FieldName.Name
  private val parentField: FieldName = FieldName.ParentName

  /** The name input's live value, located. */
  private def nameLocated: Signal[Located[String]] =
    nameVar.signal.combineWith(subject).map((v, s) => Located(v, ErrorLocation.Field(s, nameField)))

  /** The name input's current value, located, for the submit read. */
  private def nameLocatedNow: Located[String] =
    Located(nameVar.now(), ErrorLocation.Field(subjectVar.now(), nameField))

  /** The parent selection's live value, located. */
  private def parentLocated: Signal[Located[ParentSelection]] =
    parentVar.signal.combineWith(subject).map((v, s) => Located(v, ErrorLocation.Field(s, parentField)))

  /** The parent selection's current value, located, for the submit read. */
  private def parentLocatedNow: Located[ParentSelection] =
    Located(parentVar.now(), ErrorLocation.Field(subjectVar.now(), parentField))

  // Raw validation
  private val nameErrorRaw: Signal[Option[ValidationError]] =
    nameLocated.map(_.refine(ValidationUtil.refineName).left.toOption.flatMap(_.headOption))

  /** The parent selection's live refusal, for blocking submit only. The
    * dropdown's own display shows only a submit's refusals (`parentError`). */
  private val parentErrorRaw: Signal[Option[ValidationError]] =
    parentLocated.map(input => ParentSelection.toParentRef(input).toEither.left.toOption.map(_.head))

  override protected def inputsFor(location: ErrorLocation): Set[PortfolioField] =
    PortfolioFormState.inputsFor(location)

  override protected def formLine: PortfolioField = FormLevel

  // Display-controlled refusals: the live check, else the stored submit refusals
  private val nameRefusals: Signal[List[ValidationError]] = errorsFor(Name, nameErrorRaw)
  val nameError: Signal[List[String]] = messagesOf(nameRefusals)

  /** The dropdown shows only submit-time refusals (`Validation.Failure` from
    * `toDraft`, or the builder's topology check), from the stored refusals.
    * Its live check, `parentErrorRaw`, blocks the submit and is not shown
    * here. */
  private val parentRefusals: Signal[List[ValidationError]] = errorsFor(Parent, Val(None))
  val parentError: Signal[List[String]] = messagesOf(parentRefusals)

  // FormState implementation
  override def errorSignals: List[Signal[Option[String]]] =
    List(nameErrorRaw, parentErrorRaw).map(_.map(_.map(e => ValidationMessages.render(e.problem))))

  /** The inputs in the order `PortfolioFormView` renders them. */
  override protected def displayed: List[(PortfolioField, Signal[List[ValidationError]])] =
    List(Name -> nameRefusals, Parent -> parentRefusals)

  /** Build validated inputs for submission. `Unset` fails validation exactly
    * like an empty name would — there is no silent default to fall back to.
    */
  def toDraft: Validation[ValidationError, (SafeName.SafeName, ParentRef)] =
    val nameV: Validation[ValidationError, SafeName.SafeName] =
      toValidation(nameLocatedNow.refine(ValidationUtil.refineName))
    val parentV: Validation[ValidationError, ParentRef] =
      ParentSelection.toParentRef(parentLocatedNow)
    Validation.validateWith(nameV, parentV)((name, parent) => (name, parent))

  /** Reset form fields and error display state after successful submit.
   *  `parentVar` and `subjectVar` are not reset here: `PortfolioFormView` sets
   *  them at each clear or reset call site. */
  def reset(): Unit =
    nameVar.set("")
    setSubmitErrors(Nil)
    resetTouched()

object PortfolioFormState:

  /** No display target of this form. A refusal at such a location is shown on
    * the form's own line, through `unclaimed`. */
  private val noTarget: Set[PortfolioField] = Set.empty

  /** The targets a refusal belongs under. This form has two inputs and its own
    * line; every location is assigned by name, so a location added later fails
    * the build until it is given one. The builder's topology check of this
    * form's submit belongs on the form's own line. The subject does not change
    * the routing.
    *
    * Visible to the application so the mapping is testable without rendering
    * the form.
    */
  private[app] def inputsFor(location: ErrorLocation): Set[PortfolioField] =
    location match
      case ErrorLocation.Field(_, field) =>
        field match
          case FieldName.Name       => Set(PortfolioField.Name)
          case FieldName.ParentName => Set(PortfolioField.Parent)
          case FieldName.Id | FieldName.ParentId | FieldName.ChildIds | FieldName.RootId |
               FieldName.Nodes | FieldName.Trees | FieldName.SeedVarHighWater |
               FieldName.Probability | FieldName.SeedVarId | FieldName.SeedEntityId |
               FieldName.DistributionType | FieldName.Terms | FieldName.MinLoss | FieldName.MaxLoss |
               FieldName.Spec | FieldName.Percentiles | FieldName.Quantiles |
               FieldName.PercentileAt(_) | FieldName.QuantileAt(_) | FieldName.ChildAt(_) |
               FieldName.LossRange | FieldName.PercentileQuantilePairing |
               FieldName.Mitigations | FieldName.Precedence | FieldName.Cap | FieldName.Factor |
               FieldName.TargetingSource | FieldName.Deductible | FieldName.Threshold |
               FieldName.Branch | FieldName.Scenario | FieldName.At |
               FieldName.Source | FieldName.ToCommit | FieldName.CommitHash | FieldName.ContentHash |
               FieldName.WorkspaceKey | FieldName.WorkspaceKeyHash | FieldName.UserId |
               FieldName.Email | FieldName.Url | FieldName.TraceId |
               FieldName.AggregatedLoss | FieldName.MitigatedResult | FieldName.Unknown =>
            noTarget
      case ErrorLocation.Whole             => Set(PortfolioField.FormLevel)
      case ErrorLocation.Header(_)         => noTarget
      case ErrorLocation.QueryParameter(_) => noTarget

package app.state

import com.raquo.laminar.api.L.*
import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef

/** Type-safe field identifiers for the portfolio form. */
enum PortfolioField:
  case Name, Parent

  /** The form's own line, for a refusal about a combination of its inputs
    * rather than about one of them. */
  case FormLevel

/**
 * Reactive form state for creating a portfolio.
 * Validates name eagerly (Iron SafeName) and exposes parent selection as a ParentSelection.
 */
final class PortfolioFormState extends SubmitRefusals[PortfolioField]:
  import PortfolioField.*

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

  /** The parent selection's current value, located, for the submit read. */
  private def parentLocatedNow: Located[ParentSelection] =
    Located(parentVar.now(), ErrorLocation.Field(subjectVar.now(), parentField))

  // Raw validation
  private val nameErrorRaw: Signal[Option[ValidationError]] =
    nameLocated.map(_.refine(ValidationUtil.refineName).left.toOption.flatMap(_.headOption))

  override protected def inputsFor(location: ErrorLocation): Set[PortfolioField] =
    PortfolioFormState.inputsFor(location)

  override protected def formLine: PortfolioField = FormLevel

  // Display-controlled errors: the live check, else the stored submit refusals
  val nameError: Signal[List[String]] = messagesOf(errorsFor(Name, nameErrorRaw))

  /** Parent has no continuous reactive validation of its own (unlike name) —
    * only submit-time refusals (`Validation.Failure` from `toDraft`, or the
    * builder's topology check) ever show here, from the stored refusals. */
  val parentError: Signal[List[String]] = messagesOf(errorsFor(Parent, Val(None)))

  // FormState implementation
  override def errorSignals: List[Signal[Option[String]]] = List(nameErrorRaw.map(_.map(_.message)))

  val isValid: Signal[Boolean] = hasErrors.map(! _)

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
   *  Note: parentVar and subjectVar are NOT reset here — callers set them
   *  explicitly at clear/reset call sites (PortfolioFormView), same as every
   *  other reset does; resetting them inside this method too would just be a
   *  second place doing the same thing. */
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
               FieldName.Nodes | FieldName.Trees | FieldName.SeedVarHighWater | FieldName.Limit |
               FieldName.Probability | FieldName.SeedVarId | FieldName.SeedEntityId |
               FieldName.DistributionType | FieldName.Terms | FieldName.MinLoss | FieldName.MaxLoss |
               FieldName.Spec | FieldName.Percentiles | FieldName.Quantiles |
               FieldName.PercentileAt(_) | FieldName.QuantileAt(_) | FieldName.ChildAt(_) |
               FieldName.LossRange | FieldName.PercentileQuantilePairing |
               FieldName.Mitigations | FieldName.Cap | FieldName.Factor | FieldName.OverrideParams |
               FieldName.TargetingSource | FieldName.Deductible | FieldName.Threshold |
               FieldName.Branch | FieldName.Scenario | FieldName.TreeId | FieldName.At |
               FieldName.Source | FieldName.ToCommit | FieldName.CommitHash | FieldName.ContentHash |
               FieldName.WorkspaceKey | FieldName.WorkspaceKeyHash | FieldName.UserId |
               FieldName.Email | FieldName.Url | FieldName.Token | FieldName.TraceId |
               FieldName.AggregatedLoss | FieldName.MitigatedResult | FieldName.Unknown =>
            noTarget
      case ErrorLocation.Whole     => Set(PortfolioField.FormLevel)
      case ErrorLocation.Header(_) => noTarget

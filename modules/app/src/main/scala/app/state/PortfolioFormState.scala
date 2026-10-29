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
final class PortfolioFormState extends FormState[PortfolioField]:
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

  // Display-controlled errors (with submit-time server error composition)
  val nameError: Signal[Option[String]] = withSubmitErrors(Name, nameErrorRaw.map(_.map(_.message)))

  /** Parent has no continuous reactive validation of its own (unlike name) —
    * only submit-time errors (`Validation.Failure` from `toDraft`, or the
    * builder's topology check) ever populate this, via
    * `setSubmitFieldError`. */
  val parentError: Signal[Option[String]] = withSubmitErrors(Parent, Val(None))

  /** The form's own line: a submit refusal about a combination of this form's
    * inputs rather than about one of them. */
  val formLevelError: Signal[Option[String]] = withSubmitErrors(FormLevel, Val(None))

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
    resetTouched()

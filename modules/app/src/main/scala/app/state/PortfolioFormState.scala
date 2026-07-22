package app.state

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.Validation
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}

/** Type-safe field identifiers for the portfolio form. */
enum PortfolioField:
  case Name, Parent

/**
 * Reactive form state for creating a portfolio.
 * Validates name eagerly (Iron SafeName) and exposes parent selection as a ParentSelection.
 */
final class PortfolioFormState extends FormState[PortfolioField]:
  import PortfolioField.*

  // Fields
  val nameVar: Var[String] = Var("")
  val parentVar: Var[ParentSelection] = Var(ParentSelection.Unset)

  // Raw validation
  private val nameErrorRaw: Signal[Option[String]] = nameVar.signal.map { v =>
    toValidation(ValidationUtil.refineName(v, "portfolio.name")) match
      case Validation.Success(_, refined) => None
      case Validation.Failure(_, errs) => Some(errs.head.message)
  }

  // Display-controlled errors (with submit-time server error composition)
  val nameError: Signal[Option[String]] = withSubmitErrors(Name, nameErrorRaw)

  /** Parent has no continuous reactive validation of its own (unlike name) —
    * only submit-time errors (`Validation.Failure` from `toDraft`, or a
    * topology error from the server) ever populate this, via
    * `setSubmitFieldError`. */
  val parentError: Signal[Option[String]] = withSubmitErrors(Parent, Val(None))

  // FormState implementation
  override def errorSignals: List[Signal[Option[String]]] = List(nameErrorRaw)

  val isValid: Signal[Boolean] = hasErrors.map(! _)

  /** Build validated inputs for submission. `Unset` fails validation exactly
    * like an empty name would — there is no silent default to fall back to.
    */
  def toDraft: Validation[ValidationError, (SafeName.SafeName, Option[SafeName.SafeName])] =
    val nameV: Validation[ValidationError, SafeName.SafeName] =
      toValidation(ValidationUtil.refineName(nameVar.now(), "portfolio.name"))
    val parentV: Validation[ValidationError, Option[SafeName.SafeName]] =
      parentVar.now() match
        case ParentSelection.Root         => Validation.succeed(None)
        case ParentSelection.Portfolio(v) => toValidation(ValidationUtil.refineName(v, "portfolio.parentName")).map(Some(_))
        case ParentSelection.Unset        =>
          Validation.fail(ValidationError("portfolio.parentName", ValidationErrorCode.REQUIRED_FIELD, "Parent is required"))
    Validation.validateWith(nameV, parentV)((name, parent) => (name, parent))

  /** Reset form fields and error display state after successful submit.
   *  Note: parentVar is NOT reset here — callers explicitly set it to
   *  `ParentSelection.Unset` at clear/reset call sites (PortfolioFormView),
   *  same as every other reset does; resetting it inside this method too
   *  would just be a second place doing the same thing. */
  def reset(): Unit =
    nameVar.set("")
    resetTouched()

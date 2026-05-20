package app.state

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.{Validation, ZValidation}
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.data.iron.SafeName
import com.risquanter.register.domain.errors.ValidationError

/** Type-safe field identifiers for the portfolio form. */
enum PortfolioField:
  case Name, Parent

/**
 * Reactive form state for creating a portfolio.
 * Validates name eagerly (Iron SafeName) and exposes parent selection as an Option[String].
 */
final class PortfolioFormState extends FormState[PortfolioField]:
  import PortfolioField.*

  // Fields
  val nameVar: Var[String] = Var("")
  val parentVar: Var[Option[String]] = Var(None) // None means root

  // Raw validation
  private val nameErrorRaw: Signal[Option[String]] = nameVar.signal.map { v =>
    toValidation(ValidationUtil.refineName(v, "portfolio.name")) match
      case Validation.Success(_, refined) => None
      case Validation.Failure(_, errs) => Some(errs.head.message)
  }

  // Display-controlled errors (with submit-time server error composition)
  val nameError: Signal[Option[String]] = withSubmitErrors(Name, nameErrorRaw)

  // FormState implementation
  override def errorSignals: List[Signal[Option[String]]] = List(nameErrorRaw)

  val isValid: Signal[Boolean] = hasErrors.map(! _)

  /** Build validated inputs for submission. */
  def toDraft: Validation[ValidationError, (SafeName.SafeName, Option[SafeName.SafeName])] =
    val nameV: ZValidation[Nothing, ValidationError, SafeName.SafeName] =
      toValidation(ValidationUtil.refineName(nameVar.now(), "portfolio.name"))
    val parentV: Validation[ValidationError, Option[SafeName.SafeName]] =
      parentVar.now() match
        case Some(v) if v.trim.nonEmpty => toValidation(ValidationUtil.refineName(v, "portfolio.parentName")).map(Some(_))
        case _ => Validation.succeed(None)
    Validation.validateWith(nameV, parentV)((name, parent) => (name, parent))

  /** Reset form fields and error display state after successful submit.
   *  Note: parentVar is NOT reset — it is auto-synced by FormInputs.parentSelect
   *  based on available options. Resetting it to None would race with auto-sync. */
  def reset(): Unit =
    nameVar.set("")
    resetTouched()

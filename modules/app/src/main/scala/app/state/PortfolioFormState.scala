package app.state

import com.raquo.laminar.api.L.{*, given}
import zio.prelude.{Validation, ZValidation}
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.ValidationError

/**
 * Reactive form state for creating a portfolio.
 * Validates name eagerly (Iron SafeName) and exposes parent selection as an Option[String].
 */
final class PortfolioFormState extends FormState:

  // Fields
  val nameVar: Var[String] = Var("")
  val parentVar: Var[Option[String]] = Var(None) // None means root

  // Raw validation
  private val nameErrorRaw: Signal[Option[String]] = nameVar.signal.map { v =>
    toValidation(ValidationUtil.refineName(v, "portfolio.name")) match
      case Validation.Success(_, refined) => None
      case Validation.Failure(_, errs) => Some(errs.head.message)
  }

  // Display-controlled errors
  val nameError: Signal[Option[String]] = shouldShowError("name").combineWith(nameErrorRaw).map {
    case (true, err) => err
    case _ => None
  }

  // FormState implementation
  override def errorSignals: List[Signal[Option[String]]] = List(nameErrorRaw)

  val isValid: Signal[Boolean] = hasErrors.map(! _)

  /** Build validated inputs for submission. */
  def toDraft: Validation[ValidationError, (String, Option[String])] =
    val nameV: ZValidation[Nothing, ValidationError, String] =
      toValidation(ValidationUtil.refineName(nameVar.now(), "portfolio.name")).map(_.value)
    Validation.validateWith(nameV, Validation.succeed(parentVar.now()))((name, parent) => (name, parent))

  /** Reset form fields and error display state after successful submit.
   *  Note: parentVar is NOT reset — it is auto-synced by FormInputs.parentSelect
   *  based on available options. Resetting it to None would race with auto-sync. */
  def reset(): Unit =
    nameVar.set("")
    resetTouched()

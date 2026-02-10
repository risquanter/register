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

  // Error display timing
  val showErrorsVar: Var[Boolean] = Var(false)
  private val touchedFields: Var[Set[String]] = Var(Set.empty)

  def markTouched(fieldName: String): Unit =
    touchedFields.update(_ + fieldName)

  private def isTouched(fieldName: String): Signal[Boolean] =
    touchedFields.signal.map(_.contains(fieldName))

  private def shouldShowError(fieldName: String): Signal[Boolean] =
    showErrorsVar.signal.combineWith(isTouched(fieldName)).map { case (showAll, touched) => showAll || touched }

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

  def triggerValidation(): Unit =
    showErrorsVar.set(true)

  /** Build validated inputs for submission. */
  def toDraft: Validation[ValidationError, (String, Option[String])] =
    val nameV: ZValidation[Nothing, ValidationError, String] =
      toValidation(ValidationUtil.refineName(nameVar.now(), "portfolio.name")).map(_.value)
    Validation.validateWith(nameV, Validation.succeed(parentVar.now()))((name, parent) => (name, parent))

package app.state

import com.raquo.laminar.api.L.{*, given}

/**
 * FormState trait — reactive form infrastructure with error display timing control.
 *
 * Provides:
 * - Error display timing: `markTouched`, `shouldShowError`, `triggerValidation`
 * - Error aggregation: `errorSignals`, `hasErrors`
 * - Parse helpers: `parseDouble`, `parseLong`, `parseDoubleList`
 *
 * Subclasses define `errorSignals` and use `shouldShowError` to gate display.
 */
trait FormState:

  // ============================================================
  // Error Display Timing (shared infrastructure)
  // ============================================================

  /** Set to true after first submit attempt */
  private val showErrorsVar: Var[Boolean] = Var(false)

  /** Per-field "touched" state for showing errors on blur */
  private val touchedFields: Var[Set[String]] = Var(Set.empty)

  def markTouched(fieldName: String): Unit =
    touchedFields.update(_ + fieldName)

  def isTouched(fieldName: String): Signal[Boolean] =
    touchedFields.signal.map(_.contains(fieldName))

  /** Show error for a field if: showErrors is true OR field has been touched */
  def shouldShowError(fieldName: String): Signal[Boolean] =
    showErrorsVar.signal.combineWith(isTouched(fieldName)).map {
      case (showAll, touched) => showAll || touched
    }

  /** Call before submit to show all errors */
  def triggerValidation(): Unit =
    showErrorsVar.set(true)

  /** Reset error display and touched state (e.g., after successful submit) */
  def resetTouched(): Unit =
    showErrorsVar.set(false)
    touchedFields.set(Set.empty)

  // ============================================================
  // Error Aggregation
  // ============================================================

  /** List of raw error signals for this form */
  def errorSignals: List[Signal[Option[String]]]

  /** Signal indicating whether any field has an error */
  lazy val hasErrors: Signal[Boolean] =
    Signal.combineSeq(errorSignals).map(_.exists(_.isDefined))

  // ============================================================
  // Parse Helpers
  // ============================================================

  /** Helper to parse a string as a Double */
  protected def parseDouble(s: String): Option[Double] =
    scala.util.Try(s.trim.toDouble).toOption

  /** Helper to parse a string as a Long */
  protected def parseLong(s: String): Option[Long] =
    scala.util.Try(s.trim.toLong).toOption

  /** Helper to parse a comma-separated string as a list of Doubles */
  protected def parseDoubleList(s: String): List[Double] =
    if s.isBlank then Nil
    else s.split(",").toList.flatMap(part => parseDouble(part.trim))


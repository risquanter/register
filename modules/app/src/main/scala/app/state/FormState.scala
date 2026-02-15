package app.state

import com.raquo.laminar.api.L.{*, given}

/**
 * FormState trait — reactive form infrastructure with error display timing control.
 *
 * Type parameter `F` is the per-form field enum, ensuring compile-time safety
 * for field references (markTouched, withDisplayControl, setSubmitFieldError, etc.).
 *
 * Provides:
 * - Error display timing: `markTouched`, `shouldShowError`, `triggerValidation`
 * - Error aggregation: `errorSignals`, `hasErrors`
 * - Parse helpers: `parseDouble`, `parseLong`, `parseDoubleList`
 *
 * Subclasses define `errorSignals` and use `shouldShowError` to gate display.
 */
trait FormState[F]:

  // ============================================================
  // Error Display Timing (shared infrastructure)
  // ============================================================

  /** Set to true after first submit attempt */
  private val showErrorsVar: Var[Boolean] = Var(false)

  /** Per-field "touched" state for showing errors on blur */
  private val touchedFields: Var[Set[F]] = Var(Set.empty)

  /** Per-field submit-time errors routed from topology validation (W.10) */
  private val submitFieldErrors: Var[Map[F, String]] = Var(Map.empty)

  def markTouched(field: F): Unit =
    touchedFields.update(_ + field)

  def setSubmitFieldError(field: F, message: String): Unit =
    submitFieldErrors.update(_ + (field -> message))

  /** Clear a single field's submit error (e.g. when the user edits that field). */
  def clearSubmitFieldError(field: F): Unit =
    submitFieldErrors.update(_ - field)

  def isTouched(field: F): Signal[Boolean] =
    touchedFields.signal.map(_.contains(field))

  /** Show error for a field if: showErrors is true OR field has been touched */
  def shouldShowError(field: F): Signal[Boolean] =
    showErrorsVar.signal.combineWith(isTouched(field)).map {
      case (showAll, touched) => showAll || touched
    }

  /** Gate a raw error signal through display timing for a given field.
   *  Error is shown only after the field is touched (blur) or form-wide validation is triggered (submit). */
  def withDisplayControl(field: F, rawError: Signal[Option[String]]): Signal[Option[String]] =
    shouldShowError(field).combineWith(rawError).map {
      case (true, error) => error
      case (false, _)    => None
    }

  /** Compose submit-time server errors with reactive validation for a field.
   *  Reactive errors take priority; submit error is shown when no reactive error exists. */
  def withSubmitErrors(field: F, rawError: Signal[Option[String]]): Signal[Option[String]] =
    val submitErr = submitFieldErrors.signal.map(_.get(field))
    withDisplayControl(field, rawError.combineWith(submitErr).map {
      case (reactive, submitted) => reactive.orElse(submitted)
    })

  /** Call before submit to show all errors */
  def triggerValidation(): Unit =
    submitFieldErrors.set(Map.empty)
    showErrorsVar.set(true)

  /** Reset error display and touched state (e.g., after successful submit) */
  def resetTouched(): Unit =
    showErrorsVar.set(false)
    touchedFields.set(Set.empty)
    submitFieldErrors.set(Map.empty)

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


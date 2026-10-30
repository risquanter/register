package app.state

import com.raquo.laminar.api.L.*

import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, ValidationError, ValidationErrorCode}
import zio.prelude.Validation

/**
 * FormState trait — reactive form infrastructure with error display timing control.
 *
 * Type parameter `Field` is the form's own display enum — its inputs and its
 * own line — so a member such as `markTouched` cannot be handed a target the
 * form does not render.
 *
 * Provides:
 * - The subject every refusal of the form names: `subjectVar`
 * - Error display timing: `markTouched`, `shouldShowError`, `triggerValidation`
 * - Error aggregation: `errorSignals`, `hasErrors`
 * - Parse helpers: `parseDouble`, `parseLong`, `parseDoubleList`, `parseRequired`
 *
 * Subclasses define `subjectVar` and `errorSignals`, and use `shouldShowError`
 * to gate display.
 */
trait FormState[Field]:

  /** The node this form's refusals are about. A `Var` rather than a signal,
    * because the submit path reads a snapshot of it. */
  def subjectVar: Var[ErrorSubject]

  // ============================================================
  // Error Display Timing (shared infrastructure)
  // ============================================================

  /** Set to true after first submit attempt */
  private val showErrorsVar: Var[Boolean] = Var(false)

  /** Per-field "touched" state for showing errors on blur */
  private val touchedFields: Var[Set[Field]] = Var(Set.empty)

  def markTouched(field: Field): Unit =
    touchedFields.update(_ + field)

  def isTouched(field: Field): Signal[Boolean] =
    touchedFields.signal.map(_.contains(field))

  /** Show error for a field if: showErrors is true OR field has been touched */
  def shouldShowError(field: Field): Signal[Boolean] =
    showErrorsVar.signal.combineWith(isTouched(field)).map {
      case (showAll, touched) => showAll || touched
    }

  /** Gate a raw error signal through display timing for a given field.
   *  Error is shown only after the field is touched (blur) or form-wide validation is triggered (submit). */
  def withDisplayControl(field: Field, rawError: Signal[Option[String]]): Signal[Option[String]] =
    shouldShowError(field).combineWith(rawError).map {
      case (true, error) => error
      case (false, _)    => None
    }

  /** Call before submit to show all errors */
  def triggerValidation(): Unit =
    showErrorsVar.set(true)

  /** Expose the show-all-errors flag for subclasses that need to build
    * custom visibility gates (e.g. cross-field errors with content-presence
    * logic rather than per-field touch logic). */
  protected def showErrorsSignal: Signal[Boolean] = showErrorsVar.signal

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
    s.trim.toDoubleOption

  /** Helper to parse a string as a Long */
  protected def parseLong(s: String): Option[Long] =
    s.trim.toLongOption

  /** Helper to parse a comma-separated string as a list of Doubles */
  protected def parseDoubleList(s: String): List[Double] =
    if s.isBlank then Nil
    else s.split(",").toList.flatMap(part => parseDouble(part.trim))

  /** A required text input parsed into a value. Blank text is refused as
    * missing (`REQUIRED_FIELD`), and text `parse` cannot read is refused as
    * unreadable (`TYPE_MISMATCH`) — the codes the wire uses for the same two
    * situations. The parsed value keeps the input's location, so a refinement
    * applied to it next refuses at the same field.
    *
    * {{{
    * parseRequired(Located(" 12 ", at), parseLong, required, unreadable)  // Success(Located(12L, at))
    * parseRequired(Located("abc", at), parseLong, required, unreadable)   // Failure: TYPE_MISMATCH at `at`
    * }}}
    */
  protected def parseRequired[A](
    input:      Located[String],
    parse:      String => Option[A],
    required:   String,
    unreadable: String
  ): Validation[ValidationError, Located[A]] =
    if input.value.isBlank then
      Validation.fail(input.reject(ValidationErrorCode.REQUIRED_FIELD, required))
    else
      parse(input.value).fold[Validation[ValidationError, Located[A]]](
        Validation.fail(input.reject(ValidationErrorCode.TYPE_MISMATCH, unreadable))
      )(value => Validation.succeed(input.as(value)))

/** The rules a node form applies to the refusals of its last submit, over the
  * form's own mapping from a location to its targets. Both node forms apply the
  * same rules, so they are stated once here.
  */
object FormState:

  /** The refusals one target shows. Nothing until the target is touched or the
    * form submitted; then the form's own check while it refuses, otherwise the
    * stored refusals whose location names this target.
    *
    * {{{
    * selected(inputsFor, Name, show = true, own = None, stored)        // the stored refusals naming Name
    * selected(inputsFor, Name, show = true, own = Some(blank), stored) // List(blank)
    * }}}
    */
  def selected[Field](
    inputsFor: ErrorLocation => Set[Field],
    field:     Field,
    show:      Boolean,
    own:       Option[ValidationError],
    stored:    List[ValidationError]
  ): List[ValidationError] =
    (show, own) match
      case (false, _)        => Nil
      case (true, Some(err)) => List(err)
      case (true, None)      => stored.filter(e => inputsFor(e.location).contains(field))

  /** Whether an edit of `field` makes a stored refusal stale: the refusal names
    * that input, or it shows on the form's own line. A line refusal is about the
    * inputs together or about none of them, so editing any input can answer it.
    */
  def staleAfterEdit[Field](
    inputsFor: ErrorLocation => Set[Field],
    formLine:  Field,
    field:     Field
  )(error: ValidationError): Boolean =
    val targets = inputsFor(error.location)
    targets.isEmpty || targets.contains(field) || targets.contains(formLine)


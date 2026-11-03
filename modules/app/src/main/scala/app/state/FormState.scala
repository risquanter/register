package app.state

import com.raquo.laminar.api.L.*

import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, ExpectedInput, FieldProblem, ValidationError}
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
 * - Error aggregation: `errorSignals`, `blockingReasons`
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

  /** The messages of every raw check that refuses now, ignoring display timing:
    * what blocks the form's submit buttons. */
  lazy val blockingReasons: Signal[List[String]] =
    Signal.combineSeq(errorSignals).map(_.toList.flatten)

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
    * `Required`, and text `parse` cannot read is refused as `Unreadable` with
    * what it was expected to read as — the problems the wire reports for the
    * same two situations. The parsed value keeps the input's location, so a
    * refinement applied to it next refuses at the same field.
    *
    * {{{
    * parseRequired(Located(" 12 ", at), parseLong, ExpectedInput.WholeNumber)  // Success(Located(12L, at))
    * parseRequired(Located("abc", at), parseLong, ExpectedInput.WholeNumber)   // Failure: Unreadable(WholeNumber) at `at`
    * }}}
    */
  protected def parseRequired[A](
    input:    Located[String],
    parse:    String => Option[A],
    expected: ExpectedInput
  ): Validation[ValidationError, Located[A]] =
    if input.value.isBlank then
      Validation.fail(input.reject(FieldProblem.Required))
    else
      parse(input.value).fold[Validation[ValidationError, Located[A]]](
        Validation.fail(input.reject(FieldProblem.Unreadable(expected)))
      )(value => Validation.succeed(input.as(value)))

/** A form that keeps the refusals of its last submit and lets each of its
  * targets — its inputs and its own line — select the ones that name it.
  *
  * Each form supplies its mapping from a location to its targets and names its
  * own line; the store, the selection and the clearing are the same for every
  * such form, so they are stated once here.
  */
trait SubmitRefusals[Field] extends FormState[Field]:

  /** The targets a refusal at `location` belongs under; empty for none. */
  protected def inputsFor(location: ErrorLocation): Set[Field]

  /** The form's own line, for a refusal about its inputs together. */
  protected def formLine: Field

  /** Refusals of this form's last submit that its own live checks do not
    * produce: those of the submit read and of the builder's topology check.
    * The live checks are not held here: they are derived from the input
    * `Var`s, and writing a derived value back into a `Var` is the
    * self-correcting reactive `Var` defect ADR-019 names. */
  private val submitErrors: Var[List[ValidationError]] = Var(Nil)

  /** Store a submit's refusals, replacing the previous submit's. */
  def setSubmitErrors(errors: List[ValidationError]): Unit =
    submitErrors.set(errors)

  /** Drop the stored refusals naming this input, because the user edited it.
    * A refusal naming several inputs drops from all of them, since editing any
    * one can satisfy the rule. The refusals on the form's own line drop on any
    * edit, because they are about the inputs together. */
  def clearFor(field: Field): Unit =
    submitErrors.update(_.filterNot(SubmitRefusals.staleAfterEdit(inputsFor, formLine, field)))

  /** Every refusal for one target, shown once it is touched or the form
    * submitted: this form's own check while it refuses, otherwise the stored
    * refusals naming this target. A refusal whose location names several
    * inputs is selected by each of them. */
  def errorsFor(field: Field, live: Signal[Option[ValidationError]]): Signal[List[ValidationError]] =
    shouldShowError(field).combineWith(live, submitErrors.signal).map { (show, own, stored) =>
      SubmitRefusals.selected(inputsFor, field, show, own, stored)
    }

  /** The stored refusals no target of this form claims. */
  def unclaimed: Signal[List[ValidationError]] =
    submitErrors.signal.map(_.filter(e => inputsFor(e.location).isEmpty))

  /** The sentences of the refusals a target shows, for the input components. */
  protected def messagesOf(errors: Signal[List[ValidationError]]): Signal[List[String]] =
    errors.map(_.map(e => ValidationMessages.render(e.problem)))

  /** The refusals on the form's own line: a submit refusal about the form's
    * inputs together, then any refusal no target of this form claims. */
  lazy val formLevelRefusals: Signal[List[ValidationError]] =
    errorsFor(formLine, Val(None)).combineWith(unclaimed).map(_ ++ _)

  /** The form's own line, as sentences. */
  lazy val formLevelError: Signal[List[String]] = messagesOf(formLevelRefusals)

  /** Each input this form renders, in display order, with the refusals it
    * shows. Refusals rather than sentences, because the summary tells one
    * refusal shown under two inputs apart from two refusals that happen to
    * read the same. */
  protected def displayed: List[(Field, Signal[List[ValidationError]])]

  /** Every refusal the form shows now, input by input, then its own line. */
  lazy val summary: Signal[List[SummaryEntry[Field]]] =
    Signal.combineSeq(displayed.map((field, refusals) => refusals.map(field -> _)))
      .combineWith(formLevelRefusals)
      .map((perInput, line) => SubmitRefusals.summaryOf(perInput.toList, line))

/** The rules a form with a refusal store applies to the refusals of its last
  * submit, over the form's own mapping from a location to its targets. */
object SubmitRefusals:

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

  /** The summary of what a form shows: a refusal shown under two inputs — a
    * rule over the pair of them — is listed once, under the first. Two
    * refusals of different inputs are both listed even when their sentences
    * read the same, which is why the key is the refusal and not its sentence.
    * {{{
    * summaryOf(List(MinLoss -> List(rangeRefusal), MaxLoss -> List(rangeRefusal)), Nil)
    * // List(SummaryEntry(Some(MinLoss), render(rangeRefusal.problem)))
    * }}} */
  def summaryOf[Field](
    perInput: List[(Field, List[ValidationError])],
    line:     List[ValidationError]
  ): List[SummaryEntry[Field]] =
    perInput
      .flatMap((field, refusals) => refusals.map(field -> _))
      .distinctBy((_, refusal) => refusal)
      .map((field, refusal) => SummaryEntry(Some(field), ValidationMessages.render(refusal.problem))) ++
      line.map(refusal => SummaryEntry(None, ValidationMessages.render(refusal.problem)))

/** One refusal in a form's summary list: the sentence and the input it is
  * shown under, or none for the form's own line. */
final case class SummaryEntry[Field](field: Option[Field], message: String)


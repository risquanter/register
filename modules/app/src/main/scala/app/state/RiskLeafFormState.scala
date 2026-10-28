package app.state

import com.raquo.laminar.api.L.*
import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil, ValidationMessages, OccurrenceProbability}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef
import zio.prelude.Validation

/** Type-safe field identifiers for the risk leaf form. */
enum RiskLeafField:
  case Name, Parent, Probability, Percentiles, Quantiles, MinLoss, MaxLoss, Terms

/**
 * Reactive form state for creating a RiskLeaf.
 *
 * Features:
 * - Input filters: prevent invalid characters from being typed
 * - Reactive validation signals: auto-update on field changes (using ValidationUtil from common)
 * - Error display timing: controlled by showErrors flag (set on blur/submit)
 *
 * Validation rules use Iron types from common module (same as backend):
 * - SafeName: non-blank, max 50 chars
 * - Probability: 0 ≤ p ≤ 1 (closed interval), entered on the 0–100 scale
 * - Metalog mode: requires percentiles + quantiles of equal length
 * - Lognormal mode: requires minLoss < maxLoss (both positive)
 *
 * Every check reads a located accessor, so a refusal names the field its value
 * came from and the subject `subjectVar` holds.
 */
final class RiskLeafFormState extends FormState[RiskLeafField]:
  import RiskLeafField.*

  // ============================================================
  // Form Field Vars (mutable reactive state)
  // ============================================================

  /** Distribution mode toggle */
  val distributionModeVar: Var[DistributionMode] = Var(DistributionMode.Metalog)

  // Common fields
  val nameVar: Var[String] = Var("")
  val probabilityVar: Var[String] = Var("")

  /** Parent selection. Not reset by [[resetFields]] — callers explicitly set
    * it to `ParentSelection.Unset` at clear/reset call sites (see
    * [[app.views.RiskLeafFormView]]); also auto-corrected to `Unset` by
    * [[app.components.FormInputs.parentSelect]] if the option list moves out
    * from under an already-chosen value.
    */
  val parentVar: Var[ParentSelection] = Var(ParentSelection.Unset)

  // Metalog mode fields
  val percentilesVar: Var[String] = Var("")  // Placeholder text "e.g., 10, 50, 90" is on the input element
  val quantilesVar: Var[String] = Var("")              // User must provide quantiles

  // Lognormal mode fields
  val minLossVar: Var[String] = Var("")
  val maxLossVar: Var[String] = Var("")

  // Metalog terms (metalog mode only; blank = one term per anchor)
  val termsVar: Var[String] = Var("")

  /** The node this form's refusals are about. Set when the form is opened:
    * `DraftNode` for a new leaf, `ExistingNode(id)` for one the server has
    * stored. Like `parentVar`, it is not reset by [[resetFields]] — the view
    * sets it at each call site that opens or clears the form. */
  val subjectVar: Var[ErrorSubject] = Var(ErrorSubject.DraftNode)
  private def subject: StrictSignal[ErrorSubject] = subjectVar.signal

  /** Reset all form fields and error display to initial state. */
  def resetFields(): Unit =
    nameVar.set("")
    probabilityVar.set("")
    distributionModeVar.set(DistributionMode.Metalog)
    percentilesVar.set("")
    quantilesVar.set("")
    minLossVar.set("")
    maxLossVar.set("")
    termsVar.set("")
    resetTouched()

  // ============================================================
  // Input Filters (prevent invalid characters - UX only)
  // ============================================================

  /** Filter for name field: any printable characters */
  val nameFilter: String => Boolean = _ => true

  /** Filter for probability field: digits and single decimal point, max 2 dp.
    * Enforced at the character level so the user cannot type a third decimal place. */
  val probabilityFilter: String => Boolean = { s =>
    val hasSingleDot = s.count(_ == '.') <= 1
    val allValidChars = s.forall(c => c.isDigit || c == '.')
    val maxTwoDp = s.indexOf('.') match
      case -1  => true
      case idx => s.length - idx - 1 <= 2
    hasSingleDot && allValidChars && maxTwoDp
  }

  /** Filter for percentile field: digits, commas, and spaces only.
    * Percentiles are entered as integers (0 dp); decimal points are rejected
    * per decision-analysis convention (Cooke, SHELF, Keelin 2016). */
  val percentilesFilter: String => Boolean =
    _.forall(c => c.isDigit || c == ',' || c == ' ')

  /** Filter for quantiles field: digits, commas, spaces, and decimal points.
    * Prevents consecutive dots (guards against "1..2" typos). Max 2 dp is
    * enforced at validation time, not at the filter level, because the filter
    * operates on the whole comma-separated string. */
  val quantilesFilter: String => Boolean = { s =>
    val validChars = s.forall(c => c.isDigit || c == ',' || c == ' ' || c == '.')
    val noConsecutiveDots = !s.contains("..")
    validChars && noConsecutiveDots
  }

  /** Filter for loss values: digits only */
  val lossFilter: String => Boolean = _.forall(_.isDigit)

  // ============================================================
  // Located Accessors
  // ============================================================

  /** The field a refusal about each input names. Declared once; the located
    * readers refer to these and no other site in this form names a field.
    * The two pair fields name a rule over two inputs rather than either one. */
  private val nameField: FieldName        = FieldName.Name
  private val parentField: FieldName      = FieldName.ParentName
  private val probabilityField: FieldName = FieldName.Probability
  private val percentilesField: FieldName = FieldName.Percentiles
  private val quantilesField: FieldName   = FieldName.Quantiles
  private val minLossField: FieldName     = FieldName.MinLoss
  private val maxLossField: FieldName     = FieldName.MaxLoss
  private val termsField: FieldName       = FieldName.Terms
  private val pairingField: FieldName     = FieldName.PercentileQuantilePairing
  private val lossRangeField: FieldName   = FieldName.LossRange

  /** An input's live value, located at `field` of the current subject. The
    * only reader the checks use; the input `Var`s are read raw by nothing but
    * their input bindings. */
  private def located[A](input: Var[A], field: FieldName): Signal[Located[A]] =
    input.signal.combineWith(subject).map((v, s) => Located(v, ErrorLocation.Field(s, field)))

  /** An input's current value, located, for the submit read. */
  private def locatedNow[A](input: Var[A], field: FieldName): Located[A] =
    Located(input.now(), ErrorLocation.Field(subjectVar.now(), field))

  /** The first refusal a refinement reported, or none if it accepted. */
  private def firstRefusal[B](result: Either[List[ValidationError], B]): Option[ValidationError] =
    result.left.toOption.flatMap(_.headOption)

  /** A located check's sentence, for the display signals and `errorSignals`,
    * which still carry text. */
  private def messageOf(check: Signal[Option[ValidationError]]): Signal[Option[String]] =
    check.map(_.map(_.message))

  // ============================================================
  // Raw Validation Signals (using ValidationUtil from common)
  // ============================================================

  /** Name validation using Iron SafeName rules */
  private val nameErrorRaw: Signal[Option[ValidationError]] =
    located(nameVar, nameField).map(input => firstRefusal(input.refine(ValidationUtil.refineName)))

  /** Probability validation. The field is entered on the 0–100 (percent) scale
    * without the `%` sign; it is divided by 100 before being refined against the
    * Iron `OccurrenceProbability` type (closed interval 0 ≤ p ≤ 1). The refusal
    * shown is therefore expressed on the entered 0–100 scale, not the internal
    * 0–1 scale. */
  private val probabilityErrorRaw: Signal[Option[ValidationError]] =
    located(probabilityVar, probabilityField).map { input =>
      if input.value.isBlank then
        Some(input.reject(ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.probabilityRequired))
      else parseDouble(input.value) match
        case None =>
          Some(input.reject(ValidationErrorCode.INVALID_FORMAT, ValidationMessages.probabilityNotANumber))
        case Some(pct) =>
          input.as(RiskLeafFormState.pctToDomain(pct))
            .refine(ValidationUtil.refineOccurrenceProbability)
            .fold(
              _ => Some(input.reject(ValidationErrorCode.INVALID_RANGE, ValidationMessages.probabilityPercentOutOfRange)),
              _ => None
            )
    }

  /** Metalog mode: percentiles validation (0-100, but Metalog needs 0 < p < 100) */
  private val percentilesErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(percentilesVar, percentilesField)).map {
      case (DistributionMode.Metalog, input) =>
        val values = parseDoubleList(input.value)
        if input.value.isBlank then
          Some(input.reject(ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.percentilesRequired))
        else if values.isEmpty then
          Some(input.reject(ValidationErrorCode.INVALID_FORMAT, ValidationMessages.percentilesFormat))
        else if values.exists(p => p <= 0 || p >= 100) then
          Some(input.reject(ValidationErrorCode.INVALID_RANGE, ValidationMessages.percentilesOutOfRange))
        else if !ValidationUtil.isStrictlyIncreasing(values) then
          Some(input.reject(ValidationErrorCode.INVALID_COMBINATION, ValidationMessages.percentilesMustBeStrictlyIncreasing))
        else None
      case _ => None
    }

  /** Metalog mode: quantiles validation (positive loss amounts) */
  private val quantilesErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(quantilesVar, quantilesField)).map {
      case (DistributionMode.Metalog, input) =>
        val values = parseDoubleList(input.value)
        if input.value.isBlank then
          Some(input.reject(ValidationErrorCode.REQUIRED_FIELD, ValidationMessages.quantilesRequired))
        else if values.isEmpty then
          Some(input.reject(ValidationErrorCode.INVALID_FORMAT, ValidationMessages.quantilesFormat))
        else if values.exists(_ <= 0) then
          Some(input.reject(ValidationErrorCode.INVALID_RANGE, ValidationMessages.quantilesMustBePositive))
        else if !ValidationUtil.isStrictlyIncreasing(values) then
          Some(input.reject(ValidationErrorCode.INVALID_COMBINATION, ValidationMessages.quantilesMustBeStrictlyIncreasing))
        else None
      case _ => None
    }

  /** Metalog mode: cross-field validation (equal length). Located at the pair,
    * as the shared shape factory reports the same rule. */
  private val metalogCrossFieldErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal
      .combineWith(percentilesVar.signal, quantilesVar.signal, subject)
      .map {
        case (DistributionMode.Metalog, pStr, qStr, s) =>
          val pair  = Located((pStr, qStr), ErrorLocation.Field(s, pairingField))
          val pList = parseDoubleList(pStr)
          val qList = parseDoubleList(qStr)
          Option.when(pList.nonEmpty && qList.nonEmpty && pList.length != qList.length)(
            pair.reject(
              ValidationErrorCode.INVALID_COMBINATION,
              ValidationMessages.metalogLengthMismatch(pList.length, qList.length)
            )
          )
        case _ => None
      }

  /** Lognormal mode: minLoss validation using Iron PositiveLong */
  private val minLossErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(minLossVar, minLossField)).map {
      case (DistributionMode.Lognormal, input) =>
        boundError(input, ValidationMessages.minLossRequired, ValidationMessages.minLossNotWholeNumber)
      case _                                   => None
    }

  /** Lognormal mode: maxLoss validation using Iron PositiveLong */
  private val maxLossErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(maxLossVar, maxLossField)).map {
      case (DistributionMode.Lognormal, input) =>
        boundError(input, ValidationMessages.maxLossRequired, ValidationMessages.maxLossNotWholeNumber)
      case _                                   => None
    }

  /** One lognormal bound's own check: present, a whole number, and positive. */
  private def boundError(input: Located[String], required: String, notWhole: String): Option[ValidationError] =
    if input.value.isBlank then Some(input.reject(ValidationErrorCode.REQUIRED_FIELD, required))
    else parseLong(input.value) match
      case None    => Some(input.reject(ValidationErrorCode.INVALID_FORMAT, notWhole))
      case Some(n) => firstRefusal(input.as(n).refine(ValidationUtil.refinePositiveLong))

  /** Metalog mode: terms validation — blank means one term per anchor; otherwise integer in [2, n] */
  private val termsErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal.combineWith(located(termsVar, termsField), percentilesVar.signal).map {
      case (DistributionMode.Metalog, input, pStr) =>
        if input.value.isBlank then None
        else input.value.toIntOption match
          case None    => Some(input.reject(ValidationErrorCode.INVALID_FORMAT, ValidationMessages.termsMustBeWholeNumber))
          case Some(t) =>
            val n = pStr.split(",").count(_.trim.nonEmpty)
            if t < 2 || t > n then Some(input.reject(ValidationErrorCode.INVALID_RANGE, ValidationMessages.termsOutOfRange))
            else None
      case _ => None
    }

  /** Lognormal mode: cross-field validation (minLoss < maxLoss). Located at
    * the pair, as the shared shape factory reports the same rule. */
  private val lognormalCrossFieldErrorRaw: Signal[Option[ValidationError]] =
    distributionModeVar.signal
      .combineWith(minLossVar.signal, maxLossVar.signal, subject)
      .map {
        case (DistributionMode.Lognormal, minStr, maxStr, s) =>
          val pair = Located((minStr, maxStr), ErrorLocation.Field(s, lossRangeField))
          (parseLong(minStr), parseLong(maxStr)) match
            case (Some(min), Some(max)) if min >= max =>
              Some(pair.reject(ValidationErrorCode.INVALID_RANGE, ValidationMessages.minMustBeLessThanMax))
            case _ => None
        case _ => None
      }

  // ============================================================
  // Display-controlled Error Signals (for UI binding)
  // ============================================================

  val nameError: Signal[Option[String]] = withSubmitErrors(Name, messageOf(nameErrorRaw))

  /** Parent has no continuous reactive validation of its own (unlike name) —
    * only submit-time errors (`parentDraft` returning `None`, or a topology
    * error from the server) ever populate this, via `setSubmitFieldError`. */
  val parentError: Signal[Option[String]] = withSubmitErrors(Parent, Val(None))
  val probabilityError: Signal[Option[String]] = withSubmitErrors(Probability, messageOf(probabilityErrorRaw))

  // Cross-field errors use a content-presence gate, NOT the per-field touch gate.
  //
  // Problem with merging the cross-field raw error into each field's withSubmitErrors:
  //   withSubmitErrors(Percentiles, ownRaw.orElse(crossRaw))
  // gates the WHOLE combined signal behind "Percentiles was touched". So when the user
  // enters 3 percentiles (blurs, touched) then starts typing 1 quantile (not yet blurred),
  // the cross-field "lengths must match" error only appears under Percentiles — the stable,
  // completed field — while the user is still editing Quantiles. Reversal also possible.
  //
  // Correct model: the cross-field constraint is evaluable as soon as BOTH fields have
  // non-empty content. Its visibility gate must be:
  //   showAll (submit triggered) || (pField.nonEmpty && qField.nonEmpty)
  //
  // Own-field errors continue to use the per-field touch gate via withSubmitErrors.
  // The final per-field signal is: own-error (touch-gated) orElse cross-error (content-gated).

  // Track which field in each cross-field pair was most recently edited.
  // Starts with an arbitrary default (true) which is irrelevant until both fields
  // have content — the bothFilled gate suppresses the error until then.
  private val lastMetalogEdited: Signal[Boolean] =  // true = percentiles last, false = quantiles last
    EventStream
      .merge(
        percentilesVar.signal.changes.mapTo(true),
        quantilesVar.signal.changes.mapTo(false)
      )
      .toSignal(true)

  private val lastLognormalEdited: Signal[Boolean] = // true = minLoss last, false = maxLoss last
    EventStream
      .merge(
        minLossVar.signal.changes.mapTo(true),
        maxLossVar.signal.changes.mapTo(false)
      )
      .toSignal(true)

  /** Cross-field error gated to show only under the most-recently-edited field of the pair.
    *
    * `isThisFieldLast` is true when this field (not its partner) was the last edited.
    * Gate: error is shown when both fields have content AND this was last edited,
    * OR when form-wide validation has been triggered (submit).
    */
  private def crossFieldGated(
    thisField:       RiskLeafField,
    fieldA:          Var[String],
    fieldB:          Var[String],
    raw:             Signal[Option[String]],
    isThisFieldLast: Signal[Boolean]
  ): Signal[Option[String]] =
    val bothFilled =
      fieldA.signal.map(_.nonEmpty)
        .combineWith(fieldB.signal.map(_.nonEmpty))
        .map { (a, b) => a && b }
    // Gate: show when form-wide errors are forced (submit), or when this specific
    // field has been blurred AND was the last one edited AND both fields have content.
    // isTouched prevents firing on every keystroke; isThisFieldLast pins the error
    // to only one of the two fields at a time.
    showErrorsSignal
      .combineWith(isTouched(thisField), bothFilled, isThisFieldLast)
      .map { (showAll, touched, filled, isLast) => showAll || (touched && isLast && filled) }
      .combineWith(raw)
      .map { (show, err) => if show then err else None }

  private val metalogCrossFieldGated_Pct: Signal[Option[String]] =
    crossFieldGated(Percentiles, percentilesVar, quantilesVar, messageOf(metalogCrossFieldErrorRaw), lastMetalogEdited)

  private val metalogCrossFieldGated_Qt: Signal[Option[String]] =
    crossFieldGated(Quantiles, percentilesVar, quantilesVar, messageOf(metalogCrossFieldErrorRaw), lastMetalogEdited.map(!_))

  private val lognormalCrossFieldGated_Min: Signal[Option[String]] =
    crossFieldGated(MinLoss, minLossVar, maxLossVar, messageOf(lognormalCrossFieldErrorRaw), lastLognormalEdited)

  private val lognormalCrossFieldGated_Max: Signal[Option[String]] =
    crossFieldGated(MaxLoss, minLossVar, maxLossVar, messageOf(lognormalCrossFieldErrorRaw), lastLognormalEdited.map(!_))

  /** Percentiles: own error (touch-gated) ∪ metalog cross-field (last-edited-gated) */
  val percentilesError: Signal[Option[String]] =
    withSubmitErrors(Percentiles, messageOf(percentilesErrorRaw))
      .combineWith(metalogCrossFieldGated_Pct)
      .map { (own, cross) => own.orElse(cross) }

  /** Quantiles: own error (touch-gated) ∪ metalog cross-field (last-edited-gated) */
  val quantilesError: Signal[Option[String]] =
    withSubmitErrors(Quantiles, messageOf(quantilesErrorRaw))
      .combineWith(metalogCrossFieldGated_Qt)
      .map { (own, cross) => own.orElse(cross) }

  /** Min loss: own error (touch-gated) ∪ lognormal cross-field (last-edited-gated) */
  val minLossError: Signal[Option[String]] =
    withSubmitErrors(MinLoss, messageOf(minLossErrorRaw))
      .combineWith(lognormalCrossFieldGated_Min)
      .map { (own, cross) => own.orElse(cross) }

  /** Max loss: own error (touch-gated) ∪ lognormal cross-field (last-edited-gated) */
  val maxLossError: Signal[Option[String]] =
    withSubmitErrors(MaxLoss, messageOf(maxLossErrorRaw))
      .combineWith(lognormalCrossFieldGated_Max)
      .map { (own, cross) => own.orElse(cross) }
  val termsError: Signal[Option[String]] = withSubmitErrors(Terms, messageOf(termsErrorRaw))

  // ============================================================
  // FormState Implementation
  // ============================================================

  /** Raw errors for hasErrors check (ignores display timing).
   *  Cross-field errors are already folded into the per-field composed signals,
   *  but we still include the standalone cross-field raws here so that
   *  `hasErrors` / `isValid` catches them even when neither field has an own error. */
  override def errorSignals: List[Signal[Option[String]]] = List(
    nameErrorRaw,
    probabilityErrorRaw,
    percentilesErrorRaw,
    quantilesErrorRaw,
    metalogCrossFieldErrorRaw,
    termsErrorRaw,
    minLossErrorRaw,
    maxLossErrorRaw,
    lognormalCrossFieldErrorRaw
  ).map(messageOf)

  // ============================================================
  // Form Submission
  // ============================================================

  /** Check if form is valid (for submit button) */
  val isValid: Signal[Boolean] = hasErrors.map(!_)

  /** The form's text fields parsed, zipped and gated into one shape. A pure
    * function of its arguments, so the live preview and the submit build the
    * shape the same way from where each reads its values.
    *
    * The mode selects which case is built, so the fields of the other mode are
    * not read at all and cannot reach the shape. For metalog mode the zip is
    * where a percentile count unequal to the loss count is refused, which is the
    * only place in the browser that rule can be stated.
    *
    * Percentiles from the form are on the 0–100 scale and are converted to 0–1
    * before gating.
    *
    * An entry that is not a number is dropped rather than refused, so only the
    * surviving entries reach the gate and only their count is checked. A field
    * of `0.1, abc, 0.9` therefore arrives as two percentiles, and pairs
    * successfully with a two-entry loss field.
    */
  private def shapeFrom(
    mode:        DistributionMode,
    percentiles: String,
    quantiles:   String,
    terms:       String,
    minLoss:     Located[String],
    maxLoss:     Located[String],
    subject:     ErrorSubject
  ): Validation[ValidationError, DistributionShape] =
    mode match
      case DistributionMode.Metalog =>
        DistributionShape.Metalog.fromPrimitives(
          parseDoubleList(percentiles).map(RiskLeafFormState.pctToDomain).toVector,
          parseDoubleList(quantiles).toVector,
          if terms.isBlank then None else terms.toIntOption,
          subject
        )

      case DistributionMode.Lognormal =>
        Validation
          .validateWith(
            requireBound(minLoss, ValidationMessages.minLossRequired),
            requireBound(maxLoss, ValidationMessages.maxLossRequired)
          )((min, max) => (min, max))
          .flatMap { case (min, max) =>
            DistributionShape.Lognormal.fromPrimitives(min, max, subject)
          }

  /** The shape the fields currently hold, read as a snapshot for the submit. */
  def currentShapeValidation(): Validation[ValidationError, DistributionShape] =
    shapeFrom(
      distributionModeVar.now(),
      percentilesVar.now(),
      quantilesVar.now(),
      termsVar.now(),
      locatedNow(minLossVar, minLossField),
      locatedNow(maxLossVar, maxLossField),
      subjectVar.now()
    )

  /** A lognormal bound that is absent, blank, or not a whole number. All three
    * reach this as unparseable and are reported as the field being required, so
    * the inline field signal is where a non-numeric entry is named as such.
    * Reported per field, so both bounds surface at once. */
  private def requireBound(bound: Located[String], message: String): Validation[ValidationError, Long] =
    parseLong(bound.value) match
      case Some(value) => Validation.succeed(value)
      case None        => Validation.fail(bound.reject(ValidationErrorCode.REQUIRED_FIELD, message))

  /** Every value a submission needs, refined, with every field's refusal
    * accumulated rather than only the first.
    *
    * This is the form's one refinement boundary: the `Var`s above hold what the
    * user is typing, and everything past this point is a refined type. Each
    * failure names the field it came from, so the view routes it to that
    * field's inline slot and the banner stays empty.
    */
  def toDraft: Validation[ValidationError, (SafeName.SafeName, ParentRef, OccurrenceProbability, DistributionShape)] =
    val nameV = ValidationUtil.toValidation(locatedNow(nameVar, nameField).refine(ValidationUtil.refineName))
    val parentV = ParentSelection.toParentRef(locatedNow(parentVar, parentField))
    val probability = locatedNow(probabilityVar, probabilityField)
    val probabilityV = probability.value.toDoubleOption match
      case None =>
        Validation.fail(probability.reject(ValidationErrorCode.INVALID_FORMAT, ValidationMessages.probabilityNotANumber))
      case Some(pct) =>
        // The field is entered on the 0–100 scale; convert before refining.
        ValidationUtil.toValidation(
          probability.as(RiskLeafFormState.pctToDomain(pct)).refine(ValidationUtil.refineOccurrenceProbability)
        )
    Validation.validateWith(nameV, parentV, probabilityV, currentShapeValidation()) {
      (name, parent, probability, shape) => (name, parent, probability, shape)
    }

  /** Reactive signal of the current shape-only draft.
    *
    * Emits `Some(shape)` when the mode's own fields gate into a
    * [[DistributionShape]], `None` when incomplete or invalid. Does not include
    * probability — that is a leaf-level field separate from the shape. Built
    * from the values the signals carry, so it reads no snapshot.
    *
    * Used by [[app.state.DistributionChartState]] to trigger debounced preview fetches.
    */
  val draftSignal: Signal[Option[DistributionShape]] =
    distributionModeVar.signal
      .combineWith(percentilesVar.signal, quantilesVar.signal, termsVar.signal)
      .combineWith(located(minLossVar, minLossField), located(maxLossVar, maxLossField), subject)
      .map { (mode, percentiles, quantiles, terms, minLoss, maxLoss, s) =>
        shapeFrom(mode, percentiles, quantiles, terms, minLoss, maxLoss, s).toOption
      }

  /** Signal emitting an advisory warning when the implied P90/P10 loss ratio is
    * unusually high (> 100×), suggesting the expert estimates encode extreme tail
    * behaviour. Visible only in metalog mode; None in lognormal mode or when
    * insufficient data is available to compute the ratio.
    */
  val impliedRatioWarning: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(quantilesVar.signal, percentilesVar.signal).map {
      case (DistributionMode.Metalog, qStr, pStr) =>
        val qs = qStr.split(",").flatMap(_.trim.toDoubleOption)
        val ps = pStr.split(",").flatMap(_.trim.toDoubleOption)
        if qs.length >= 2 && qs.length == ps.length then
          val p10idx = ps.indexWhere(_ <= 10)
          val p90idx = ps.lastIndexWhere(_ >= 90)
          if p10idx >= 0 && p90idx >= 0 && p10idx != p90idx && qs(p10idx) > 0 then
            val ratio = qs(p90idx) / qs(p10idx)
            if ratio > 100 then Some(RiskLeafFormState.impliedRatioTooHigh(ratio))
            else None
          else None
        else None
      case _ => None
    }


object RiskLeafFormState:
  /** Convert a 0–100 percent-scale value (as entered in the form) to the
    * 0–1 domain scale used in [[DistributionShape]].
    *
    * Used for scalar probability (`pctToDomain(pct)`) and per-element
    * percentile arrays (`arr.map(pctToDomain)`).
    */
  def pctToDomain(pct: Double): Double = pct / 100.0

  /** Convert a 0–1 domain value to its 0–100 display string — the shared
    * [[app.core.NumberFormat.percentValue]] under this form-facing name. */
  def domainToDisplayPct(p: Double, decimals: Int): String =
    app.core.NumberFormat.percentValue(p, decimals)

  /** The advisory shown when the implied P90/P10 loss ratio exceeds 100×.
    *
    * {{{
    * impliedRatioTooHigh(250.0)
    * // P90/P10 ratio is 250× — very high. Review whether this reflects expert judgment.
    * }}}
    */
  def impliedRatioTooHigh(ratio: Double): String =
    f"P90/P10 ratio is $ratio%.0f× — very high. Review whether this reflects expert judgment."

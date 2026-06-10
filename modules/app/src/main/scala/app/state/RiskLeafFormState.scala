package app.state

import com.raquo.laminar.api.L.{*, given}
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.{ValidationUtil, ValidationMessages, OccurrenceProbability}
import com.risquanter.register.domain.errors.ValidationError
import zio.prelude.Validation

/** Type-safe field identifiers for the risk leaf form. */
enum RiskLeafField:
  case Name, Probability, Percentiles, Quantiles, MinLoss, MaxLoss, Terms

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
 * - Probability: 0.0 < p < 1.0 (exclusive - open interval)
 * - Expert mode: requires percentiles + quantiles of equal length
 * - Lognormal mode: requires minLoss < maxLoss (both non-negative)
 */
final class RiskLeafFormState extends FormState[RiskLeafField]:
  import RiskLeafField.*

  // ============================================================
  // Form Field Vars (mutable reactive state)
  // ============================================================
  
  /** Distribution mode toggle */
  val distributionModeVar: Var[DistributionMode] = Var(DistributionMode.Expert)
  
  // Common fields
  val nameVar: Var[String] = Var("")
  val probabilityVar: Var[String] = Var("")

  /** Parent selection — None means root. Not reset by [[resetFields]]; auto-synced by
    * [[app.components.FormInputs.parentSelect]] based on available options.
    */
  val parentVar: Var[Option[String]] = Var(None)
  
  // Expert mode fields
  val percentilesVar: Var[String] = Var("")  // Placeholder text "e.g., 10, 50, 90" is on the input element
  val quantilesVar: Var[String] = Var("")              // User must provide quantiles
  
  // Lognormal mode fields
  val minLossVar: Var[String] = Var("")
  val maxLossVar: Var[String] = Var("")

  // Metalog terms (expert mode only; blank = server applies min(n, 4) default)
  val termsVar: Var[String] = Var("")

  /** Reset all form fields and error display to initial state. */
  def resetFields(): Unit =
    nameVar.set("")
    probabilityVar.set("")
    distributionModeVar.set(DistributionMode.Expert)
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
  // Raw Validation Signals (using ValidationUtil from common)
  // ============================================================
  
  /** Name validation using Iron SafeName rules */
  private val nameErrorRaw: Signal[Option[String]] = nameVar.signal.map { v =>
    ValidationUtil.refineName(v) match
      case Right(_) => None
      case Left(errors) => Some(errors.head.message)
  }
  
  /** Probability validation. The field is entered on the 0–100 (percent) scale
    * without the `%` sign; it is divided by 100 before being refined against the
    * Iron `OccurrenceProbability` type (closed interval 0 ≤ p ≤ 1). The error message is
    * therefore expressed on the entered 0–100 scale, not the internal 0–1 scale. */
  private val probabilityErrorRaw: Signal[Option[String]] = probabilityVar.signal.map { v =>
    if v.isBlank then Some(ValidationMessages.probabilityRequired)
    else this.parseDouble(v) match
      case None => Some(ValidationMessages.probabilityNotANumber)
      case Some(pct) =>
        ValidationUtil.refineOccurrenceProbability(RiskLeafFormState.pctToDomain(pct)) match
          case Right(_) => None
          case Left(_)  => Some("Probability must be between 0 and 100 (inclusive)")
  }
  
  /** Expert mode: percentiles validation (0-100, but Metalog needs 0 < p < 100) */
  private val percentilesErrorRaw: Signal[Option[String]] = 
    distributionModeVar.signal.combineWith(percentilesVar.signal).map {
      case (DistributionMode.Expert, v) =>
        if v.isBlank then Some(ValidationMessages.percentilesRequired)
        else
          val values = parseDoubleList(v)
          if values.isEmpty then Some(ValidationMessages.percentilesFormat)
          else if values.exists(p => p <= 0 || p >= 100) then 
            Some(ValidationMessages.percentilesOutOfRange)
          else if !ValidationUtil.isStrictlyIncreasing(values) then
            Some(ValidationMessages.percentilesMustBeStrictlyIncreasing)
          else None
      case _ => None
    }
  
  /** Expert mode: quantiles validation (non-negative loss amounts) */
  private val quantilesErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(quantilesVar.signal).map {
      case (DistributionMode.Expert, v) =>
        if v.isBlank then Some(ValidationMessages.quantilesRequired)
        else
          val values = parseDoubleList(v)
          if values.isEmpty then Some(ValidationMessages.quantilesFormat)
          else if values.exists(_ < 0) then Some(ValidationMessages.quantilesMustBeNonNegative)
          else if !ValidationUtil.isStrictlyIncreasing(values) then
            Some(ValidationMessages.quantilesMustBeStrictlyIncreasing)
          else None
      case _ => None
    }
  
  /** Expert mode: cross-field validation (equal length) */
  private val expertCrossFieldErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal
      .combineWith(percentilesVar.signal, quantilesVar.signal)
      .map {
        case (DistributionMode.Expert, pStr, qStr) =>
          val pList = parseDoubleList(pStr)
          val qList = parseDoubleList(qStr)
          if pList.nonEmpty && qList.nonEmpty && pList.length != qList.length then
            Some(ValidationMessages.expertLengthMismatch(pList.length, qList.length))
          else None
        case _ => None
      }
  
  /** Lognormal mode: minLoss validation using Iron NonNegativeLong */
  private val minLossErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(minLossVar.signal).map {
      case (DistributionMode.Lognormal, v) =>
        if v.isBlank then Some(ValidationMessages.minLossRequired)
        else parseLong(v) match
          case None => Some(ValidationMessages.lossMustBeWholeNumber("Minimum loss"))
          case Some(n) => 
            ValidationUtil.refineNonNegativeLong(n, "minLoss") match
              case Right(_) => None
              case Left(errors) => Some(errors.head.message)
      case _ => None
    }
  
  /** Lognormal mode: maxLoss validation using Iron NonNegativeLong */
  private val maxLossErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(maxLossVar.signal).map {
      case (DistributionMode.Lognormal, v) =>
        if v.isBlank then Some(ValidationMessages.maxLossRequired)
        else parseLong(v) match
          case None => Some(ValidationMessages.lossMustBeWholeNumber("Maximum loss"))
          case Some(n) =>
            ValidationUtil.refineNonNegativeLong(n, "maxLoss") match
              case Right(_) => None
              case Left(errors) => Some(errors.head.message)
      case _ => None
    }
  
  /** Expert mode: terms validation — blank means use server default; otherwise integer in [2, n] */
  private val termsErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(termsVar.signal, percentilesVar.signal).map {
      case (DistributionMode.Expert, tStr, pStr) =>
        if tStr.isBlank then None
        else tStr.toIntOption match
          case None    => Some("Terms must be a whole number")
          case Some(t) =>
            val n = pStr.split(",").count(_.trim.nonEmpty)
            if t < 2 || t > n then Some(ValidationMessages.termsOutOfRange)
            else None
      case _ => None
    }

  /** Lognormal mode: cross-field validation (minLoss < maxLoss) */
  private val lognormalCrossFieldErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal
      .combineWith(minLossVar.signal, maxLossVar.signal)
      .map {
        case (DistributionMode.Lognormal, minStr, maxStr) =>
          (parseLong(minStr), parseLong(maxStr)) match
            case (Some(min), Some(max)) if min >= max =>
              Some(ValidationMessages.minMustBeLessThanMax)
            case _ => None
        case _ => None
      }

  // ============================================================
  // Display-controlled Error Signals (for UI binding)
  // ============================================================
  
  val nameError: Signal[Option[String]] = withSubmitErrors(Name, nameErrorRaw)
  val probabilityError: Signal[Option[String]] = withSubmitErrors(Probability, probabilityErrorRaw)

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
  private val lastExpertEdited: Signal[Boolean] =  // true = percentiles last, false = quantiles last
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

  private val expertCrossFieldGated_Pct: Signal[Option[String]] =
    crossFieldGated(Percentiles, percentilesVar, quantilesVar, expertCrossFieldErrorRaw, lastExpertEdited)

  private val expertCrossFieldGated_Qt: Signal[Option[String]] =
    crossFieldGated(Quantiles, percentilesVar, quantilesVar, expertCrossFieldErrorRaw, lastExpertEdited.map(!_))

  private val lognormalCrossFieldGated_Min: Signal[Option[String]] =
    crossFieldGated(MinLoss, minLossVar, maxLossVar, lognormalCrossFieldErrorRaw, lastLognormalEdited)

  private val lognormalCrossFieldGated_Max: Signal[Option[String]] =
    crossFieldGated(MaxLoss, minLossVar, maxLossVar, lognormalCrossFieldErrorRaw, lastLognormalEdited.map(!_))

  /** Percentiles: own error (touch-gated) ∪ expert cross-field (last-edited-gated) */
  val percentilesError: Signal[Option[String]] =
    withSubmitErrors(Percentiles, percentilesErrorRaw)
      .combineWith(expertCrossFieldGated_Pct)
      .map { (own, cross) => own.orElse(cross) }

  /** Quantiles: own error (touch-gated) ∪ expert cross-field (last-edited-gated) */
  val quantilesError: Signal[Option[String]] =
    withSubmitErrors(Quantiles, quantilesErrorRaw)
      .combineWith(expertCrossFieldGated_Qt)
      .map { (own, cross) => own.orElse(cross) }

  /** Min loss: own error (touch-gated) ∪ lognormal cross-field (last-edited-gated) */
  val minLossError: Signal[Option[String]] =
    withSubmitErrors(MinLoss, minLossErrorRaw)
      .combineWith(lognormalCrossFieldGated_Min)
      .map { (own, cross) => own.orElse(cross) }

  /** Max loss: own error (touch-gated) ∪ lognormal cross-field (last-edited-gated) */
  val maxLossError: Signal[Option[String]] =
    withSubmitErrors(MaxLoss, maxLossErrorRaw)
      .combineWith(lognormalCrossFieldGated_Max)
      .map { (own, cross) => own.orElse(cross) }
  val termsError: Signal[Option[String]] = withSubmitErrors(Terms, termsErrorRaw)

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
    expertCrossFieldErrorRaw,
    termsErrorRaw,
    minLossErrorRaw,
    maxLossErrorRaw,
    lognormalCrossFieldErrorRaw
  )

  // ============================================================
  // Form Submission
  // ============================================================
  
  /** Check if form is valid (for submit button) */
  val isValid: Signal[Boolean] = hasErrors.map(!_)

  /** Build the current shape-only Distribution from form fields.
    *
    * Reads all distribution-related Vars via `.now()`. Returns `Some(dist)` when
    * the current field values produce a valid [[Distribution]] (cross-field rules
    * pass), `None` when incomplete or invalid.
    *
    * Percentiles from the form are in 0–100 scale; converted to 0–1 before
    * passing to [[Distribution.create]].
    */
  def currentShapeValidation(): Validation[ValidationError, Distribution] =
    val mode = distributionModeVar.now()
    val minLossOpt = mode match
      case DistributionMode.Lognormal => parseLong(minLossVar.now())
      case _                          => None
    val maxLossOpt = mode match
      case DistributionMode.Lognormal => parseLong(maxLossVar.now())
      case _                          => None
    val pcts = mode match
      // UI uses 0-100 scale; domain model requires 0-1 scale
      case DistributionMode.Expert => toArrayOpt(parseDoubleList(percentilesVar.now()).map(RiskLeafFormState.pctToDomain))
      case _                       => None
    val quants = mode match
      case DistributionMode.Expert => toArrayOpt(parseDoubleList(quantilesVar.now()))
      case _                       => None
    val termsOpt = if termsVar.now().isBlank then None else termsVar.now().toIntOption
    Distribution.create(
      distributionType = mode.toApiString,
      minLoss          = minLossOpt,
      maxLoss          = maxLossOpt,
      percentiles      = pcts,
      quantiles        = quants,
      fieldPrefix      = "leaf",
      terms            = termsOpt
    )

  private def currentShapeDraft(): Option[Distribution] =
    currentShapeValidation() match
      case Validation.Success(_, dist) => Some(dist)
      case _                           => None

  /** Refined occurrence probability from the current form value, or None if invalid.
    *
    * Called by [[app.views.RiskLeafFormView]] at submit time to pass a typed
    * `OccurrenceProbability` to [[TreeBuilderState.addLeaf]] alongside the shape `Distribution`.
    */
  def refinedProbability: Option[OccurrenceProbability] =
    probabilityVar.now().toDoubleOption.flatMap { pct =>
      // Field is entered on the 0–100 (percent) scale; convert to 0–1 before refining.
      ValidationUtil.refineOccurrenceProbability(RiskLeafFormState.pctToDomain(pct)).toOption
    }

  /** Reactive signal of the current shape-only distribution draft.
    *
    * Emits `Some(dist)` when the distribution fields produce a valid [[Distribution]]
    * (cross-field rules pass), `None` when incomplete or invalid.
    * Does not include probability — that is a leaf-level field separate from shape.
    *
    * Used by [[app.state.DistributionChartState]] to trigger debounced preview fetches.
    */
  val draftSignal: Signal[Option[Distribution]] =
    distributionModeVar.signal
      .combineWith(percentilesVar.signal, quantilesVar.signal, minLossVar.signal)
      .combineWith(maxLossVar.signal, termsVar.signal)
      .map(_ => currentShapeDraft())

  /** Signal emitting an advisory warning when the implied P90/P10 loss ratio is
    * unusually high (> 100×), suggesting the expert estimates encode extreme tail
    * behaviour. Visible only in expert mode; None in lognormal mode or when
    * insufficient data is available to compute the ratio.
    */
  val impliedRatioWarning: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(quantilesVar.signal, percentilesVar.signal).map {
      case (DistributionMode.Expert, qStr, pStr) =>
        val qs = qStr.split(",").flatMap(_.trim.toDoubleOption)
        val ps = pStr.split(",").flatMap(_.trim.toDoubleOption)
        if qs.length >= 2 && qs.length == ps.length then
          val p10idx = ps.indexWhere(_ <= 10)
          val p90idx = ps.lastIndexWhere(_ >= 90)
          if p10idx >= 0 && p90idx >= 0 && p10idx != p90idx && qs(p10idx) > 0 then
            val ratio = qs(p90idx) / qs(p10idx)
            if ratio > 100 then
              Some(f"P90/P10 ratio is $ratio%.0f\u00d7 \u2014 very high. Review whether this reflects expert judgment.")
            else None
          else None
        else None
      case _ => None
    }

  private def toArrayOpt(values: List[Double]): Option[Array[Double]] =
    if values.isEmpty then None else Some(values.toArray)


object RiskLeafFormState:
  /** Convert a 0–100 percent-scale value (as entered in the form) to the
    * 0–1 domain scale used in [[Distribution]].
    *
    * Used for scalar probability (`pctToDomain(pct)`) and per-element
    * percentile arrays (`arr.map(pctToDomain)`).
    */
  def pctToDomain(pct: Double): Double = pct / 100.0

  /** Convert a 0–1 domain value to its 0–100 display string, rounded to
    * `decimals` decimal places using half-up rounding.
    *
    * Uses [[BigDecimal]] to eliminate floating-point noise
    * (e.g., `0.1 * 100 = 10.000000000000001`).
    *
    * - `decimals = 0` → percentiles (integers: "10", "50", "90")
    * - `decimals = 2` → probability ("20.5" — trailing zeros stripped)
    */
  def domainToDisplayPct(p: Double, decimals: Int): String =
    BigDecimal(p * 100.0)
      .setScale(decimals, scala.math.BigDecimal.RoundingMode.HALF_UP)
      .underlying.stripTrailingZeros.toPlainString

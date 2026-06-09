package app.state

import com.raquo.laminar.api.L.{*, given}
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.{ValidationUtil, ValidationMessages, Probability}
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
  
  /** 
   * Filter for probability field: digits and single decimal point.
   * Prevents patterns like "0.2.5" or "1..2"
   */
  val probabilityFilter: String => Boolean = { s =>
    val hasSingleDot = s.count(_ == '.') <= 1
    val allValidChars = s.forall(c => c.isDigit || c == '.')
    hasSingleDot && allValidChars
  }
  
  /** 
   * Filter for percentiles/quantiles: digits, commas, spaces, decimal points.
   * Prevents consecutive dots.
   */
  val arrayFilter: String => Boolean = { s =>
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
  
  /** Probability validation using Iron Probability type (open interval 0 < p < 1) */
  private val probabilityErrorRaw: Signal[Option[String]] = probabilityVar.signal.map { v =>
    if v.isBlank then Some(ValidationMessages.probabilityRequired)
    else this.parseDouble(v) match
      case None => Some(ValidationMessages.probabilityNotANumber)
      case Some(prob) => 
        ValidationUtil.refineProbability(prob) match
          case Right(_) => None
          case Left(errs) => Some(errs.head.message)
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

  // Cross-field errors merged into per-field signals (BCA-style):
  // own-field error takes priority; cross-field error is the fallback.
  // Both fields get a red border when the cross-field constraint is violated.

  /** Percentiles: own error ∪ expert cross-field (length mismatch) */
  val percentilesError: Signal[Option[String]] = withSubmitErrors(Percentiles,
    percentilesErrorRaw.combineWith(expertCrossFieldErrorRaw).map { case (own, cross) => own.orElse(cross) }
  )
  /** Quantiles: own error ∪ expert cross-field (length mismatch) */
  val quantilesError: Signal[Option[String]] = withSubmitErrors(Quantiles,
    quantilesErrorRaw.combineWith(expertCrossFieldErrorRaw).map { case (own, cross) => own.orElse(cross) }
  )
  /** Min loss: own error ∪ lognormal cross-field (min ≥ max) */
  val minLossError: Signal[Option[String]] = withSubmitErrors(MinLoss,
    minLossErrorRaw.combineWith(lognormalCrossFieldErrorRaw).map { case (own, cross) => own.orElse(cross) }
  )
  /** Max loss: own error ∪ lognormal cross-field (min ≥ max) */
  val maxLossError: Signal[Option[String]] = withSubmitErrors(MaxLoss,
    maxLossErrorRaw.combineWith(lognormalCrossFieldErrorRaw).map { case (own, cross) => own.orElse(cross) }
  )
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
  private def currentShapeDraft(): Option[Distribution] =
    val mode = distributionModeVar.now()
    val minLossOpt = mode match
      case DistributionMode.Lognormal => parseLong(minLossVar.now())
      case _                          => None
    val maxLossOpt = mode match
      case DistributionMode.Lognormal => parseLong(maxLossVar.now())
      case _                          => None
    val pcts = mode match
      // UI uses 0-100 scale; domain model requires 0-1 scale
      case DistributionMode.Expert => toArrayOpt(parseDoubleList(percentilesVar.now()).map(_ / 100.0))
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
    ) match
      case Validation.Success(_, dist) => Some(dist)
      case _                           => None

  /** Refined occurrence probability from the current form value, or None if invalid.
    *
    * Called by [[app.views.RiskLeafFormView]] at submit time to pass a typed
    * `Probability` to [[TreeBuilderState.addLeaf]] alongside the shape `Distribution`.
    */
  def refinedProbability: Option[Probability] =
    probabilityVar.now().toDoubleOption.flatMap { p =>
      ValidationUtil.refineProbability(p).toOption
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



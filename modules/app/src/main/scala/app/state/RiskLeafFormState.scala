package app.state

import com.raquo.laminar.api.L.{*, given}
import com.risquanter.register.domain.data.iron.ValidationUtil
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import zio.prelude.Validation
import app.state.LeafDistributionDraft

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
class RiskLeafFormState extends FormState:

  // ============================================================
  // Error Display Timing Control
  // ============================================================
  
  /** Set to true after first submit attempt or field blur */
  val showErrorsVar: Var[Boolean] = Var(false)
  
  /** Per-field "touched" state for showing errors on blur */
  val touchedFields: Var[Set[String]] = Var(Set.empty)
  
  def markTouched(fieldName: String): Unit =
    touchedFields.update(_ + fieldName)
  
  def isTouched(fieldName: String): Signal[Boolean] =
    touchedFields.signal.map(_.contains(fieldName))
  
  /** Show error for a field if: showErrors is true OR field has been touched */
  def shouldShowError(fieldName: String): Signal[Boolean] =
    showErrorsVar.signal.combineWith(isTouched(fieldName)).map {
      case (showAll, touched) => showAll || touched
    }

  // ============================================================
  // Form Field Vars (mutable reactive state)
  // ============================================================
  
  /** Distribution mode toggle */
  val distributionModeVar: Var[DistributionMode] = Var(DistributionMode.Expert)
  
  // Common fields
  val nameVar: Var[String] = Var("")
  val probabilityVar: Var[String] = Var("")
  
  // Expert mode fields
  val percentilesVar: Var[String] = Var("10, 50, 90")  // Default expert percentiles
  val quantilesVar: Var[String] = Var("")              // User must provide quantiles
  
  // Lognormal mode fields
  val minLossVar: Var[String] = Var("")
  val maxLossVar: Var[String] = Var("")

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
    if v.isBlank then Some("Probability is required")
    else this.parseDouble(v) match
      case None => Some("Probability must be a number")
      case Some(prob) => 
        ValidationUtil.refineProbability(prob) match
          case Right(_) => None
          case Left(errs) => Some(errs.head.message)
  }
  
  /** Expert mode: percentiles validation (0-100, but Metalog needs 0 < p < 100) */
  private val percentilesErrorRaw: Signal[Option[String]] = 
    distributionModeVar.signal.combineWith(percentilesVar.signal).map {
      case (DistributionMode.Expert, v) =>
        if v.isBlank then Some("Percentiles are required for expert mode")
        else
          val values = parseDoubleList(v)
          if values.isEmpty then Some("Enter comma-separated percentile values")
          else if values.exists(p => p <= 0 || p >= 100) then 
            Some("Percentiles must be between 0 and 100 (exclusive)")
          else None
      case _ => None
    }
  
  /** Expert mode: quantiles validation (non-negative loss amounts) */
  private val quantilesErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(quantilesVar.signal).map {
      case (DistributionMode.Expert, v) =>
        if v.isBlank then Some("Quantiles are required for expert mode")
        else
          val values = parseDoubleList(v)
          if values.isEmpty then Some("Enter comma-separated quantile values (loss amounts)")
          else if values.exists(_ < 0) then Some("Quantiles must be non-negative")
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
            Some(s"Percentiles and quantiles must have same length (${pList.length} vs ${qList.length})")
          else None
        case _ => None
      }
  
  /** Lognormal mode: minLoss validation using Iron NonNegativeLong */
  private val minLossErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(minLossVar.signal).map {
      case (DistributionMode.Lognormal, v) =>
        if v.isBlank then Some("Minimum loss is required for lognormal mode")
        else parseLong(v) match
          case None => Some("Minimum loss must be a whole number")
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
        if v.isBlank then Some("Maximum loss is required for lognormal mode")
        else parseLong(v) match
          case None => Some("Maximum loss must be a whole number")
          case Some(n) =>
            ValidationUtil.refineNonNegativeLong(n, "maxLoss") match
              case Right(_) => None
              case Left(errors) => Some(errors.head.message)
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
              Some("Minimum loss must be less than maximum loss")
            case _ => None
        case _ => None
      }

  // ============================================================
  // Display-controlled Error Signals (for UI binding)
  // ============================================================
  
  /** Combine raw error with display timing */
  private def withDisplayControl(fieldName: String, rawError: Signal[Option[String]]): Signal[Option[String]] =
    shouldShowError(fieldName).combineWith(rawError).map {
      case (true, error) => error
      case (false, _) => None
    }
  
  val nameError: Signal[Option[String]] = withDisplayControl("name", nameErrorRaw)
  val probabilityError: Signal[Option[String]] = withDisplayControl("probability", probabilityErrorRaw)
  val percentilesError: Signal[Option[String]] = withDisplayControl("percentiles", percentilesErrorRaw)
  val quantilesError: Signal[Option[String]] = withDisplayControl("quantiles", quantilesErrorRaw)
  val expertCrossFieldError: Signal[Option[String]] = withDisplayControl("expertCrossField", expertCrossFieldErrorRaw)
  val minLossError: Signal[Option[String]] = withDisplayControl("minLoss", minLossErrorRaw)
  val maxLossError: Signal[Option[String]] = withDisplayControl("maxLoss", maxLossErrorRaw)
  val lognormalCrossFieldError: Signal[Option[String]] = withDisplayControl("lognormalCrossField", lognormalCrossFieldErrorRaw)

  // ============================================================
  // FormState Implementation
  // ============================================================
  
  /** Raw errors for hasErrors check (ignores display timing) */
  override def errorSignals: List[Signal[Option[String]]] = List(
    nameErrorRaw,
    probabilityErrorRaw,
    percentilesErrorRaw,
    quantilesErrorRaw,
    expertCrossFieldErrorRaw,
    minLossErrorRaw,
    maxLossErrorRaw,
    lognormalCrossFieldErrorRaw
  )

  // ============================================================
  // Form Submission
  // ============================================================
  
  /** Call before submit to show all errors */
  def triggerValidation(): Unit =
    showErrorsVar.set(true)
  
  /** Check if form is valid (for submit button) */
  val isValid: Signal[Boolean] = hasErrors.map(!_)

  /** Build a distribution draft from current fields (lightweight parsing; full validation happens in TreeBuilderState). */
  def toDistributionDraft: Validation[ValidationError, LeafDistributionDraft] =
    val mode = distributionModeVar.now()
    val distType = mode match
      case DistributionMode.Expert => "expert"
      case DistributionMode.Lognormal => "lognormal"

    val probabilityV = parseDoubleField(probabilityVar.now(), "leaf.probability")
    val minLossV = mode match
      case DistributionMode.Lognormal => parseLongField(minLossVar.now(), "leaf.minLoss").map(Some(_))
      case _ => Validation.succeed(None)
    val maxLossV = mode match
      case DistributionMode.Lognormal => parseLongField(maxLossVar.now(), "leaf.maxLoss").map(Some(_))
      case _ => Validation.succeed(None)
    val percentilesV = mode match
      case DistributionMode.Expert => Validation.succeed(toArrayOpt(parseDoubleList(percentilesVar.now())))
      case _ => Validation.succeed(None)
    val quantilesV = mode match
      case DistributionMode.Expert => Validation.succeed(toArrayOpt(parseDoubleList(quantilesVar.now())))
      case _ => Validation.succeed(None)

    Validation.validateWith(probabilityV, minLossV, maxLossV, percentilesV, quantilesV) {
      (prob, minL, maxL, pcts, quants) =>
        LeafDistributionDraft(
          distributionType = distType,
          probability = prob,
          minLoss = minL,
          maxLoss = maxL,
          percentiles = pcts,
          quantiles = quants
        )
    }

  private def parseDoubleField(raw: String, field: String): Validation[ValidationError, Double] =
    this.parseDouble(raw) match
      case Some(v) => Validation.succeed(v)
      case None => Validation.fail(ValidationError(field, ValidationErrorCode.INVALID_FORMAT, s"$field must be a number"))

  private def parseLongField(raw: String, field: String): Validation[ValidationError, Long] =
    this.parseLong(raw) match
      case Some(v) => Validation.succeed(v)
      case None => Validation.fail(ValidationError(field, ValidationErrorCode.INVALID_FORMAT, s"$field must be a whole number"))

  private def toArrayOpt(values: List[Double]): Option[Array[Double]] =
    if values.isEmpty then None else Some(values.toArray)



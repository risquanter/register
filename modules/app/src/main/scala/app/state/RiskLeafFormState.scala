package app.state

import com.raquo.laminar.api.L.{*, given}

/**
 * Reactive form state for creating a RiskLeaf.
 * 
 * Features:
 * - Input filters: prevent invalid characters from being typed
 * - Reactive validation signals: auto-update on field changes
 * - Error display timing: controlled by showErrors flag (set on blur/submit)
 * 
 * Validation rules mirror RiskNode.scala smart constructor:
 * - SafeId: 3-30 alphanumeric chars + hyphen/underscore
 * - SafeName: non-blank, max 50 chars
 * - Probability: 0.0 < p < 1.0
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
  val idVar: Var[String] = Var("")
  val nameVar: Var[String] = Var("")
  val probabilityVar: Var[String] = Var("")
  
  // Expert mode fields
  val percentilesVar: Var[String] = Var("10, 50, 90")  // Default expert percentiles
  val quantilesVar: Var[String] = Var("")              // User must provide quantiles
  
  // Lognormal mode fields
  val minLossVar: Var[String] = Var("")
  val maxLossVar: Var[String] = Var("")

  // ============================================================
  // Input Filters (prevent invalid characters - BCG pattern)
  // ============================================================
  
  /** Filter for ID field: alphanumeric + hyphen + underscore only */
  val idFilter: String => Boolean = _.forall(c => c.isLetterOrDigit || c == '-' || c == '_')
  
  /** Filter for name field: any printable characters */
  val nameFilter: String => Boolean = _ => true
  
  /** Filter for probability field: digits and decimal point */
  val probabilityFilter: String => Boolean = _.forall(c => c.isDigit || c == '.')
  
  /** Filter for percentiles/quantiles: digits, commas, spaces, decimal points */
  val arrayFilter: String => Boolean = _.forall(c => c.isDigit || c == ',' || c == ' ' || c == '.')
  
  /** Filter for loss values: digits only */
  val lossFilter: String => Boolean = _.forall(_.isDigit)

  // ============================================================
  // Raw Validation Signals (always computed, may not be shown)
  // ============================================================
  
  /** ID validation: 3-30 alphanumeric + hyphen/underscore */
  private val idErrorRaw: Signal[Option[String]] = idVar.signal.map { v =>
    if v.isBlank then Some("ID is required")
    else if v.length < 3 then Some("ID must be at least 3 characters")
    else if v.length > 30 then Some("ID must be at most 30 characters")
    else if !v.matches("^[a-zA-Z0-9_-]+$") then Some("ID can only contain letters, numbers, hyphens, and underscores")
    else None
  }
  
  /** Name validation: non-blank, max 50 chars */
  private val nameErrorRaw: Signal[Option[String]] = nameVar.signal.map { v =>
    if v.isBlank then Some("Name is required")
    else if v.length > 50 then Some("Name must be at most 50 characters")
    else None
  }
  
  /** Probability validation: 0.0 < p < 1.0 */
  private val probabilityErrorRaw: Signal[Option[String]] = probabilityVar.signal.map { v =>
    if v.isBlank then Some("Probability is required")
    else parseDouble(v) match
      case None => Some("Probability must be a number")
      case Some(p) if p <= 0.0 => Some("Probability must be greater than 0")
      case Some(p) if p >= 1.0 => Some("Probability must be less than 1")
      case _ => None
  }
  
  /** Expert mode: percentiles validation */
  private val percentilesErrorRaw: Signal[Option[String]] = 
    distributionModeVar.signal.combineWith(percentilesVar.signal).map {
      case (DistributionMode.Expert, v) =>
        if v.isBlank then Some("Percentiles are required for expert mode")
        else
          val values = parseDoubleList(v)
          if values.isEmpty then Some("Enter comma-separated percentile values")
          else if values.exists(p => p < 0 || p > 100) then Some("Percentiles must be between 0 and 100")
          else None
      case _ => None
    }
  
  /** Expert mode: quantiles validation */
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
  
  /** Lognormal mode: minLoss validation */
  private val minLossErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(minLossVar.signal).map {
      case (DistributionMode.Lognormal, v) =>
        if v.isBlank then Some("Minimum loss is required for lognormal mode")
        else parseLong(v) match
          case None => Some("Minimum loss must be a whole number")
          case Some(n) if n < 0 => Some("Minimum loss must be non-negative")
          case _ => None
      case _ => None
    }
  
  /** Lognormal mode: maxLoss validation */
  private val maxLossErrorRaw: Signal[Option[String]] =
    distributionModeVar.signal.combineWith(maxLossVar.signal).map {
      case (DistributionMode.Lognormal, v) =>
        if v.isBlank then Some("Maximum loss is required for lognormal mode")
        else parseLong(v) match
          case None => Some("Maximum loss must be a whole number")
          case Some(n) if n < 0 => Some("Maximum loss must be non-negative")
          case _ => None
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
  
  val idError: Signal[Option[String]] = withDisplayControl("id", idErrorRaw)
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
    idErrorRaw,
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



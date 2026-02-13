package com.risquanter.register.domain.data.iron

import com.risquanter.register.common.Constants

/**
 * Centralized catalog of all user-facing validation messages.
 *
 * Follows the "message catalog" pattern: every string the user can see lives
 * in a single grep-friendly file, making future i18n, copy-editing, or UX
 * review straightforward.
 *
 * Numeric limits reference [[Constants]] so there is exactly one source of
 * truth for constraint values.
 *
 * This object is shared across JVM and JS (lives in `common`), so both
 * backend [[ValidationUtil]] and frontend form states import from here.
 */
object ValidationMessages:

  // ══════════════════════════════════════════════════════════════════
  // Name (SafeName: Not[Blank] & MaxLength[50])
  // ══════════════════════════════════════════════════════════════════
  val nameRequired: String      = "Name must not be blank"
  val nameTooLong: String       = s"Name must be at most ${Constants.MaxNameLength} characters"

  // ══════════════════════════════════════════════════════════════════
  // Email
  // ══════════════════════════════════════════════════════════════════
  val emailInvalid: String      = "Please enter a valid email address"

  // ══════════════════════════════════════════════════════════════════
  // URL
  // ══════════════════════════════════════════════════════════════════
  val urlInvalid: String        = "Please enter a valid URL (e.g. https://example.com)"

  // ══════════════════════════════════════════════════════════════════
  // Numeric — non-negative / positive
  // ══════════════════════════════════════════════════════════════════
  val valueMustBeNonNegative: String    = "Value must be zero or greater"
  val valueMustBePositive: String       = "Value must be greater than zero"

  // ══════════════════════════════════════════════════════════════════
  // Probability (open interval 0 < p < 1)
  // ══════════════════════════════════════════════════════════════════
  val probabilityRequired: String       = "Probability is required"
  val probabilityNotANumber: String     = "Probability must be a number"
  val probabilityOutOfRange: String     = "Probability must be between 0 and 1 (exclusive)"

  // ══════════════════════════════════════════════════════════════════
  // Distribution type
  // ══════════════════════════════════════════════════════════════════
  val distributionTypeInvalid: String   = "Distribution type must be 'expert' or 'lognormal'"

  // ══════════════════════════════════════════════════════════════════
  // Short optional text (MaxLength[20])
  // ══════════════════════════════════════════════════════════════════
  val shortTextTooLong: String  =
    s"Value must be at most ${Constants.MaxShortTextLength} characters"

  // ══════════════════════════════════════════════════════════════════
  // Lognormal mode — min/max loss fields
  // ══════════════════════════════════════════════════════════════════
  val minLossRequired: String           = "Minimum loss is required for lognormal mode"
  val maxLossRequired: String           = "Maximum loss is required for lognormal mode"
  def lossMustBeWholeNumber(field: String): String =
    s"$field must be a whole number"
  val minMustBeLessThanMax: String      = "Minimum loss must be less than maximum loss"

  // ══════════════════════════════════════════════════════════════════
  // Expert mode — percentiles / quantiles
  // ══════════════════════════════════════════════════════════════════
  val percentilesRequired: String       = "Percentiles are required for expert mode"
  val percentilesFormat: String         = "Enter comma-separated percentile values"
  val percentilesOutOfRange: String     = "Percentiles must be between 0 and 100 (exclusive)"

  val quantilesRequired: String         = "Quantiles are required for expert mode"
  val quantilesFormat: String           = "Enter comma-separated quantile values (loss amounts)"
  val quantilesMustBeNonNegative: String = "Quantiles must be non-negative"

  def expertLengthMismatch(pLen: Int, qLen: Int): String =
    s"Percentiles and quantiles must have same length ($pLen vs $qLen)"

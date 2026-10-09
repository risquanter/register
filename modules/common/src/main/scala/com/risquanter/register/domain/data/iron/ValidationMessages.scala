package com.risquanter.register.domain.data.iron

import com.risquanter.register.common.Constants

/** All user-facing validation message strings. Shared across JVM and JS. Numeric limits reference `Constants`. */
object ValidationMessages:

  // ══════════════════════════════════════════════════════════════════
  // Name (SafeName: Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9 /\\-]+$"])
  // ══════════════════════════════════════════════════════════════════
  val nameRequired: String      = "Name must not be blank"
  val nameTooLong: String       = s"Name must be at most ${Constants.MaxNameLength} characters"
  val nameInvalidChars: String  = "Name must use only letters, digits, spaces, hyphens, and forward slashes"

  // ══════════════════════════════════════════════════════════════════
  // Scenario name (DD-5: ScenarioName — input whitelist folds to a BranchRef
  // segment slug: letters -> lowercase, space -> '-')
  // ══════════════════════════════════════════════════════════════════
  val scenarioNameRequired: String     = "Scenario name must not be blank"
  val scenarioNameTooLong: String      = s"Scenario name must be at most ${Constants.MaxScenarioNameLength} characters"
  val scenarioNameInvalidChars: String = "Scenario name must use only letters, digits, spaces, hyphens, and underscores"
  val scenarioNameInvalidStart: String = "Scenario name must start with a letter or digit"

  // ══════════════════════════════════════════════════════════════════
  // Reserved FOL symbol names
  // ══════════════════════════════════════════════════════════════════
  val reservedNodeName: String  = "Node name must not be a reserved query symbol (e.g. 'leaf', 'p95')"

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
  val capMustExceedDeductible: String   = "Cap must be greater than deductible"
  val aggregatedLossOverflow: String    = "Aggregated loss exceeds the maximum representable value"
  val retentionFactorOutOfRange: String =
    "Mitigation factor must be greater than 0 and at most 1 — a mitigation reduces a figure, so it can neither increase it nor remove it entirely"
  val residualProbabilityOutOfRange: String =
    "Mitigated probability must be greater than 0 and at most 1 — a mitigation reduces the chance a risk occurs, so it cannot remove the possibility entirely"
  val lossCapMustBePositive: String =
    "Loss cap must be greater than 0 — a cap of zero would leave the entity bearing nothing, which states that the transfer cannot fail"
  val portfolioHasNoChildren: String    = "Portfolio must have at least one child"

  // ══════════════════════════════════════════════════════════════════
  // Probability (open interval 0 < p < 1)
  // ══════════════════════════════════════════════════════════════════
  val probabilityRequired: String       = "Probability is required"
  val probabilityNotANumber: String     = "Probability must be a number"
  val probabilityOutOfRange: String     = "Probability must be between 0 and 1 (exclusive)"

  // Occurrence probability (closed interval 0 ≤ p ≤ 1)
  // ══════════════════════════════════════════════════════════════════
  val occurrenceProbabilityOutOfRange: String = "Occurrence probability must be between 0 and 1 (inclusive)"

  // ══════════════════════════════════════════════════════════════════
  // Distribution type
  // ══════════════════════════════════════════════════════════════════
  val distributionTypeInvalid: String   = "Distribution type must be 'expert' or 'lognormal'"

  // ══════════════════════════════════════════════════════════════════
  // Workspace key (base64url, 22 chars)
  // ══════════════════════════════════════════════════════════════════
  val workspaceKeyInvalid: String = "Invalid workspace key (expected 22-character base64url token)"

  // ══════════════════════════════════════════════════════════════════
  // Workspace key hash (sha256, 64 lowercase hex chars)
  // ══════════════════════════════════════════════════════════════════
  val workspaceKeyHashInvalid: String = "Invalid workspace key hash (expected 64-character lowercase hex SHA-256 digest)"

  // ══════════════════════════════════════════════════════════════════
  // User ID (UUID v4 — from Keycloak JWT sub claim via x-user-id header)
  // ══════════════════════════════════════════════════════════════════
  val userIdInvalid: String = "User ID must be a valid UUID (e.g. 8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf)"

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

  val mitigationEliminatesRisk: String =
    "This mitigation removes every loss from the risk, leaving a residual of exactly zero — a mitigation reduces exposure and cannot assert that a risk has ceased to exist. Raise the cap or the scale factor, lower the deductible, or lower the threshold; a risk that genuinely no longer exists is removed from the tree instead."

  def mitigationLowersMinLossBelowFloor(min: Long, minFactor: Double): String =
    f"This mitigation reduces the minimum loss below 1, the smallest representable whole unit. The leaf's minimum loss is $min, so the smallest severity factor that still applies here is $minFactor%.3e (1 / $min)."

  // ══════════════════════════════════════════════════════════════════
  // Expert mode — percentiles / quantiles
  // ══════════════════════════════════════════════════════════════════
  val percentilesRequired: String       = "Percentiles are required for expert mode"
  val percentilesFormat: String         = "Enter comma-separated percentile values"
  val percentilesOutOfRange: String     = "Percentiles must be between 0 and 100 (exclusive)"
  val percentilesMustBeStrictlyIncreasing: String =
    "Percentiles must be strictly increasing — values must be in ascending order"

  val quantilesRequired: String         = "Quantiles are required for expert mode"
  val quantilesFormat: String           = "Enter comma-separated quantile values (loss amounts)"
  val quantilesMustBePositive: String =
    "Quantile loss amounts must be greater than zero — the distribution fit takes their logarithm"
  val quantilesMustBeStrictlyIncreasing: String =
    "Quantiles must be strictly increasing — a higher percentile must correspond to a higher loss value"

  def expertLengthMismatch(pLen: Int, qLen: Int): String =
    s"Percentiles and quantiles must have same length ($pLen vs $qLen)"

  // ══════════════════════════════════════════════════════════════════
  // Expert mode — Metalog terms
  // ══════════════════════════════════════════════════════════════════
  val termsOutOfRange: String =
    "Terms must be between 2 and the number of percentile-quantile pairs"

  // ══════════════════════════════════════════════════════════════════
  // Distribution fit failure
  // ══════════════════════════════════════════════════════════════════
  val distributionFitFailed: String =
    "The inputs could not be fitted to a valid distribution. " +
    "Try reducing Terms, check that quantile values are strictly increasing, " +
    "or remove conflicting estimates."

  // ══════════════════════════════════════════════════════════════════
  // Seed identity (TODO item 12 / PLAN-SEED-IDENTITY.md)
  // ══════════════════════════════════════════════════════════════════
  // Bounds are fixed by the HDR generator's 8-decimal-digit ID budget and
  // must match the Iron type-level literals in OpaqueTypes/ValidationUtil
  // (type-level constraints cannot reference runtime constants).
  val seedEntityIdOutOfRange: String =
    "seedEntityId must be between 1 and 99999999 (0 is reserved)"
  val seedVarIdOutOfRange: String =
    "seedVarId must be between 1 and 49999999"

  def seedVarIdInUse(id: Long, holders: Seq[String]): String =
    s"seedVarId $id is used by multiple nodes: ${holders.mkString(", ")} — " +
    "choose distinct values or omit to auto-assign"

  def seedEntityIdInUse(id: Long): String =
    s"seedEntityId $id is already used by another workspace — " +
    "choose another value or omit to auto-assign"

  def maxTreesPerWorkspaceReached(limit: Int): String =
    s"workspace already holds the maximum of $limit risk trees — " +
    "delete one before creating another"

  val seedVarIdImmutable: String =
    "seedVarId is immutable once assigned — omit it for existing nodes; " +
    "to change a risk's stream, delete the leaf and recreate it"

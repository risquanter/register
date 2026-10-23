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
  // Scenario name — the input whitelist folds to a BranchRef segment slug:
  // letters -> lowercase, space -> '-'
  // ══════════════════════════════════════════════════════════════════
  val scenarioNameRequired: String     = "Scenario name must not be blank"
  val scenarioNameTooLong: String      = s"Scenario name must be at most ${Constants.MaxScenarioNameLength} characters"
  val scenarioNameInvalidChars: String = "Scenario name must use only letters, digits, spaces, hyphens, and underscores"
  val scenarioNameInvalidStart: String = "Scenario name must start with a letter or digit"

  // ══════════════════════════════════════════════════════════════════
  // Reserved FOL symbol names
  // ══════════════════════════════════════════════════════════════════
  val reservedNodeName: String  = "Node name must not be a reserved query symbol (e.g. 'leaf', 'p95')"

  def reservedNodeNames(names: Seq[String]): String =
    s"$reservedNodeName: ${names.mkString(", ")}"

  // ══════════════════════════════════════════════════════════════════
  // Tree topology — the rules a submitted or stored node set must satisfy
  // ══════════════════════════════════════════════════════════════════
  /** Why a name was refused as repeated, with how many nodes carry it and what
    * resolves it.
    *
    * The count is there so a user resolving one of several repetitions sees it
    * fall rather than an unchanged sentence. The last clause is there because no
    * holder of the name is more at fault than its twins — any of them may be
    * the one renamed, and the error names none of them individually.
    *
    * {{{
    * duplicateNodeNames("Fire", 3)
    * // duplicate node name: Fire — used by 3 nodes; names must be unique
    * // within a tree. Rename all but one of them.
    * }}}
    */
  def duplicateNodeNames(name: String, occurrences: Int): String =
    s"duplicate node name: $name — used by $occurrences nodes; names must be unique within a tree. " +
    "Rename all but one of them."

  val exactlyOneRootRequired: String = "Exactly one root is required"
  val multipleRootsFound: String     = "Multiple roots found"
  val leafNeedsPortfolioParent: String = "leaf must have a parent portfolio"

  /** A form submitted with nothing chosen in its parent control. Distinct from
    * `leafNeedsPortfolioParent`, which refuses a parent that was chosen and is
    * the wrong kind of node. */
  val parentRequired: String = "Parent is required"

  def parentNameIsLeaf(parent: String): String =
    s"parentName '$parent' refers to a leaf; must reference a portfolio"

  def parentNameNotFound(parent: String): String =
    s"parentName '$parent' not found in portfolios"

  def cycleDetected(node: String): String =
    s"Cycle detected at node '$node'"

  def portfoliosWithoutChildren(names: Seq[String]): String =
    s"Every portfolio must have at least one child: ${names.mkString(", ")}"

  def tooManyNodes(count: Int, limit: Int): String =
    s"too many nodes: $count exceeds the limit of $limit"

  def tooManyMitigations(count: Int, limit: Int): String =
    s"too many mitigations: $count exceeds the limit of $limit"

  def rootIdNotFound(rootId: String): String =
    s"rootId '$rootId' not found in nodes"

  def duplicateMitigationIds(id: String, occurrences: Int): String =
    s"duplicate mitigation id: $id — carried by $occurrences mitigations; identifiers must be unique within a tree. " +
    s"Remove all but one of them, or give each its own identifier."

  def duplicateMitigationNames(name: String, occurrences: Int): String =
    s"duplicate mitigation name: $name — used by $occurrences mitigations; names must be unique within a tree. " +
    s"Rename all but one of them."

  // ══════════════════════════════════════════════════════════════════
  // ULID identifiers (SafeId and the nominal wrappers over it)
  // ══════════════════════════════════════════════════════════════════
  /** Why a ULID was refused, stating the constraint and not the value.
    *
    * The submitted value is deliberately absent. It is client-supplied text,
    * and a validation message reaches a client inside
    * `WireError.ValidationRejected`, so it is a disclosure surface whether or
    * not the value came from the same client (ADR-035). The 26 characters and
    * the alphabet are fixed by the ULID specification rather than chosen here.
    */
  val invalidUlid: String =
    "Invalid ID (expected a 26-character ULID in Crockford base32: digits and uppercase letters, excluding I, L, O and U)"

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

  /** Why a loss range was refused, naming both bounds. The pair is the subject
    * of the refusal: neither bound is wrong on its own and editing either one
    * satisfies the rule, so the sentence names both rather than blaming one.
    *
    * {{{
    * lossRangeNotOrdered(120, 20)
    * // Minimum loss (120) must be less than maximum loss (20)
    * }}}
    */
  def lossRangeNotOrdered(min: Long, max: Long): String =
    s"Minimum loss ($min) must be less than maximum loss ($max)"

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

  /** Why an anchor count was refused, with the bounds and the count supplied.
    *
    * The bounds are the caller's, so the sentence cannot state one the caller
    * does not enforce. They originate in the metalog fitter: below the minimum
    * there is no curve to fit, and above the maximum the fitter throws.
    *
    * {{{
    * anchorCountOutOfRange(1, 2, 20)
    * // Expert mode takes between 2 and 20 percentile-quantile pairs (got 1)
    * }}}
    */
  def anchorCountOutOfRange(count: Int, min: Int, max: Int): String =
    s"Expert mode takes between $min and $max percentile-quantile pairs (got $count)"

  // ══════════════════════════════════════════════════════════════════
  // Expert mode — Metalog terms
  // ══════════════════════════════════════════════════════════════════
  val termsOutOfRange: String =
    "Terms must be between 2 and the number of percentile-quantile pairs"

  /** Why a stated term count was refused as outside the fit's own range.
    *
    * Distinct from `termsOutOfRange`, which refuses a count inside this range
    * that exceeds the number of anchors supplied. The bounds are
    * `Metalog.validateInputs`' precondition, which throws outside [2, 20], and
    * must match the type-level literals of `MetalogTermCount` in OpaqueTypes
    * and `refineMetalogTermCount` in ValidationUtil (type-level constraints
    * cannot reference runtime constants).
    */
  val termsOutsideFitterRange: String =
    "Terms must be between 2 and 20 — a fit with fewer has no curve to describe, and the fitter accepts no more"

  // ══════════════════════════════════════════════════════════════════
  // Distribution fit failure
  // ══════════════════════════════════════════════════════════════════
  val distributionFitFailed: String =
    "The inputs could not be fitted to a valid distribution. " +
    "Try reducing Terms, check that quantile values are strictly increasing, " +
    "or remove conflicting estimates."

  // ══════════════════════════════════════════════════════════════════
  // Seed identity
  // ══════════════════════════════════════════════════════════════════
  // Bounds are fixed by the HDR generator's 8-decimal-digit ID budget and
  // must match the Iron type-level literals in OpaqueTypes/ValidationUtil
  // (type-level constraints cannot reference runtime constants).
  val seedEntityIdOutOfRange: String =
    "seedEntityId must be between 1 and 99999999 (0 is reserved)"
  val seedVarIdOutOfRange: String =
    "seedVarId must be between 1 and 49999999"

  def seedVarIdInUse(id: Long, holders: Seq[String]): String =
    s"seedVarId $id is used by multiple nodes: ${holders.mkString(", ")}. Only one may keep it. " +
    "A seedVarId cannot be changed once it is assigned, so change or omit the one you supplied."

  def seedEntityIdInUse(id: Long): String =
    s"seedEntityId $id is already used by another workspace — " +
    "choose another value or omit to auto-assign"

  def maxTreesPerWorkspaceReached(limit: Int): String =
    s"workspace already holds the maximum of $limit risk trees — " +
    "delete one before creating another"

  val seedVarIdImmutable: String =
    "seedVarId is immutable once assigned — omit it for existing nodes; " +
    "to change a risk's stream, delete the leaf and recreate it"

  def seedVarIdAssignmentExhausted(next: Long): String =
    s"seedVarId assignment space exhausted: next value $next exceeds the valid range"

  def seedVarHighWaterBelowMax(highWater: Long, maxAssigned: Long): String =
    s"seedVarHighWater $highWater is below the highest assigned seedVarId $maxAssigned"

  val seedVarHighWaterNotDerivable: String =
    "tree has no leaves — seedVarHighWater cannot be derived"

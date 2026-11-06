package com.risquanter.register.domain.data.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import io.github.iltotore.iron.constraint.string.Match
import com.risquanter.register.domain.data.iron.{SafeName, Email, Url, SecureUrl, SafeId, TreeId, NodeId, WorkspaceKeySecret, WorkspaceKeyHash, ExternalTokenStr, PrintableAscii}
import com.bilalfazlani.zioUlid.ULID
import com.risquanter.register.domain.errors.{Alphabet, ErrorLocation, FieldName, FieldProblem, ValidationError, ValueFormat, ValueRange}
import zio.prelude.Validation
import zio.NonEmptyChunk

object ValidationUtil {

  /** The length bounds the refinements below enforce, as the values a refusal
    * carries. Each mirrors the `MaxLength` literal of the constraint it names,
    * because a type-level literal cannot be read back at runtime. */
  private val NameLengthLimit: NonNegativeInt         = 50  // SafeNameConstraint
  private val ScenarioNameLengthLimit: NonNegativeInt = 64  // ScenarioNameInputConstraint
  private val ShortTextLengthLimit: NonNegativeInt    = 20  // SafeExtraShortStr

  // Helper: trim and safeguard against null values
  def nonEmpty(s: String): String = if (s == null) "" else s.trim

  /**
   * Convert Either[List[ValidationError], A] to Validation[ValidationError, A]
   * Preserves all errors by using NonEmptyChunk for proper error accumulation.
   */
  def toValidation[A](either: Either[List[ValidationError], A]): Validation[ValidationError, A] =
    either match {
      case Right(a) => Validation.succeed(a)
      case Left(errors) =>
        errors match {
          case head :: tail => Validation.failNonEmptyChunk(NonEmptyChunk(head, tail*))
          case Nil =>
            // Unreachable: every refinement here returns at least one error.
            Validation.fail(ValidationError(ErrorLocation.requestField(FieldName.Unknown), FieldProblem.Unspecified))
        }
    }

  /** The groups of two or more elements sharing a key, ascending by key.
    *
    * The one definition of what counts as a duplicate. A group of one is not a
    * duplication, so it does not appear.
    *
    * {{{
    * // four nodes named Fire, Flood, Fire, Fire
    * duplicateGroups(nodes, _.name)
    * // List(("Fire", the three Fire nodes))  — Flood is a group of one
    * }}}
    */
  def duplicateGroups[A, K](elements: Seq[A], key: A => K)(using Ordering[K]): List[(K, Seq[A])] =
    elements
      .groupBy(key)
      .filter { case (_, group) => group.sizeIs > 1 }
      .toList
      .sortBy { case (k, _) => k }

  /** Fails when the submitted collection contains duplicates. Duplicates are
    * found by grouping on the key: a group larger than one is a duplication,
    * and each one becomes an error carrying its group.
    *
    * One error per duplicated key, so a caller cannot report some duplications
    * and leave the rest out.
    *
    * {{{
    * // four nodes named Fire, Flood, Fire, Fire
    * requireDistinctBy(nodes, _.name, toError)
    * // toError called once, with ("Fire", the three Fire nodes)
    * }}}
    */
  def requireDistinctBy[A, K](
      elements: Seq[A],
      key: A => K,
      toError: (K, Seq[A]) => ValidationError
  )(using Ordering[K]): Validation[ValidationError, Unit] =
    duplicateGroups(elements, key) match {
      case Nil => Validation.succeed(())
      case head :: tail =>
        Validation.failNonEmptyChunk(
          NonEmptyChunk(toError.tupled(head), tail.map(toError.tupled)*)
        )
    }

  /** One refusal at `location`. */
  private def refusal(location: ErrorLocation, problem: FieldProblem): List[ValidationError] =
    List(ValidationError(location, problem))

  // Refinement for name; whitelist: letters, digits, space, hyphen, forward-slash
  def refineName(value: String, location: ErrorLocation): Either[List[ValidationError], SafeName.SafeName] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[SafeNameConstraint]
      .map(SafeName.SafeName(_))
      .left
      .map { err =>
        val problem = (sanitized.isEmpty || err.toLowerCase.contains("blank"), sanitized.length > NameLengthLimit) match
          case (true, _) => FieldProblem.Required
          case (_, true) => FieldProblem.TooLong(NameLengthLimit)
          case _         => FieldProblem.InvalidCharacters(Alphabet.NodeName)
        refusal(location, problem)
      }
  }

  // Refinement for scenario names: input whitelist letters/digits/space/
  // hyphen/underscore (max 64), then fold letters to lowercase and map each
  // contiguous run of spaces -> a single '-' to produce the BranchRef segment
  // slug (scenarios.<ws>.<name-slug>) — "a  b" and "a b" both fold to "a-b",
  // not "a--b". Invalid characters are rejected outright (400) — never
  // silently stripped ("no lossy slugification").
  def refineScenarioName(value: String, location: ErrorLocation): Either[List[ValidationError], ScenarioName.ScenarioName] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[ScenarioNameInputConstraint]
      .left
      .map { err =>
        val problem = (sanitized.isEmpty || err.toLowerCase.contains("blank"), sanitized.length > ScenarioNameLengthLimit) match
          case (true, _) => FieldProblem.Required
          case (_, true) => FieldProblem.TooLong(ScenarioNameLengthLimit)
          case _         => FieldProblem.InvalidCharacters(Alphabet.ScenarioName)
        refusal(location, problem)
      }
      .flatMap { validInput =>
        val slug = validInput.toLowerCase.replaceAll(" +", "-")
        slug
          .refineEither[ScenarioNameConstraint]
          .map(ScenarioName.ScenarioName(_))
          .left
          .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.ScenarioNameStart)))
      }
  }

  // Refinement for email; whitelist regex: local-part, @, domain with TLD
  def refineEmail(value: String, location: ErrorLocation): Either[List[ValidationError], Email.Email] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"]]
      .map(Email.Email(_))
      .left
      .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.Email)))
  }

  // Refinement for URL using shared service URL regex (allows localhost/IP/IPv6)
  def refineUrl(value: String, location: ErrorLocation): Either[List[ValidationError], Url.Url] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[UrlConstraint]
      .map(Url.Url(_))
      .left
      .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.Url)))
  }

  // Refinement for HTTPS-only URLs (SecureUrlConstraint).
  // Rejects http:// — use for any external service endpoint that will receive a credential in headers.
  // See ADR-001-appendix.md, "External-System Output Boundary Constraints".
  def refineSecureUrl(value: String, location: ErrorLocation): Either[List[ValidationError], SecureUrl.SecureUrl] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[SecureUrlConstraint]
      .map(SecureUrl.SecureUrl(_))
      .left
      .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.SecureUrl)))
  }

  // Refinement for external service tokens (ExternalTokenStr).
  // Validates PrintableAscii + MaxLength[2048] — blocks CRLF injection and non-ASCII bytes.
  // Returns ExternalTokenStr; wrap in a credential final class (ADR-022 R1–R8) at the call site.
  // See ADR-001-appendix.md, "External-System Output Boundary Constraints".
  def refineExternalToken(value: String, location: ErrorLocation): Either[List[ValidationError], ExternalTokenStr] = {
    value
      .refineEither[Not[Blank] & MaxLength[2048] & PrintableAscii]
      .left
      .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.ExternalToken)))
  }

  /** True when every adjacent pair in `xs` satisfies a < b.  Single-element
   *  and empty sequences are trivially increasing. Used by
   *  `QuantileAnchors.create`, which owns the ordering rule, and by
   *  `RiskLeafFormState` for the form's own live feedback.
   */
  def isStrictlyIncreasing(xs: Seq[Double]): Boolean =
    xs.sliding(2).forall { case Seq(a, b) => a < b; case _ => true }

  // Refinement for non-negative long values
  def refineNonNegativeLong(value: Long, location: ErrorLocation): Either[List[ValidationError], NonNegativeLong] = {
    value
      .refineEither[GreaterEqual[0L]]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.NonNegative)))
  }

  // Refinement for positive long values (must be > 0)
  def refinePositiveLong(value: Long, location: ErrorLocation): Either[List[ValidationError], PositiveLong] = {
    value
      .refineEither[Greater[0L]]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.Positive)))
  }

  // Refinement for seed entity IDs (HDR Entity axis: 1 <= v < 10^8; 0 reserved)
  def refineSeedEntityId(value: Long, location: ErrorLocation): Either[List[ValidationError], SeedEntityId.SeedEntityId] = {
    value
      .refineEither[GreaterEqual[1L] & Less[100000000L]]
      .map(SeedEntityId.SeedEntityId(_))
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.SeedEntityId)))
  }

  // Refinement for seed var IDs (HDR Var axis: 1 <= v < 5*10^7; doubled to 2k/2k+1)
  def refineSeedVarId(value: Long, location: ErrorLocation): Either[List[ValidationError], SeedVarId.SeedVarId] = {
    value
      .refineEither[GreaterEqual[1L] & Less[50000000L]]
      .map(SeedVarId.SeedVarId(_))
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.SeedVarId)))
  }

  // Refinement for an open-interval probability (must be between 0.0 and 1.0, exclusive)
  def refineOpenProbability(value: Double, location: ErrorLocation): Either[List[ValidationError], OpenProbability] = {
    value
      .refineEither[OpenProbabilityConstraint]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.OpenUnitInterval)))
  }

  // Refinement for a probability (must be between 0.0 and 1.0, inclusive)
  def refineProbability(value: Double, location: ErrorLocation): Either[List[ValidationError], Probability] = {
    value
      .refineEither[ProbabilityConstraint]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.ClosedUnitInterval)))
  }

  // Refinement for positive integers (must be > 0)
  def refinePositiveInt(value: Int, location: ErrorLocation): Either[List[ValidationError], PositiveInt] = {
    value
      .refineEither[Greater[0]]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.Positive)))
  }

  // Refinement for non-negative integers (must be >= 0)
  def refineNonNegativeInt(value: Int, location: ErrorLocation): Either[List[ValidationError], NonNegativeInt] = {
    value
      .refineEither[GreaterEqual[0]]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.NonNegative)))
  }

  // Refinement for non-negative double values (must be >= 0.0 and finite).
  // LessEqual[1.7976931348623157e308] rejects +Infinity; GreaterEqual[0.0] already
  // rejects -Infinity and NaN (IEEE 754 comparisons against them are always
  // false). See NonNegativeDouble in OpaqueTypes.scala for why Not[Infinity]
  // isn't used here instead.
  def refineNonNegativeDouble(value: Double, location: ErrorLocation): Either[List[ValidationError], NonNegativeDouble] = {
    value
      .refineEither[GreaterEqual[0.0] & LessEqual[1.7976931348623157e308]]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.NonNegative)))
  }

  // Refinement for a strictly positive, finite double — a loss figure in
  // millions. LessEqual[1.7976931348623157e308] rejects +Infinity; Greater[0.0]
  // already rejects -Infinity, NaN and zero (IEEE 754 comparisons against the
  // first two are always false). Zero is refused because the distribution fit
  // takes the logarithm of a loss.
  def refinePositiveDouble(value: Double, location: ErrorLocation): Either[List[ValidationError], PositiveDouble] = {
    value
      .refineEither[PositiveDoubleConstraint]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.PositiveLoss)))
  }

  // Refinement for a metalog fit's term count. The bounds are the fitter's own
  // precondition — Metalog.validateInputs throws outside [2, 20] — and must
  // match the type-level literals of MetalogTermCount in OpaqueTypes and the
  // sentence ValidationMessages renders for ValueRange.MetalogTerms (type-level
  // constraints cannot reference runtime constants).
  def refineMetalogTermCount(value: Int, location: ErrorLocation): Either[List[ValidationError], MetalogTermCount] = {
    value
      .refineEither[GreaterEqual[2] & LessEqual[20]]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.MetalogTerms)))
  }

  // Refinement for a retention factor: a fraction of a figure that is kept
  def refineRetentionFactor(value: Double, location: ErrorLocation): Either[List[ValidationError], RetentionFactor] = {
    value
      .refineEither[UnitFractionConstraint]
      .map(RetentionFactor(_))
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.RetentionFactor)))
  }

  // Refinement for a probability a mitigation may assert: above 0, at most 1.
  // Shares refineRetentionFactor's predicate and differs in the type it builds
  // and in its range case, whose sentence states what a mitigation may not assert.
  def refineResidualProbability(value: Double, location: ErrorLocation): Either[List[ValidationError], ResidualProbability] = {
    value
      .refineEither[UnitFractionConstraint]
      .map(ResidualProbability(_))
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.ResidualProbability)))
  }

  // Refinement for a loss cap: the largest loss the entity bears, at least 1.
  // Shares refinePositiveLong's constraint and differs only in its range case,
  // whose sentence states why a cap of zero is refused rather than only the bound.
  def refineLossCap(value: Long, location: ErrorLocation): Either[List[ValidationError], PositiveLong] = {
    value
      .refineEither[Greater[0L]]
      .left
      .map(_ => refusal(location, FieldProblem.OutOfRange(ValueRange.LossCap)))
  }

  // Refinement for distribution type (must be "Metalog" or "Lognormal")
  def refineDistributionType(value: String, location: ErrorLocation): Either[List[ValidationError], DistributionType] = {
    value
      .refineEither[DistributionTypeConstraint]
      .left
      .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.DistributionTypeName)))
  }

  // Refinement for risk/portfolio IDs (ULID, canonical uppercase Crockford base32, 26 chars)
  def refineId(value: String, location: ErrorLocation): Either[List[ValidationError], SafeId.SafeId] = {
    refineUlid(value, location, SafeId.SafeId(_))
  }

  // Refinement for tree IDs — delegates to refineId and wraps in TreeId case class.
  // @see ADR-018 for nominal wrapper pattern
  def refineTreeId(value: String, location: ErrorLocation): Either[List[ValidationError], TreeId] =
    refineId(value, location).map(TreeId(_))

  // Refinement for node IDs — delegates to refineId and wraps in NodeId case class.
  // @see ADR-018 for nominal wrapper pattern
  def refineNodeId(value: String, location: ErrorLocation): Either[List[ValidationError], NodeId] =
    refineId(value, location).map(NodeId(_))

  // Refinement for workspace keys — validates base64url format (22 chars, no padding).
  // Standalone validation — NOT a ULID, different charset and length.
  def refineWorkspaceKeySecret(value: String, location: ErrorLocation): Either[List[ValidationError], WorkspaceKeySecret] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Match["^[A-Za-z0-9_-]{22}$"]]
      .map(WorkspaceKeySecret(_)) // refined: WorkspaceKeyStr — Iron proof carried through to WorkspaceKeySecret.apply
      .left
      .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.WorkspaceKey)))
  }

  // Refinement for workspace key hashes — validates SHA-256 lowercase hex format (64 chars).
  def refineWorkspaceKeyHash(value: String, location: ErrorLocation): Either[List[ValidationError], WorkspaceKeyHash] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Match["^[0-9a-f]{64}$"]]
      .map(WorkspaceKeyHash(_))
      .left
      .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.WorkspaceKeyHash)))
  }

  // Refinement for user IDs (UUID v4 format — as issued by Keycloak JWT sub claim via Istio x-user-id header injection).
  // Validates verbatim: Keycloak sub claims are lowercase UUID v4, 8-4-4-4-12 hex format.
  def refineUserId(value: String, location: ErrorLocation): Either[List[ValidationError], UuidStr] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[UuidConstraint]
      .left
      .map(_ => refusal(location, FieldProblem.WrongFormat(ValueFormat.UserId)))
  }

  // Refinement for optional short text (max 20 chars)
  def refineShortOptText(
      value: Option[String],
      location: ErrorLocation
  ): Either[List[ValidationError], Option[SafeExtraShortStr]] = value match {
    case None =>
      // No value provided; that's acceptable
      Right(None)

    case Some(text) =>
      val sanitized = text.trim
      if (sanitized.isEmpty) {
        // A value was provided but trimming resulted in an empty string. Treat as empty.
        Right(None)
      } else {
        // Use Iron to refine the trimmed string with the checks: non-blank and max length 20
        sanitized
          .refineEither[Not[Blank] & MaxLength[20]]
          .left
          .map(_ => refusal(location, FieldProblem.TooLong(ShortTextLengthLimit)))
          .map(refined => Some(refined))
      }
  }

  // Shared canonical ULID string refinement
  private type UlidCanonical = String :| Match["^[0-9A-HJKMNP-TV-Z]{26}$"]

  // Shared ULID refinement for ID-like types with custom labels and field paths
  private def refineUlid[A](
      value: String,
      location: ErrorLocation,
      wrap: UlidCanonical => A
  ): Either[List[ValidationError], A] = {
    val sanitized = Option(value).map(_.trim).getOrElse("")
    val normalized = sanitized.toUpperCase
    val refused = refusal(location, FieldProblem.WrongFormat(ValueFormat.Ulid))
    ULID(normalized) match {
      case Right(parsed) =>
        val canonical = parsed.toString
        canonical
          .refineEither[Match["^[0-9A-HJKMNP-TV-Z]{26}$"]]
          .map(wrap)
          .left
          .map(_ => refused)
      case Left(_) => Left(refused)
    }
  }
}

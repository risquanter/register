package com.risquanter.register.domain.data.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.string.{Match, ValidURL}
import com.risquanter.register.domain.data.iron.{SafeName, Email, Url, SecureUrl, SafeId, TreeId, NodeId, WorkspaceKeySecret, WorkspaceKeyHash, UserId, ExternalTokenStr, PrintableAscii}
import com.bilalfazlani.zioUlid.ULID
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import zio.prelude.Validation
import zio.NonEmptyChunk

object ValidationUtil {

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
            // Should never happen - ValidationUtil methods always return at least one error
            Validation.fail(ValidationError("unknown", ValidationErrorCode.CONSTRAINT_VIOLATION, "Unknown validation error"))
        }
    }

  // Refinement for name; whitelist: letters, digits, space, hyphen, forward-slash
  def refineName(value: String, fieldPath: String = "name"): Either[List[ValidationError], SafeName.SafeName] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[SafeNameConstraint]
      .map(SafeName.SafeName(_))
      .left
      .map { err =>
        val (code, message) = (sanitized.isEmpty || err.toLowerCase.contains("blank"), sanitized.length > 50) match
          case (true, _) => (ValidationErrorCode.REQUIRED_FIELD,  ValidationMessages.nameRequired)
          case (_, true) => (ValidationErrorCode.INVALID_LENGTH,  ValidationMessages.nameTooLong)
          case _         => (ValidationErrorCode.INVALID_PATTERN, ValidationMessages.nameInvalidChars)
        List(ValidationError(field = fieldPath, code = code, message = message))
      }
  }

  // Refinement for email; whitelist regex: local-part, @, domain with TLD
  def refineEmail(value: String, fieldPath: String = "email"): Either[List[ValidationError], Email.Email] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"]]
      .map(Email.Email(_))
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = ValidationMessages.emailInvalid
      )))
  }

  // Refinement for URL using shared service URL regex (allows localhost/IP/IPv6)
  def refineUrl(value: String, fieldPath: String = "url"): Either[List[ValidationError], Url.Url] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[UrlConstraint]
      .map(Url.Url(_))
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = ValidationMessages.urlInvalid
      )))
  }

  // Refinement for HTTPS-only URLs (SecureUrlConstraint).
  // Rejects http:// — use for any external service endpoint that will receive a credential in headers.
  // See ADR-001 §8.
  def refineSecureUrl(value: String, fieldPath: String = "url"): Either[List[ValidationError], SecureUrl.SecureUrl] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[SecureUrlConstraint]
      .map(SecureUrl.SecureUrl(_))
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = s"URL must be a valid HTTPS endpoint (http:// is not permitted for external service credentials)"
      )))
  }

  // Refinement for external service tokens (ExternalTokenStr).
  // Validates PrintableAscii + MaxLength[2048] — blocks CRLF injection and non-ASCII bytes.
  // Returns ExternalTokenStr; wrap in a credential final class (ADR-022 R1–R8) at the call site.
  // See ADR-001 §8.
  def refineExternalToken(value: String, fieldPath: String = "token"): Either[List[ValidationError], ExternalTokenStr] = {
    value
      .refineEither[Not[Blank] & MaxLength[2048] & PrintableAscii]
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = "Token must be non-empty, at most 2048 characters, and contain only printable ASCII (0x21\u20130x7E)"
      )))
  }

  /** True when every adjacent pair in `xs` satisfies a < b.  Single-element
   *  and empty sequences are trivially increasing. Used by both Distribution.create
   *  (server-side validation) and RiskLeafFormState (client-side form validation).
   */
  def isStrictlyIncreasing(xs: Seq[Double]): Boolean =
    xs.sliding(2).forall { case Seq(a, b) => a < b; case _ => true }

  // Refinement for non-negative long values
  def refineNonNegativeLong(value: Long, fieldPath: String = "value"): Either[List[ValidationError], NonNegativeLong] = {
    value
      .refineEither[GreaterEqual[0L]]
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = ValidationMessages.valueMustBeNonNegative
      )))
  }

  // Refinement for seed entity IDs (HDR Entity axis: 1 <= v < 10^8; 0 reserved)
  def refineSeedEntityId(value: Long, fieldPath: String = "seedEntityId"): Either[List[ValidationError], SeedEntityId.SeedEntityId] = {
    value
      .refineEither[GreaterEqual[1L] & Less[100000000L]]
      .map(SeedEntityId.SeedEntityId(_))
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = ValidationMessages.seedEntityIdOutOfRange
      )))
  }

  // Refinement for seed var IDs (HDR Var axis: 1 <= v < 5*10^7; doubled to 2k/2k+1)
  def refineSeedVarId(value: Long, fieldPath: String = "seedVarId"): Either[List[ValidationError], SeedVarId.SeedVarId] = {
    value
      .refineEither[GreaterEqual[1L] & Less[50000000L]]
      .map(SeedVarId.SeedVarId(_))
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = ValidationMessages.seedVarIdOutOfRange
      )))
  }

  // Refinement for probability (must be between 0.0 and 1.0, exclusive)
  def refineProbability(value: Double, fieldPath: String = "probability"): Either[List[ValidationError], Probability] = {
    value
      .refineEither[Greater[0.0] & Less[1.0]]
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = ValidationMessages.probabilityOutOfRange
      )))
  }

  // Refinement for occurrence probability (must be between 0.0 and 1.0, inclusive)
  def refineOccurrenceProbability(value: Double, fieldPath: String = "probability"): Either[List[ValidationError], OccurrenceProbability] = {
    value
      .refineEither[GreaterEqual[0.0] & LessEqual[1.0]]
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = ValidationMessages.occurrenceProbabilityOutOfRange
      )))
  }

  // Refinement for positive integers (must be > 0)
  def refinePositiveInt(value: Int, fieldPath: String = "value"): Either[List[ValidationError], PositiveInt] = {
    value
      .refineEither[Greater[0]]
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = ValidationMessages.valueMustBePositive
      )))
  }

  // Refinement for non-negative integers (must be >= 0)
  def refineNonNegativeInt(value: Int, fieldPath: String = "value"): Either[List[ValidationError], NonNegativeInt] = {
    value
      .refineEither[GreaterEqual[0]]
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = ValidationMessages.valueMustBeNonNegative
      )))
  }

  // Refinement for distribution type (must be "expert" or "lognormal")
  def refineDistributionType(value: String, fieldPath: String = "distributionType"): Either[List[ValidationError], DistributionType] = {
    value
      .refineEither[Match["^(expert|lognormal)$"]]
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_PATTERN,
        message = ValidationMessages.distributionTypeInvalid
      )))
  }

  // Refinement for risk/portfolio IDs (ULID, canonical uppercase Crockford base32, 26 chars)
  def refineId(value: String, fieldPath: String = "id"): Either[List[ValidationError], SafeId.SafeId] = {
    refineUlid(value, fieldPath, "ID", SafeId.SafeId(_))
  }

  // Refinement for tree IDs — delegates to refineId and wraps in TreeId case class.
  // @see ADR-018 for nominal wrapper pattern
  def refineTreeId(value: String, fieldPath: String = "treeId"): Either[List[ValidationError], TreeId] =
    refineId(value, fieldPath).map(TreeId(_))

  // Refinement for node IDs — delegates to refineId and wraps in NodeId case class.
  // @see ADR-018 for nominal wrapper pattern
  def refineNodeId(value: String, fieldPath: String = "nodeId"): Either[List[ValidationError], NodeId] =
    refineId(value, fieldPath).map(NodeId(_))

  // Refinement for workspace keys — validates base64url format (22 chars, no padding).
  // Standalone validation — NOT a ULID, different charset and length.
  def refineWorkspaceKeySecret(value: String, fieldPath: String = "workspaceKey"): Either[List[ValidationError], WorkspaceKeySecret] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Match["^[A-Za-z0-9_-]{22}$"]]
      .map(WorkspaceKeySecret(_)) // refined: WorkspaceKeyStr — Iron proof carried through to WorkspaceKeySecret.apply
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = ValidationMessages.workspaceKeyInvalid
      )))
  }

  // Refinement for workspace key hashes — validates SHA-256 lowercase hex format (64 chars).
  def refineWorkspaceKeyHash(value: String, fieldPath: String = "workspaceKeyHash"): Either[List[ValidationError], WorkspaceKeyHash] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Match["^[0-9a-f]{64}$"]]
      .map(WorkspaceKeyHash(_))
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = ValidationMessages.workspaceKeyHashInvalid
      )))
  }

  // Refinement for user IDs (UUID v4 format — as issued by Keycloak JWT sub claim via Istio x-user-id header injection).
  // Validates verbatim: Keycloak sub claims are lowercase UUID v4, 8-4-4-4-12 hex format.
  def refineUserId(value: String, fieldPath: String = "userId"): Either[List[ValidationError], UuidStr] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[UuidConstraint]
      .left
      .map(_ => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = ValidationMessages.userIdInvalid
      )))
  }

  // Refinement for optional short text (max 20 chars)
  def refineShortOptText(
      value: Option[String],
      fieldPath: String = "value"
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
          .map(_ =>
            List(ValidationError(
              field = fieldPath,
              code = ValidationErrorCode.INVALID_LENGTH,
              message = ValidationMessages.shortTextTooLong
            ))
          )
          .map(refined => Some(refined))
      }
  }

  // Shared canonical ULID string refinement
  private type UlidCanonical = String :| Match["^[0-9A-HJKMNP-TV-Z]{26}$"]

  // Shared ULID refinement for ID-like types with custom labels and field paths
  private def refineUlid[A](
      value: String,
      fieldPath: String,
      label: String,
      wrap: UlidCanonical => A
  ): Either[List[ValidationError], A] = {
    val sanitized = Option(value).map(_.trim).getOrElse("")
    val normalized = sanitized.toUpperCase
    ULID(normalized) match {
      case Right(parsed) =>
        val canonical = parsed.toString
        canonical
          .refineEither[Match["^[0-9A-HJKMNP-TV-Z]{26}$"]]
          .map(wrap)
          .left
          .map(err =>
            List(
              ValidationError(
                field = fieldPath,
                code = ValidationErrorCode.INVALID_FORMAT,
                message = s"$label '$sanitized' is not a valid ULID: $err"
              )
            )
          )
      case Left(err) =>
        Left(
          List(
            ValidationError(
              field = fieldPath,
              code = ValidationErrorCode.INVALID_FORMAT,
              message = s"$label '$sanitized' is not a valid ULID: ${err.getMessage}"
            )
          )
        )
    }
  }
}

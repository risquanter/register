package com.risquanter.register.domain.data.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.string.{Match, ValidURL}
import com.risquanter.register.domain.data.iron.{SafeName, Email, Url, SafeId, TreeId, NodeId}
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

  // Refinement for name; using a maximum length of 50
  def refineName(value: String, fieldPath: String = "name"): Either[List[ValidationError], SafeName.SafeName] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MaxLength[50]]
      .map(SafeName.SafeName(_))
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = if err.contains("Blank") then ValidationErrorCode.REQUIRED_FIELD else ValidationErrorCode.INVALID_LENGTH,
        message = s"Name '$sanitized' failed constraint check: $err"
      )))
  }

  // Refinement for email; using a maximum length of 50 and requiring single @ symbol
  def refineEmail(value: String, fieldPath: String = "email"): Either[List[ValidationError], Email.Email] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MaxLength[50] & Match["[^@]+@[^@]+"]]
      .map(Email.Email(_))
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = s"Email '$sanitized' is invalid: $err"
      )))
  }

  // Refinement for URL using shared service URL regex (allows localhost/IP/IPv6)
  def refineUrl(value: String, fieldPath: String = "url"): Either[List[ValidationError], Url.Url] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[UrlConstraint]
      .map(Url.Url(_))
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_FORMAT,
        message = s"URL '$sanitized' is invalid: $err"
      )))
  }

  // Refinement for non-negative long values
  def refineNonNegativeLong(value: Long, fieldPath: String = "value"): Either[List[ValidationError], NonNegativeLong] = {
    value
      .refineEither[GreaterEqual[0L]]
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = s"Value must be non-negative: $err"
      )))
  }

  // Refinement for probability (must be between 0.0 and 1.0, exclusive)
  def refineProbability(value: Double, fieldPath: String = "probability"): Either[List[ValidationError], Probability] = {
    value
      .refineEither[Greater[0.0] & Less[1.0]]
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = s"Value must be between 0.0 and 1.0 (exclusive): $err"
      )))
  }

  // Refinement for positive integers (must be > 0)
  def refinePositiveInt(value: Int, fieldPath: String = "value"): Either[List[ValidationError], PositiveInt] = {
    value
      .refineEither[Greater[0]]
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = s"Value must be positive (> 0): $err"
      )))
  }

  // Refinement for non-negative integers (must be >= 0)
  def refineNonNegativeInt(value: Int, fieldPath: String = "value"): Either[List[ValidationError], NonNegativeInt] = {
    value
      .refineEither[GreaterEqual[0]]
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_RANGE,
        message = s"Value must be non-negative (>= 0): $err"
      )))
  }

  // Refinement for distribution type (must be "expert" or "lognormal")
  def refineDistributionType(value: String, fieldPath: String = "distributionType"): Either[List[ValidationError], DistributionType] = {
    value
      .refineEither[Match["^(expert|lognormal)$"]]
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = ValidationErrorCode.INVALID_PATTERN,
        message = s"Distribution type '$value' must be either 'expert' or 'lognormal': $err"
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
          .map(err =>
            List(ValidationError(
              field = fieldPath,
              code = ValidationErrorCode.INVALID_LENGTH,
              message = s"Value failed constraint check: $err"
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

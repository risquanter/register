package com.risquanter.register.domain.data.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.string.{Match, ValidURL}
import com.risquanter.register.domain.data.iron.{SafeName, Email, Url, SafeId}
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

  // Refinement for risk/portfolio IDs (alphanumeric + hyphen/underscore, 3-30 chars)
  def refineId(value: String, fieldPath: String = "id"): Either[List[ValidationError], SafeId.SafeId] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MinLength[3] & MaxLength[30] & Match["^[a-zA-Z0-9_-]+$"]]
      .map(SafeId.SafeId(_))
      .left
      .map(err => List(ValidationError(
        field = fieldPath,
        code = if err.contains("MinLength") || err.contains("MaxLength") then ValidationErrorCode.INVALID_LENGTH else ValidationErrorCode.INVALID_PATTERN,
        message = s"ID '$sanitized' must be 3-30 alphanumeric characters (with _ or -): $err"
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
}

package com.risquanter.register.domain.data.iron

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.constraint.collection.{MaxLength, MinLength}
import io.github.iltotore.iron.constraint.string.{Match, ValidURL}
import com.risquanter.register.domain.data.iron.{SafeName, Email, Url, SafeId}

object ValidationUtil {

  // Helper: trim and safeguard against null values
  def nonEmpty(s: String): String = if (s == null) "" else s.trim

  // Refinement for name; using a maximum length of 50
  def refineName(value: String, fieldPath: String = "name"): Either[List[String], SafeName.SafeName] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MaxLength[50]]
      .map(SafeName.SafeName(_))
      .left
      .map(err => List(s"[$fieldPath] Name '$sanitized' failed constraint check: $err"))
  }

  // Refinement for email; using a maximum length of 50 and requiring single @ symbol
  def refineEmail(value: String, fieldPath: String = "email"): Either[List[String], Email.Email] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MaxLength[50] & Match["[^@]+@[^@]+"]]
      .map(Email.Email(_))
      .left
      .map(err => List(s"[$fieldPath] Email '$sanitized' is invalid: $err"))
  }

  // Refinement for URL using Iron's built-in ValidURL constraint
  def refineUrl(value: String, fieldPath: String = "url"): Either[List[String], Url.Url] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MaxLength[200] & ValidURL]
      .map(Url.Url(_))
      .left
      .map(err => List(s"[$fieldPath] URL '$sanitized' is invalid: $err"))
  }

  // Refinement for non-negative long values
  def refineNonNegativeLong(value: Long, fieldPath: String = "value"): Either[List[String], NonNegativeLong] = {
    value
      .refineEither[GreaterEqual[0L]]
      .left
      .map(err => List(s"[$fieldPath] Value must be non-negative: $err"))
  }

  // Refinement for probability (must be between 0.0 and 1.0, exclusive)
  def refineProbability(value: Double, fieldPath: String = "probability"): Either[List[String], Probability] = {
    value
      .refineEither[Greater[0.0] & Less[1.0]]
      .left
      .map(err => List(s"[$fieldPath] Value '$value' must be between 0.0 and 1.0: $err"))
  }

  // Refinement for positive integers (must be > 0)
  def refinePositiveInt(value: Int, fieldPath: String = "value"): Either[List[String], PositiveInt] = {
    value
      .refineEither[Greater[0]]
      .left
      .map(err => List(s"[$fieldPath] Value must be positive (> 0): $err"))
  }

  // Refinement for non-negative integers (must be >= 0)
  def refineNonNegativeInt(value: Int, fieldPath: String = "value"): Either[List[String], NonNegativeInt] = {
    value
      .refineEither[GreaterEqual[0]]
      .left
      .map(err => List(s"[$fieldPath] Value must be non-negative (>= 0): $err"))
  }

  // Refinement for distribution type (must be "expert" or "lognormal")
  def refineDistributionType(value: String, fieldPath: String = "distributionType"): Either[List[String], DistributionType] = {
    value
      .refineEither[Match["^(expert|lognormal)$"]]
      .left
      .map(err => List(s"[$fieldPath] Distribution type '$value' must be either 'expert' or 'lognormal': $err"))
  }

  // Refinement for risk/portfolio IDs (alphanumeric + hyphen/underscore, 3-30 chars)
  def refineId(value: String, fieldPath: String = "id"): Either[List[String], SafeId.SafeId] = {
    val sanitized = nonEmpty(value)
    sanitized
      .refineEither[Not[Blank] & MinLength[3] & MaxLength[30] & Match["^[a-zA-Z0-9_-]+$"]]
      .map(SafeId.SafeId(_))
      .left
      .map(err => List(s"[$fieldPath] ID '$sanitized' must be 3-30 alphanumeric characters (with _ or -): $err"))
  }

  // Refinement for optional short text (max 20 chars)
  def refineShortOptText(
      value: Option[String],
      fieldPath: String = "value"
  ): Either[List[String], Option[SafeExtraShortStr]] = value match {
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
            List(s"[$fieldPath] Value failed constraint check: $err")
          )
          .map(refined => Some(refined))
      }
  }
}

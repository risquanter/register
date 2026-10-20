package com.risquanter.register.domain.data.iron

import zio.test.*
import io.github.iltotore.iron.*

import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName}

object ValidationUtilSpec extends ZIOSpecDefault {

  /** These tests exercise what each refinement accepts, rejects and says — not
    * where it reports, which every caller supplies. */
  private val at: ErrorLocation = ErrorLocation.Whole

  /** A location distinguishable from `at`, for the tests that pin that the
    * location the caller passes is the one the error carries. */
  private val passedIn: ErrorLocation =
    ErrorLocation.Field(ErrorSubject.DraftNode, FieldName.MinLoss)

  def spec = suite("ValidationUtil")(
    suite("refineName")(
      test("accepts valid name") {
        val result = ValidationUtil.refineName("John Doe", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("John Doe")
        )
      },

      test("trims whitespace") {
        val result = ValidationUtil.refineName("  Jane Smith  ", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("Jane Smith")
        )
      },

      test("rejects blank string") {
        val result = ValidationUtil.refineName("   ", at)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message == ValidationMessages.nameRequired)
        )
      },

      test("rejects empty string") {
        val result = ValidationUtil.refineName("", at)
        assertTrue(result.isLeft)
      },

      test("rejects null") {
        val result = ValidationUtil.refineName(null, at)
        assertTrue(result.isLeft)
      },

      test("rejects string over 50 chars") {
        val longName = "a" * 51
        val result = ValidationUtil.refineName(longName, at)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message == ValidationMessages.nameTooLong)
        )
      },

      test("returns descriptive error message") {
        val result = ValidationUtil.refineName("", at)
        assertTrue(
          result.isLeft &&
          result.left.exists(errors =>
            errors.head.message == ValidationMessages.nameRequired
          )
        )
      }
    ),

    suite("refineScenarioName")(
      test("accepts valid name unchanged") {
        val result = ValidationUtil.refineScenarioName("stress-2026", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("stress-2026")
        )
      },

      test("folds letters to lowercase and maps space to hyphen") {
        val result = ValidationUtil.refineScenarioName("New Vendor Risk", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("new-vendor-risk")
        )
      },

      test("folds a contiguous run of spaces to a single hyphen, not one hyphen per space") {
        val result = ValidationUtil.refineScenarioName("New   Vendor    Risk", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("new-vendor-risk")
        )
      },

      test("accepts underscores and digits") {
        val result = ValidationUtil.refineScenarioName("vendor_risk_1", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("vendor_risk_1")
        )
      },

      test("trims surrounding whitespace") {
        val result = ValidationUtil.refineScenarioName("  stress-2026  ", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("stress-2026")
        )
      },

      test("rejects blank string") {
        val result = ValidationUtil.refineScenarioName("   ", at)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message == ValidationMessages.scenarioNameRequired)
        )
      },

      test("rejects null") {
        val result = ValidationUtil.refineScenarioName(null, at)
        assertTrue(result.isLeft)
      },

      test("rejects string over 64 chars") {
        val longName = "a" * 65
        val result = ValidationUtil.refineScenarioName(longName, at)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message == ValidationMessages.scenarioNameTooLong)
        )
      },

      test("rejects a character outside the whitelist, without stripping it (no lossy slugification)") {
        val result = ValidationUtil.refineScenarioName("stress!2026", at)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message == ValidationMessages.scenarioNameInvalidChars)
        )
      },

      test("rejects a name that folds to a leading hyphen") {
        val result = ValidationUtil.refineScenarioName("-leading-hyphen", at)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message == ValidationMessages.scenarioNameInvalidStart)
        )
      },

      test("rejects a name that folds to a leading underscore") {
        val result = ValidationUtil.refineScenarioName("_leading_underscore", at)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message == ValidationMessages.scenarioNameInvalidStart)
        )
      }
    ),

    suite("refineEmail")(
      test("accepts valid email with @") {
        val result = ValidationUtil.refineEmail("user@example.com", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("user@example.com")
        )
      },

      test("trims whitespace") {
        val result = ValidationUtil.refineEmail("  test@test.com  ", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("test@test.com")
        )
      },

      test("rejects blank email") {
        val result = ValidationUtil.refineEmail("   ", at)
        assertTrue(result.isLeft)
      },

      test("rejects email without @") {
        val result = ValidationUtil.refineEmail("notanemail", at)
        assertTrue(
          result.isLeft &&
          result.left.exists(errors =>
            errors.head.message.toLowerCase.contains("email") ||
            errors.head.message.contains("@")
          )
        )
      },

      test("rejects email with multiple @") {
        val result = ValidationUtil.refineEmail("user@@example.com", at)
        assertTrue(result.isLeft)
      },

      test("rejects string over 50 chars") {
        val longEmail = "a" * 45 + "@test.com"
        val result = ValidationUtil.refineEmail(longEmail, at)
        assertTrue(result.isLeft)
      }
    ),

    suite("refineUrl")(
      test("accepts valid url with domain") {
        val result = ValidationUtil.refineUrl("http://example.com", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("http://example.com")
        )
      },

      test("accepts url with https protocol") {
        val result = ValidationUtil.refineUrl("https://example.com", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("https://example.com")
        )
      },

      test("accepts url with http protocol") {
        val result = ValidationUtil.refineUrl("http://test.org", at)
        assertTrue(result.isRight)
      },

      test("trims whitespace") {
        val result = ValidationUtil.refineUrl("  http://test.org  ", at)
        assertTrue(
          result.isRight &&
          result.map(_.value).contains("http://test.org")
        )
      },

      test("rejects blank url") {
        val result = ValidationUtil.refineUrl("", at)
        assertTrue(result.isLeft)
      },

      test("rejects url without dot") {
        val result = ValidationUtil.refineUrl("notaurl", at)
        assertTrue(
          result.isLeft &&
          result.left.exists(errors =>
            errors.head.message.toLowerCase.contains("url") ||
            errors.head.message.contains(".")
          )
        )
      }
    ),

    suite("refineNonNegativeLong")(
      test("accepts zero") {
        val result = ValidationUtil.refineNonNegativeLong(0L, at)
        assertTrue(
          result.isRight &&
          result.contains(0L)
        )
      },

      test("accepts positive number") {
        val result = ValidationUtil.refineNonNegativeLong(100L, at)
        assertTrue(
          result.isRight &&
          result.contains(100L)
        )
      },

      test("rejects negative number") {
        val result = ValidationUtil.refineNonNegativeLong(-1L, at)
        assertTrue(result.isLeft)
      },

      test("the location the caller passed reaches the error") {
        val result = ValidationUtil.refineNonNegativeLong(-5L, passedIn)
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => errors.head.location == passedIn)
        )
      }
    ),

    suite("refinePositiveLong")(
      test("rejects zero — a lognormal bound feeds a logarithm") {
        val result = ValidationUtil.refinePositiveLong(0L, at)
        assertTrue(result.isLeft)
      },

      test("accepts one whole unit, the smallest representable bound") {
        val result = ValidationUtil.refinePositiveLong(1L, at)
        assertTrue(
          result.isRight &&
          result.contains(1L)
        )
      },

      test("rejects negative number") {
        val result = ValidationUtil.refinePositiveLong(-1L, at)
        assertTrue(result.isLeft)
      },

      test("the location the caller passed reaches the error") {
        val result = ValidationUtil.refinePositiveLong(0L, passedIn)
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => errors.head.location == passedIn)
        )
      }
    ),

    suite("refineRetentionFactor")(
      test("accepts the smallest positive double") {
        val result = ValidationUtil.refineRetentionFactor(Double.MinPositiveValue, at)
        assertTrue(result.isRight)
      },

      test("accepts a factor of exactly one — a mitigation that reduces nothing") {
        val result = ValidationUtil.refineRetentionFactor(1.0, at)
        assertTrue(
          result.isRight &&
          result.contains(1.0)
        )
      },

      test("rejects zero — no transform may assert a risk has ceased to exist") {
        val result = ValidationUtil.refineRetentionFactor(0.0, at)
        assertTrue(result.isLeft)
      },

      test("rejects a factor above one — an increase is stated through an Override") {
        assertTrue(
          ValidationUtil.refineRetentionFactor(1.0000001, at).isLeft,
          ValidationUtil.refineRetentionFactor(2.0, at).isLeft
        )
      },

      test("rejects not-a-number, which fails the lower bound under IEEE 754") {
        val result = ValidationUtil.refineRetentionFactor(Double.NaN, at)
        assertTrue(result.isLeft)
      },

      test("the location the caller passed reaches the error") {
        val result = ValidationUtil.refineRetentionFactor(0.0, passedIn)
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => errors.head.location == passedIn)
        )
      }
    ),

    suite("refineResidualProbability")(
      test("accepts the smallest positive double") {
        assertTrue(ValidationUtil.refineResidualProbability(Double.MinPositiveValue, at).isRight)
      },

      test("accepts a probability of exactly one") {
        val result = ValidationUtil.refineResidualProbability(1.0, at)
        assertTrue(
          result.isRight &&
          result.contains(1.0)
        )
      },

      test("rejects zero — a mitigation cannot assert a risk has been prevented") {
        assertTrue(ValidationUtil.refineResidualProbability(0.0, at).isLeft)
      },

      test("rejects a probability above one") {
        assertTrue(
          ValidationUtil.refineResidualProbability(1.0000001, at).isLeft,
          ValidationUtil.refineResidualProbability(2.0, at).isLeft
        )
      },

      test("rejects not-a-number, which fails the lower bound under IEEE 754") {
        assertTrue(ValidationUtil.refineResidualProbability(Double.NaN, at).isLeft)
      },

      test("the location the caller passed reaches the error") {
        val result = ValidationUtil.refineResidualProbability(0.0, passedIn)
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => errors.head.location == passedIn)
        )
      }
    ),

    suite("refineLossCap")(
      test("accepts a cap of one, the smallest loss the entity can bear") {
        val result = ValidationUtil.refineLossCap(1L, at)
        assertTrue(
          result.isRight &&
          result.contains(1L)
        )
      },

      test("rejects zero — the entity would bear nothing") {
        assertTrue(ValidationUtil.refineLossCap(0L, at).isLeft)
      },

      test("rejects a negative cap") {
        assertTrue(ValidationUtil.refineLossCap(-1L, at).isLeft)
      },

      test("its message states why, not only the bound") {
        val result = ValidationUtil.refineLossCap(0L, at)
        assertTrue(
          result.isLeft &&
          result.left.exists(_.head.message.contains("transfer cannot fail"))
        )
      }
    ),

    suite("refineProbability")(
      test("accepts value between 0 and 1") {
        val result = ValidationUtil.refineProbability(0.5, at)
        assertTrue(
          result.isRight &&
          result.contains(0.5)
        )
      },

      test("rejects 0.0") {
        val result = ValidationUtil.refineProbability(0.0, at)
        assertTrue(result.isLeft)
      },

      test("rejects 1.0") {
        val result = ValidationUtil.refineProbability(1.0, at)
        assertTrue(result.isLeft)
      },

      test("rejects negative value") {
        val result = ValidationUtil.refineProbability(-0.1, at)
        assertTrue(result.isLeft)
      },

      test("rejects value over 1") {
        val result = ValidationUtil.refineProbability(1.5, at)
        assertTrue(result.isLeft)
      }
    ),

    suite("refineOccurrenceProbability")(
      test("accepts value between 0 and 1 exclusive") {
        val result = ValidationUtil.refineOccurrenceProbability(0.5, at)
        assertTrue(result.isRight && result.contains(0.5))
      },

      test("accepts 0.0 (event that never occurs)") {
        val result = ValidationUtil.refineOccurrenceProbability(0.0, at)
        assertTrue(result.isRight && result.contains(0.0))
      },

      test("accepts 1.0 (event that always occurs)") {
        val result = ValidationUtil.refineOccurrenceProbability(1.0, at)
        assertTrue(result.isRight && result.contains(1.0))
      },

      test("rejects value below 0.0") {
        val result = ValidationUtil.refineOccurrenceProbability(-0.001, at)
        assertTrue(result.isLeft)
      },

      test("rejects value above 1.0") {
        val result = ValidationUtil.refineOccurrenceProbability(1.001, at)
        assertTrue(result.isLeft)
      },

      test("error message references inclusive range") {
        val result = ValidationUtil.refineOccurrenceProbability(-0.1, at)
        assertTrue(result.left.exists(errs =>
          errs.exists(e => e.message.contains("inclusive"))
        ))
      },

      test("the location the caller passed reaches the error") {
        val result = ValidationUtil.refineOccurrenceProbability(-0.1, passedIn)
        assertTrue(result.left.exists(errs =>
          errs.exists(e => e.location == passedIn)
        ))
      }
    ),

    suite("refineShortOptText")(
      test("accepts None") {
        val result = ValidationUtil.refineShortOptText(None, at)
        assertTrue(
          result.isRight &&
          result.contains(None)
        )
      },

      test("accepts Some with valid text") {
        val result = ValidationUtil.refineShortOptText(Some("Valid"), at)
        assertTrue(
          result.isRight &&
          result.exists(_.isDefined)
        )
      },

      test("trims whitespace in Some") {
        val result = ValidationUtil.refineShortOptText(Some("  Valid  "), at)
        assertTrue(
          result.isRight &&
          result.exists(opt => opt.map(_ == "Valid").getOrElse(false))
        )
      },

      test("converts Some with blank to None") {
        val result = ValidationUtil.refineShortOptText(Some("   "), at)
        assertTrue(
          result.isRight &&
          result.contains(None)
        )
      },

      test("converts Some with empty string to None") {
        val result = ValidationUtil.refineShortOptText(Some(""), at)
        assertTrue(
          result.isRight &&
          result.contains(None)
        )
      },

      test("rejects Some with string over 20 chars") {
        val result = ValidationUtil.refineShortOptText(Some("a" * 21), at)
        assertTrue(result.isLeft)
      },

      test("the location the caller passed reaches the error") {
        val result = ValidationUtil.refineShortOptText(Some("a" * 25), passedIn)
        assertTrue(
          result.isLeft &&
          result.left.exists(errors => errors.head.location == passedIn)
        )
      }
    ),

    suite("nonEmpty helper")(
      test("trims whitespace") {
        val result = ValidationUtil.nonEmpty("  hello  ")
        assertTrue(result == "hello")
      },

      test("handles null as empty string") {
        val result = ValidationUtil.nonEmpty(null)
        assertTrue(result == "")
      },

      test("preserves non-blank content") {
        val result = ValidationUtil.nonEmpty("Valid Name")
        assertTrue(result == "Valid Name")
      }
    )
  )
}

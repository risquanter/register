package com.risquanter.register.domain.data

import zio.test.*
import zio.prelude.Validation

import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode
}

object DistributionSpec extends ZIOSpecDefault:

  private def assertFailWithCode(
    v: Validation[ValidationError, ?],
    field: FieldName,
    code: ValidationErrorCode
  ) =
    val at = ErrorLocation.Field(ErrorSubject.Request, field)
    v match
      case Validation.Failure(_, errs) =>
        val matches = errs.exists(e => e.location == at && e.code == code)
        assertTrue(matches).label(
          s"Expected FAILURE with code $code at $at, got: " +
            errs.map(e => s"${ErrorLocation.describe(e.location)}/${e.code}").mkString(", ")
        )
      case Validation.Success(_, _) =>
        assertTrue(false).label(s"Expected failure at $at with code $code but got Success")

  // ── Expert mode happy path ────────────────────────────────────────

  private def validExpertCreate =
    Distribution.create(
      distributionType = "expert",
      minLoss          = None,
      maxLoss          = None,
      percentiles      = Some(Array(0.05, 0.50, 0.95)),
      quantiles        = Some(Array(1000.0, 5000.0, 25000.0)),
      subject          = ErrorSubject.Request
    )

  def spec = suite("Distribution.create")(

    suite("expert mode — happy path")(

      test("valid monotone expert inputs succeed") {
        assertTrue(validExpertCreate.isSuccess)
      },

      test("single anchor point succeeds (no ordering check needed)") {
        assertTrue(
          Distribution.create(
            distributionType = "expert",
            minLoss          = None,
            maxLoss          = None,
            percentiles      = Some(Array(0.5)),
            quantiles        = Some(Array(5000.0)),
            subject          = ErrorSubject.Request
          ).isSuccess
        )
      }

    ),

    suite("expert mode — quantile positivity")(

      test("a quantile of exactly zero is rejected — the metalog fit takes its logarithm") {
        val result = Distribution.create(
          distributionType = "expert",
          minLoss          = None,
          maxLoss          = None,
          percentiles      = Some(Array(0.05, 0.50, 0.95)),
          quantiles        = Some(Array(0.0, 5000.0, 25000.0)),
          subject          = ErrorSubject.Request
        )
        assertFailWithCode(result, FieldName.quantileAt(0), ValidationErrorCode.INVALID_RANGE)
      },

      test("the smallest positive quantile is accepted") {
        assertTrue(
          Distribution.create(
            distributionType = "expert",
            minLoss          = None,
            maxLoss          = None,
            percentiles      = Some(Array(0.05, 0.50, 0.95)),
            quantiles        = Some(Array(Double.MinPositiveValue, 5000.0, 25000.0)),
            subject          = ErrorSubject.Request
          ).isSuccess
        )
      },

      test("a negative quantile is still rejected") {
        val result = Distribution.create(
          distributionType = "expert",
          minLoss          = None,
          maxLoss          = None,
          percentiles      = Some(Array(0.05, 0.50, 0.95)),
          quantiles        = Some(Array(-1.0, 5000.0, 25000.0)),
          subject          = ErrorSubject.Request
        )
        assertFailWithCode(result, FieldName.quantileAt(0), ValidationErrorCode.INVALID_RANGE)
      }

    ),

    suite("requireStrictlyIncreasing — quantiles")(

      test("non-monotone quantiles produce INVALID_COMBINATION on request.quantiles") {
        val result = Distribution.create(
          distributionType = "expert",
          minLoss          = None,
          maxLoss          = None,
          percentiles      = Some(Array(0.05, 0.50, 0.95)),
          quantiles        = Some(Array(5000.0, 1000.0, 25000.0)),  // decreasing: 5000 → 1000
          subject          = ErrorSubject.Request
        )
        assertFailWithCode(result, FieldName.Quantiles, ValidationErrorCode.INVALID_COMBINATION)
      },

      test("equal adjacent quantiles produce INVALID_COMBINATION (strictly increasing required)") {
        val result = Distribution.create(
          distributionType = "expert",
          minLoss          = None,
          maxLoss          = None,
          percentiles      = Some(Array(0.05, 0.50, 0.95)),
          quantiles        = Some(Array(1000.0, 1000.0, 25000.0)),  // equal: 1000 = 1000
          subject          = ErrorSubject.Request
        )
        assertFailWithCode(result, FieldName.Quantiles, ValidationErrorCode.INVALID_COMBINATION)
      }

    ),

    suite("requireStrictlyIncreasing — percentiles")(

      test("non-monotone percentiles produce INVALID_COMBINATION on request.percentiles") {
        val result = Distribution.create(
          distributionType = "expert",
          minLoss          = None,
          maxLoss          = None,
          percentiles      = Some(Array(0.9, 0.1, 0.5)),  // out of order
          quantiles        = Some(Array(1000.0, 5000.0, 25000.0)),
          subject          = ErrorSubject.Request
        )
        assertFailWithCode(result, FieldName.Percentiles, ValidationErrorCode.INVALID_COMBINATION)
      },

      test("equal adjacent percentiles produce INVALID_COMBINATION") {
        val result = Distribution.create(
          distributionType = "expert",
          minLoss          = None,
          maxLoss          = None,
          percentiles      = Some(Array(0.05, 0.05, 0.95)),  // equal: 0.05 = 0.05
          quantiles        = Some(Array(1000.0, 5000.0, 25000.0)),
          subject          = ErrorSubject.Request
        )
        assertFailWithCode(result, FieldName.Percentiles, ValidationErrorCode.INVALID_COMBINATION)
      }

    ),

    suite("requireStrictlyIncreasing — error accumulation")(

      test("both non-monotone percentiles and quantiles fail simultaneously (not short-circuit)") {
        val result = Distribution.create(
          distributionType = "expert",
          minLoss          = None,
          maxLoss          = None,
          percentiles      = Some(Array(0.9, 0.1, 0.5)),    // out of order
          quantiles        = Some(Array(5000.0, 1000.0, 25000.0)),  // out of order
          subject          = ErrorSubject.Request
        )
        result match
          case Validation.Failure(_, errs) =>
            val locations = errs.map(_.location).toList
            assertTrue(
              locations.contains(ErrorLocation.requestField(FieldName.Percentiles)),
              locations.contains(ErrorLocation.requestField(FieldName.Quantiles))
            ).label(
              "Expected errors on both percentiles and quantiles, got: " +
                locations.map(ErrorLocation.describe).mkString(", ")
            )
          case Validation.Success(_, _) =>
            assertTrue(false).label("Expected failure but got Success")
      }

    ),

    suite("lognormal mode")(

      test("valid lognormal inputs succeed") {
        assertTrue(
          Distribution.create(
            distributionType = "lognormal",
            minLoss          = Some(1000L),
            maxLoss          = Some(50000L),
            percentiles      = None,
            quantiles        = None,
            subject          = ErrorSubject.Request
          ).isSuccess
        )
      }

    )

  )

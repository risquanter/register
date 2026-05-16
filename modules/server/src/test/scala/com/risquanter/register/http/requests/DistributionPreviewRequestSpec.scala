package com.risquanter.register.http.requests

import zio.prelude.Validation
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.domain.errors.ValidationErrorCode

object DistributionPreviewRequestSpec extends ZIOSpecDefault:

  // ── helpers ────────────────────────────────────────────────────────────────

  private def expertReq(
    percentiles: Array[Double] = Array(5.0, 50.0, 95.0),
    quantiles:   Array[Double] = Array(1000.0, 5000.0, 25000.0),
    terms:       Option[Int]   = None
  ): DistributionPreviewRequest =
    DistributionPreviewRequest(
      distributionType = "expert",
      percentiles      = Some(percentiles),
      quantiles        = Some(quantiles),
      terms            = terms,
      minLoss          = None,
      maxLoss          = None
    )

  private def lognormalReq(
    minLoss: Long = 1_000L,
    maxLoss: Long = 50_000L
  ): DistributionPreviewRequest =
    DistributionPreviewRequest(
      distributionType = "lognormal",
      percentiles      = None,
      quantiles        = None,
      terms            = None,
      minLoss          = Some(minLoss),
      maxLoss          = Some(maxLoss)
    )

  private def isFailWithCode(
    v:    Validation[?, ?],
    code: ValidationErrorCode
  ): Boolean = v match
    case Validation.Failure(_, errs) =>
      errs.exists {
        case e: com.risquanter.register.domain.errors.ValidationError => e.code == code
        case _                                                          => false
      }
    case _ => false

  // ── spec ───────────────────────────────────────────────────────────────────

  def spec = suite("DistributionPreviewRequest.validate")(

    // ── valid inputs ──────────────────────────────────────────────────────────

    suite("valid expert requests")(

      test("accepts a 3-anchor expert request without terms") {
        assertTrue(DistributionPreviewRequest.validate(expertReq()).isSuccess)
      },

      test("accepts expert request with terms ≤ anchor count") {
        assertTrue(DistributionPreviewRequest.validate(expertReq(terms = Some(3))).isSuccess)
      },

      test("accepts expert request with terms equal to 1") {
        // Edge: minimum plausible terms value
        assertTrue(
          DistributionPreviewRequest.validate(
            expertReq(terms = Some(1))
          ).isSuccess
        )
      }
    ),

    suite("valid lognormal requests")(

      test("accepts a lognormal request with minLoss < maxLoss") {
        assertTrue(DistributionPreviewRequest.validate(lognormalReq()).isSuccess)
      },

      test("accepts lognormal with adjacent values") {
        assertTrue(DistributionPreviewRequest.validate(lognormalReq(minLoss = 1L, maxLoss = 2L)).isSuccess)
      }
    ),

    // ── distributionType errors ───────────────────────────────────────────────

    suite("distributionType validation")(

      test("rejects unknown distribution type") {
        val result = DistributionPreviewRequest.validate(
          DistributionPreviewRequest("unknown", None, None, None, None, None)
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.UNSUPPORTED_DISTRIBUTION_TYPE))
      },

      test("rejects empty-string distribution type") {
        val result = DistributionPreviewRequest.validate(
          DistributionPreviewRequest("", None, None, None, None, None)
        )
        assertTrue(result.isFailure)
      }
    ),

    // ── expert-mode cross-field errors ────────────────────────────────────────

    suite("expert mode cross-field rules")(

      test("rejects expert when percentiles are missing") {
        val req    = expertReq().copy(percentiles = None)
        val result = DistributionPreviewRequest.validate(req)
        assertTrue(isFailWithCode(result, ValidationErrorCode.REQUIRED_FIELD))
      },

      test("rejects expert when quantiles are missing") {
        val req    = expertReq().copy(quantiles = None)
        val result = DistributionPreviewRequest.validate(req)
        assertTrue(isFailWithCode(result, ValidationErrorCode.REQUIRED_FIELD))
      },

      test("rejects expert when percentile and quantile lengths differ") {
        val result = DistributionPreviewRequest.validate(
          expertReq(
            percentiles = Array(5.0, 50.0, 95.0),
            quantiles   = Array(1000.0, 5000.0)
          )
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_COMBINATION))
      },

      test("rejects expert when terms exceeds anchor count") {
        val result = DistributionPreviewRequest.validate(
          expertReq(terms = Some(4)) // 3 anchors, terms = 4
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_COMBINATION))
      },

      // ── quantile monotonicity ────────────────────────────────────────────

      test("rejects expert when quantiles are strictly decreasing") {
        val result = DistributionPreviewRequest.validate(
          expertReq(quantiles = Array(25_000.0, 5_000.0, 1_000.0))
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_COMBINATION))
      },

      test("rejects expert when quantiles are non-monotone (valley)") {
        val result = DistributionPreviewRequest.validate(
          expertReq(quantiles = Array(1_000.0, 25_000.0, 5_000.0))
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_COMBINATION))
      },

      test("rejects expert when quantiles contain a repeated value (not strictly increasing)") {
        val result = DistributionPreviewRequest.validate(
          expertReq(
            percentiles = Array(5.0, 50.0, 95.0),
            quantiles   = Array(1_000.0, 1_000.0, 25_000.0)
          )
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_COMBINATION))
      },

      test("accepts expert when quantiles are strictly increasing") {
        val result = DistributionPreviewRequest.validate(
          expertReq(quantiles = Array(1_000.0, 5_000.0, 25_000.0))
        )
        assertTrue(result.isSuccess)
      },

      test("accepts expert with two anchors (monotonicity edge case)") {
        val result = DistributionPreviewRequest.validate(
          expertReq(
            percentiles = Array(5.0, 95.0),
            quantiles   = Array(1_000.0, 25_000.0)
          )
        )
        assertTrue(result.isSuccess)
      }
    ),

    // ── lognormal-mode cross-field errors ─────────────────────────────────────

    suite("lognormal mode cross-field rules")(

      test("rejects lognormal when minLoss is missing") {
        val req    = lognormalReq().copy(minLoss = None)
        val result = DistributionPreviewRequest.validate(req)
        assertTrue(isFailWithCode(result, ValidationErrorCode.REQUIRED_FIELD))
      },

      test("rejects lognormal when maxLoss is missing") {
        val req    = lognormalReq().copy(maxLoss = None)
        val result = DistributionPreviewRequest.validate(req)
        assertTrue(isFailWithCode(result, ValidationErrorCode.REQUIRED_FIELD))
      },

      test("rejects lognormal when minLoss equals maxLoss") {
        val result = DistributionPreviewRequest.validate(
          lognormalReq(minLoss = 10_000L, maxLoss = 10_000L)
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_LOGNORMAL_PARAMS))
      },

      test("rejects lognormal when minLoss > maxLoss") {
        val result = DistributionPreviewRequest.validate(
          lognormalReq(minLoss = 50_000L, maxLoss = 1_000L)
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_LOGNORMAL_PARAMS))
      }
    )
  )

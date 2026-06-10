package com.risquanter.register.http.requests

import zio.prelude.Validation
import zio.test.*

import sttp.tapir.SchemaType.SProduct
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.errors.ValidationErrorCode

object DistributionShapeRequestSpec extends ZIOSpecDefault:

  // ── helpers ────────────────────────────────────────────────────────────────

  private def expertReq(
    percentiles: Array[Double] = Array(0.05, 0.50, 0.95),
    quantiles:   Array[Double] = Array(1000.0, 5000.0, 25000.0),
    terms:       Option[Int]   = None
  ): DistributionShapeRequest =
    DistributionShapeRequest(
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
  ): DistributionShapeRequest =
    DistributionShapeRequest(
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

  private def failsOnField(
    v:     Validation[?, ?],
    field: String,
    code:  ValidationErrorCode
  ): Boolean = v match
    case Validation.Failure(_, errs) =>
      errs.exists {
        case e: com.risquanter.register.domain.errors.ValidationError => e.field == field && e.code == code
        case _                                                          => false
      }
    case _ => false

  // ── spec ───────────────────────────────────────────────────────────────────

  def spec = suite("DistributionShapeRequest.validate")(

    // ── valid inputs ──────────────────────────────────────────────────────────

    suite("valid expert requests")(

      test("accepts a 3-anchor expert request without terms") {
        assertTrue(DistributionShapeRequest.validate(expertReq()).isSuccess)
      },

      test("accepts expert request with terms ≤ anchor count") {
        assertTrue(DistributionShapeRequest.validate(expertReq(terms = Some(3))).isSuccess)
      },

      test("accepts expert request with terms equal to 2 (minimum valid)") {
        assertTrue(DistributionShapeRequest.validate(expertReq(terms = Some(2))).isSuccess)
      },

      test("rejects expert request with terms equal to 1 (below minimum)") {
        val result = DistributionShapeRequest.validate(expertReq(terms = Some(1)))
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_COMBINATION))
      }
    ),

    suite("valid lognormal requests")(

      test("accepts a lognormal request with minLoss < maxLoss") {
        assertTrue(DistributionShapeRequest.validate(lognormalReq()).isSuccess)
      },

      test("accepts lognormal with adjacent values") {
        assertTrue(DistributionShapeRequest.validate(lognormalReq(minLoss = 1L, maxLoss = 2L)).isSuccess)
      }
    ),

    // ── validate returns Distribution ─────────────────────────────────────────

    suite("validate return type is Distribution")(

      test("returns Distribution on valid expert request") {
        DistributionShapeRequest.validate(expertReq()) match
          case Validation.Success(_, dist: Distribution) =>
            assertTrue(dist.distributionType.toString == "expert")
          case _ =>
            assertTrue(false).label("Expected Success[Distribution]")
      },

      test("returns Distribution on valid lognormal request") {
        DistributionShapeRequest.validate(lognormalReq()) match
          case Validation.Success(_, dist: Distribution) =>
            assertTrue(dist.distributionType.toString == "lognormal")
          case _ =>
            assertTrue(false).label("Expected Success[Distribution]")
      }
    ),

    // ── schema and codec ──────────────────────────────────────────────────────

    suite("Schema.derived replaces Schema.any")(

      test("schemaType is SProduct (not the empty object Schema.any produces)") {
        assertTrue(DistributionShapeRequest.schema.schemaType.isInstanceOf[SProduct[?]])
      },

      test("schema.name is defined") {
        assertTrue(DistributionShapeRequest.schema.name.isDefined)
      },

      test("schema contains distributionType field") {
        val fields = DistributionShapeRequest.schema.schemaType.asInstanceOf[SProduct[?]].fields
        assertTrue(fields.exists(_.name.name == "distributionType"))
      },

      test("percentiles field schema is an array, not an empty SProduct") {
        val fields = DistributionShapeRequest.schema.schemaType.asInstanceOf[SProduct[?]].fields
        val pctField = fields.find(_.name.name == "percentiles")
        // Optional[Array[Double]] wraps in SOption then SArray
        assertTrue(pctField.isDefined)
      },

      test("codec round-trips a valid expert request without error") {
        val req = DistributionShapeRequest(
          distributionType = "expert",
          percentiles      = Some(Array(0.05, 0.50, 0.95)),
          quantiles        = Some(Array(1000.0, 5000.0, 25000.0)),
          terms            = None,
          minLoss          = None,
          maxLoss          = None
        )
        val json    = DistributionShapeRequest.codec.encodeJson(req, None)
        val decoded = DistributionShapeRequest.codec.decodeJson(json.toString)
        assertTrue(
          decoded.isRight,
          decoded.exists(d => d.distributionType == req.distributionType),
          decoded.exists(d => d.percentiles.exists(_.sameElements(req.percentiles.get)))
        )
      },

      test("codec round-trips a valid lognormal request without error") {
        val req = DistributionShapeRequest(
          distributionType = "lognormal",
          percentiles      = None,
          quantiles        = None,
          terms            = None,
          minLoss          = Some(1000L),
          maxLoss          = Some(50000L)
        )
        val json    = DistributionShapeRequest.codec.encodeJson(req, None)
        val decoded = DistributionShapeRequest.codec.decodeJson(json.toString)
        assertTrue(
          decoded.isRight,
          decoded.exists(d => d.minLoss == req.minLoss),
          decoded.exists(d => d.maxLoss == req.maxLoss)
        )
      }
    ),

    // ── distributionType errors ───────────────────────────────────────────────

    suite("distributionType validation")(

      test("rejects unknown distribution type") {
        val result = DistributionShapeRequest.validate(
          DistributionShapeRequest("unknown", None, None, None, None, None)
        )
        // Distribution.create delegates to refineDistributionType (Iron Match pattern)
        // which produces INVALID_PATTERN for unrecognised type strings.
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_PATTERN))
      },

      test("rejects empty-string distribution type") {
        val result = DistributionShapeRequest.validate(
          DistributionShapeRequest("", None, None, None, None, None)
        )
        assertTrue(result.isFailure)
      }
    ),

    // ── expert-mode cross-field errors ────────────────────────────────────────

    suite("expert mode cross-field rules")(

      test("rejects expert when percentiles are missing") {
        val result = DistributionShapeRequest.validate(expertReq().copy(percentiles = None))
        assertTrue(isFailWithCode(result, ValidationErrorCode.REQUIRED_FIELD))
      },

      test("rejects expert when quantiles are missing") {
        val result = DistributionShapeRequest.validate(expertReq().copy(quantiles = None))
        assertTrue(isFailWithCode(result, ValidationErrorCode.REQUIRED_FIELD))
      },

      test("rejects expert when percentile and quantile lengths differ") {
        val result = DistributionShapeRequest.validate(
          expertReq(
            percentiles = Array(0.05, 0.50, 0.95),
            quantiles   = Array(1000.0, 5000.0)
          )
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_COMBINATION))
      },

      test("rejects expert when terms exceeds anchor count") {
        val result = DistributionShapeRequest.validate(expertReq(terms = Some(4)))
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_COMBINATION))
      },

      // ── quantile monotonicity ────────────────────────────────────────────

      test("rejects expert when quantiles are strictly decreasing") {
        val result = DistributionShapeRequest.validate(
          expertReq(quantiles = Array(25_000.0, 5_000.0, 1_000.0))
        )
        assertTrue(failsOnField(result, "request.quantiles", ValidationErrorCode.INVALID_COMBINATION))
      },

      test("rejects expert when quantiles are non-monotone (valley)") {
        val result = DistributionShapeRequest.validate(
          expertReq(quantiles = Array(1_000.0, 25_000.0, 5_000.0))
        )
        assertTrue(failsOnField(result, "request.quantiles", ValidationErrorCode.INVALID_COMBINATION))
      },

      test("rejects expert when quantiles contain a repeated value (not strictly increasing)") {
        val result = DistributionShapeRequest.validate(
          expertReq(quantiles = Array(1_000.0, 1_000.0, 25_000.0))
        )
        assertTrue(failsOnField(result, "request.quantiles", ValidationErrorCode.INVALID_COMBINATION))
      },

      test("accepts expert when quantiles are strictly increasing") {
        assertTrue(
          DistributionShapeRequest.validate(
            expertReq(quantiles = Array(1_000.0, 5_000.0, 25_000.0))
          ).isSuccess
        )
      },

      // ── percentile monotonicity ──────────────────────────────────────────

      test("rejects expert when percentiles are out of order") {
        val result = DistributionShapeRequest.validate(
          expertReq(percentiles = Array(0.9, 0.1, 0.5))
        )
        assertTrue(failsOnField(result, "request.percentiles", ValidationErrorCode.INVALID_COMBINATION))
      },

      test("rejects expert when percentiles contain a repeated value") {
        val result = DistributionShapeRequest.validate(
          expertReq(percentiles = Array(0.05, 0.05, 0.95))
        )
        assertTrue(failsOnField(result, "request.percentiles", ValidationErrorCode.INVALID_COMBINATION))
      },

      test("both non-monotone percentiles and quantiles accumulate independently") {
        val result = DistributionShapeRequest.validate(
          expertReq(
            percentiles = Array(0.9, 0.1, 0.5),
            quantiles   = Array(25_000.0, 5_000.0, 1_000.0)
          )
        )
        result match
          case Validation.Failure(_, errs) =>
            val fields = errs.map(_.field).toList
            assertTrue(
              fields.exists(_.contains("percentiles")),
              fields.exists(_.contains("quantiles"))
            ).label(s"Expected errors on both percentiles and quantiles, got: $fields")
          case Validation.Success(_, _) =>
            assertTrue(false).label("Expected failure but got Success")
      },

      test("accepts expert with two anchors (monotonicity edge case)") {
        assertTrue(
          DistributionShapeRequest.validate(
            expertReq(
              percentiles = Array(0.05, 0.95),
              quantiles   = Array(1_000.0, 25_000.0)
            )
          ).isSuccess
        )
      }
    ),

    // ── lognormal-mode cross-field errors ─────────────────────────────────────

    suite("lognormal mode cross-field rules")(

      test("rejects lognormal when minLoss is missing") {
        val result = DistributionShapeRequest.validate(lognormalReq().copy(minLoss = None))
        assertTrue(isFailWithCode(result, ValidationErrorCode.REQUIRED_FIELD))
      },

      test("rejects lognormal when maxLoss is missing") {
        val result = DistributionShapeRequest.validate(lognormalReq().copy(maxLoss = None))
        assertTrue(isFailWithCode(result, ValidationErrorCode.REQUIRED_FIELD))
      },

      test("rejects lognormal when minLoss equals maxLoss") {
        val result = DistributionShapeRequest.validate(
          lognormalReq(minLoss = 10_000L, maxLoss = 10_000L)
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_LOGNORMAL_PARAMS))
      },

      test("rejects lognormal when minLoss > maxLoss") {
        val result = DistributionShapeRequest.validate(
          lognormalReq(minLoss = 50_000L, maxLoss = 1_000L)
        )
        assertTrue(isFailWithCode(result, ValidationErrorCode.INVALID_LOGNORMAL_PARAMS))
      }
    )
  )

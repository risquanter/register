package com.risquanter.register.domain.data

import zio.test.*
import zio.prelude.Validation
import zio.json.{DecoderOps, EncoderOps}

import com.risquanter.register.domain.data.iron.ValidationMessages
import com.risquanter.register.domain.errors.{
  ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode
}

/** Every construction rule the shape coproduct holds, asserted at the layer that
  * refuses it rather than downstream.
  *
  * Two things the suite is checking beyond each individual rule. A rule that a
  * field type carries — a percentile's range, a loss's positivity, both
  * lognormal bounds' positivity — is refused by that type and reported against
  * the element's own location, so the test names the element. And a rule
  * relating several values — the anchor count, both ordering rules, the term
  * count against the anchor count, the bound ordering — is refused by the
  * sequence's or the pair's own gate, which is the only way in.
  */
object DistributionShapeSpec extends ZIOSpecDefault:

  private def assertFailWithCode(
    v:     Validation[ValidationError, ?],
    field: FieldName,
    code:  ValidationErrorCode
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

  private def expert(
    percentiles: Vector[Double],
    quantiles:   Vector[Double],
    terms:       Option[Int]
  ): Validation[ValidationError, DistributionShape.Expert] =
    DistributionShape.Expert.fromPrimitives(percentiles, quantiles, terms, ErrorSubject.Request)

  private def lognormal(minLoss: Long, maxLoss: Long): Validation[ValidationError, DistributionShape.Lognormal] =
    DistributionShape.Lognormal.fromPrimitives(minLoss, maxLoss, ErrorSubject.Request)

  private val threePercentiles = Vector(0.05, 0.50, 0.95)
  private val threeQuantiles   = Vector(1000.0, 5000.0, 25000.0)

  def spec = suite("DistributionShape")(

    suite("expert — happy path")(

      test("monotone anchors with no stated term count succeed") {
        assertTrue(expert(threePercentiles, threeQuantiles, None).isSuccess)
      },

      test("a stated term count equal to the anchor count succeeds") {
        assertTrue(expert(threePercentiles, threeQuantiles, Some(3)).isSuccess)
      },

      test("a stated term count below the anchor count succeeds — it smooths the fit") {
        assertTrue(expert(threePercentiles, threeQuantiles, Some(2)).isSuccess)
      }

    ),

    suite("expert — percentile range, carried by the anchor's field type")(

      test("a percentile of exactly zero is refused") {
        assertFailWithCode(
          expert(Vector(0.0, 0.50, 0.95), threeQuantiles, None),
          FieldName.percentileAt(0),
          ValidationErrorCode.INVALID_RANGE
        )
      },

      test("a percentile of exactly one is refused") {
        assertFailWithCode(
          expert(Vector(0.05, 0.50, 1.0), threeQuantiles, None),
          FieldName.percentileAt(2),
          ValidationErrorCode.INVALID_RANGE
        )
      },

      test("a NaN percentile is refused") {
        assertFailWithCode(
          expert(Vector(Double.NaN, 0.50, 0.95), threeQuantiles, None),
          FieldName.percentileAt(0),
          ValidationErrorCode.INVALID_RANGE
        )
      }

    ),

    suite("expert — loss positivity, carried by the anchor's field type")(

      test("a quantile of exactly zero is refused — the metalog fit takes its logarithm") {
        assertFailWithCode(
          expert(threePercentiles, Vector(0.0, 5000.0, 25000.0), None),
          FieldName.quantileAt(0),
          ValidationErrorCode.INVALID_RANGE
        )
      },

      test("the smallest positive quantile is accepted") {
        assertTrue(
          expert(threePercentiles, Vector(Double.MinPositiveValue, 5000.0, 25000.0), None).isSuccess
        )
      },

      test("a negative quantile is refused") {
        assertFailWithCode(
          expert(threePercentiles, Vector(-1.0, 5000.0, 25000.0), None),
          FieldName.quantileAt(0),
          ValidationErrorCode.INVALID_RANGE
        )
      },

      test("an infinite quantile is refused") {
        assertFailWithCode(
          expert(threePercentiles, Vector(Double.PositiveInfinity, 5000.0, 25000.0), None),
          FieldName.quantileAt(0),
          ValidationErrorCode.INVALID_RANGE
        )
      }

    ),

    suite("expert — ordering, held by QuantileAnchors.create")(

      test("decreasing quantiles are refused") {
        assertFailWithCode(
          expert(threePercentiles, Vector(5000.0, 1000.0, 25000.0), None),
          FieldName.Quantiles,
          ValidationErrorCode.INVALID_COMBINATION
        )
      },

      test("equal adjacent quantiles are refused — strictly increasing is the rule") {
        assertFailWithCode(
          expert(threePercentiles, Vector(1000.0, 1000.0, 25000.0), None),
          FieldName.Quantiles,
          ValidationErrorCode.INVALID_COMBINATION
        )
      },

      test("decreasing percentiles are refused") {
        assertFailWithCode(
          expert(Vector(0.9, 0.1, 0.5), threeQuantiles, None),
          FieldName.Percentiles,
          ValidationErrorCode.INVALID_COMBINATION
        )
      },

      test("equal adjacent percentiles are refused") {
        assertFailWithCode(
          expert(Vector(0.05, 0.05, 0.95), threeQuantiles, None),
          FieldName.Percentiles,
          ValidationErrorCode.INVALID_COMBINATION
        )
      },

      test("both orderings fail together rather than short-circuiting") {
        expert(Vector(0.9, 0.1, 0.5), Vector(5000.0, 1000.0, 25000.0), None) match
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

    suite("expert — anchor count, held by QuantileAnchors.create")(

      test("a single anchor is refused — two is the floor below which there is no curve") {
        assertFailWithCode(
          expert(Vector(0.5), Vector(5000.0), None),
          FieldName.Percentiles,
          ValidationErrorCode.CONSTRAINT_VIOLATION
        )
      },

      test("no anchors at all is refused") {
        assertFailWithCode(
          expert(Vector.empty[Double], Vector.empty[Double], None),
          FieldName.Percentiles,
          ValidationErrorCode.CONSTRAINT_VIOLATION
        )
      },

      test("two anchors succeed") {
        assertTrue(expert(Vector(0.1, 0.9), Vector(1000.0, 25000.0), None).isSuccess)
      },

      test("twenty anchors succeed") {
        val ps = (1 to 20).map(_ / 21.0).toVector
        val qs = (1 to 20).map(i => i * 1000.0).toVector
        assertTrue(expert(ps, qs, None).isSuccess)
      },

      test("twenty-one anchors are refused") {
        val ps = (1 to 21).map(_ / 22.0).toVector
        val qs = (1 to 21).map(i => i * 1000.0).toVector
        assertFailWithCode(
          expert(ps, qs, None),
          FieldName.Percentiles,
          ValidationErrorCode.CONSTRAINT_VIOLATION
        )
      }

    ),

    suite("expert — the pairing rule, held by the one place that zips")(

      test("unequal sequence lengths are refused") {
        assertFailWithCode(
          expert(Vector(0.1, 0.9), Vector(5000.0), None),
          FieldName.PercentileQuantilePairing,
          ValidationErrorCode.INVALID_COMBINATION
        )
      }

    ),

    suite("expert — term count")(

      test("a term count of zero is refused by MetalogTermCount at the boundary") {
        assertFailWithCode(
          expert(threePercentiles, threeQuantiles, Some(0)),
          FieldName.Terms,
          ValidationErrorCode.INVALID_RANGE
        )
      },

      test("a term count of one is refused by MetalogTermCount at the boundary") {
        assertFailWithCode(
          expert((1 to 5).map(_ / 6.0).toVector, (1 to 5).map(i => i * 1000.0).toVector, Some(1)),
          FieldName.Terms,
          ValidationErrorCode.INVALID_RANGE
        )
      },

      test("a term count of twenty-one is refused by MetalogTermCount at the boundary") {
        val ps = (1 to 20).map(_ / 21.0).toVector
        val qs = (1 to 20).map(i => i * 1000.0).toVector
        assertFailWithCode(expert(ps, qs, Some(21)), FieldName.Terms, ValidationErrorCode.INVALID_RANGE)
      },

      test("a term count above the anchor count is refused by Expert.create") {
        assertFailWithCode(
          expert(threePercentiles, threeQuantiles, Some(4)),
          FieldName.Terms,
          ValidationErrorCode.INVALID_COMBINATION
        )
      },

      test("an absent term count is always valid — termsToFit cannot exceed the anchor count") {
        val shape = expert(threePercentiles, threeQuantiles, None)
        assertTrue(shape.isSuccess)
      }

    ),

    suite("lognormal")(

      test("ordered positive bounds succeed") {
        assertTrue(lognormal(1000L, 50000L).isSuccess)
      },

      test("a minimum equal to the maximum is refused") {
        assertFailWithCode(lognormal(1000L, 1000L), FieldName.LossRange, ValidationErrorCode.INVALID_RANGE)
      },

      test("a minimum above the maximum is refused") {
        assertFailWithCode(lognormal(50000L, 1000L), FieldName.LossRange, ValidationErrorCode.INVALID_RANGE)
      },

      test("an unordered pair reports at the range, not at either bound — either edit satisfies it") {
        val locations = lognormal(1000L, 100L).toEither.swap.toOption.toList.flatMap(_.toList).map(_.location)
        assertTrue(
          locations.contains(ErrorLocation.requestField(FieldName.LossRange)),
          !locations.contains(ErrorLocation.requestField(FieldName.MinLoss)),
          !locations.contains(ErrorLocation.requestField(FieldName.MaxLoss))
        )
      },

      test("bounds of one and two succeed — the smallest ordered positive pair") {
        assertTrue(lognormal(1L, 2L).isSuccess)
      },

      test("a zero minimum is refused by PositiveLong — the bound feeds a logarithm in the fit") {
        assertFailWithCode(lognormal(0L, 50000L), FieldName.MinLoss, ValidationErrorCode.INVALID_RANGE)
      },

      test("a zero maximum is refused by PositiveLong") {
        assertFailWithCode(lognormal(1000L, 0L), FieldName.MaxLoss, ValidationErrorCode.INVALID_RANGE)
      },

      test("a negative minimum is refused by PositiveLong") {
        assertFailWithCode(lognormal(-1L, 50000L), FieldName.MinLoss, ValidationErrorCode.INVALID_RANGE)
      },

      test("a negative maximum is refused by PositiveLong") {
        assertFailWithCode(lognormal(1000L, -1L), FieldName.MaxLoss, ValidationErrorCode.INVALID_RANGE)
      }

    ),

    suite("the case is the mode")(

      test("each case states its own mode spelling") {
        val e = expert(threePercentiles, threeQuantiles, None).toEither.toOption.get
        val l = lognormal(1000L, 50000L).toEither.toOption.get
        assertTrue(
          (e.distributionType: String) == "expert",
          (l.distributionType: String) == "lognormal"
        )
      }

    ),

    suite("codec — the discriminator decides the case")(

      test("an unrecognised mode spelling is refused by the decoder") {
        val json = """{"distributionType":"weibull","minLoss":1000,"maxLoss":50000}"""
        assertTrue(json.fromJson[DistributionShape].isLeft)
      },

      test("an absent mode spelling is refused by the decoder") {
        val json = """{"minLoss":1000,"maxLoss":50000}"""
        assertTrue(json.fromJson[DistributionShape].isLeft)
      },

      test("a lognormal record survives decode-then-encode byte-identically") {
        val shape: DistributionShape = lognormal(1000L, 50000L).toEither.toOption.get
        val json                     = shape.toJson
        assertTrue(json.fromJson[DistributionShape].map(_.toJson) == Right(json))
      },

      test("an expert record survives decode-then-encode byte-identically") {
        val shape: DistributionShape = expert(threePercentiles, threeQuantiles, Some(3)).toEither.toOption.get
        val json                     = shape.toJson
        assertTrue(json.fromJson[DistributionShape].map(_.toJson) == Right(json))
      },

      test("a malformed expert record reports the expert gate's own complaint, not the lognormal one") {
        val json = """{"distributionType":"expert","percentiles":[0.9,0.1],"quantiles":[1000.0,5000.0]}"""
        val message = json.fromJson[DistributionShape].swap.toOption.getOrElse("")
        assertTrue(message.contains(ValidationMessages.percentilesMustBeStrictlyIncreasing))
      },

      test("an expert record is refused when it carries only the lognormal fields") {
        val json = """{"distributionType":"expert","minLoss":1000,"maxLoss":50000}"""
        assertTrue(json.fromJson[DistributionShape].isLeft)
      }

    ),

    suite("resolve is total")(

      test("a stated term count is what the fit runs with") {
        val shape = expert(threePercentiles, threeQuantiles, Some(2)).toEither.toOption.get
        DistributionShape.resolve(shape) match
          case ExpertDistributionParams(_, _, terms) => assertTrue((terms: Int) == 2)
          case other                                 => assertTrue(false).label(s"expected expert params, got $other")
      },

      test("an absent term count resolves to one term per anchor") {
        val shape = expert(threePercentiles, threeQuantiles, None).toEither.toOption.get
        DistributionShape.resolve(shape) match
          case ExpertDistributionParams(_, _, terms) => assertTrue((terms: Int) == 3)
          case other                                 => assertTrue(false).label(s"expected expert params, got $other")
      },

      test("a lognormal resolves to its two bounds and the BCG confidence level") {
        val shape = lognormal(1000L, 50000L).toEither.toOption.get
        DistributionShape.resolve(shape) match
          case LognormalDistributionParams(min, max, confidence) =>
            assertTrue(
              (min: Long) == 1000L,
              (max: Long) == 50000L,
              confidence == DistributionShape.LognormalConfidenceInterval
            )
          case other => assertTrue(false).label(s"expected lognormal params, got $other")
      },

      test("at every anchor count, the resolved term count is one per anchor and inside the fitter's range") {
        check(Gen.int(2, 20)) { anchorCount =>
          val ps    = (1 to anchorCount).map(_ / (anchorCount + 1.0)).toVector
          val qs    = (1 to anchorCount).map(i => i * 1000.0).toVector
          val shape = expert(ps, qs, None).toEither.toOption.get
          DistributionShape.resolve(shape) match
            case ExpertDistributionParams(_, _, terms) =>
              assertTrue((terms: Int) == anchorCount, (terms: Int) >= 2, (terms: Int) <= 20)
            case other => assertTrue(false).label(s"expected expert params, got $other")
        }
      }

    )

  )

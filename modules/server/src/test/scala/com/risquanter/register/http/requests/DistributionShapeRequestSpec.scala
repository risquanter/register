package com.risquanter.register.http.requests

import zio.prelude.Validation
import zio.test.*

import sttp.tapir.SchemaType.SCoproduct
import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError, ValidationErrorCode}

/** The submitted shape and its gate: one request case per mode, and `toShape`
  * refining each into the domain shape.
  *
  * What the two cases make unrepresentable is not tested here, because there is
  * no Scala expression for it: a metalog body has no bound to omit and a
  * lognormal body has no anchor sequence to omit, so a missing-field rule has
  * nothing to refuse. The wire is the one place those states can still be
  * written, so the decode suite below is where they are refused.
  */
object DistributionShapeRequestSpec extends ZIOSpecDefault:

  // ── fixtures ───────────────────────────────────────────────────────────────

  /** Three anchors at the 5th, 50th and 95th percentile, strictly increasing in
    * both sequences, so the fixture itself breaks no rule. */
  private val anchorPercentiles: Vector[Double] = Vector(0.05, 0.50, 0.95)
  private val anchorQuantiles:   Vector[Double] = Vector(1_000.0, 5_000.0, 25_000.0)

  private def metalogReq(
    percentiles: Vector[Double],
    quantiles:   Vector[Double],
    terms:       Option[Int]
  ): DistributionShapeRequest.Metalog =
    DistributionShapeRequest.Metalog(percentiles, quantiles, terms)

  private def lognormalReq(minLoss: Long, maxLoss: Long): DistributionShapeRequest.Lognormal =
    DistributionShapeRequest.Lognormal(minLoss, maxLoss)

  /** `toShape` reports against the subject it is given; every test here submits
    * the request as a whole, so `ErrorLocation.requestField` is what a refusal
    * carries. */
  private def shapeOf(request: DistributionShapeRequest): Validation[ValidationError, DistributionShape] =
    request.toShape(ErrorSubject.Request)

  private def failsOnField(
    v:     Validation[ValidationError, ?],
    field: FieldName,
    code:  ValidationErrorCode
  ): Boolean =
    val at = ErrorLocation.requestField(field)
    v match
      case Validation.Failure(_, errs) => errs.exists(e => e.location == at && e.code == code)
      case Validation.Success(_, _)    => false

  private def locationsOf(v: Validation[ValidationError, ?]): List[ErrorLocation] =
    v match
      case Validation.Failure(_, errs) => errs.toList.map(_.location)
      case Validation.Success(_, _)    => Nil

  // ── spec ───────────────────────────────────────────────────────────────────

  def spec = suite("DistributionShapeRequest.toShape")(

    suite("accepted metalog requests")(

      test("three anchors with no stated term count") {
        assertTrue(shapeOf(metalogReq(anchorPercentiles, anchorQuantiles, None)).isSuccess)
      },

      test("a term count equal to the anchor count") {
        assertTrue(shapeOf(metalogReq(anchorPercentiles, anchorQuantiles, Some(3))).isSuccess)
      },

      test("a term count of two, the smallest the fitter accepts") {
        assertTrue(shapeOf(metalogReq(anchorPercentiles, anchorQuantiles, Some(2))).isSuccess)
      },

      test("two anchors, the smallest count the shape accepts") {
        assertTrue(
          shapeOf(metalogReq(Vector(0.05, 0.95), Vector(1_000.0, 25_000.0), None)).isSuccess
        )
      },

      test("the gate produces the metalog case") {
        shapeOf(metalogReq(anchorPercentiles, anchorQuantiles, None)) match
          case Validation.Success(_, shape) =>
            assertTrue(shape.isInstanceOf[DistributionShape.Metalog])
          case Validation.Failure(_, errs)  =>
            assertTrue(false).label(s"expected a shape, got ${errs.map(_.message).mkString("; ")}")
      }
    ),

    suite("accepted lognormal requests")(

      test("a minimum below the maximum") {
        assertTrue(shapeOf(lognormalReq(1_000L, 50_000L)).isSuccess)
      },

      test("adjacent bounds, the narrowest ordered pair") {
        assertTrue(shapeOf(lognormalReq(1L, 2L)).isSuccess)
      },

      test("the gate produces the lognormal case") {
        shapeOf(lognormalReq(1_000L, 50_000L)) match
          case Validation.Success(_, shape) =>
            assertTrue(shape.isInstanceOf[DistributionShape.Lognormal])
          case Validation.Failure(_, errs)  =>
            assertTrue(false).label(s"expected a shape, got ${errs.map(_.message).mkString("; ")}")
      }
    ),

    suite("metalog rules the field types cannot hold")(

      test("a term count of one is outside the fitter's range") {
        assertTrue(failsOnField(
          shapeOf(metalogReq(anchorPercentiles, anchorQuantiles, Some(1))),
          FieldName.Terms,
          ValidationErrorCode.INVALID_RANGE
        ))
      },

      test("a term count above the anchor count is refused: a fit cannot use more terms than points") {
        assertTrue(failsOnField(
          shapeOf(metalogReq(anchorPercentiles, anchorQuantiles, Some(4))),
          FieldName.Terms,
          ValidationErrorCode.INVALID_COMBINATION
        ))
      },

      test("unequal sequence lengths are refused against the pairing, not against either sequence") {
        assertTrue(failsOnField(
          shapeOf(metalogReq(anchorPercentiles, Vector(1_000.0, 5_000.0), None)),
          FieldName.PercentileQuantilePairing,
          ValidationErrorCode.INVALID_COMBINATION
        ))
      },

      test("strictly decreasing quantiles are refused against the quantiles") {
        assertTrue(failsOnField(
          shapeOf(metalogReq(anchorPercentiles, Vector(25_000.0, 5_000.0, 1_000.0), None)),
          FieldName.Quantiles,
          ValidationErrorCode.INVALID_COMBINATION
        ))
      },

      test("quantiles that rise then fall are refused") {
        assertTrue(failsOnField(
          shapeOf(metalogReq(anchorPercentiles, Vector(1_000.0, 25_000.0, 5_000.0), None)),
          FieldName.Quantiles,
          ValidationErrorCode.INVALID_COMBINATION
        ))
      },

      test("a repeated quantile is refused: the sequence must strictly increase") {
        assertTrue(failsOnField(
          shapeOf(metalogReq(anchorPercentiles, Vector(1_000.0, 1_000.0, 25_000.0), None)),
          FieldName.Quantiles,
          ValidationErrorCode.INVALID_COMBINATION
        ))
      },

      test("percentiles out of order are refused against the percentiles") {
        assertTrue(failsOnField(
          shapeOf(metalogReq(Vector(0.9, 0.1, 0.5), anchorQuantiles, None)),
          FieldName.Percentiles,
          ValidationErrorCode.INVALID_COMBINATION
        ))
      },

      test("a repeated percentile is refused") {
        assertTrue(failsOnField(
          shapeOf(metalogReq(Vector(0.05, 0.05, 0.95), anchorQuantiles, None)),
          FieldName.Percentiles,
          ValidationErrorCode.INVALID_COMBINATION
        ))
      },

      test("both sequences out of order accumulate, one refusal each") {
        val locations = locationsOf(
          shapeOf(metalogReq(Vector(0.9, 0.1, 0.5), Vector(25_000.0, 5_000.0, 1_000.0), None))
        )
        assertTrue(
          locations.contains(ErrorLocation.requestField(FieldName.Percentiles)),
          locations.contains(ErrorLocation.requestField(FieldName.Quantiles))
        ).label(
          "expected a refusal against each sequence, got: " +
            locations.map(ErrorLocation.describe).mkString(", ")
        )
      }
    ),

    suite("lognormal rules the field types cannot hold")(

      test("equal bounds are refused against the pair, not against either bound") {
        assertTrue(failsOnField(
          shapeOf(lognormalReq(10_000L, 10_000L)),
          FieldName.LossRange,
          ValidationErrorCode.INVALID_RANGE
        ))
      },

      test("a minimum above the maximum is refused against the pair") {
        assertTrue(failsOnField(
          shapeOf(lognormalReq(50_000L, 1_000L)),
          FieldName.LossRange,
          ValidationErrorCode.INVALID_RANGE
        ))
      },

      test("a non-positive minimum is refused against that bound alone") {
        assertTrue(locationsOf(shapeOf(lognormalReq(0L, 50_000L)))
          .contains(ErrorLocation.requestField(FieldName.MinLoss)))
      }
    ),

    suite("the wire form — where a cross-mode body is still writable")(

      test("a body tagged metalog carrying only lognormal fields is refused") {
        val decoded = DistributionShapeRequest.codec.decodeJson(
          """{"distributionType":"Metalog","minLoss":1000,"maxLoss":50000}"""
        )
        assertTrue(decoded.isLeft)
      },

      test("a body tagged lognormal carrying only metalog fields is refused") {
        val decoded = DistributionShapeRequest.codec.decodeJson(
          """{"distributionType":"Lognormal","percentiles":[0.05,0.95],"quantiles":[1000.0,25000.0]}"""
        )
        assertTrue(decoded.isLeft)
      },

      test("an unrecognised mode spelling is refused by the discriminator") {
        val decoded = DistributionShapeRequest.codec.decodeJson(
          """{"distributionType":"unknown"}"""
        )
        assertTrue(decoded.isLeft)
      },

      test("a body with no mode spelling is refused") {
        val decoded = DistributionShapeRequest.codec.decodeJson(
          """{"minLoss":1000,"maxLoss":50000}"""
        )
        assertTrue(decoded.isLeft)
      },

      test("a lognormal request encodes to one object naming its mode") {
        // The discriminator is named `distributionType` and each case's tag value
        // is its own name, so the encoded object carries the mode and that
        // mode's fields, with no key for the other mode. The literal is what
        // makes a rename of either case fail here.
        val req: DistributionShapeRequest = lognormalReq(1_000L, 50_000L)
        assertTrue(
          DistributionShapeRequest.codec.encodeJson(req, None).toString ==
            """{"distributionType":"Lognormal","minLoss":1000,"maxLoss":50000}"""
        )
      },

      test("a metalog request encodes to one object naming its mode") {
        val req: DistributionShapeRequest = metalogReq(Vector(0.05, 0.95), Vector(1_000.0, 25_000.0), Some(2))
        assertTrue(
          DistributionShapeRequest.codec.encodeJson(req, None).toString ==
            """{"distributionType":"Metalog","percentiles":[0.05,0.95],"quantiles":[1000.0,25000.0],"terms":2}"""
        )
      },

      test("a body naming one mode and also carrying the other's fields decodes to the named mode, dropping the rest") {
        // The derived decoder ignores keys the named case does not declare, so a
        // mixed body is accepted and the surplus values are discarded rather
        // than refused. Strictness is not available here: the library's
        // no-extra-fields annotation treats the discriminator key as an extra
        // field and rejects every valid body along with the mixed one.
        val decoded = DistributionShapeRequest.codec.decodeJson(
          """{"distributionType":"Lognormal","minLoss":1000,"maxLoss":50000,"percentiles":[0.1,0.9],"quantiles":[1.0,2.0]}"""
        )
        assertTrue(decoded == Right(DistributionShapeRequest.Lognormal(1000L, 50000L)))
      },

      test("a metalog request round-trips through the codec") {
        val req: DistributionShapeRequest = metalogReq(anchorPercentiles, anchorQuantiles, None)
        val decoded = DistributionShapeRequest.codec.decodeJson(
          DistributionShapeRequest.codec.encodeJson(req, None).toString
        )
        assertTrue(decoded.contains(req))
      },

      test("a lognormal request round-trips through the codec") {
        val req: DistributionShapeRequest = lognormalReq(1_000L, 50_000L)
        val decoded = DistributionShapeRequest.codec.decodeJson(
          DistributionShapeRequest.codec.encodeJson(req, None).toString
        )
        assertTrue(decoded.contains(req))
      },

      test("fromShape is the inverse of toShape, in both modes") {
        // Both halves are stated here because both are now assertable: the
        // request's sequences are immutable, so the projection back out of a
        // shape is compared by value rather than by identity.
        def inverts(req: DistributionShapeRequest): Boolean =
          shapeOf(req) match
            case Validation.Success(_, shape) => DistributionShapeRequest.fromShape(shape) == req
            case Validation.Failure(_, _)     => false

        assertTrue(
          inverts(lognormalReq(1_000L, 50_000L)),
          inverts(metalogReq(anchorPercentiles, anchorQuantiles, Some(2))),
          inverts(metalogReq(anchorPercentiles, anchorQuantiles, None))
        )
      },

      test("two structurally identical metalog requests are equal") {
        // The defect this pins: with a mutable array field these two would be
        // unequal, and every round-trip assertion above would have had to
        // compare element by element instead of comparing the value.
        assertTrue(
          metalogReq(Vector(0.05, 0.95), Vector(1_000.0, 25_000.0), Some(2)) ==
            metalogReq(Vector(0.05, 0.95), Vector(1_000.0, 25_000.0), Some(2))
        )
      }
    ),

    suite("the Tapir schema")(

      test("the schema is a coproduct, one member per mode") {
        assertTrue(DistributionShapeRequest.schema.schemaType.isInstanceOf[SCoproduct[?]])
      },

      test("the schema is named, so the generated document names the type") {
        assertTrue(DistributionShapeRequest.schema.name.isDefined)
      }
    )
  )

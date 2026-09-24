package com.risquanter.register.domain.data

import zio.test.*
import zio.prelude.{Equal, Identity}
import zio.json.{EncoderOps, DecoderOps}
import io.github.iltotore.iron.autoRefine

/**
 * ResultTransformSpec as shared data: the InsurancePolicy cross-field rule,
 * Equal lawfulness on reified data, discriminated codec round-trips, and the
 * TransformPipeline monoid. Interpreting a spec into an executable transform
 * is the server's concern and is tested there.
 */
object ResultTransformSpecSpec extends ZIOSpecDefault {

  import ResultTransformSpec.*

  private val allSpecs: List[ResultTransformSpec] = List(
    ApplyDeductible(10000L),
    CapLosses(1000000L),
    ScaleLosses(0.8),
    FilterBelowThreshold(10000L),
    InsurancePolicy.create(10000L, 1000000L).toEither.toOption.get
  )

  def spec = suite("ResultTransformSpecSpec")(

    suite("cross-field rules")(
      test("InsurancePolicy.create rejects cap <= deductible") {
        assertTrue(
          InsurancePolicy.create(10000L, 10000L).toEither.isLeft,
          InsurancePolicy.create(10000L, 5000L).toEither.isLeft
        )
      }
    ),

    suite("Equal on reified data")(
      test("structurally identical specs compare equal (the defect Equal[RiskTransform] had)") {
        assertTrue(
          Equal[ResultTransformSpec].equal(CapLosses(1000L), CapLosses(1000L)),
          !Equal[ResultTransformSpec].equal(CapLosses(1000L), CapLosses(2000L)),
          !Equal[ResultTransformSpec].equal(CapLosses(1000L), FilterBelowThreshold(1000L))
        )
      }
    ),

    suite("codec")(
      test("every case round-trips") {
        assertTrue(allSpecs.forall { s =>
          s.toJson.fromJson[ResultTransformSpec] == Right(s)
        })
      },
      test("op discriminator prevents cross-case decoding") {
        // InsurancePolicy JSON must not decode as ApplyDeductible despite the shared field
        val policyJson = (InsurancePolicy.create(10000L, 1000000L).toEither.toOption.get: ResultTransformSpec).toJson
        assertTrue(policyJson.fromJson[ResultTransformSpec].toOption.get match {
          case _: InsurancePolicy => true
          case _                  => false
        })
      },
      test("invalid parameters are rejected at decode") {
        assertTrue("""{"op":"capLosses","cap":-5}""".fromJson[ResultTransformSpec].isLeft)
      }
    ),

    suite("TransformPipeline")(
      test("Identity laws: empty is identity, combine is concatenation (associative)") {
        val a = TransformPipeline(List(ApplyDeductible(10000L)))
        val b = TransformPipeline(List(CapLosses(1000000L)))
        val c = TransformPipeline(List(ScaleLosses(0.5)))
        val I = Identity[TransformPipeline]
        assertTrue(
          I.combine(I.identity, a) == a,
          I.combine(a, I.identity) == a,
          I.combine(a, I.combine(b, c)) == I.combine(I.combine(a, b), c),
          I.combine(a, b).steps == a.steps ++ b.steps
        )
      },
      test("pipeline JSON round-trips") {
        val p = TransformPipeline(allSpecs)
        assertTrue(p.toJson.fromJson[TransformPipeline] == Right(p))
      }
    )
  )
}

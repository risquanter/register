package com.risquanter.register.domain.data

import zio.test.*
import zio.prelude.{Equal, Identity}
import zio.json.{EncoderOps, DecoderOps}
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.ResultTransformSpec.*
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, FieldProblem, SubmittedMitigations, ValueRange}
import com.risquanter.register.testutil.TestHelpers.retentionFactor

/**
 * ResultTransformSpec as shared data: the field rules each case refines,
 * Equal lawfulness on reified data, discriminated codec round-trips, and the
 * TransformPipeline monoid. Interpreting a spec into an executable transform
 * is the server's concern and is tested there.
 */
object ResultTransformSpecSpec extends ZIOSpecDefault {

  private val allSpecs: List[ResultTransformSpec] = List(
    ApplyDeductible(10000L),
    CapLosses(1000000L),
    ScaleLosses(retentionFactor(0.8)),
    FilterBelowThreshold(10000L),
    InsurancePolicy(10000L, 1000000L)
  )

  def spec = suite("ResultTransformSpecSpec")(

    suite("field rules")(
      test("InsurancePolicy.refine accumulates both field refusals at the subject it is given") {
        val subject = ErrorSubject.mitigationAtIndex(SubmittedMitigations.NewMitigations, 1)
        val errors  = InsurancePolicy.refine(-1L, 0L, subject).toEither.swap.toOption.get.toList
        assertTrue(errors.map(_.location) == List(
          ErrorLocation.Field(subject, FieldName.Deductible),
          ErrorLocation.Field(subject, FieldName.Limit)
        ))
      },
      test("InsurancePolicy.refine accepts a zero deductible — first-unit coverage") {
        assertTrue(InsurancePolicy.refine(0L, 50L, ErrorSubject.Request).toEither == Right(InsurancePolicy(0L, 50L)))
      },
      test("InsurancePolicy.refine accepts a limit at or below the deductible") {
        assertTrue(InsurancePolicy.refine(50L, 5L, ErrorSubject.Request).toEither == Right(InsurancePolicy(50L, 5L)))
      },
      test("ApplyDeductible.refine refuses zero — the entity would bear nothing") {
        val errors = ApplyDeductible.refine(0L, ErrorSubject.Request).toEither.swap.toOption.get.toList
        assertTrue(
          errors.map(_.problem) == List(FieldProblem.OutOfRange(ValueRange.Deductible)),
          errors.map(_.location) == List(ErrorLocation.Field(ErrorSubject.Request, FieldName.Deductible))
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
        val policyJson = (InsurancePolicy(10000L, 1000000L): ResultTransformSpec).toJson
        assertTrue(policyJson.fromJson[ResultTransformSpec].toOption.get match {
          case _: InsurancePolicy => true
          case _                  => false
        })
      },
      test("invalid parameters are rejected at decode") {
        assertTrue("""{"op":"CapLosses","cap":-5}""".fromJson[ResultTransformSpec].isLeft)
      },
      test("a cap of zero is rejected at decode — the entity would bear nothing") {
        assertTrue("""{"op":"CapLosses","cap":0}""".fromJson[ResultTransformSpec].isLeft)
      },
      test("a deductible of zero is rejected at decode — the entity would bear nothing") {
        assertTrue("""{"op":"ApplyDeductible","deductible":0}""".fromJson[ResultTransformSpec].isLeft)
      },
      test("an insurance policy with a limit of zero is rejected at decode") {
        assertTrue(
          """{"op":"InsurancePolicy","deductible":0,"limit":0}"""
            .fromJson[ResultTransformSpec].isLeft
        )
      },
      test("an insurance policy written with a cap is rejected at decode — the field is limit") {
        assertTrue(
          """{"op":"InsurancePolicy","deductible":1,"cap":10}"""
            .fromJson[ResultTransformSpec].isLeft
        )
      }
    ),

    suite("TransformPipeline")(
      test("Identity laws: empty is identity, combine is concatenation (associative)") {
        val a = TransformPipeline(List(ApplyDeductible(10000L)))
        val b = TransformPipeline(List(CapLosses(1000000L)))
        val c = TransformPipeline(List(ScaleLosses(retentionFactor(0.5))))
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

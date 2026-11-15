package com.risquanter.register.mitigation

import zio.test.*
import zio.prelude.Identity
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.domain.data.{ResultTransformSpec, TransformPipeline}
import com.risquanter.register.domain.data.ResultTransformSpec.*
import com.risquanter.register.testutil.RiskResultTestSupport.outcomesOf
import com.risquanter.register.testutil.TestHelpers.retentionFactor

/**
 * ResultTransformInterpreter: equivalence between each spec case and the
 * RiskResultTransform constructor it names, and the TransformPipeline
 * interpretation law with its order sensitivity.
 */
object ResultTransformInterpreterSpec extends ZIOSpecDefault {

  private val outcomes = outcomesOf(100,Map(1 -> 2000000L, 2 -> 50000L, 3 -> 5000L))

  def spec = suite("ResultTransformInterpreterSpec")(

    suite("interpreter")(
      test("each case behaves as its RiskResultTransform constructor") {
        val pairs: List[(ResultTransformSpec, RiskResultTransform)] = List(
          ApplyDeductible(10000L)       -> RiskResultTransform.applyDeductible(10000L),
          CapLosses(1000000L)           -> RiskResultTransform.capLosses(1000000L),
          ScaleLosses(retentionFactor(0.8)) -> RiskResultTransform.scaleLosses(retentionFactor(0.8)),
          FilterBelowThreshold(10000L)  -> RiskResultTransform.filterBelowThreshold(10000L)
        )
        assertTrue(pairs.forall { case (spec, direct) =>
          ResultTransformInterpreter.toTransform(spec).run(outcomes).outcomes == direct.run(outcomes).outcomes
        })
      },
      test("InsurancePolicy interprets as insurancePolicy") {
        val policy = InsurancePolicy(10000L, 1000000L)
        val direct = RiskResultTransform.insurancePolicy(10000L, 1000000L)
        assertTrue(
          ResultTransformInterpreter.toTransform(policy).run(outcomes).outcomes == direct.run(outcomes).outcomes
        )
      }
    ),

    suite("TransformPipeline")(
      test("interpretation law: toTransform(a <> b) == toTransform(a) andThen toTransform(b)") {
        val a = TransformPipeline(List(ApplyDeductible(10000L), ScaleLosses(retentionFactor(0.5))))
        val b = TransformPipeline(List(CapLosses(400000L)))
        val combined = Identity[TransformPipeline].combine(a, b)
        val lhs = ResultTransformInterpreter.toTransform(combined).run(outcomes)
        val rhs = ResultTransformInterpreter.toTransform(a)
          .andThen(ResultTransformInterpreter.toTransform(b)).run(outcomes)
        assertTrue(lhs.outcomes == rhs.outcomes)
      },
      test("pipeline order is preserved (non-commutative)") {
        val deductibleThenScale =
          TransformPipeline(List(ApplyDeductible(10000L), ScaleLosses(retentionFactor(0.5))))
        val scaleThenDeductible =
          TransformPipeline(List(ScaleLosses(retentionFactor(0.5)), ApplyDeductible(10000L)))
        val input = outcomesOf(100,Map(1 -> 100000L))
        assertTrue(
          ResultTransformInterpreter.toTransform(deductibleThenScale).run(input).outcomeOf(1) == 5000L,  // min(100K, 10K) × 0.5
          ResultTransformInterpreter.toTransform(scaleThenDeductible).run(input).outcomeOf(1) == 10000L  // min(100K × 0.5, 10K)
        )
      }
    )
  )
}

package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{EncoderOps, DecoderOps}
import io.github.iltotore.iron.{autoRefine, refineUnsafe}
import com.risquanter.register.domain.data.iron.{RetentionFactor, ValidationUtil}
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.testutil.TestHelpers.{idStr, nodeId, unsafeGet}

/**
 * RiskLeafTransform: application closure (output is always a valid RiskLeaf),
 * per-representation op semantics, Override replacement, and the
 * OverrideDistributionParams mode invariant (shared with RiskLeaf).
 */
object RiskLeafTransformSpec extends ZIOSpecDefault {

  private def lognormalLeaf(prob: Double = 0.4, min: Long = 1000L, max: Long = 100000L): RiskLeaf =
    unsafeGet(RiskLeaf.create(
      id = idStr("logn-leaf"), name = "Lognormal Leaf", distributionType = "lognormal",
      probability = prob, minLoss = Some(min), maxLoss = Some(max),
      parentId = Some(nodeId("root-pf")), seedVarId = 1L
    ), "leaf")

  private def expertLeaf(prob: Double = 0.4): RiskLeaf =
    unsafeGet(RiskLeaf.create(
      id = idStr("exp-leaf"), name = "Expert Leaf", distributionType = "expert",
      probability = prob,
      percentiles = Some(Array(0.1, 0.5, 0.9)),
      quantiles = Some(Array(1000.0, 5000.0, 20000.0)),
      parentId = Some(nodeId("root-pf")), terms = Some(3), seedVarId = 2L
    ), "leaf")

  private def apply(t: RiskLeafTransform, leaf: RiskLeaf): RiskLeaf =
    RiskLeafTransform.applyTo(t, leaf).toEither.toOption.get

  private val expertOverride: OverrideDistributionParams =
    OverrideDistributionParams.create(
      distributionType = ValidationUtil.refineDistributionType("expert").toOption.get,
      percentiles = Some(Array(0.1, 0.9)),
      quantiles = Some(Array(500.0, 8000.0)),
      minLoss = None, maxLoss = None, terms = Some(2)
    ).toEither.toOption.get

  def spec = suite("RiskLeafTransformSpec")(

    suite("identity and closure")(
      test("Keep/Keep leaves all simulation-relevant params unchanged") {
        val leaf = lognormalLeaf()
        val out = apply(RiskLeafTransform.identity, leaf)
        assertTrue(
          out.probability == leaf.probability,
          out.minLoss == leaf.minLoss,
          out.maxLoss == leaf.maxLoss,
          out.id == leaf.id,
          out.seedVarId == leaf.seedVarId,
          out.parentId == leaf.parentId
        )
      },
      test("property: output always passes the leaf smart constructor") {
        val factors: Gen[Any, RetentionFactor] = Gen.double(0.1, 1.0).map(_.refineUnsafe)
        check(factors, factors) { (lf, df) =>
          val t = RiskLeafTransform(LikelihoodTransform.Scale(lf), DistributionTransform.ScaleSeverity(df))
          val logn = RiskLeafTransform.applyTo(t, lognormalLeaf())
          val exp  = RiskLeafTransform.applyTo(t, expertLeaf())
          assertTrue(logn.toEither.isRight, exp.toEither.isRight)
        }
      }
    ),

    suite("likelihood component")(
      test("Scale multiplies the probability") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), lognormalLeaf(prob = 0.4))
        assertTrue(math.abs(out.probability - 0.2) < 1e-9)
      },
      test("a factor above 1 is rejected at the boundary, so no clamp is needed") {
        assertTrue(ValidationUtil.refineRetentionFactor(4.0).isLeft)
      },
      test("Override cannot state a probability of zero — no mitigation eliminates a risk") {
        assertTrue(
          ValidationUtil.refineResidualProbability(0.0).isLeft,
          ValidationUtil.refineResidualProbability(0.05).isRight
        )
      },
      test("Override replaces the probability") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Override(0.05), DistributionTransform.Keep), lognormalLeaf(prob = 0.4))
        assertTrue((out.probability: Double) == 0.05)
      }
    ),

    suite("distribution component — ScaleSeverity")(
      test("lognormal: scales both bounds") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.ScaleSeverity(0.5)), lognormalLeaf(min = 1000L, max = 100000L))
        assertTrue(
          out.minLoss.map(l => l: Long) == Some(500L),
          out.maxLoss.map(l => l: Long) == Some(50000L)
        )
      },
      test("lognormal: outward rounding keeps adjacent bounds apart") {
        // Round-to-nearest sent 50.5 and 51.005 both to 51, collapsing the
        // interval and failing with "minLoss must be < maxLoss".
        val out = apply(
          RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.ScaleSeverity(0.505)),
          lognormalLeaf(min = 100L, max = 101L))
        assertTrue(
          out.minLoss.map(l => l: Long) == Some(50L),
          out.maxLoss.map(l => l: Long) == Some(52L)
        )
      },
      test("lognormal: a sub-unit lower bound is rejected, naming the smallest workable factor") {
        val result = RiskLeafTransform.applyTo(
          RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.ScaleSeverity(0.0005)),
          lognormalLeaf(min = 1000L, max = 100000L))
        val errors = result.toEither.swap.toOption.toList.flatMap(_.toChunk.toList)
        assertTrue(
          errors.exists(_.code == ValidationErrorCode.INVALID_LOGNORMAL_PARAMS),
          errors.exists(_.message.contains("1.000e-03"))
        )
      },
      test("expert: scales quantiles, percentiles unchanged") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.ScaleSeverity(0.5)), expertLeaf())
        assertTrue(
          out.quantiles.get.sameElements(Array(500.0, 2500.0, 10000.0)),
          out.percentiles.get.sameElements(Array(0.1, 0.5, 0.9))
        )
      }
    ),

    suite("bound arithmetic")(
      test("scaleSeverityBounds floors the lower bound and ceils the upper") {
        assertTrue(
          RiskLeafTransform.scaleSeverityBounds(100L, 101L, 0.505) == ((50L, 52L)),
          RiskLeafTransform.scaleSeverityBounds(1000L, 100000L, 0.5) == ((500L, 50000L)),
          RiskLeafTransform.scaleSeverityBounds(7L, 9L, 1.0) == ((7L, 9L))
        )
      },
      test("lowerBoundRepresentable holds at one whole unit and fails below it") {
        assertTrue(
          RiskLeafTransform.lowerBoundRepresentable(1L),
          !RiskLeafTransform.lowerBoundRepresentable(0L)
        )
      },
      test("minRepresentableFactor is the reciprocal of the lower bound") {
        assertTrue(
          RiskLeafTransform.minRepresentableFactor(1000L) == 0.001,
          RiskLeafTransform.minRepresentableFactor(40000L) == 0.000025
        )
      }
    ),

    suite("distribution component — Override")(
      test("replaces wholesale, including a representation switch") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Override(expertOverride)), lognormalLeaf())
        assertTrue(
          out.distributionType.toString == "expert",
          out.quantiles.get.sameElements(Array(500.0, 8000.0)),
          out.minLoss.isEmpty,
          out.maxLoss.isEmpty
        )
      }
    ),

    suite("OverrideDistributionParams mode invariant (shared with RiskLeaf)")(
      test("expert without quantiles is rejected") {
        val result = OverrideDistributionParams.create(
          ValidationUtil.refineDistributionType("expert").toOption.get,
          percentiles = Some(Array(0.1, 0.9)), quantiles = None,
          minLoss = None, maxLoss = None, terms = None
        )
        assertTrue(result.toEither.isLeft)
      },
      test("lognormal with min >= max is rejected") {
        val result = OverrideDistributionParams.create(
          ValidationUtil.refineDistributionType("lognormal").toOption.get,
          percentiles = None, quantiles = None,
          minLoss = Some(5000L), maxLoss = Some(5000L), terms = None
        )
        assertTrue(result.toEither.isLeft)
      },
      test("content-based equality on array fields") {
        val a = expertOverride
        val b = OverrideDistributionParams.create(
          ValidationUtil.refineDistributionType("expert").toOption.get,
          percentiles = Some(Array(0.1, 0.9)),
          quantiles = Some(Array(500.0, 8000.0)),
          minLoss = None, maxLoss = None, terms = Some(2)
        ).toEither.toOption.get
        assertTrue(a == b)
      }
    ),

    suite("codec")(
      test("RiskLeafTransform round-trips (all component kinds)") {
        val transforms = List(
          RiskLeafTransform.identity,
          RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.ScaleSeverity(0.8)),
          RiskLeafTransform(LikelihoodTransform.Override(0.05), DistributionTransform.Keep),
          RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Override(expertOverride))
        )
        assertTrue(transforms.forall(t => t.toJson.fromJson[RiskLeafTransform] == Right(t)))
      },
      test("the removed narrow operation is rejected as an unknown op") {
        val result = """{"op":"narrow","fraction":1.0}""".fromJson[DistributionTransform]
        assertTrue(
          result.isLeft,
          result.swap.toOption.exists(_.contains("invalid distribution transform: op 'narrow'"))
        )
      }
    )
  )
}

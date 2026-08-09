package com.risquanter.register.domain.data

import zio.test.*
import zio.json.{EncoderOps, DecoderOps}
import io.github.iltotore.iron.{autoRefine, refineUnsafe}
import com.risquanter.register.domain.data.iron.{NonNegativeDouble, ShrinkFraction, ValidationUtil}
import com.risquanter.register.testutil.TestHelpers.{idStr, nodeId}

/**
 * RiskLeafTransform: application closure (output is always a valid RiskLeaf),
 * per-representation op semantics, Override replacement, and the
 * OverrideDistributionParams mode invariant (shared with RiskLeaf).
 */
object RiskLeafTransformSpec extends ZIOSpecDefault {

  private def lognormalLeaf(prob: Double = 0.4, min: Long = 1000L, max: Long = 100000L): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = idStr("logn-leaf"), name = "Lognormal Leaf", distributionType = "lognormal",
      probability = prob, minLoss = Some(min), maxLoss = Some(max),
      parentId = Some(nodeId("root-pf")), seedVarId = 1L
    )

  private def expertLeaf(prob: Double = 0.4): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = idStr("exp-leaf"), name = "Expert Leaf", distributionType = "expert",
      probability = prob,
      percentiles = Some(Array(0.1, 0.5, 0.9)),
      quantiles = Some(Array(1000.0, 5000.0, 20000.0)),
      parentId = Some(nodeId("root-pf")), terms = Some(3), seedVarId = 2L
    )

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
        val factors: Gen[Any, NonNegativeDouble] = Gen.double(0.1, 2.0).map(_.refineUnsafe)
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
      test("Scale clamps at 1.0") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Scale(4.0), DistributionTransform.Keep), lognormalLeaf(prob = 0.4))
        assertTrue((out.probability: Double) == 1.0)
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
      test("expert: scales quantiles, percentiles unchanged") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.ScaleSeverity(0.5)), expertLeaf())
        assertTrue(
          out.quantiles.get.sameElements(Array(500.0, 2500.0, 10000.0)),
          out.percentiles.get.sameElements(Array(0.1, 0.5, 0.9))
        )
      }
    ),

    suite("distribution component — Narrow")(
      test("lognormal: contracts toward the geometric mean, order preserved") {
        val base = lognormalLeaf(min = 1000L, max = 100000L) // geometric mean = 10000
        val out = apply(RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Narrow(0.5)), base)
        val newMin = out.minLoss.map(l => l: Long).get
        val newMax = out.maxLoss.map(l => l: Long).get
        assertTrue(newMin > 1000L, newMax < 100000L, newMin < newMax)
      },
      test("lognormal with minLoss 0 is rejected (log space undefined)") {
        val base = lognormalLeaf(min = 0L, max = 100000L)
        val result = RiskLeafTransform.applyTo(
          RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Narrow(0.5)), base)
        assertTrue(result.toEither.isLeft)
      },
      test("expert: pulls quantiles toward the interpolated median, monotone") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Narrow(0.5)), expertLeaf())
        val qs = out.quantiles.get
        // median at p=0.5 is 5000; q' = 5000 + (q - 5000) * 0.5
        assertTrue(
          qs.sameElements(Array(3000.0, 5000.0, 12500.0)),
          qs.sliding(2).forall(p => p(0) <= p(1))
        )
      },
      test("fraction 0 is a no-op on expert quantiles") {
        val out = apply(RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Narrow(0.0)), expertLeaf())
        assertTrue(out.quantiles.get.sameElements(Array(1000.0, 5000.0, 20000.0)))
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
          RiskLeafTransform(LikelihoodTransform.Override(0.05), DistributionTransform.Narrow(0.3)),
          RiskLeafTransform(LikelihoodTransform.Keep, DistributionTransform.Override(expertOverride))
        )
        assertTrue(transforms.forall(t => t.toJson.fromJson[RiskLeafTransform] == Right(t)))
      },
      test("invalid narrow fraction rejected at decode") {
        assertTrue("""{"op":"narrow","fraction":1.0}""".fromJson[DistributionTransform].isLeft)
      }
    )
  )
}

package com.risquanter.register.services

import zio.*
import zio.test.*

import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.testutil.TestHelpers.{expertShape, lognormalShape}

object DistributionPreviewServiceSpec extends ZIOSpecDefault:

  private val serviceLayer = DistributionPreviewService.layer

  // ── fixtures ───────────────────────────────────────────────────────────────

  /** Three anchors, at the 5th, 50th and 95th percentile. The count is load-bearing:
    * with no explicit term count the fit derives one from the anchors, and the
    * metadata assertions below are written against three. */
  private val anchorPercentiles: Vector[Double] = Vector(0.05, 0.50, 0.95)
  private val anchorQuantiles:   Vector[Double] = Vector(1_000.0, 5_000.0, 25_000.0)

  private def expert(terms: Option[Int]): DistributionShape =
    expertShape(anchorPercentiles, anchorQuantiles, terms)

  /** The two bounds of a 90% confidence interval. */
  private val lognormal: DistributionShape = lognormalShape(1_000L, 50_000L)

  private def preview(shape: DistributionShape) =
    ZIO.serviceWithZIO[DistributionPreviewService](_.preview(shape))

  // ── spec ───────────────────────────────────────────────────────────────────

  def spec = suite("DistributionPreviewService")(

    // ── expert mode ───────────────────────────────────────────────────────────

    suite("expert mode")(

      test("returns exactly 200 points") {
        for resp <- preview(expert(None))
        yield assertTrue(resp.points.length == 200)
      },

      test("all pdf and cdf values are finite and non-negative") {
        for resp <- preview(expert(None))
        yield assertTrue(
          resp.points.forall(p => p.pdf.isFinite && p.pdf >= 0.0) &&
          resp.points.forall(p => p.cdf.isFinite && p.cdf >= 0.0 && p.cdf <= 1.0)
        )
      },

      test("cdf values are strictly increasing") {
        for resp <- preview(expert(None))
        yield
          val cdfs = resp.points.map(_.cdf)
          val monotone = cdfs.sliding(2).forall { case Vector(a, b) => a < b; case _ => true }
          assertTrue(monotone)
      },

      test("x values (quantiles) are non-negative with lower bound 0") {
        // The expert distribution is fitted with lower = Some(0.0)
        for resp <- preview(expert(None))
        yield assertTrue(resp.points.forall(_.x >= 0.0))
      },

      test("response metadata reflects resolved terms and anchor count") {
        // 3 anchors, no explicit terms → one term per anchor, so resolvedTerms = 3
        for resp <- preview(expert(None))
        yield assertTrue(
          resp.distributionType == "expert" &&
          resp.resolvedTerms    == Some(3) &&
          resp.anchorCount      == Some(3)
        )
      },

      test("respects explicit terms override") {
        for resp <- preview(expert(Some(2)))
        yield assertTrue(resp.resolvedTerms == Some(2))
      }
    ),

    // ── lognormal mode ────────────────────────────────────────────────────────

    suite("lognormal mode")(

      test("returns exactly 200 points") {
        for resp <- preview(lognormal)
        yield assertTrue(resp.points.length == 200)
      },

      test("all pdf and cdf values are finite and non-negative") {
        for resp <- preview(lognormal)
        yield assertTrue(
          resp.points.forall(p => p.pdf.isFinite && p.pdf >= 0.0) &&
          resp.points.forall(p => p.cdf.isFinite && p.cdf >= 0.0 && p.cdf <= 1.0)
        )
      },

      test("cdf values are strictly increasing") {
        for resp <- preview(lognormal)
        yield
          val cdfs = resp.points.map(_.cdf)
          val monotone = cdfs.sliding(2).forall { case Vector(a, b) => a < b; case _ => true }
          assertTrue(monotone)
      },

      test("response metadata has no resolvedTerms or anchorCount") {
        for resp <- preview(lognormal)
        yield assertTrue(
          resp.distributionType == "lognormal" &&
          resp.resolvedTerms    == None         &&
          resp.anchorCount      == None
        )
      }
    )
  ).provideLayer(serviceLayer)

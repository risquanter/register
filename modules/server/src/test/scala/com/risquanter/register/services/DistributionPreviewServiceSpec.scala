package com.risquanter.register.services

import zio.*
import zio.prelude.Validation
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.errors.{ValidationErrorCode, ValidationFailed}
import com.risquanter.register.http.requests.{DistributionPreviewResponse}

object DistributionPreviewServiceSpec extends ZIOSpecDefault:

  private val serviceLayer = DistributionPreviewService.layer

  // ── helpers ────────────────────────────────────────────────────────────────

  private def expertDist(
    percentiles: Array[Double] = Array(0.05, 0.50, 0.95),
    quantiles:   Array[Double] = Array(1_000.0, 5_000.0, 25_000.0),
    terms:       Option[Int]   = None
  ): Distribution =
    Distribution.create(
      distributionType = "expert",
      minLoss          = None,
      maxLoss          = None,
      percentiles      = Some(percentiles),
      quantiles        = Some(quantiles),
      terms            = terms
    ) match
      case Validation.Success(_, d) => d
      case Validation.Failure(_, e) => throw new RuntimeException(s"Bad test fixture: ${e.toList}")

  private def lognormalDist(
    minLoss: Long = 1_000L,
    maxLoss: Long = 50_000L
  ): Distribution =
    Distribution.create(
      distributionType = "lognormal",
      minLoss          = Some(minLoss),
      maxLoss          = Some(maxLoss),
      percentiles      = None,
      quantiles        = None
    ) match
      case Validation.Success(_, d) => d
      case Validation.Failure(_, e) => throw new RuntimeException(s"Bad test fixture: ${e.toList}")

  private def preview(dist: Distribution) =
    ZIO.serviceWithZIO[DistributionPreviewService](_.preview(dist))

  // ── spec ───────────────────────────────────────────────────────────────────

  def spec = suite("DistributionPreviewService")(

    // ── expert mode ───────────────────────────────────────────────────────────

    suite("expert mode")(

      test("returns exactly 200 points") {
        for resp <- preview(expertDist())
        yield assertTrue(resp.points.length == 200)
      },

      test("all pdf and cdf values are finite and non-negative") {
        for resp <- preview(expertDist())
        yield assertTrue(
          resp.points.forall(p => p.pdf.isFinite && p.pdf >= 0.0) &&
          resp.points.forall(p => p.cdf.isFinite && p.cdf >= 0.0 && p.cdf <= 1.0)
        )
      },

      test("cdf values are strictly increasing") {
        for resp <- preview(expertDist())
        yield
          val cdfs = resp.points.map(_.cdf)
          val monotone = cdfs.sliding(2).forall { case Array(a, b) => a < b; case _ => true }
          assertTrue(monotone)
      },

      test("x values (quantiles) are non-negative with lower bound 0") {
        // The expert distribution is fitted with lower = Some(0.0)
        for resp <- preview(expertDist())
        yield assertTrue(resp.points.forall(_.x >= 0.0))
      },

      test("response metadata reflects resolved terms and anchor count") {
        // 3 anchors, no explicit terms → resolvedTerms = min(3, 4) = 3
        for resp <- preview(expertDist())
        yield assertTrue(
          resp.distributionType == "expert" &&
          resp.resolvedTerms    == Some(3) &&
          resp.anchorCount      == Some(3)
        )
      },

      test("respects explicit terms override") {
        for resp <- preview(expertDist(terms = Some(2)))
        yield assertTrue(resp.resolvedTerms == Some(2))
      }
    ),

    // ── lognormal mode ────────────────────────────────────────────────────────

    suite("lognormal mode")(

      test("returns exactly 200 points") {
        for resp <- preview(lognormalDist())
        yield assertTrue(resp.points.length == 200)
      },

      test("all pdf and cdf values are finite and non-negative") {
        for resp <- preview(lognormalDist())
        yield assertTrue(
          resp.points.forall(p => p.pdf.isFinite && p.pdf >= 0.0) &&
          resp.points.forall(p => p.cdf.isFinite && p.cdf >= 0.0 && p.cdf <= 1.0)
        )
      },

      test("cdf values are strictly increasing") {
        for resp <- preview(lognormalDist())
        yield
          val cdfs = resp.points.map(_.cdf)
          val monotone = cdfs.sliding(2).forall { case Array(a, b) => a < b; case _ => true }
          assertTrue(monotone)
      },

      test("response metadata has no resolvedTerms or anchorCount") {
        for resp <- preview(lognormalDist())
        yield assertTrue(
          resp.distributionType == "lognormal" &&
          resp.resolvedTerms    == None         &&
          resp.anchorCount      == None
        )
      }
    ),


  ).provideLayer(serviceLayer)

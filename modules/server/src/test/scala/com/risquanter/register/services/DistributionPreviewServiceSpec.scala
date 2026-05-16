package com.risquanter.register.services

import zio.*
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.domain.errors.{ValidationErrorCode, ValidationFailed}
import com.risquanter.register.http.requests.{DistributionPreviewRequest, DistributionPreviewResponse}

object DistributionPreviewServiceSpec extends ZIOSpecDefault:

  private val serviceLayer = DistributionPreviewService.layer

  // ── helpers ────────────────────────────────────────────────────────────────

  private def expertReq(
    percentiles: Array[Double] = Array(5.0, 50.0, 95.0),
    quantiles:   Array[Double] = Array(1_000.0, 5_000.0, 25_000.0),
    terms:       Option[Int]   = None
  ): DistributionPreviewRequest =
    DistributionPreviewRequest(
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
  ): DistributionPreviewRequest =
    DistributionPreviewRequest(
      distributionType = "lognormal",
      percentiles      = None,
      quantiles        = None,
      terms            = None,
      minLoss          = Some(minLoss),
      maxLoss          = Some(maxLoss)
    )

  private def preview(req: DistributionPreviewRequest) =
    ZIO.serviceWithZIO[DistributionPreviewService](_.preview(req))

  // ── spec ───────────────────────────────────────────────────────────────────

  def spec = suite("DistributionPreviewService")(

    // ── expert mode ───────────────────────────────────────────────────────────

    suite("expert mode")(

      test("returns exactly 200 points") {
        for resp <- preview(expertReq())
        yield assertTrue(resp.points.length == 200)
      },

      test("all pdf and cdf values are finite and non-negative") {
        for resp <- preview(expertReq())
        yield assertTrue(
          resp.points.forall(p => p.pdf.isFinite && p.pdf >= 0.0) &&
          resp.points.forall(p => p.cdf.isFinite && p.cdf >= 0.0 && p.cdf <= 1.0)
        )
      },

      test("cdf values are strictly increasing") {
        for resp <- preview(expertReq())
        yield
          val cdfs = resp.points.map(_.cdf)
          val monotone = cdfs.sliding(2).forall { case Array(a, b) => a < b; case _ => true }
          assertTrue(monotone)
      },

      test("x values (quantiles) are non-negative with lower bound 0") {
        // The expert distribution is fitted with lower = Some(0.0)
        for resp <- preview(expertReq())
        yield assertTrue(resp.points.forall(_.x >= 0.0))
      },

      test("response metadata reflects resolved terms and anchor count") {
        // 3 anchors, no explicit terms → resolvedTerms = min(3, 4) = 3
        for resp <- preview(expertReq())
        yield assertTrue(
          resp.distributionType == "expert" &&
          resp.resolvedTerms    == Some(3) &&
          resp.anchorCount      == Some(3)
        )
      },

      test("respects explicit terms override") {
        for resp <- preview(expertReq(terms = Some(2)))
        yield assertTrue(resp.resolvedTerms == Some(2))
      },

      test("returns ValidationFailed for out-of-range percentiles (bad fit)") {
        // Percentiles outside (0, 100) are normalised to outside (0, 1), which
        // MetalogDistribution.fromPercentilesUnsafe rejects.
        val badReq = expertReq(percentiles = Array(0.0, 50.0, 100.0)) // 0.0 and 100.0 are invalid
        for result <- preview(badReq).either
        yield assertTrue(result.isLeft)
      }
    ),

    // ── lognormal mode ────────────────────────────────────────────────────────

    suite("lognormal mode")(

      test("returns exactly 200 points") {
        for resp <- preview(lognormalReq())
        yield assertTrue(resp.points.length == 200)
      },

      test("all pdf and cdf values are finite and non-negative") {
        for resp <- preview(lognormalReq())
        yield assertTrue(
          resp.points.forall(p => p.pdf.isFinite && p.pdf >= 0.0) &&
          resp.points.forall(p => p.cdf.isFinite && p.cdf >= 0.0 && p.cdf <= 1.0)
        )
      },

      test("cdf values are strictly increasing") {
        for resp <- preview(lognormalReq())
        yield
          val cdfs = resp.points.map(_.cdf)
          val monotone = cdfs.sliding(2).forall { case Array(a, b) => a < b; case _ => true }
          assertTrue(monotone)
      },

      test("response metadata has no resolvedTerms or anchorCount") {
        for resp <- preview(lognormalReq())
        yield assertTrue(
          resp.distributionType == "lognormal" &&
          resp.resolvedTerms    == None         &&
          resp.anchorCount      == None
        )
      }
    ),

    // ── unknown distribution type ─────────────────────────────────────────────

    suite("unknown distribution type")(

      test("fails with UNSUPPORTED_DISTRIBUTION_TYPE") {
        val req = DistributionPreviewRequest("beta", None, None, None, None, None)
        for result <- preview(req).either
        yield assertTrue(
          result match
            case Left(ValidationFailed(errs)) =>
              errs.exists(_.code == ValidationErrorCode.UNSUPPORTED_DISTRIBUTION_TYPE)
            case _ => false
        )
      }
    )

  ).provideLayer(serviceLayer)

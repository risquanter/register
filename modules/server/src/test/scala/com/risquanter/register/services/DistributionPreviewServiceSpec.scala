package com.risquanter.register.services

import zio.*
import zio.json.*
import zio.json.ast.{Json, JsonCursor}
import zio.test.*
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.http.responses.PreviewFit
import com.risquanter.register.testutil.TestHelpers.{metalogShape, lognormalShape}

object DistributionPreviewServiceSpec extends ZIOSpecDefault:

  private val serviceLayer = DistributionPreviewService.layer

  // ── fixtures ───────────────────────────────────────────────────────────────

  /** Three anchors, at the 5th, 50th and 95th percentile. The count is load-bearing:
    * with no explicit term count the fit derives one from the anchors, and the
    * metadata assertions below are written against three. */
  private val anchorPercentiles: Vector[Double] = Vector(0.05, 0.50, 0.95)
  private val anchorQuantiles:   Vector[Double] = Vector(1_000.0, 5_000.0, 25_000.0)

  private def metalog(terms: Option[Int]): DistributionShape =
    metalogShape(anchorPercentiles, anchorQuantiles, terms)

  /** The two bounds of a 90% confidence interval. */
  private val lognormal: DistributionShape = lognormalShape(1_000L, 50_000L)

  private def preview(shape: DistributionShape) =
    ZIO.serviceWithZIO[DistributionPreviewService](_.preview(shape))

  /** The value under the shape's tag key in the fit's JSON, which is what a
    * reader uses to tell the two fits apart. */
  private def tagOf(fit: PreviewFit): Option[String] =
    fit.toJson.fromJson[Json].toOption
      .flatMap(_.get(JsonCursor.field(DistributionShape.TagField)).toOption)
      .collect { case Json.Str(value) => value }

  // ── spec ───────────────────────────────────────────────────────────────────

  def spec = suite("DistributionPreviewService")(

    // ── metalog mode ──────────────────────────────────────────────────────────

    suite("metalog mode")(

      test("returns exactly 200 points") {
        for resp <- preview(metalog(None))
        yield assertTrue(resp.points.length == 200)
      },

      test("all pdf and cdf values are finite and non-negative") {
        for resp <- preview(metalog(None))
        yield assertTrue(
          resp.points.forall(p => p.pdf.isFinite && p.pdf >= 0.0) &&
          resp.points.forall(p => p.cdf.isFinite && p.cdf >= 0.0 && p.cdf <= 1.0)
        )
      },

      test("cdf values are strictly increasing") {
        for resp <- preview(metalog(None))
        yield
          val cdfs = resp.points.map(_.cdf)
          val monotone = cdfs.sliding(2).forall { case Vector(a, b) => a < b; case _ => true }
          assertTrue(monotone)
      },

      test("x values (quantiles) are non-negative with lower bound 0") {
        // The metalog distribution is fitted with lower = Some(0.0)
        for resp <- preview(metalog(None))
        yield assertTrue(resp.points.forall(_.x >= 0.0))
      },

      test("the fit carries the resolved term count: one term per anchor when none is stated") {
        for resp <- preview(metalog(None))
        yield assertTrue(resp.fit == PreviewFit.Metalog(3))
      },

      test("respects explicit terms override") {
        for resp <- preview(metalog(Some(2)))
        yield assertTrue(resp.fit == PreviewFit.Metalog(2))
      },

      test("the fit is tagged under the shape's key with the mode's case name") {
        // Compared against literals rather than the constants, so a change to
        // either spelling fails here instead of passing.
        for
          metalogResp   <- preview(metalog(None))
          lognormalResp <- preview(lognormal)
        yield assertTrue(
          DistributionShape.TagField == "distributionType",
          tagOf(metalogResp.fit)     == Some("Metalog"),
          tagOf(lognormalResp.fit)   == Some("Lognormal")
        )
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

      test("the fit is the lognormal case, which carries nothing") {
        for resp <- preview(lognormal)
        yield assertTrue(resp.fit == PreviewFit.Lognormal())
      }
    )
  ).provideLayer(serviceLayer)

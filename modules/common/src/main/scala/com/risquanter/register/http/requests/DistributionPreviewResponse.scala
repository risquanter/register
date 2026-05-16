package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.tapir.Schema

/** One sample point on a distribution preview curve.
  *
  * @param x   Loss value (same units as the user's input, e.g. $M)
  * @param pdf  Probability density at `x`
  * @param cdf  Cumulative probability P(Loss ≤ x), i.e. the p used to sample this point
  */
final case class DistributionPreviewPoint(x: Double, pdf: Double, cdf: Double)

object DistributionPreviewPoint:
  given schema: Schema[DistributionPreviewPoint] = Schema.derived[DistributionPreviewPoint]
  given codec: JsonCodec[DistributionPreviewPoint] = DeriveJsonCodec.gen[DistributionPreviewPoint]

/** Response body for the distribution preview endpoint.
  *
  * Contains 200 uniformly-spaced sample points sufficient for both PDF and CDF
  * chart views; the view switch is a pure client-side transform — no second fetch.
  *
  * @param distributionType  `"expert"` or `"lognormal"`, echoed from the request
  * @param resolvedTerms     Metalog terms value actually used; `None` for lognormal
  * @param anchorCount       Number of input anchor points; `None` for lognormal.
  *                          Together with `resolvedTerms` this drives the coherence
  *                          echo caption in the chart view.
  * @param points            200-point sampled curve, ordered by ascending `cdf`
  */
final case class DistributionPreviewResponse(
  distributionType: String,
  resolvedTerms:    Option[Int],
  anchorCount:      Option[Int],
  points:           Array[DistributionPreviewPoint]
)

object DistributionPreviewResponse:
  given schema: Schema[DistributionPreviewResponse] = Schema.any[DistributionPreviewResponse]
  given codec: JsonCodec[DistributionPreviewResponse] = DeriveJsonCodec.gen[DistributionPreviewResponse]

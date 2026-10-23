package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.tapir.Schema

import com.risquanter.register.domain.data.iron.DistributionType
import com.risquanter.register.domain.data.iron.given

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
  * The points are held in an immutable sequence, so two responses describing the
  * same curve are equal. An `Array` field would make the derived `equals`
  * compare by reference and report two identical responses as different.
  *
  * @param distributionType  The mode the curve was fitted in, echoed from the
  *                          request. A refined type rather than a raw string,
  *                          so only the two modes that exist are representable.
  * @param resolvedTerms     Metalog terms value actually used; `None` for lognormal
  * @param anchorCount       Number of input anchor points; `None` for lognormal.
  *                          Together with `resolvedTerms` this drives the coherence
  *                          echo caption in the chart view.
  * @param points            200-point sampled curve, ordered by ascending `cdf`
  */
final case class DistributionPreviewResponse(
  distributionType: DistributionType,
  resolvedTerms:    Option[Int],
  anchorCount:      Option[Int],
  points:           Vector[DistributionPreviewPoint]
)

object DistributionPreviewResponse:
  // The mode field's schema comes from `IronTapirCodecs`, which is where every
  // refined type's Tapir schema lives, so the derivation describes this response
  // as the record it is rather than as an unconstrained object.
  given schema: Schema[DistributionPreviewResponse] =
    import com.risquanter.register.http.codecs.IronTapirCodecs.given
    Schema.derived[DistributionPreviewResponse]
  given codec: JsonCodec[DistributionPreviewResponse] = DeriveJsonCodec.gen[DistributionPreviewResponse]

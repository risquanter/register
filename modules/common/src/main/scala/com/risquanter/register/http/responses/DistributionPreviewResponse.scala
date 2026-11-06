package com.risquanter.register.http.responses

import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}

import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.data.iron.{MetalogTermCount, OpenProbability}
import com.risquanter.register.domain.data.iron.given
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** One sample point on a distribution preview curve. `x` is the loss at the
  * point and `pdf` the density there, both as the fitter computed them; `cdf`
  * is the cumulative probability the point was sampled at, which is the
  * server's own grid and so is typed.
  */
final case class DistributionPreviewPoint(x: Double, pdf: Double, cdf: OpenProbability)

object DistributionPreviewPoint:
  given codec: JsonCodec[DistributionPreviewPoint] = DeriveJsonCodec.gen[DistributionPreviewPoint]
  given schema: Schema[DistributionPreviewPoint] =
    Schema.derived[DistributionPreviewPoint]

/** How the previewed curve was fitted, as a closed set (ADR-038 §2). A metalog
  * carries the term count the fit used, which the server resolves when the
  * author named none; a lognormal carries nothing. The tag key is the shape's
  * own, so the response names the mode under the key the request does, and the
  * tag values are the case names the request's shape uses.
  */
@jsonDiscriminator(DistributionShape.TagField)
enum PreviewFit:
  case Metalog(resolvedTerms: MetalogTermCount)
  case Lognormal()

object PreviewFit:
  given codec: JsonCodec[PreviewFit] = DeriveJsonCodec.gen[PreviewFit]

  /** Declared rather than left to the holder's derivation, so the response's
    * published description is the two fits and names the key that decides
    * between them. */
  given schema: Schema[PreviewFit] =
    given Configuration = Configuration.default.withDiscriminator(DistributionShape.TagField)
    Schema.derived[PreviewFit]

/** Response body for the distribution preview endpoint: the fit, and the
  * sampled curve ordered by ascending `cdf`. The points are an immutable
  * sequence, so two responses describing the same curve are equal.
  */
final case class DistributionPreviewResponse(fit: PreviewFit, points: Vector[DistributionPreviewPoint])

object DistributionPreviewResponse:
  given codec: JsonCodec[DistributionPreviewResponse] = DeriveJsonCodec.gen[DistributionPreviewResponse]
  given schema: Schema[DistributionPreviewResponse] = Schema.derived[DistributionPreviewResponse]

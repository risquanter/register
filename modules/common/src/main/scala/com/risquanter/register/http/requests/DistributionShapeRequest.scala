package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import zio.prelude.Validation
import sttp.tapir.Schema

import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.errors.ValidationError

/** Distribution shape parameters shared by the preview endpoint and the tree leaf wire DTOs.
  *
  * Carries the raw (unrefined) distribution parameters needed to fit and sample a
  * distribution curve, and to construct a [[Distribution]] domain value. Both modes
  * share this type; the `distributionType` field acts as the discriminator.
  *
  * Expert mode fields: `percentiles`, `quantiles`, `terms`.
  * Lognormal mode fields: `minLoss`, `maxLoss`.
  *
  * Percentile values are in domain scale 0–1. The form's 0–100 display values are
  * divided by 100 (via `pctToDomain`) before serialising into this type.
  *
  * All cross-field validation is owned by [[Distribution.create]], which is
  * invoked by [[DistributionShapeRequest.validate]].
  */
final case class DistributionShapeRequest(
  distributionType: String,
  percentiles:      Option[Array[Double]],
  quantiles:        Option[Array[Double]],
  terms:            Option[Int],
  minLoss:          Option[Long],
  maxLoss:          Option[Long]
)

object DistributionShapeRequest:
  given schema: Schema[DistributionShapeRequest] = Schema.derived
  given codec:  JsonCodec[DistributionShapeRequest] = DeriveJsonCodec.gen

  /** Validate cross-field rules and return a [[Distribution]] domain value, or
    * accumulated [[ValidationError]]s.
    *
    * Delegates entirely to [[Distribution.create]], which owns all invariants:
    * element range, strictly-increasing order, length match, and terms limit.
    */
  def validate(req: DistributionShapeRequest): Validation[ValidationError, Distribution] =
    Distribution.create(
      distributionType = req.distributionType,
      minLoss          = req.minLoss,
      maxLoss          = req.maxLoss,
      percentiles      = req.percentiles,
      quantiles        = req.quantiles,
      terms            = req.terms
    )

package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import zio.prelude.Validation
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil}
import com.risquanter.register.domain.data.iron.ValidationUtil.toValidation
import com.risquanter.register.domain.errors.ValidationError

final case class DistributionUpdateRequest(
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)
object DistributionUpdateRequest:
  given JsonCodec[DistributionUpdateRequest] = DeriveJsonCodec.gen

  def validate(req: DistributionUpdateRequest): Validation[ValidationError, Distribution] =
    Distribution.create(
      distributionType = req.distributionType,
      probability = req.probability,
      minLoss = req.minLoss,
      maxLoss = req.maxLoss,
      percentiles = req.percentiles,
      quantiles = req.quantiles,
      fieldPrefix = "request"
    )

final case class NodeRenameRequest(
  name: String
)
object NodeRenameRequest:
  given JsonCodec[NodeRenameRequest] = DeriveJsonCodec.gen

  def validate(req: NodeRenameRequest): Validation[ValidationError, SafeName.SafeName] =
    toValidation(ValidationUtil.refineName(req.name, "request.name"))

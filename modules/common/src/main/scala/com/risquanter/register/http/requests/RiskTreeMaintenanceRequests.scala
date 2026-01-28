package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}

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

final case class NodeRenameRequest(
  name: String
)
object NodeRenameRequest:
  given JsonCodec[NodeRenameRequest] = DeriveJsonCodec.gen

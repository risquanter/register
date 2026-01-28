package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}

/** Create request payloads for hierarchical risk trees. */
final case class RiskTreeDefinitionRequest(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequest],
  leaves: Seq[RiskLeafDefinitionRequest]
)
object RiskTreeDefinitionRequest:
  given JsonCodec[RiskTreeDefinitionRequest] = DeriveJsonCodec.gen

final case class RiskPortfolioDefinitionRequest(
  name: String,
  parentName: Option[String]
)
object RiskPortfolioDefinitionRequest:
  given JsonCodec[RiskPortfolioDefinitionRequest] = DeriveJsonCodec.gen

final case class RiskLeafDefinitionRequest(
  name: String,
  parentName: Option[String],
  distributionType: String,
  probability: Double,
  minLoss: Option[Long],
  maxLoss: Option[Long],
  percentiles: Option[Array[Double]],
  quantiles: Option[Array[Double]]
)
object RiskLeafDefinitionRequest:
  given JsonCodec[RiskLeafDefinitionRequest] = DeriveJsonCodec.gen

package com.risquanter.register.domain.data

import zio.json.{JsonCodec, DeriveJsonCodec, JsonDecoder, JsonEncoder}

/** Recursive ADT representing a risk hierarchy tree.
  * 
  * Tree Structure:
  * - RiskLeaf: Terminal node with distribution parameters (actual risk)
  * - RiskPortfolio: Branch node containing children (aggregation of risks)
  * 
  * Example:
  * {{{
  * RiskPortfolio(
  *   id = "ops-risk",
  *   name = "Operational Risk",
  *   children = Array(
  *     RiskLeaf(
  *       id = "cyber",
  *       name = "Cyber Attack",
  *       distributionType = "lognormal",
  *       probability = 0.25,
  *       minLoss = Some(1000),
  *       maxLoss = Some(50000)
  *     ),
  *     RiskPortfolio(
  *       id = "it-risk",
  *       name = "IT Risk",
  *       children = Array(...)
  *     )
  *   )
  * )
  * }}}
  */
sealed trait RiskNode {
  def id: String
  def name: String
}

object RiskNode {
  // Recursive JSON codec - handles nested structures
  given codec: JsonCodec[RiskNode] = DeriveJsonCodec.gen[RiskNode]
}

/** Leaf node: Represents an actual risk with a loss distribution.
  * 
  * Distribution Modes:
  * - Expert Opinion: distributionType="expert", provide percentiles + quantiles
  * - Lognormal (BCG): distributionType="lognormal", provide minLoss + maxLoss (80% CI)
  * 
  * @param id Unique identifier for this risk (must be unique within tree)
  * @param name Human-readable risk name
  * @param distributionType "expert" or "lognormal"
  * @param probability Risk occurrence probability [0.0, 1.0]
  * @param percentiles Expert opinion: percentiles [0.0, 1.0] (expert mode only)
  * @param quantiles Expert opinion: loss values in millions (expert mode only)
  * @param minLoss Lognormal: 80% CI lower bound in millions (lognormal mode only)
  * @param maxLoss Lognormal: 80% CI upper bound in millions (lognormal mode only)
  */
final case class RiskLeaf(
  id: String,
  name: String,
  distributionType: String,
  probability: Double,
  percentiles: Option[Array[Double]] = None,
  quantiles: Option[Array[Double]] = None,
  minLoss: Option[Long] = None,
  maxLoss: Option[Long] = None
) extends RiskNode

object RiskLeaf {
  given codec: JsonCodec[RiskLeaf] = DeriveJsonCodec.gen[RiskLeaf]
}

/** RiskPortfolio node: Aggregates child risks (can be leaves or other portfolios).
  * 
  * Behavior:
  * - No distribution parameters (computes from children)
  * - No probability (implicitly 1.0 - portfolio always "occurs")
  * - Loss = sum of all children's losses per trial
  * - Can nest arbitrarily deep (typically 5-6 levels)
  * 
  * @param id Unique identifier for this portfolio (must be unique within tree)
  * @param name Human-readable portfolio name
  * @param children Array of child nodes (RiskLeaf or RiskPortfolio)
  */
final case class RiskPortfolio(
  id: String,
  name: String,
  children: Array[RiskNode]
) extends RiskNode

object RiskPortfolio {
  given codec: JsonCodec[RiskPortfolio] = DeriveJsonCodec.gen[RiskPortfolio]
  
  /** Helper: Create a flat portfolio from legacy risk array */
  def fromFlatRisks(id: String, name: String, risks: Array[RiskNode]): RiskPortfolio =
    RiskPortfolio(id, name, risks)
}

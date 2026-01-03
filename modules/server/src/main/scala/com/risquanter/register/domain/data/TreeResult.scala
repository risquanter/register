package com.risquanter.register.domain.data

/** Result of recursive tree simulation.
  * 
  * Preserves tree structure while carrying RiskResult for each node.
  * Enables bottom-up LEC computation at all tree levels.
  * 
  * Example:
  * {{{
  * RiskTreeResult.Branch(
  *   id = "ops-risk",
  *   result = RiskResult(...),  // Aggregated loss distribution
  *   children = Vector(
  *     RiskTreeResult.Leaf(id = "cyber", result = RiskResult(...)),
  *     RiskTreeResult.Branch(
  *       id = "it-risk",
  *       result = RiskResult(...),
  *       children = Vector(...)
  *     )
  *   )
  * )
  * }}}
  */
sealed trait RiskTreeResult {
  def id: String
  def result: RiskResult
}

object RiskTreeResult {
  /** Leaf result: Terminal node with its own risk distribution */
  final case class Leaf(
    id: String,
    result: RiskResult
  ) extends RiskTreeResult
  
  /** Branch result: RiskPortfolio with aggregated result + children */
  final case class Branch(
    id: String,
    result: RiskResult,               // Aggregated from children
    children: Vector[RiskTreeResult]  // Child results (leaves or branches)
  ) extends RiskTreeResult
}

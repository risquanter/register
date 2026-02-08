package com.risquanter.register.http.responses

import zio.json.{JsonCodec, DeriveJsonCodec}
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.TreeId

/** Response DTO for simulation data with Loss Exceedance Curve
  * 
  * Contains aggregated quantiles and optional Vega-Lite visualization.
  * Use hierarchical lecNode field in RiskTreeWithLEC for detailed per-risk data.
  * 
  * @param id Unique simulation identifier
  * @param name Simulation name
  * @param quantiles Aggregated key percentiles (p50, p90, p95, p99) in millions
  * @param exceedanceCurve Optional Vega-Lite JSON for aggregated LEC visualization
  */
final case class SimulationResponse(
  id: TreeId,
  name: String,
  quantiles: Map[String, Double],
  exceedanceCurve: Option[String] // Vega-Lite JSON as string
)

/** Loss Exceedance Curve data for a single risk
  * 
  * @param name Risk identifier
  * @param quantiles Key percentiles (p50, p90, p95, p99) in millions
  * @param exceedanceCurve Optional Vega-Lite JSON for this risk's LEC
  */
final case class RiskLEC(
  name: String,
  quantiles: Map[String, Double],
  exceedanceCurve: Option[String]
)

object SimulationResponse {
  given codec: JsonCodec[SimulationResponse] = DeriveJsonCodec.gen[SimulationResponse]
  
  /** Convert domain model to response DTO (metadata only, no LEC)
    * Used for GET endpoints that retrieve persisted risk tree metadata
    */
  def fromRiskTree(tree: RiskTree): SimulationResponse = SimulationResponse(
    id = tree.id,
    name = tree.name.value,
    quantiles = Map.empty,
    exceedanceCurve = None
  )
  
  /** Create response with LEC data from simulation execution
    * Used for POST endpoint that creates and runs risk tree
    * 
    * @param tree Persisted risk tree metadata
    * @param quantiles Aggregated quantiles
    * @param vegaLiteJson Aggregated Vega-Lite spec
    */
  def withLEC(
    tree: RiskTree,
    quantiles: Map[String, Double],
    vegaLiteJson: Option[String]
  ): SimulationResponse = SimulationResponse(
    id = tree.id,
    name = tree.name.value,
    quantiles = quantiles,
    exceedanceCurve = vegaLiteJson
  )
}

object RiskLEC {
  given codec: JsonCodec[RiskLEC] = DeriveJsonCodec.gen[RiskLEC]
}

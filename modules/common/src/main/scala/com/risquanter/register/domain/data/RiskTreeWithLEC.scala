package com.risquanter.register.domain.data

import com.risquanter.register.http.responses.RiskLEC

/** Result of creating and executing a risk tree with LEC computation
  * Combines persisted tree metadata with computed LEC data
  * 
  * @param riskTree Persisted risk tree metadata
  * @param quantiles Aggregated key percentiles from the loss distribution
  * @param vegaLiteSpec Optional Vega-Lite JSON for aggregated LEC visualization
  * @param individualRisks Per-risk LEC data (empty for single-risk trees)
  */
final case class RiskTreeWithLEC(
  riskTree: RiskTree,
  quantiles: Map[String, Double],
  vegaLiteSpec: Option[String],
  individualRisks: Array[RiskLEC] = Array.empty
)

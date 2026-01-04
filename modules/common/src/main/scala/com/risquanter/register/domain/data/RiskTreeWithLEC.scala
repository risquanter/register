package com.risquanter.register.domain.data

import com.risquanter.register.http.responses.RiskLEC
import zio.json.{JsonCodec, DeriveJsonCodec}

/** Result of creating and executing a risk tree with LEC computation
  * Combines persisted tree metadata with computed LEC data
  * 
  * **Hierarchical structure:**
  * - lecCurveData: Hierarchical LEC tree with curves
  * - depth: Number of levels included
  * 
  * **Top-level aggregates:**
  * - quantiles: Key percentiles from aggregate loss distribution
  * - vegaLiteSpec: Vega-Lite JSON embedding all visible curves
  * 
  * **Provenance (optional):**
  * - provenance: Complete reproducibility metadata for all nodes
  * - Enabled via ?includeProvenance=true query parameter
  * 
  * @param riskTree Persisted risk tree metadata
  * @param quantiles Aggregated key percentiles from the loss distribution
  * @param vegaLiteSpec Vega-Lite JSON embedding all visible curves
  * @param lecCurveData Hierarchical LEC tree with depth-controlled children
  * @param depth Number of tree levels included in lecCurveData
  * @param provenance Optional provenance metadata for reproducibility
  */
final case class RiskTreeWithLEC(
  riskTree: RiskTree,
  quantiles: Map[String, Double],
  vegaLiteSpec: Option[String],
  lecCurveData: Option[LECCurveData] = None,
  depth: Int = 0,
  provenance: Option[TreeProvenance] = None
)

object RiskTreeWithLEC {
  given codec: JsonCodec[RiskTreeWithLEC] = DeriveJsonCodec.gen[RiskTreeWithLEC]
}

package com.risquanter.register.domain.data

import com.risquanter.register.http.responses.RiskLEC
import zio.json.{JsonCodec, DeriveJsonCodec}
import sttp.tapir.Schema

/** Result of creating and executing a risk tree with LEC computation
  * Combines persisted tree metadata with computed LEC data
  * 
  * **Flat LEC structure (post ADR-004a/005 redesign):**
  * - lecCurve: LEC data for root node only (no embedded children)
  * - Client fetches child curves separately via node-specific endpoints
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
  * @param lecCurve Root node LEC curve (flat, children fetched separately)
  * @param provenance Optional provenance metadata for reproducibility
  */
final case class RiskTreeWithLEC(
  riskTree: RiskTree,
  quantiles: Map[String, Double],
  vegaLiteSpec: Option[String],
  lecCurve: Option[LECCurveResponse] = None,
  provenance: Option[TreeProvenance] = None
)

object RiskTreeWithLEC {
  given codec: JsonCodec[RiskTreeWithLEC] = DeriveJsonCodec.gen[RiskTreeWithLEC]
  given schema: Schema[RiskTreeWithLEC] = Schema.derived[RiskTreeWithLEC]
}

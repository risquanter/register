package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import com.risquanter.register.domain.data.RiskNode

/** Request DTO for creating a new risk tree
  * 
  * **Hierarchical Structure:**
  * - Provide `root` RiskNode (can be RiskLeaf or RiskPortfolio)
  * - Supports arbitrary nesting depth
  * - Each node has its own distribution and probability
  * 
  * **Distribution Modes:**
  * 1. **Expert Opinion**: `distributionType="expert"`, provide `percentiles` + `quantiles`
  * 2. **Lognormal (BCG)**: `distributionType="lognormal"`, provide `minLoss` + `maxLoss` (80% CI bounds)
  * 
  * @param name Risk tree name
  * @param nTrials Number of Monte Carlo trials (default: 10,000)
  * @param root Hierarchical risk tree structure (required)
  */
final case class CreateSimulationRequest(
  name: String,
  nTrials: Int = 10000,
  root: RiskNode
)

object CreateSimulationRequest {
  given codec: JsonCodec[CreateSimulationRequest] = DeriveJsonCodec.gen[CreateSimulationRequest]
}

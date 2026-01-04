package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}
import com.risquanter.register.domain.data.RiskNode

/** Request DTO for creating a new risk tree
  * 
  * **Hierarchical Structure (Recommended):**
  * - Provide `root` RiskNode (can be RiskLeaf or RiskPortfolio)
  * - Supports arbitrary nesting depth
  * - Each node has its own distribution and probability
  * 
  * **Flat Structure (Backward Compatible):**
  * - Provide `risks` array of RiskDefinition
  * - Automatically wrapped in root RiskPortfolio
  * - Limited to one-level trees
  * 
  * **Distribution Modes:**
  * 1. **Expert Opinion**: `distributionType="expert"`, provide `percentiles` + `quantiles`
  * 2. **Lognormal (BCG)**: `distributionType="lognormal"`, provide `minLoss` + `maxLoss` (80% CI bounds)
  * 
  * @param name Risk tree name
  * @param nTrials Number of Monte Carlo trials (default: 10,000)
  * @param root Hierarchical risk tree structure (preferred)
  * @param risks Flat array of risks for backward compatibility (deprecated)
  */
final case class CreateSimulationRequest(
  name: String,
  nTrials: Int = 10000,
  root: Option[RiskNode] = None,
  risks: Option[Array[RiskDefinition]] = None
)

/** Individual risk definition for flat portfolio simulations (deprecated, use RiskNode instead)
  * 
  * @param name Unique risk identifier
  * @param distributionType "expert" or "lognormal"
  * @param probability Risk occurrence probability [0.0, 1.0]
  * @param percentiles Expert opinion percentiles (expert mode)
  * @param quantiles Expert opinion quantiles in millions (expert mode)
  * @param minLoss Lognormal 80% CI lower bound in millions (lognormal mode)
  * @param maxLoss Lognormal 80% CI upper bound in millions (lognormal mode)
  */
final case class RiskDefinition(
  name: String,
  distributionType: String, // "expert" or "lognormal"
  probability: Double,
  percentiles: Option[Array[Double]] = None,
  quantiles: Option[Array[Double]] = None,
  minLoss: Option[Long] = None,
  maxLoss: Option[Long] = None
)

object CreateSimulationRequest {
  given codec: JsonCodec[CreateSimulationRequest] = DeriveJsonCodec.gen[CreateSimulationRequest]
}

object RiskDefinition {
  given codec: JsonCodec[RiskDefinition] = DeriveJsonCodec.gen[RiskDefinition]
}

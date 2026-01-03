package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}

/** Request DTO for creating a new simulation
  * 
  * **One-Level Tree (BCG-Compatible):**
  * - Root portfolio contains array of leaf risks
  * - Each risk has its own distribution and probability
  * - Returns aggregated LEC + individual risk LECs
  * 
  * **Two Distribution Modes:**
  * 1. **Expert Opinion**: `distributionType="expert"`, provide `percentiles` + `quantiles`
  * 2. **Lognormal (BCG)**: `distributionType="lognormal"`, provide `minLoss` + `maxLoss` (80% CI bounds)
  * 
  * @param name Simulation name
  * @param nTrials Number of Monte Carlo trials (max 100,000 for synchronous execution)
  * @param risks Array of risk definitions (required, must have ≥1 element)
  */
final case class CreateSimulationRequest(
  name: String,
  nTrials: Int = 10000,
  risks: Array[RiskDefinition]
)

/** Individual risk definition for portfolio simulations
  * 
  * @param riskName Unique risk identifier
  * @param distributionType "expert" or "lognormal"
  * @param probability Risk occurrence probability [0.0, 1.0]
  * @param percentiles Expert opinion percentiles (expert mode)
  * @param quantiles Expert opinion quantiles in millions (expert mode)
  * @param minLoss Lognormal 80% CI lower bound in millions (lognormal mode)
  * @param maxLoss Lognormal 80% CI upper bound in millions (lognormal mode)
  */
final case class RiskDefinition(
  riskName: String,
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

package com.risquanter.register.http.responses

import zio.json.{JsonCodec, DeriveJsonCodec}
import com.risquanter.register.domain.data.Simulation

/** Response DTO for simulation data
  * Uses plain types with JsonCodec for serialization
  */
final case class SimulationResponse(
  id: Long,
  name: String,
  minLoss: Long,
  maxLoss: Long,
  likelihoodId: Long,
  probability: Double
)

object SimulationResponse {
  given codec: JsonCodec[SimulationResponse] = DeriveJsonCodec.gen[SimulationResponse]
  
  /** Convert domain model to response DTO
    * Extracts .value from Iron opaque types
    */
  def fromSimulation(sim: Simulation): SimulationResponse = SimulationResponse(
    id = sim.id,
    name = sim.name.value,
    minLoss = sim.minLoss,
    maxLoss = sim.maxLoss,
    likelihoodId = sim.likelihoodId,
    probability = sim.probability.toDouble
  )
}

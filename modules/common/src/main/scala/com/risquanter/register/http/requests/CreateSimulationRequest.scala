package com.risquanter.register.http.requests

import zio.json.{JsonCodec, DeriveJsonCodec}

/** Request DTO for creating a new simulation
  * Uses plain types - validation happens after deserialization
  */
final case class CreateSimulationRequest(
  // id is generated on the backend at creation time
  name: String,
  minLoss: Long,
  maxLoss: Long,
  likelihoodId: Long,
  probability: Double)

object CreateSimulationRequest {
  given codec: JsonCodec[CreateSimulationRequest] = DeriveJsonCodec.gen[CreateSimulationRequest]
}

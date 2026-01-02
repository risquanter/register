package com.risquanter.register.domain.data

import com.risquanter.register.domain.data.iron.{SafeName, NonNegativeLong, Probability}

/** Domain model for a simulation registration
  * Uses Iron refined types for type safety - no JsonCodec
  */
final case class Simulation(
  id: NonNegativeLong,
  name: SafeName.SafeName,
  minLoss: NonNegativeLong,
  maxLoss: NonNegativeLong,
  likelihoodId: NonNegativeLong,
  probability: Probability
)

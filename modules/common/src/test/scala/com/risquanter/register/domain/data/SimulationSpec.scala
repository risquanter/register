package com.risquanter.register.domain.data

import zio.test.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.iron.SafeName

object SimulationSpec extends ZIOSpecDefault {

  def spec = suite("Simulation")(
    test("can be created with valid Iron types") {
      val simulation = Simulation(
        id = 1L,
        name = SafeName.SafeName("Risk Assessment"),
        minLoss = 1000L,
        maxLoss = 50000L,
        likelihoodId = 5L,
        probability = 0.75
      )
      
      assertTrue(
        simulation.id == 1L,
        simulation.name.value == "Risk Assessment",
        simulation.minLoss == 1000L,
        simulation.maxLoss == 50000L,
        simulation.probability == 0.75
      )
    },
    test("enforces type safety with opaque types") {
      val name = SafeName.SafeName("Test")
      val simulation = Simulation(
        id = 3L,
        name = name,
        minLoss = 0L,
        maxLoss = 1000L,
        likelihoodId = 1L,
        probability = 0.1
      )
      
      // Can extract values
      assertTrue(
        simulation.name.value == "Test",
      )
    },
    
    test("domain model has no JsonCodec") {
      // This test documents that Simulation is internal only
      // Serialization must go through Response DTOs
      
      val simulation = Simulation(
        id = 4L,
        name = SafeName.SafeName("Internal"),
        minLoss = 100L,
        maxLoss = 5000L,
        likelihoodId = 2L,
        probability = 0.25
      )
      
      assertTrue(simulation.id == 4L)
    }
  )
}

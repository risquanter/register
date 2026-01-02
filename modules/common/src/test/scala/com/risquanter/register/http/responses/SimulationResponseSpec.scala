package com.risquanter.register.http.responses

import zio.test.*
import zio.json.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.Simulation
import com.risquanter.register.domain.data.iron.SafeName

object SimulationResponseSpec extends ZIOSpecDefault {

  def spec = suite("SimulationResponse")(
    test("has JsonCodec for serialization") {
      val response = SimulationResponse(
        id = 1L,
        name = "Test Simulation",
        minLoss = 1000L,
        maxLoss = 50000L,
        likelihoodId = 5L,
        probability = 0.75
      )
      
      val json = response.toJson
      val decoded = json.fromJson[SimulationResponse]
      
      assertTrue(
        decoded.isRight,
        decoded.contains(response)
      )
    },
    
    test("fromSimulation converts domain model to response") {
      val simulation = Simulation(
        id = 1L,
        name = SafeName.SafeName("Risk Assessment".refineUnsafe),
        minLoss = 1000L,
        maxLoss = 50000L,
        likelihoodId = 5L,
        probability = 0.75
      )
      
      val response = SimulationResponse.fromSimulation(simulation)
      
      assertTrue(
        response.id == 1L,
        response.name == "Risk Assessment",
        response.minLoss == 1000L,
        response.maxLoss == 50000L,
        response.likelihoodId == 5L,
        response.probability == 0.75
      )
    },
    
    test("fromSimulation extracts .value from opaque types") {
      val simulation = Simulation(
        id = 2L,
        name = SafeName.SafeName("Test".refineUnsafe),
        minLoss = 100L,
        maxLoss = 5000L,
        likelihoodId = 1L,
        probability = 0.5
      )
      
      val response = SimulationResponse.fromSimulation(simulation)
      
      // Verify that name.value was extracted (SafeName -> String)
      val nameStr: String = response.name
      assertTrue(nameStr == "Test")
    },
    
    test("serializes to JSON correctly") {
      val response = SimulationResponse(
        id = 4L,
        name = "JSON Test",
        minLoss = 200L,
        maxLoss = 2000L,
        likelihoodId = 3L,
        probability = 0.3
      )
      
      val json = response.toJson
      
      assertTrue(
        json.contains("\"id\":4"),
        json.contains("\"name\":\"JSON Test\""),
        json.contains("\"probability\":0.3")
      )
    },
    
    test("round-trip: domain -> response -> JSON -> response") {
      val simulation = Simulation(
        id = 5L,
        name = SafeName.SafeName("Round Trip".refineUnsafe),
        minLoss = 1000L,
        maxLoss = 15000L,
        likelihoodId = 4L,
        probability = 0.8
      )
      
      val response1 = SimulationResponse.fromSimulation(simulation)
      val json = response1.toJson
      val response2 = json.fromJson[SimulationResponse]
      
      assertTrue(
        response2.isRight,
        response2.contains(response1)
      )
    }
  )
}

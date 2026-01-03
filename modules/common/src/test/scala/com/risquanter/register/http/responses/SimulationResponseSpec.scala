package com.risquanter.register.http.responses

import zio.test.*
import zio.json.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf}
import com.risquanter.register.domain.data.iron.SafeName

object SimulationResponseSpec extends ZIOSpecDefault {

  def spec = suite("SimulationResponse")(
    test("has JsonCodec for serialization") {
      val response = SimulationResponse(
        id = 1L,
        name = "Test Simulation",
        quantiles = Map("p50" -> 10.5, "p90" -> 25.0, "p95" -> 30.0, "p99" -> 40.0),
        exceedanceCurve = Some("""{"$schema":"https://vega.github.io/schema/vega-lite/v5.json"}"""),
        individualRisks = Array.empty
      )
      
      val json = response.toJson
      val decoded = json.fromJson[SimulationResponse]
      
      assertTrue(
        decoded.isRight,
        decoded.map(_.id).contains(1L),
        decoded.map(_.name).contains("Test Simulation"),
        decoded.map(_.quantiles.size).contains(4)
      )
    },
    
    test("fromRiskTree converts domain model to response (metadata only)") {
      val riskTree = RiskTree(
        id = 1L,
        name = SafeName.SafeName("Risk Assessment".refineUnsafe),
        nTrials = 10000,
        root = RiskLeaf(
          id = "test-risk",
          name = "TestRisk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(50000L),
          percentiles = None,
          quantiles = None
        )
      )
      
      val response = SimulationResponse.fromRiskTree(riskTree)
      
      assertTrue(
        response.id == 1L,
        response.name == "Risk Assessment",
        response.quantiles.isEmpty, // No LEC data yet
        response.exceedanceCurve.isEmpty,
        response.individualRisks.isEmpty
      )
    },
    
    test("fromRiskTree extracts .value from opaque types") {
      val riskTree = RiskTree(
        id = 2L,
        name = SafeName.SafeName("Test".refineUnsafe),
        nTrials = 5000,
        root = RiskLeaf(
          id = "risk1",
          name = "Risk1",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(100L),
          maxLoss = Some(5000L),
          percentiles = None,
          quantiles = None
        )
      )
      
      val response = SimulationResponse.fromRiskTree(riskTree)
      
      // Verify that name.value was extracted (SafeName -> String)
      val nameStr: String = response.name
      assertTrue(nameStr == "Test")
    },
    
    test("serializes to JSON correctly") {
      val response = SimulationResponse(
        id = 4L,
        name = "JSON Test",
        quantiles = Map("p50" -> 5.0, "p90" -> 15.0),
        exceedanceCurve = None,
        individualRisks = Array.empty
      )
      
      val json = response.toJson
      
      assertTrue(
        json.contains("\"id\":4"),
        json.contains("\"name\":\"JSON Test\""),
        json.contains("\"quantiles\":")
      )
    },
    
    test("round-trip: domain -> response -> JSON -> response") {
      val riskTree = RiskTree(
        id = 5L,
        name = SafeName.SafeName("Round Trip".refineUnsafe),
        nTrials = 10000,
        root = RiskLeaf(
          id = "risk1",
          name = "Risk1",
          distributionType = "lognormal",
          probability = 0.8,
          minLoss = Some(1000L),
          maxLoss = Some(15000L),
          percentiles = None,
          quantiles = None
        )
      )
      
      val response1 = SimulationResponse.fromRiskTree(riskTree)
      val json = response1.toJson
      val response2 = json.fromJson[SimulationResponse]
      
      assertTrue(
        response2.isRight,
        response2.map(_.id).contains(5L),
        response2.map(_.name).contains("Round Trip")
      )
    }
  )
}

package com.risquanter.register.http.requests

import zio.test.*
import zio.json.*
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf}

object CreateSimulationRequestSpec extends ZIOSpecDefault {

  def spec = suite("CreateSimulationRequest")(
    test("has JsonCodec for serialization - hierarchical format") {
      val leaf = RiskLeaf.create(
        id = "risk1",
        name = "Risk1",
        distributionType = "lognormal",
        probability = 0.5,
        minLoss = Some(1000L),
        maxLoss = Some(50000L)
      ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))

      val request = CreateSimulationRequest(
        name = "Risk Assessment",
        nTrials = 10000,
        root = leaf
      )
      
      val json = request.toJson
      val decoded = json.fromJson[CreateSimulationRequest]
      
      assertTrue(
        decoded.isRight,
        decoded.map(_.name).contains("Risk Assessment")
      )
    },
    
    test("can deserialize from JSON string - hierarchical format") {
      val json = """{"name":"Test","nTrials":5000,"root":{"RiskLeaf":{"id":"single-risk","name":"SingleRisk","distributionType":"lognormal","probability":0.5,"minLoss":100,"maxLoss":1000}}}"""
      val result = json.fromJson[CreateSimulationRequest]
      
      assertTrue(
        result.isRight,
        result.map(_.name).contains("Test"),
        result.map(_.nTrials).contains(5000)
      )
    },
    
    test("serializes to JSON correctly") {
      val leaf = RiskLeaf.create(
        id = "risk1",
        name = "Risk1",
        distributionType = "lognormal",
        probability = 0.25,
        minLoss = Some(500L),
        maxLoss = Some(10000L)
      ).toEither.getOrElse(throw new RuntimeException("Invalid test data"))

      val request = CreateSimulationRequest(
        name = "Test Sim",
        nTrials = 10000,
        root = leaf
      )
      
      val json = request.toJson
      
      assertTrue(
        json.contains("\"name\":\"Test Sim\""),
        json.contains("\"nTrials\":10000"),
        json.contains("\"root\":")
      )
    }
  )
}

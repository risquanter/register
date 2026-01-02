package com.risquanter.register.http.requests

import zio.test.*
import zio.json.*

object CreateSimulationRequestSpec extends ZIOSpecDefault {

  def spec = suite("CreateSimulationRequest")(
    test("has JsonCodec for serialization") {
      val request = CreateSimulationRequest(
        name = "Risk Assessment",
        minLoss = 1000L,
        maxLoss = 50000L,
        likelihoodId = 5L,
        probability = 0.75
      )
      
      val json = request.toJson
      val decoded = json.fromJson[CreateSimulationRequest]
      
      assertTrue(
        decoded.isRight,
        decoded.contains(request)
      )
    },
    
    test("can deserialize from JSON string") {
      val json = """{"name":"Test","minLoss":100,"maxLoss":1000,"likelihoodId":1,"probability":0.5}"""
      val result = json.fromJson[CreateSimulationRequest]
      
      assertTrue(
        result.isRight,
        result.map(_.name).contains("Test"),
        result.map(_.minLoss).contains(100L),
        result.map(_.probability).contains(0.5)
      )
    },
    
    test("uses plain types - no validation on deserialization") {
      // This documents that validation happens AFTER deserialization
      // Invalid values can be deserialized, then validated via ValidationUtil
      
      val json = """{"name":"","minLoss":-100,"maxLoss":5000,"likelihoodId":1,"probability":1.5}"""
      val result = json.fromJson[CreateSimulationRequest]
      
      assertTrue(
        result.isRight, // Deserializes successfully even with invalid values
        result.map(_.name).contains(""),
        result.map(_.minLoss).contains(-100L),
        result.map(_.probability).contains(1.5)
      )
    },
    
    test("optional fields default to None") {
      val json = """{"name":"Simple","minLoss":100,"maxLoss":1000,"likelihoodId":1,"probability":0.5}"""
      val result = json.fromJson[CreateSimulationRequest]
      
      // There are no optional fields in CreateSimulationRequest, but this test
      // serves as a placeholder if optional fields are added in the future.
      assertTrue(
        result.isRight
      )
    },
    
    test("serializes to JSON correctly") {
      val request = CreateSimulationRequest(
        name = "Test Sim",
        minLoss = 500L,
        maxLoss = 10000L,
        likelihoodId = 3L,
        probability = 0.25
      )
      
      val json = request.toJson
      
      assertTrue(
        json.contains("\"name\":\"Test Sim\""),
        json.contains("\"minLoss\":500"),
        json.contains("\"probability\":0.25")
      )
    }
  )
}

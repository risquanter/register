package com.risquanter.register.http.requests

import zio.test.*
import zio.json.*
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.domain.tree.NodeId
import com.risquanter.register.testutil.TestHelpers.{safeId, idStr}

object RiskTreeDefinitionRequestSpec extends ZIOSpecDefault {

  def spec = suite("RiskTreeDefinitionRequest")(
    test("has JsonCodec for serialization - flat format") {
      val leaf = RiskLeaf.unsafeApply(
        id = idStr("risk1"),
        name = "Risk1",
        distributionType = "lognormal",
        probability = 0.5,
        minLoss = Some(1000L),
        maxLoss = Some(50000L),
        parentId = None
      )

      val request = RiskTreeDefinitionRequest(
        name = "Risk Assessment",
        nodes = Seq(leaf),
        rootId = idStr("risk1")
      )
      
      val json = request.toJson
      val decoded = json.fromJson[RiskTreeDefinitionRequest]
      
      assertTrue(
        decoded.isRight,
        decoded.map(_.name).contains("Risk Assessment"),
        decoded.map(_.nodes.size).contains(1),
        decoded.map(_.rootId).contains(idStr("risk1"))
      )
    },
    
    test("can deserialize from JSON string - flat format") {
      val single = idStr("single-risk")
      val json = s"""{"name":"Test","nodes":[{"RiskLeaf":{"id":"$single","name":"SingleRisk","distributionType":"lognormal","probability":0.5,"minLoss":100,"maxLoss":1000}}],"rootId":"$single"}"""
      val result = json.fromJson[RiskTreeDefinitionRequest]
      
      assertTrue(
        result.isRight,
        result.map(_.name).contains("Test"),
        result.map(_.nodes.size).contains(1)
      )
    },
    
    test("serializes to JSON correctly") {
      val leaf = RiskLeaf.unsafeApply(
        id = idStr("risk1"),
        name = "Risk1",
        distributionType = "lognormal",
        probability = 0.25,
        minLoss = Some(500L),
        maxLoss = Some(10000L),
        parentId = None
      )

      val request = RiskTreeDefinitionRequest(
        name = "Test Sim",
        nodes = Seq(leaf),
        rootId = idStr("risk1")
      )
      
      val json = request.toJson
      
      assertTrue(
        json.contains("\"name\":\"Test Sim\""),
        json.contains("\"nodes\":"),
        json.contains(s"\"rootId\":\"${idStr("risk1")}\"")
      )
    },
    
    test("validate rejects empty name") {
      val leaf = RiskLeaf.unsafeApply(
        id = idStr("risk1"),
        name = "Risk1",
        distributionType = "lognormal",
        probability = 0.5,
        minLoss = Some(100L),
        maxLoss = Some(1000L),
        parentId = None
      )
      
      val request = RiskTreeDefinitionRequest(
        name = "",
        nodes = Seq(leaf),
        rootId = idStr("risk1")
      )
      
      val result = RiskTreeDefinitionRequest.validate(request)
      
      assertTrue(
        result.toEither.isLeft,
        result.toEither.left.exists(_.exists(_.field.contains("name")))
      )
    },
    
    test("validate rejects empty nodes") {
      val request = RiskTreeDefinitionRequest(
        name = "Valid Name",
        nodes = Seq.empty,
        rootId = "nonexistent"
      )
      
      val result = RiskTreeDefinitionRequest.validate(request)
      
      assertTrue(
        result.toEither.isLeft,
        result.toEither.left.exists(_.exists(_.field.contains("nodes")))
      )
    },
    
    test("validate rejects rootId not in nodes") {
      val leaf = RiskLeaf.unsafeApply(
        id = idStr("risk1"),
        name = "Risk1",
        distributionType = "lognormal",
        probability = 0.5,
        minLoss = Some(100L),
        maxLoss = Some(1000L),
        parentId = None
      )
      
      val request = RiskTreeDefinitionRequest(
        name = "Valid Name",
        nodes = Seq(leaf),
        rootId = "wrong-id"  // Not in nodes list
      )
      
      val result = RiskTreeDefinitionRequest.validate(request)
      
      assertTrue(
        result.toEither.isLeft,
        result.toEither.left.exists(_.exists(_.message.contains("not found in nodes")))
      )
    },
    
    test("validate accepts valid request with portfolio") {
      val childA = RiskLeaf.unsafeApply(
        id = idStr("child-a"),
        name = "Child A",
        distributionType = "lognormal",
        probability = 0.3,
        minLoss = Some(100L),
        maxLoss = Some(1000L),
        parentId = Some(safeId("root"))
      )
      val childB = RiskLeaf.unsafeApply(
        id = idStr("child-b"),
        name = "Child B",
        distributionType = "lognormal",
        probability = 0.2,
        minLoss = Some(200L),
        maxLoss = Some(2000L),
        parentId = Some(safeId("root"))
      )
      val root = RiskPortfolio.unsafeFromStrings(
        id = idStr("root"),
        name = "Root",
        childIds = Array(idStr("child-a"), idStr("child-b")),
        parentId = None
      )
      
      val request = RiskTreeDefinitionRequest(
        name = "Valid Tree",
        nodes = Seq(root, childA, childB),
        rootId = idStr("root")
      )
      
      val result = RiskTreeDefinitionRequest.validate(request)
      
      assertTrue(
        result.toEither.isRight,
        result.toEither.exists(_._1.value == "Valid Tree"),
        result.toEither.exists(_._2.size == 3),
        result.toEither.exists(_._3.value.toString == idStr("root"))
      )
    }
  )
}

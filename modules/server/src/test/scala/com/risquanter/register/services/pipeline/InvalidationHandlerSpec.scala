package com.risquanter.register.services.pipeline

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskResult}
import com.risquanter.register.domain.data.iron.*
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.testutil.TestHelpers.*
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

/**
  * Tests for the SSE-only InvalidationHandler (milestone 2b Phase A).
  *
  * The handler no longer touches any cache — the content-addressed
  * ContentCache has no invalidation operation. What it must get right is the
  * SSE node list: every node whose figures changed (nodes + ancestors), with
  * reparent and content-change contributions unioned ADDITIVELY (TODO item
  * 17: the old exclusive if/else-if dropped the content change for a node
  * that was both reparented and param-changed in one mutation).
  */
object InvalidationHandlerSpec extends ZIOSpecDefault {

  // ========================================
  // Test fixtures
  // ========================================

  val cyberLeaf = RiskLeaf.unsafeApply(
    id = idStr("cyber"),
    name = "Cyber Attack",
    distributionType = "lognormal",
    probability = 0.25,
    minLoss = Some(1000L),
    maxLoss = Some(50000L),
    parentId = Some(nodeId("ops-risk")),
    seedVarId = 1L
  )

  val hardwareLeaf = RiskLeaf.unsafeApply(
    id = idStr("hardware"),
    name = "Hardware Failure",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(500L),
    maxLoss = Some(10000L),
    parentId = Some(nodeId("it-risk")),
    seedVarId = 2L
  )

  val softwareLeaf = RiskLeaf.unsafeApply(
    id = idStr("software"),
    name = "Software Bug",
    distributionType = "lognormal",
    probability = 0.3,
    minLoss = Some(100L),
    maxLoss = Some(5000L),
    parentId = Some(nodeId("it-risk")),
    seedVarId = 3L
  )

  val itPortfolio = RiskPortfolio.unsafeFromStrings(
    id = idStr("it-risk"),
    name = "IT Risk",
    childIds = Array(idStr("hardware"), idStr("software")),
    parentId = Some(nodeId("ops-risk"))
  )

  val rootPortfolio = RiskPortfolio.unsafeFromStrings(
    id = idStr("ops-risk"),
    name = "Operational Risk",
    childIds = Array(idStr("cyber"), idStr("it-risk")),
    parentId = None
  )

  val allNodes = Seq(rootPortfolio, cyberLeaf, itPortfolio, hardwareLeaf, softwareLeaf)
  val testTreeId: TreeId = treeId("test-tree")
  val testTree = unsafeGet(
    RiskTree.fromNodes(
      id = testTreeId,
      name = SafeName.SafeName("Test Tree".refineUnsafe),
      nodes = allNodes,
      rootId = nodeId("ops-risk")
    ),
    "Test fixture has invalid RiskTree"
  )

  private def treeWith(nodes: Seq[com.risquanter.register.domain.data.RiskNode]): RiskTree =
    unsafeGet(
      RiskTree.fromNodes(
        id = testTreeId,
        name = SafeName.SafeName("Test Tree".refineUnsafe),
        nodes = nodes,
        rootId = nodeId("ops-risk")
      ),
      "Mutated fixture has invalid RiskTree"
    )

  val testLayer: ZLayer[Any, Nothing, InvalidationHandler & SSEHub] =
    ZLayer.make[InvalidationHandler & SSEHub](
      InvalidationHandler.live,
      SSEHub.live
    )

  // ========================================
  // Tests
  // ========================================

  def spec = suite("InvalidationHandlerSpec")(

    test("param change publishes the node and its full ancestor path") {
      val changedSoftware = RiskLeaf.unsafeApply(
        id = idStr("software"),
        name = "Software Bug",
        distributionType = "lognormal",
        probability = 0.7,
        minLoss = Some(100L),
        maxLoss = Some(5000L),
        parentId = Some(nodeId("it-risk")),
        seedVarId = 3L
      )
      val newTree = treeWith(Seq(rootPortfolio, cyberLeaf, itPortfolio, hardwareLeaf, changedSoftware))
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(testTree, newTree)
      } yield {
        val ids = result.invalidatedNodes.map(_.value).toSet
        // software's aggregates changed with it: software → it-risk → ops-risk
        assertTrue(ids == Set(idStr("software"), idStr("it-risk"), idStr("ops-risk"))) &&
        assertTrue(result.subscribersNotified == 0)
      }
    },

    test("reparent + param change in ONE mutation includes the node itself (additive union — TODO item 17)") {
      // hardware moves it-risk → ops-risk AND its probability changes in the same PUT
      val movedChangedHardware = RiskLeaf.unsafeApply(
        id = idStr("hardware"),
        name = "Hardware Failure",
        distributionType = "lognormal",
        probability = 0.6,               // param change
        minLoss = Some(500L),
        maxLoss = Some(10000L),
        parentId = Some(nodeId("ops-risk")),  // reparent
        seedVarId = 2L
      )
      val newItPortfolio = RiskPortfolio.unsafeFromStrings(
        id = idStr("it-risk"),
        name = "IT Risk",
        childIds = Array(idStr("software")),
        parentId = Some(nodeId("ops-risk"))
      )
      val newRoot = RiskPortfolio.unsafeFromStrings(
        id = idStr("ops-risk"),
        name = "Operational Risk",
        childIds = Array(idStr("cyber"), idStr("it-risk"), idStr("hardware")),
        parentId = None
      )
      val newTree = treeWith(Seq(newRoot, cyberLeaf, newItPortfolio, movedChangedHardware, softwareLeaf))
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(testTree, newTree)
      } yield {
        val ids = result.invalidatedNodes.map(_.value).toSet
        // The exclusive if/else-if dropped hardware itself here — the node
        // whose params changed MUST be in the published list
        assertTrue(ids.contains(idStr("hardware"))) &&
        // Both the old parent's and new parent's aggregates changed
        assertTrue(ids.contains(idStr("it-risk"))) &&
        assertTrue(ids.contains(idStr("ops-risk")))
      }
    },

    test("no-change mutation publishes nothing") {
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(testTree, testTree)
      } yield assertTrue(result.invalidatedNodes.isEmpty) &&
        assertTrue(result.subscribersNotified == 0)
    },

    test("removed node is published (browsers must drop it)") {
      val newItPortfolio = RiskPortfolio.unsafeFromStrings(
        id = idStr("it-risk"),
        name = "IT Risk",
        childIds = Array(idStr("software")),
        parentId = Some(nodeId("ops-risk"))
      )
      val newTree = treeWith(Seq(rootPortfolio, cyberLeaf, newItPortfolio, softwareLeaf))
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(testTree, newTree)
      } yield {
        val ids = result.invalidatedNodes.map(_.value).toSet
        assertTrue(ids.contains(idStr("hardware"))) &&     // removed node
        assertTrue(ids.contains(idStr("it-risk")))          // surviving parent
      }
    },

    test("tree deletion publishes every node ID") {
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleTreeDeletion(testTree)
      } yield assertTrue(
        result.invalidatedNodes.map(_.value).toSet ==
          Set(idStr("ops-risk"), idStr("cyber"), idStr("it-risk"), idStr("hardware"), idStr("software"))
      )
    },

    test("SSE subscribers are counted when present") {
      val changedCyber = RiskLeaf.unsafeApply(
        id = idStr("cyber"),
        name = "Cyber Attack",
        distributionType = "lognormal",
        probability = 0.5,
        minLoss = Some(1000L),
        maxLoss = Some(50000L),
        parentId = Some(nodeId("ops-risk")),
        seedVarId = 1L
      )
      val newTree = treeWith(Seq(rootPortfolio, changedCyber, itPortfolio, hardwareLeaf, softwareLeaf))
      for {
        handler <- ZIO.service[InvalidationHandler]
        hub     <- ZIO.service[SSEHub]
        // Subscribe to SSE events for the test tree
        stream  <- hub.subscribe(testTreeId)
        // Start consuming in background (lazy subscription semantics)
        queue   <- Queue.unbounded[SSEEvent]
        fiber   <- stream.foreach(queue.offer).fork
        // Wait for subscriber to be registered
        _       <- hub.subscriberCount(testTreeId).repeatUntil(_ >= 1)
        result  <- handler.handleMutation(testTree, newTree)
        _       <- fiber.interrupt
        // Verify subscriber was counted
      } yield assertTrue(result.subscribersNotified == 1)
    } @@ TestAspect.withLiveClock

  ).provide(testLayer) @@ TestAspect.sequential
}

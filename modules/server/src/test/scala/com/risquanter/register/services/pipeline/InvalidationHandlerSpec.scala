package com.risquanter.register.services.pipeline

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskResult, LECCurveResponse, LECPoint}
import com.risquanter.register.domain.data.iron.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.cache.TreeCacheManager
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.testutil.TestHelpers.*
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

/**
  * Tests for InvalidationHandler (ADR-005, ADR-014).
  *
  * Verifies that handleNodeChange returns both:
  * - invalidatedNodes: the node + ancestor path whose cache entries were cleared
  * - subscribersNotified: how many SSE subscribers received the event
  *
  * Uses a stub RiskTreeService that returns a known test tree,
  * with real TreeCacheManager and SSEHub implementations.
  */
object InvalidationHandlerSpec extends ZIOSpecDefault {

  // ========================================
  // Test fixtures — same tree shape as RiskResultCacheSpec
  // ========================================

  val cyberLeaf = RiskLeaf.unsafeApply(
    id = idStr("cyber"),
    name = "Cyber Attack",
    distributionType = "lognormal",
    probability = 0.25,
    minLoss = Some(1000L),
    maxLoss = Some(50000L),
    parentId = Some(nodeId("ops-risk"))
  )

  val hardwareLeaf = RiskLeaf.unsafeApply(
    id = idStr("hardware"),
    name = "Hardware Failure",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(500L),
    maxLoss = Some(10000L),
    parentId = Some(nodeId("it-risk"))
  )

  val softwareLeaf = RiskLeaf.unsafeApply(
    id = idStr("software"),
    name = "Software Bug",
    distributionType = "lognormal",
    probability = 0.3,
    minLoss = Some(100L),
    maxLoss = Some(5000L),
    parentId = Some(nodeId("it-risk"))
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

  // ========================================
  // Stub RiskTreeService — returns testTree for known ID, None otherwise
  // ========================================

  val stubTreeService: RiskTreeService = new RiskTreeService {
    def create(req: RiskTreeDefinitionRequest): Task[RiskTree] =
      ZIO.fail(new UnsupportedOperationException("stub"))
    def update(id: TreeId, req: RiskTreeUpdateRequest): Task[RiskTree] =
      ZIO.fail(new UnsupportedOperationException("stub"))
    def delete(id: TreeId): Task[RiskTree] =
      ZIO.fail(new UnsupportedOperationException("stub"))
    def getAll: Task[List[RiskTree]] =
      ZIO.fail(new UnsupportedOperationException("stub"))
    def getById(id: TreeId): Task[Option[RiskTree]] =
      ZIO.succeed(if id == testTreeId then Some(testTree) else None)
    def getLECCurve(treeId: TreeId, nodeId: NodeId, includeProvenance: Boolean): Task[LECCurveResponse] =
      ZIO.fail(new UnsupportedOperationException("stub"))
    def probOfExceedance(treeId: TreeId, nodeId: NodeId, threshold: Long, includeProvenance: Boolean): Task[BigDecimal] =
      ZIO.fail(new UnsupportedOperationException("stub"))
    def getLECCurvesMulti(treeId: TreeId, nodeIds: Set[NodeId], includeProvenance: Boolean): Task[Map[NodeId, Vector[LECPoint]]] =
      ZIO.fail(new UnsupportedOperationException("stub"))
  }

  val testLayer: ZLayer[Any, Nothing, InvalidationHandler & SSEHub] =
    ZLayer.make[InvalidationHandler & SSEHub](
      InvalidationHandler.live,
      TreeCacheManager.layer,
      SSEHub.live,
      ZLayer.succeed(stubTreeService)
    )

  // ========================================
  // Tests
  // ========================================

  def spec = suite("InvalidationHandlerSpec")(

    test("returns invalidated nodes (node + ancestors) and subscriber count") {
      for {
        handler <- ZIO.service[InvalidationHandler]
        // Invalidate a leaf node — should clear cyber + ops-risk (root)
        result  <- handler.handleNodeChange(testTreeId, nodeId("cyber"))
      } yield {
        // cyber's ancestor path: cyber → ops-risk (root)
        assertTrue(result.invalidatedNodes.map(_.value).toSet == Set(idStr("cyber"), idStr("ops-risk"))) &&
        // No SSE subscribers connected, so 0 notified
        assertTrue(result.subscribersNotified == 0)
      }
    },

    test("invalidating deeper node includes full ancestor path") {
      for {
        handler <- ZIO.service[InvalidationHandler]
        // hardware is nested: hardware → it-risk → ops-risk
        result  <- handler.handleNodeChange(testTreeId, nodeId("hardware"))
      } yield {
        val ids = result.invalidatedNodes.map(_.value).toSet
        assertTrue(ids == Set(idStr("hardware"), idStr("it-risk"), idStr("ops-risk"))) &&
        assertTrue(result.subscribersNotified == 0)
      }
    },

    test("tree not found returns empty result") {
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleNodeChange(treeId("nonexistent"), nodeId("cyber"))
      } yield {
        assertTrue(result.invalidatedNodes.isEmpty) &&
        assertTrue(result.subscribersNotified == 0)
      }
    },

    test("SSE subscribers are counted when present") {
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
        result  <- handler.handleNodeChange(testTreeId, nodeId("cyber"))
        _       <- fiber.interrupt
        // Verify subscriber was counted
      } yield assertTrue(result.subscribersNotified == 1)
    } @@ TestAspect.withLiveClock

  ).provide(testLayer) @@ TestAspect.sequential
}

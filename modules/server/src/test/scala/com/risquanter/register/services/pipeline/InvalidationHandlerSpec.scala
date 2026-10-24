package com.risquanter.register.services.pipeline

import zio.*
import zio.test.*
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.data.iron.*
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.testutil.TestHelpers.*

/**
  * Tests for the SSE-only InvalidationHandler.
  *
  * The handler touches no cache — the content-addressed ContentCache has no
  * invalidation operation. What it must get right is the SSE node list: every
  * node whose figures changed (nodes + ancestors), with reparent and
  * content-change contributions unioned ADDITIVELY. An exclusive if/else-if
  * would drop the content change for a node that was both reparented and
  * param-changed in one mutation.
  */
object InvalidationHandlerSpec extends ZIOSpecDefault {

  // ========================================
  // Test fixtures
  // ========================================

  val cyberLeaf = unsafeGet(RiskLeaf.create(
    id = idStr("cyber"),
    name = "Cyber Attack",
    probability = 0.25,
    shape = lognormalShape(1000L, 50000L),
    parentId = Some(nodeId("ops-risk")),
    seedVarId = 1L
  ), "leaf")

  val hardwareLeaf = unsafeGet(RiskLeaf.create(
    id = idStr("hardware"),
    name = "Hardware Failure",
    probability = 0.1,
    shape = lognormalShape(500L, 10000L),
    parentId = Some(nodeId("it-risk")),
    seedVarId = 2L
  ), "leaf")

  val softwareLeaf = unsafeGet(RiskLeaf.create(
    id = idStr("software"),
    name = "Software Bug",
    probability = 0.3,
    shape = lognormalShape(100L, 5000L),
    parentId = Some(nodeId("it-risk")),
    seedVarId = 3L
  ), "leaf")

  val itPortfolio = unsafeGet(RiskPortfolio.createFromStrings(
    id = idStr("it-risk"),
    name = "IT Risk",
    childIds = Vector(idStr("hardware"), idStr("software")),
    parentId = Some(nodeId("ops-risk"))
  ), "portfolio")

  val rootPortfolio = unsafeGet(RiskPortfolio.createFromStrings(
    id = idStr("ops-risk"),
    name = "Operational Risk",
    childIds = Vector(idStr("cyber"), idStr("it-risk")),
    parentId = None
  ), "portfolio")

  val allNodes = Seq(rootPortfolio, cyberLeaf, itPortfolio, hardwareLeaf, softwareLeaf)
  val testTreeId: TreeId = treeId("test-tree")
  val testTree = unsafeGet(
    RiskTree.fromNodes(
      id = testTreeId,
      name = SafeName.SafeName("Test Tree".refineUnsafe),
      nodes = allNodes,
      rootId = nodeId("ops-risk"),
      mitigations = Nil
    ),
    "Test fixture has invalid RiskTree"
  )

  private def treeWith(nodes: Seq[com.risquanter.register.domain.data.RiskNode]): RiskTree =
    unsafeGet(
      RiskTree.fromNodes(
        id = testTreeId,
        name = SafeName.SafeName("Test Tree".refineUnsafe),
        nodes = nodes,
        rootId = nodeId("ops-risk"),
        mitigations = Nil
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
      val changedSoftware = unsafeGet(RiskLeaf.create(
        id = idStr("software"),
        name = "Software Bug",
        probability = 0.7,
        shape = lognormalShape(100L, 5000L),
        parentId = Some(nodeId("it-risk")),
        seedVarId = 3L
      ), "leaf")
      val newTree = treeWith(Seq(rootPortfolio, cyberLeaf, itPortfolio, hardwareLeaf, changedSoftware))
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(testTree, newTree, BranchChoice.Main)
      } yield {
        val ids = result.invalidatedNodes.map(_.value).toSet
        // software's aggregates changed with it: software → it-risk → ops-risk
        assertTrue(ids == Set(idStr("software"), idStr("it-risk"), idStr("ops-risk"))) &&
        assertTrue(result.subscribersNotified == 0)
      }
    },

    test("reparent + param change in ONE mutation includes the node itself (additive union)") {
      // hardware moves it-risk → ops-risk AND its probability changes in the same PUT
      val movedChangedHardware = unsafeGet(RiskLeaf.create(
        id = idStr("hardware"),
        name = "Hardware Failure",
        probability = 0.6,               // param change
        shape = lognormalShape(500L, 10000L),
        parentId = Some(nodeId("ops-risk")),  // reparent
        seedVarId = 2L
      ), "leaf")
      val newItPortfolio = unsafeGet(RiskPortfolio.createFromStrings(
        id = idStr("it-risk"),
        name = "IT Risk",
        childIds = Vector(idStr("software")),
        parentId = Some(nodeId("ops-risk"))
      ), "portfolio")
      val newRoot = unsafeGet(RiskPortfolio.createFromStrings(
        id = idStr("ops-risk"),
        name = "Operational Risk",
        childIds = Vector(idStr("cyber"), idStr("it-risk"), idStr("hardware")),
        parentId = None
      ), "portfolio")
      val newTree = treeWith(Seq(newRoot, cyberLeaf, newItPortfolio, movedChangedHardware, softwareLeaf))
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(testTree, newTree, BranchChoice.Main)
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
        result  <- handler.handleMutation(testTree, testTree, BranchChoice.Main)
      } yield assertTrue(result.invalidatedNodes.isEmpty) &&
        assertTrue(result.subscribersNotified == 0)
    },

    test("a freshly REBUILT identical tree publishes nothing (content comparison, not reference)") {
      // Every PUT rebuilds the tree from the request, so arrays
      // (metalog percentiles/quantiles, portfolio childIds) are fresh
      // instances. Case-class `==` compares arrays by reference and would
      // report every portfolio (and every metalog leaf) as changed on every
      // mutation. Includes a metalog leaf to cover the array-valued fields.
      def buildMetalogTree(): RiskTree = treeWith(Seq(
        unsafeGet(RiskPortfolio.createFromStrings(
          id = idStr("ops-risk"),
          name = "Operational Risk",
          childIds = Vector(idStr("cyber"), idStr("metalog-leaf")),
          parentId = None
        ), "portfolio"),
        cyberLeaf,
        unsafeGet(RiskLeaf.create(
          id = idStr("metalog-leaf"),
          name = "Metalog Leaf",
          probability = 0.2,
          shape = metalogShape(Vector(0.1, 0.5, 0.9), Vector(1000.0, 5000.0, 25000.0), Some(3)),
          parentId = Some(nodeId("ops-risk")),
          seedVarId = 4L
        ), "leaf")
      ))
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(buildMetalogTree(), buildMetalogTree(), BranchChoice.Main)
      } yield assertTrue(result.invalidatedNodes.isEmpty)
    },

    test("changing only a metalog leaf's quantile VALUES publishes exactly that leaf + ancestors") {
      def metalogLeaf(quantiles: Vector[Double]): RiskLeaf =
        unsafeGet(RiskLeaf.create(
          id = idStr("metalog-leaf"),
          name = "Metalog Leaf",
          probability = 0.2,
          shape = metalogShape(Vector(0.1, 0.5, 0.9), quantiles, Some(3)),
          parentId = Some(nodeId("it-risk")),
          seedVarId = 4L
        ), "leaf")
      def build(quantiles: Vector[Double]): RiskTree = treeWith(Seq(
        rootPortfolio,
        cyberLeaf,
        unsafeGet(RiskPortfolio.createFromStrings(
          id = idStr("it-risk"),
          name = "IT Risk",
          childIds = Vector(idStr("hardware"), idStr("software"), idStr("metalog-leaf")),
          parentId = Some(nodeId("ops-risk"))
        ), "portfolio"),
        hardwareLeaf,
        softwareLeaf,
        metalogLeaf(quantiles)
      ))
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(
          build(Vector(1000.0, 5000.0, 25000.0)),
          build(Vector(1000.0, 5000.0, 30000.0)),
          BranchChoice.Main
        )
      } yield assertTrue(
        result.invalidatedNodes.map(_.value).toSet ==
          Set(idStr("metalog-leaf"), idStr("it-risk"), idStr("ops-risk"))
      )
    },

    test("removed node is published (browsers must drop it)") {
      val newItPortfolio = unsafeGet(RiskPortfolio.createFromStrings(
        id = idStr("it-risk"),
        name = "IT Risk",
        childIds = Vector(idStr("software")),
        parentId = Some(nodeId("ops-risk"))
      ), "portfolio")
      val newTree = treeWith(Seq(rootPortfolio, cyberLeaf, newItPortfolio, softwareLeaf))
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleMutation(testTree, newTree, BranchChoice.Main)
      } yield {
        val ids = result.invalidatedNodes.map(_.value).toSet
        assertTrue(ids.contains(idStr("hardware"))) &&     // removed node
        assertTrue(ids.contains(idStr("it-risk")))          // surviving parent
      }
    },

    test("tree deletion publishes every node ID") {
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleTreeDeletion(testTree, BranchChoice.Main)
      } yield assertTrue(
        result.invalidatedNodes.map(_.value).toSet ==
          Set(idStr("ops-risk"), idStr("cyber"), idStr("it-risk"), idStr("hardware"), idStr("software"))
      )
    },

    test("a whole-tree change publishes every node ID") {
      // The extent of the change is unknown, so every node is named and a
      // subscriber re-fetches the whole tree. Same node set as a deletion,
      // reached without a before-state to diff against.
      for {
        handler <- ZIO.service[InvalidationHandler]
        result  <- handler.handleWholeTreeChange(testTree, BranchChoice.Main)
      } yield assertTrue(
        result.invalidatedNodes.map(_.value).toSet ==
          Set(idStr("ops-risk"), idStr("cyber"), idStr("it-risk"), idStr("hardware"), idStr("software"))
      )
    },

    test("SSE subscribers are counted when present") {
      val changedCyber = unsafeGet(RiskLeaf.create(
        id = idStr("cyber"),
        name = "Cyber Attack",
        probability = 0.5,
        shape = lognormalShape(1000L, 50000L),
        parentId = Some(nodeId("ops-risk")),
        seedVarId = 1L
      ), "leaf")
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
        result  <- handler.handleMutation(testTree, newTree, BranchChoice.Main)
        _       <- fiber.interrupt
        // Verify subscriber was counted
      } yield assertTrue(result.subscribersNotified == 1)
    } @@ TestAspect.withLiveClock

  ).provide(testLayer) @@ TestAspect.sequential
}

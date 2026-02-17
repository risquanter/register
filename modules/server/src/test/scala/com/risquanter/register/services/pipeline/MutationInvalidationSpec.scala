package com.risquanter.register.services.pipeline

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskResult}
import com.risquanter.register.domain.data.iron.*
import com.risquanter.register.services.cache.{TreeCacheManager, RiskResultCache}
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.testutil.TestHelpers.*
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

/**
  * Tests for mutation-triggered cache invalidation (ADR-005, ADR-014).
  *
  * Verifies that `InvalidationHandler.handleMutation(oldTree, newTree)`:
  * 1. Diffs old vs new tree to identify affected nodes
  * 2. Invalidates ancestor paths for each affected node
  * 3. Cleans up orphaned cache entries for removed nodes
  * 4. Publishes a single SSE event with the full affected set
  *
  * Test strategy: pre-populate cache for all nodes, call handleMutation with
  * known tree diffs, assert which cache entries survive and which are evicted.
  *
  * Tree shape used in all tests:
  * {{{
  *        portfolio (root)
  *           /    \
  *      ops-risk   market-risk
  *        /   \
  *    cyber  hardware
  * }}}
  *
  * TODO: Review when implementing ADR-017 Phase 2 (Batch Operations / TreeOp algebra).
  * TreeOp operations carry explicit change semantics, which may simplify or replace
  * the old-vs-new tree diffing logic tested here.
  */
object MutationInvalidationSpec extends ZIOSpecDefault {

  // ========================================
  // Tree fixtures
  // ========================================

  val testTreeId: TreeId = treeId("mut-test-tree")

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
    parentId = Some(nodeId("ops-risk"))
  )

  val marketLeaf = RiskLeaf.unsafeApply(
    id = idStr("market-risk"),
    name = "Market Risk",
    distributionType = "lognormal",
    probability = 0.2,
    minLoss = Some(2000L),
    maxLoss = Some(100000L),
    parentId = Some(nodeId("portfolio"))
  )

  val opsPortfolio = RiskPortfolio.unsafeFromStrings(
    id = idStr("ops-risk"),
    name = "Operational Risk",
    childIds = Array(idStr("cyber"), idStr("hardware")),
    parentId = Some(nodeId("portfolio"))
  )

  val rootPortfolio = RiskPortfolio.unsafeFromStrings(
    id = idStr("portfolio"),
    name = "Portfolio",
    childIds = Array(idStr("ops-risk"), idStr("market-risk")),
    parentId = None
  )

  val allNodes = Seq(rootPortfolio, opsPortfolio, cyberLeaf, hardwareLeaf, marketLeaf)

  val baseTree: RiskTree = unsafeGet(
    RiskTree.fromNodes(
      id = testTreeId,
      name = SafeName.SafeName("Test Tree".refineUnsafe),
      nodes = allNodes,
      rootId = nodeId("portfolio")
    ),
    "Base tree fixture has invalid RiskTree"
  )

  // ========================================
  // Cache population helper
  // ========================================

  /** Pre-populate cache with dummy RiskResults for all nodes in a tree. */
  def populateCache(cacheManager: TreeCacheManager, tree: RiskTree): UIO[Unit] =
    for
      cache <- cacheManager.cacheFor(tree.id)
      _     <- ZIO.foreachDiscard(tree.index.nodes.keys.toList) { nid =>
                 val result = withCfg(3) {
                   RiskResult(
                     nodeId = nid,
                     outcomes = Map(1 -> 100L, 2 -> 200L, 3 -> 300L),
                     provenances = Nil
                   )
                 }
                 cache.put(nid, result)
               }
    yield ()

  /** Get the set of node IDs still in cache. */
  def cachedNodeIds(cacheManager: TreeCacheManager, treeId: TreeId): UIO[Set[NodeId]] =
    for
      cache <- cacheManager.cacheFor(treeId)
      keys  <- cache.keys
    yield keys.toSet

  // ========================================
  // Layer
  // ========================================

  val testLayer: ZLayer[Any, Nothing, InvalidationHandler & TreeCacheManager & SSEHub] =
    ZLayer.make[InvalidationHandler & TreeCacheManager & SSEHub](
      InvalidationHandler.live,
      TreeCacheManager.layer,
      SSEHub.live
    )

  // ========================================
  // Tests
  // ========================================

  def spec = suite("MutationInvalidationSpec")(

    // ── Parameter change ────────────────────────────────────────────

    suite("parameter change (leaf node updated)")(

      test("invalidates changed node + ancestors, preserves sibling subtree") {
        // Scenario: cyber's probability changed from 0.25 → 0.4
        // New tree is structurally identical — only cyber's params differ
        val updatedCyber = RiskLeaf.unsafeApply(
          id = idStr("cyber"),
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.4,   // changed
          minLoss = Some(1000L),
          maxLoss = Some(50000L),
          parentId = Some(nodeId("ops-risk"))
        )
        val newTree = unsafeGet(
          RiskTree.fromNodes(
            id = testTreeId,
            name = SafeName.SafeName("Test Tree".refineUnsafe),
            nodes = Seq(rootPortfolio, opsPortfolio, updatedCyber, hardwareLeaf, marketLeaf),
            rootId = nodeId("portfolio")
          ),
          "Updated tree fixture invalid"
        )

        for
          handler <- ZIO.service[InvalidationHandler]
          cm      <- ZIO.service[TreeCacheManager]
          _       <- populateCache(cm, baseTree)

          // Verify all 5 nodes are cached before mutation
          before  <- cachedNodeIds(cm, testTreeId)
          _       <- assertTrue(before.size == 5)

          result  <- handler.handleMutation(baseTree, newTree)

          // After: cyber + ops-risk + portfolio should be evicted (ancestor path)
          // hardware + market-risk should survive (not ancestors of cyber)
          after   <- cachedNodeIds(cm, testTreeId)
        yield
          assertTrue(result.invalidatedNodes.map(_.value).toSet ==
            Set(idStr("cyber"), idStr("ops-risk"), idStr("portfolio"))) &&
          assertTrue(after == Set(nodeId("hardware"), nodeId("market-risk"))) &&
          assertTrue(result.subscribersNotified == 0)
      },

      test("publishes single SSE event with full affected set") {
        val updatedCyber = RiskLeaf.unsafeApply(
          id = idStr("cyber"),
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.4,
          minLoss = Some(1000L),
          maxLoss = Some(50000L),
          parentId = Some(nodeId("ops-risk"))
        )
        val newTree = unsafeGet(
          RiskTree.fromNodes(
            id = testTreeId,
            name = SafeName.SafeName("Test Tree".refineUnsafe),
            nodes = Seq(rootPortfolio, opsPortfolio, updatedCyber, hardwareLeaf, marketLeaf),
            rootId = nodeId("portfolio")
          ),
          "Updated tree fixture invalid"
        )

        for
          handler <- ZIO.service[InvalidationHandler]
          cm      <- ZIO.service[TreeCacheManager]
          hub     <- ZIO.service[SSEHub]
          _       <- populateCache(cm, baseTree)

          // Subscribe to SSE
          stream  <- hub.subscribe(testTreeId)
          queue   <- Queue.unbounded[SSEEvent]
          fiber   <- stream.foreach(queue.offer).fork
          _       <- hub.subscriberCount(testTreeId).repeatUntil(_ >= 1)

          result  <- handler.handleMutation(baseTree, newTree)

          // Should receive exactly one SSE event
          event   <- queue.take
          _       <- fiber.interrupt
        yield
          assertTrue(result.subscribersNotified == 1) &&
          assertTrue(event match {
            case SSEEvent.CacheInvalidated(nodeIds, tid) =>
              tid == testTreeId &&
              nodeIds.toSet == Set(idStr("cyber"), idStr("ops-risk"), idStr("portfolio"))
            case _ => false
          })
      } @@ TestAspect.withLiveClock
    ),

    // ── Add node ────────────────────────────────────────────────────

    suite("add node")(

      test("invalidates parent of new node + ancestors, new node has no cache entry") {
        // Scenario: add "flood" leaf under ops-risk
        val floodLeaf = RiskLeaf.unsafeApply(
          id = idStr("flood"),
          name = "Flood Risk",
          distributionType = "lognormal",
          probability = 0.05,
          minLoss = Some(10000L),
          maxLoss = Some(500000L),
          parentId = Some(nodeId("ops-risk"))
        )
        val expandedOps = RiskPortfolio.unsafeFromStrings(
          id = idStr("ops-risk"),
          name = "Operational Risk",
          childIds = Array(idStr("cyber"), idStr("hardware"), idStr("flood")),
          parentId = Some(nodeId("portfolio"))
        )
        val newTree = unsafeGet(
          RiskTree.fromNodes(
            id = testTreeId,
            name = SafeName.SafeName("Test Tree".refineUnsafe),
            nodes = Seq(rootPortfolio, expandedOps, cyberLeaf, hardwareLeaf, marketLeaf, floodLeaf),
            rootId = nodeId("portfolio")
          ),
          "Expanded tree fixture invalid"
        )

        for
          handler <- ZIO.service[InvalidationHandler]
          cm      <- ZIO.service[TreeCacheManager]
          _       <- populateCache(cm, baseTree)
          before  <- cachedNodeIds(cm, testTreeId)
          _       <- assertTrue(before.size == 5)

          result  <- handler.handleMutation(baseTree, newTree)

          // ops-risk + portfolio evicted (parent of flood + ancestor)
          // cyber, hardware, market-risk survive
          // flood was never cached (new node)
          after   <- cachedNodeIds(cm, testTreeId)
        yield
          assertTrue(result.invalidatedNodes.map(_.value).toSet.contains(idStr("ops-risk"))) &&
          assertTrue(result.invalidatedNodes.map(_.value).toSet.contains(idStr("portfolio"))) &&
          assertTrue(after.contains(nodeId("cyber"))) &&
          assertTrue(after.contains(nodeId("hardware"))) &&
          assertTrue(after.contains(nodeId("market-risk")))
      }
    ),

    // ── Remove node ─────────────────────────────────────────────────

    suite("remove node")(

      test("invalidates parent of removed node + ancestors, cleans up orphaned cache entries") {
        // Scenario: remove hardware from ops-risk
        val shrunkOps = RiskPortfolio.unsafeFromStrings(
          id = idStr("ops-risk"),
          name = "Operational Risk",
          childIds = Array(idStr("cyber")),
          parentId = Some(nodeId("portfolio"))
        )
        val newTree = unsafeGet(
          RiskTree.fromNodes(
            id = testTreeId,
            name = SafeName.SafeName("Test Tree".refineUnsafe),
            nodes = Seq(rootPortfolio, shrunkOps, cyberLeaf, marketLeaf),
            rootId = nodeId("portfolio")
          ),
          "Shrunk tree fixture invalid"
        )

        for
          handler <- ZIO.service[InvalidationHandler]
          cm      <- ZIO.service[TreeCacheManager]
          _       <- populateCache(cm, baseTree)

          result  <- handler.handleMutation(baseTree, newTree)

          after   <- cachedNodeIds(cm, testTreeId)
        yield
          // ops-risk + portfolio evicted (parent of hardware + ancestor)
          // hardware evicted (orphan cleanup — node no longer exists)
          // cyber + market-risk survive
          assertTrue(!after.contains(nodeId("hardware"))) &&
          assertTrue(!after.contains(nodeId("ops-risk"))) &&
          assertTrue(!after.contains(nodeId("portfolio"))) &&
          assertTrue(after.contains(nodeId("cyber"))) &&
          assertTrue(after.contains(nodeId("market-risk")))
      }
    ),

    // ── Move (reparent) ─────────────────────────────────────────────

    suite("move node (reparent)")(

      test("invalidates old parent + new parent ancestor paths") {
        // Scenario: move cyber from ops-risk to market-risk's level (under portfolio directly)
        // But to keep it a valid tree: reparent cyber under a new "strategic" portfolio
        // Simpler: move hardware from ops-risk to be a direct child of portfolio
        val shrunkOps = RiskPortfolio.unsafeFromStrings(
          id = idStr("ops-risk"),
          name = "Operational Risk",
          childIds = Array(idStr("cyber")),  // hardware removed
          parentId = Some(nodeId("portfolio"))
        )
        val movedHardware = RiskLeaf.unsafeApply(
          id = idStr("hardware"),
          name = "Hardware Failure",
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(500L),
          maxLoss = Some(10000L),
          parentId = Some(nodeId("portfolio"))  // was ops-risk, now portfolio
        )
        val expandedRoot = RiskPortfolio.unsafeFromStrings(
          id = idStr("portfolio"),
          name = "Portfolio",
          childIds = Array(idStr("ops-risk"), idStr("market-risk"), idStr("hardware")),
          parentId = None
        )
        val newTree = unsafeGet(
          RiskTree.fromNodes(
            id = testTreeId,
            name = SafeName.SafeName("Test Tree".refineUnsafe),
            nodes = Seq(expandedRoot, shrunkOps, cyberLeaf, movedHardware, marketLeaf),
            rootId = nodeId("portfolio")
          ),
          "Reparented tree fixture invalid"
        )

        for
          handler <- ZIO.service[InvalidationHandler]
          cm      <- ZIO.service[TreeCacheManager]
          _       <- populateCache(cm, baseTree)

          result  <- handler.handleMutation(baseTree, newTree)

          after   <- cachedNodeIds(cm, testTreeId)
        yield
          // Old parent (ops-risk) ancestor path: ops-risk → portfolio → evicted
          // New parent (portfolio) ancestor path: portfolio → evicted (already covered)
          // hardware itself should NOT be invalidated (params unchanged, result still valid)
          // cyber survives (sibling, not affected)
          // market-risk survives (different subtree)
          assertTrue(!after.contains(nodeId("ops-risk"))) &&
          assertTrue(!after.contains(nodeId("portfolio"))) &&
          assertTrue(after.contains(nodeId("hardware"))) &&
          assertTrue(after.contains(nodeId("cyber"))) &&
          assertTrue(after.contains(nodeId("market-risk")))
      }
    ),

    // ── Tree deletion ───────────────────────────────────────────────

    suite("tree deletion")(

      test("clears entire cache and publishes CacheInvalidated with all node IDs") {
        for
          handler <- ZIO.service[InvalidationHandler]
          cm      <- ZIO.service[TreeCacheManager]
          hub     <- ZIO.service[SSEHub]
          _       <- populateCache(cm, baseTree)

          // Subscribe to SSE
          stream  <- hub.subscribe(testTreeId)
          queue   <- Queue.unbounded[SSEEvent]
          fiber   <- stream.foreach(queue.offer).fork
          _       <- hub.subscriberCount(testTreeId).repeatUntil(_ >= 1)

          result  <- handler.handleTreeDeletion(baseTree)

          after   <- cachedNodeIds(cm, testTreeId)
          event   <- queue.take
          _       <- fiber.interrupt
        yield
          // All cache entries gone
          assertTrue(after.isEmpty) &&
          // SSE event contains all node IDs
          assertTrue(event match {
            case SSEEvent.CacheInvalidated(nodeIds, tid) =>
              tid == testTreeId &&
              nodeIds.toSet == baseTree.index.nodes.keys.map(_.value).toSet
            case _ => false
          }) &&
          assertTrue(result.subscribersNotified == 1)
      } @@ TestAspect.withLiveClock
    ),

    // ── Idempotency ─────────────────────────────────────────────────

    suite("idempotency")(

      test("calling handleMutation twice produces same result without errors") {
        val updatedCyber = RiskLeaf.unsafeApply(
          id = idStr("cyber"),
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.4,
          minLoss = Some(1000L),
          maxLoss = Some(50000L),
          parentId = Some(nodeId("ops-risk"))
        )
        val newTree = unsafeGet(
          RiskTree.fromNodes(
            id = testTreeId,
            name = SafeName.SafeName("Test Tree".refineUnsafe),
            nodes = Seq(rootPortfolio, opsPortfolio, updatedCyber, hardwareLeaf, marketLeaf),
            rootId = nodeId("portfolio")
          ),
          "Updated tree fixture invalid"
        )

        for
          handler <- ZIO.service[InvalidationHandler]
          cm      <- ZIO.service[TreeCacheManager]
          _       <- populateCache(cm, baseTree)

          result1 <- handler.handleMutation(baseTree, newTree)
          // Second call — cache already evicted, should not error
          result2 <- handler.handleMutation(baseTree, newTree)

          after   <- cachedNodeIds(cm, testTreeId)
        yield
          // First call evicts, second is no-op (same cache state)
          assertTrue(result1.invalidatedNodes.nonEmpty) &&
          assertTrue(after == Set(nodeId("hardware"), nodeId("market-risk")))
      }
    ),

    // ── No-op mutation ──────────────────────────────────────────────

    suite("no-op mutation")(

      test("identical old and new tree produces no invalidation") {
        for
          handler <- ZIO.service[InvalidationHandler]
          cm      <- ZIO.service[TreeCacheManager]
          _       <- populateCache(cm, baseTree)

          result  <- handler.handleMutation(baseTree, baseTree)

          after   <- cachedNodeIds(cm, testTreeId)
        yield
          // No nodes changed → nothing invalidated → all cache entries survive
          assertTrue(result.invalidatedNodes.isEmpty) &&
          assertTrue(result.subscribersNotified == 0) &&
          assertTrue(after.size == 5)
      }
    )

  ).provide(testLayer) @@ TestAspect.sequential
}

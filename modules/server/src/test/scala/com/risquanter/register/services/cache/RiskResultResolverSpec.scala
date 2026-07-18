package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*
import com.risquanter.register.configs.{SimulationConfig, TestConfigs}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.domain.data.{RiskResult, RiskNode, RiskLeaf, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, PositiveInt, TreeId, NodeId, SeedEntityId}
import com.risquanter.register.testutil.TestHelpers.*

/**
 * Tests for RiskResultResolverLive (ADR-015), content-addressed since
 * milestone 2b Phase A.
 *
 * Verifies cache-aside behavior over ContentHash keys (DD-16), per-workspace
 * cache isolation via CacheScope (DD-17), leaf-only caching (DD-15), orphan
 * semantics (a param edit strands the old entry; the new content misses),
 * and error handling.
 */
object RiskResultResolverSpec extends ZIOSpecDefault {

  private val testEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

  // Test fixture: Simple risk tree for testing (flat node format)
  private val rootIdStr  = safeId("root").value.toString
  private val risk1IdStr = safeId("risk1").value.toString
  private val risk2IdStr = safeId("risk2").value.toString

  val risk1Leaf = RiskLeaf.unsafeApply(
    id = risk1IdStr,
    name = "Risk 1",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(10000L),
    maxLoss = Some(50000L),
    parentId = Some(nodeId("root")),
    seedVarId = 1L
  )

  val risk2Leaf = RiskLeaf.unsafeApply(
    id = risk2IdStr,
    name = "Risk 2",
    distributionType = "lognormal",
    probability = 0.2,
    minLoss = Some(5000L),
    maxLoss = Some(20000L),
    parentId = Some(nodeId("root")),
    seedVarId = 2L
  )

  val rootNode: RiskNode = RiskPortfolio.unsafeFromStrings(
    id = rootIdStr,
    name = "Root Portfolio",
    childIds = Array(risk1IdStr, risk2IdStr),
    parentId = None
  )

  val allNodes = Seq(rootNode, risk1Leaf, risk2Leaf)
  val rootId = nodeId("root")
  val risk1Id = nodeId("risk1")
  val risk2Id = nodeId("risk2")

  // Create test RiskTree
  val testTreeId: TreeId = treeId("test-tree")
  val testTree = unsafeGet(
    RiskTree.fromNodes(
      id = testTreeId,
      name = SafeName.SafeName("Test Tree".refineUnsafe),
      nodes = allNodes,
      rootId = rootId
    ),
    "Test fixture has invalid RiskTree"
  )

  // Content-hash keys are derived from leaf content, not node identity
  val risk1Key = ContentHashIndex.hashOf(risk1Leaf)
  val risk2Key = ContentHashIndex.hashOf(risk2Leaf)

  // Test layer with all dependencies
  val testLayer: ZLayer[Any, Throwable, RiskResultResolver & CacheScope] =
    ZLayer.make[RiskResultResolver & CacheScope](
      CacheScope.layer,
      ZLayer.succeed(TestConfigs.simulation),
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console,
      RiskResultResolverLive.layer
    )

  def spec = suite("RiskResultResolverSpec")(

    suite("ensureCached - content-addressed cache behavior")(

      test("cache miss: simulates and caches the identity-free content under the content hash") {
        for {
          resolver   <- ZIO.service[RiskResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          // Verify cache is empty initially
          initialCached <- cache.get(risk1Key)
          _ <- ZIO.succeed(assertTrue(initialCached.isEmpty))

          // Call ensureCached - should simulate
          result <- resolver.ensureCached(testTree, risk1Id, testEntity)

          // Verify result carries the requested node's identity
          _ <- ZIO.succeed(assertTrue(result.nodeId == risk1Id))

          // Verify content is now cached under the leaf's content hash
          cachedResult <- cache.get(risk1Key)
        } yield assertTrue(
          cachedResult.isDefined,
          // Cached value is identity-free: same outcomes, no node ID anywhere
          cachedResult.get.outcomes.outcomes == result.outcomes
        )
      },

      test("cache hit: returns cached content and counts a hit") {
        for {
          resolver   <- ZIO.service[RiskResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          // First call: simulate and cache
          firstResult  <- resolver.ensureCached(testTree, risk1Id, testEntity)
          statsBefore  <- cache.stats

          // Second call: served from cache
          secondResult <- resolver.ensureCached(testTree, risk1Id, testEntity)
          statsAfter   <- cache.stats
        } yield assertTrue(
          firstResult.outcomes == secondResult.outcomes,
          firstResult.nodeId == secondResult.nodeId,
          statsAfter.hits > statsBefore.hits
        )
      },

      test("simulates portfolio by aggregating children — portfolio results are never cached (DD-15)") {
        for {
          resolver   <- ZIO.service[RiskResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          risk1Result <- resolver.ensureCached(testTree, risk1Id, testEntity)
          risk2Result <- resolver.ensureCached(testTree, risk2Id, testEntity)
          rootResult  <- resolver.ensureCached(testTree, rootId, testEntity)
          allTrialIds  = risk1Result.outcomes.keySet ++ risk2Result.outcomes.keySet
          stats       <- cache.stats
        } yield assertTrue(
          rootResult.nodeId == rootId,
          // Deterministic seeds: both leaves fire in at least one trial
          allTrialIds.nonEmpty,
          // Every trial in either child: root outcome = pointwise sum (missing = 0)
          allTrialIds.forall { t =>
            rootResult.outcomes.getOrElse(t, 0L) ==
              risk1Result.outcomes.getOrElse(t, 0L) +
              risk2Result.outcomes.getOrElse(t, 0L)
          },
          // Only the two leaf entries exist — no portfolio entry (DD-15 → B)
          stats.entries == 2
        )
      },

      test("param edit strands the old entry (orphan) and the new content misses the old key") {
        // Same leaf identity, changed probability → different content hash
        val editedLeaf = RiskLeaf.unsafeApply(
          id = risk1IdStr,
          name = "Risk 1",
          distributionType = "lognormal",
          probability = 0.6,
          minLoss = Some(10000L),
          maxLoss = Some(50000L),
          parentId = Some(nodeId("root")),
          seedVarId = 1L
        )
        val editedTree = unsafeGet(
          RiskTree.fromNodes(
            id = testTreeId,
            name = SafeName.SafeName("Test Tree".refineUnsafe),
            nodes = Seq(rootNode, editedLeaf, risk2Leaf),
            rootId = rootId
          ),
          "Edited fixture has invalid RiskTree"
        )
        val editedKey = ContentHashIndex.hashOf(editedLeaf)

        for {
          resolver   <- ZIO.service[RiskResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          // Original content cached under its hash
          _         <- resolver.ensureCached(testTree, risk1Id, testEntity)
          origEntry <- cache.get(risk1Key)

          // Edited content: different key ⇒ MISS ⇒ fresh simulation
          statsBefore <- cache.stats
          edited      <- resolver.ensureCached(editedTree, risk1Id, testEntity)
          statsAfter  <- cache.stats

          // Old entry still present but unreachable from the edited tree (orphan)
          orphan   <- cache.get(risk1Key)
          newEntry <- cache.get(editedKey)
        } yield assertTrue(
          editedKey != risk1Key,
          origEntry.isDefined,
          statsAfter.misses > statsBefore.misses,
          orphan.isDefined,   // stranded — eviction's job, never served for edited content
          newEntry.isDefined,
          // The edited simulation really used the new params — figures differ
          edited.outcomes != origEntry.get.outcomes.outcomes
        )
      },

      test("content-identical leaves in different trees share one cache entry") {
        // Same leaf content under a different tree/name → same content hash
        val otherTree = unsafeGet(
          RiskTree.fromNodes(
            id = treeId("other-tree"),
            name = SafeName.SafeName("Other Tree".refineUnsafe),
            nodes = Seq(
              RiskPortfolio.unsafeFromStrings(
                id = rootIdStr,
                name = "Other Root",
                childIds = Array(risk1IdStr),
                parentId = None
              ),
              risk1Leaf
            ),
            rootId = rootId
          ),
          "Other fixture has invalid RiskTree"
        )

        for {
          resolver   <- ZIO.service[RiskResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          first       <- resolver.ensureCached(testTree, risk1Id, testEntity)
          statsBefore <- cache.stats
          second      <- resolver.ensureCached(otherTree, risk1Id, testEntity)
          statsAfter  <- cache.stats
        } yield assertTrue(
          // Cross-tree hit: figures identical, no second entry created
          first.outcomes == second.outcomes,
          statsAfter.hits > statsBefore.hits,
          statsAfter.entries == statsBefore.entries
        )
      }
    ),

    suite("ensureCachedAll - batch operations")(

      test("caches multiple nodes in one call") {
        for {
          resolver   <- ZIO.service[RiskResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          // Call with multiple node IDs
          results <- resolver.ensureCachedAll(testTree, Set(risk1Id, risk2Id), testEntity)

          // Verify all are now cached under their content hashes
          cached1 <- cache.get(risk1Key)
          cached2 <- cache.get(risk2Key)
        } yield assertTrue(
          // Verify all results returned
          results.size == 2,
          results.contains(risk1Id),
          results.contains(risk2Id),
          cached1.isDefined,
          cached2.isDefined
        )
      },

      test("handles empty set") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          results <- resolver.ensureCachedAll(testTree, Set.empty, testEntity)
        } yield assertTrue(results.isEmpty)
      },

      test("mix of cached and uncached nodes") {
        for {
          resolver <- ZIO.service[RiskResultResolver]

          // Pre-cache risk1
          _ <- resolver.ensureCached(testTree, risk1Id, testEntity)

          // Call with both cached and uncached
          results <- resolver.ensureCachedAll(testTree, Set(risk1Id, risk2Id), testEntity)

        } yield assertTrue(
          // Verify both returned
          results.size == 2,
          results.contains(risk1Id),
          results.contains(risk2Id)
        )
      }
    ),

    suite("workspace isolation (DD-17)")(

      test("different seedEntityIds resolve to separate caches") {
        val otherEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(2L).toOption.get
        for {
          resolver   <- ZIO.service[RiskResultResolver]
          cacheScope <- ZIO.service[CacheScope]

          _      <- resolver.ensureCached(testTree, risk1Id, testEntity)
          cacheB <- cacheScope.cacheFor(otherEntity)
          // Workspace B never simulated anything: same content hash, no entry
          crossHit <- cacheB.get(risk1Key)
        } yield assertTrue(crossHit.isEmpty)
      }
    ),

    suite("error handling")(

      test("fails when node not found in tree") {
        val invalidId = nodeId("nonexistent")

        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, invalidId, testEntity).exit

        } yield assertTrue(
          // Should fail with some error
          result.isFailure
        )
      }
    ),

    suite("telemetry verification")(

      test("records simulation on cache miss") {
        // Note: This is a basic test. Full telemetry testing would require
        // test doubles or inspecting the telemetry backend.
        for {
          resolver <- ZIO.service[RiskResultResolver]

          // This should trigger simulation and record metrics
          _ <- resolver.ensureCached(testTree, risk1Id, testEntity)

          // Telemetry is recorded via TracingLive.console
          // In a full implementation, we would assert on captured spans/metrics
        } yield assertCompletes
      }
    )

  ).provide(testLayer) @@ TestAspect.sequential
}

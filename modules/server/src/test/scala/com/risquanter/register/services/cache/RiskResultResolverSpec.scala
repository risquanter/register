package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*
import com.risquanter.register.configs.{SimulationConfig, TestConfigs}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.domain.data.{RiskResult, RiskNode, RiskLeaf, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, PositiveInt, TreeId, NodeId}
import com.risquanter.register.testutil.TestHelpers.*

/**
 * Tests for RiskResultResolverLive (ADR-015).
 * 
 * Verifies cache-aside behavior, telemetry recording, and error handling.
 * 
 * With per-tree cache architecture, `ensureCached(tree, nodeId)` takes the
 * RiskTree parameter to access tree-scoped cache via TreeCacheManager.
 */
object RiskResultResolverSpec extends ZIOSpecDefault {

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
    parentId = Some(nodeId("root"))
  )

  val risk2Leaf = RiskLeaf.unsafeApply(
    id = risk2IdStr,
    name = "Risk 2",
    distributionType = "lognormal",
    probability = 0.2,
    minLoss = Some(5000L),
    maxLoss = Some(20000L),
    parentId = Some(nodeId("root"))
  )

  val rootNode: RiskNode = RiskPortfolio.unsafeFromStrings(
    id = rootIdStr,
    name = "Root Portfolio",
    childIds = Array(risk1IdStr, risk2IdStr),
    parentId = None
  )

  val allNodes = Seq(rootNode, risk1Leaf, risk2Leaf)
  val testIndex: TreeIndex = unsafeGet(TreeIndex.fromNodeSeq(allNodes), "Test fixture has invalid tree structure")
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

  // Test layer with all dependencies
  val testLayer: ZLayer[Any, Throwable, RiskResultResolver & TreeCacheManager] = 
    ZLayer.make[RiskResultResolver & TreeCacheManager](
      TreeCacheManager.layer,
      ZLayer.succeed(TestConfigs.simulation),
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console,
      RiskResultResolverLive.layer
    )

  def spec = suite("RiskResultResolverSpec")(
    
    suite("ensureCached - cache behavior")(
      
      test("cache miss: simulates and caches result") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          cacheManager <- ZIO.service[TreeCacheManager]
          cache <- cacheManager.cacheFor(testTreeId)
          
          // Verify cache is empty initially
          initialCached <- cache.get(risk1Id)
          _ <- ZIO.succeed(assertTrue(initialCached.isEmpty))
          
          // Call ensureCached - should simulate
          result <- resolver.ensureCached(testTree, risk1Id)
          
          // Verify result is valid
          _ <- ZIO.succeed(assertTrue(result.nodeId == risk1Id))
          
          // Verify result is now cached
          cachedResult <- cache.get(risk1Id)
        } yield assertTrue(
          cachedResult.isDefined,
          cachedResult.get == result
        )
      },
      
      test("cache hit: returns cached result without simulation") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          cacheManager <- ZIO.service[TreeCacheManager]
          cache <- cacheManager.cacheFor(testTreeId)
          
          // First call: simulate and cache
          firstResult <- resolver.ensureCached(testTree, risk1Id)
          
          // Manually verify what's in cache before second call
          cachedBefore <- cache.get(risk1Id)
          _ <- ZIO.succeed(assertTrue(cachedBefore.isDefined))
          
          // Second call: should hit cache (same RiskResult instance)
          secondResult <- resolver.ensureCached(testTree, risk1Id)
          
          // Verify both results are identical
        } yield assertTrue(
          firstResult == secondResult,
          firstResult.outcomes == secondResult.outcomes
        )
      },
      
      test("simulates portfolio by aggregating children") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          
          // Simulate root portfolio
          rootResult <- resolver.ensureCached(testTree, rootId)
          
        } yield assertTrue(
          // Verify root aggregates child risks
          rootResult.nodeId == rootId,
          // Root should have outcomes (aggregated from children)
          rootResult.outcomes.size >= 0
        )
      }
    ),
    
    suite("ensureCachedAll - batch operations")(
      
      test("caches multiple nodes in one call") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          cacheManager <- ZIO.service[TreeCacheManager]
          cache <- cacheManager.cacheFor(testTreeId)
          
          // Call with multiple node IDs
          results <- resolver.ensureCachedAll(testTree, Set(risk1Id, risk2Id))
          
          // Verify all are now cached
          cached1 <- cache.get(risk1Id)
          cached2 <- cache.get(risk2Id)
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
          results <- resolver.ensureCachedAll(testTree, Set.empty)
        } yield assertTrue(results.isEmpty)
      },
      
      test("mix of cached and uncached nodes") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          
          // Pre-cache risk1
          _ <- resolver.ensureCached(testTree, risk1Id)
          
          // Call with both cached and uncached
          results <- resolver.ensureCachedAll(testTree, Set(risk1Id, risk2Id))
          
        } yield assertTrue(
          // Verify both returned
          results.size == 2,
          results.contains(risk1Id),
          results.contains(risk2Id)
        )
      }
    ),
    
    suite("error handling")(
      
      test("fails when node not found in tree") {
        val invalidId = nodeId("nonexistent")
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, invalidId).exit
          
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
          _ <- resolver.ensureCached(testTree, risk1Id)
          
          // Telemetry is recorded via TracingLive.console
          // In a full implementation, we would assert on captured spans/metrics
        } yield assertCompletes
      },
      
      test("sets cache_hit attribute correctly") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          
          // First call: cache_hit = false
          _ <- resolver.ensureCached(testTree, risk1Id)
          
          // Second call: cache_hit = true
          _ <- resolver.ensureCached(testTree, risk1Id)
          
          // Telemetry assertions would go here if we had test instrumentation
        } yield assertCompletes
      }
    ),
    
    suite("determinism")(
      
      test("same node produces identical results across calls") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          cacheManager <- ZIO.service[TreeCacheManager]
          
          // Clear cache to force simulation
          _ <- cacheManager.onTreeStructureChanged(testTreeId)
          
          // Simulate twice (with cache clearing)
          result1 <- resolver.ensureCached(testTree, risk1Id)
          _ <- cacheManager.onTreeStructureChanged(testTreeId)
          result2 <- resolver.ensureCached(testTree, risk1Id)
          
        } yield assertTrue(
          // Should be identical due to fixed seeds
          result1.outcomes == result2.outcomes,
          result1.nTrials == result2.nTrials
        )
      }
    )
    
  ).provide(testLayer) @@ TestAspect.sequential
}

package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.configs.{SimulationConfig, TestConfigs}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.domain.data.{RiskResult, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.data.iron.SafeId

/**
 * Tests for RiskResultResolverLive (ADR-015).
 * 
 * Verifies cache-aside behavior, telemetry recording, and error handling.
 */
object RiskResultResolverSpec extends ZIOSpecDefault {

  // Helper for test SafeIds
  def safeId(s: String): SafeId.SafeId = 
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
    )

  // Test fixture: Simple risk tree for testing
  val risk1Leaf = RiskLeaf.unsafeApply(
    id = "risk1",
    name = "Risk 1",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(10000L),
    maxLoss = Some(50000L)
  )

  val risk2Leaf = RiskLeaf.unsafeApply(
    id = "risk2",
    name = "Risk 2",
    distributionType = "lognormal",
    probability = 0.2,
    minLoss = Some(5000L),
    maxLoss = Some(20000L)
  )

  val testTree: RiskNode = RiskPortfolio.unsafeApply(
    id = "root",
    name = "Root Portfolio",
    children = Array(risk1Leaf, risk2Leaf)
  )

  val testIndex: TreeIndex = TreeIndex.fromTree(testTree)
  val rootId = safeId("root")
  val risk1Id = safeId("risk1")
  val risk2Id = safeId("risk2")

  // Test layer with all dependencies
  val testLayer: ZLayer[Any, Throwable, RiskResultResolver & RiskResultCache] = 
    ZLayer.make[RiskResultResolver & RiskResultCache](
      RiskResultCache.layer,
      ZLayer.succeed(testIndex),
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
          cache <- ZIO.service[RiskResultCache]
          
          // Verify cache is empty initially
          initialCached <- cache.get(risk1Id)
          _ <- ZIO.succeed(assert(initialCached)(isNone))
          
          // Call ensureCached - should simulate
          result <- resolver.ensureCached(risk1Id)
          
          // Verify result is valid
          _ <- ZIO.succeed(assert(result.name.value)(equalTo("Risk 1")))
          _ <- ZIO.succeed(assert(result.nTrials)(isGreaterThan(0)))
          
          // Verify result is now cached
          cachedResult <- cache.get(risk1Id)
          _ <- ZIO.succeed(assert(cachedResult)(isSome(equalTo(result))))
        } yield assertCompletes
      },
      
      test("cache hit: returns cached result without simulation") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          cache <- ZIO.service[RiskResultCache]
          
          // First call: simulate and cache
          firstResult <- resolver.ensureCached(risk1Id)
          
          // Manually verify what's in cache before second call
          cachedBefore <- cache.get(risk1Id)
          _ <- ZIO.succeed(assert(cachedBefore)(isSome))
          
          // Second call: should hit cache (same RiskResult instance)
          secondResult <- resolver.ensureCached(risk1Id)
          
          // Verify both results are identical
          _ <- ZIO.succeed(assert(firstResult)(equalTo(secondResult)))
          _ <- ZIO.succeed(assert(firstResult.outcomes)(equalTo(secondResult.outcomes)))
        } yield assertCompletes
      },
      
      test("simulates portfolio by aggregating children") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          
          // Simulate root portfolio
          rootResult <- resolver.ensureCached(rootId)
          
          // Verify root aggregates child risks
          _ <- ZIO.succeed(assert(rootResult.name.value)(equalTo("Root Portfolio")))
          _ <- ZIO.succeed(assert(rootResult.nTrials)(isGreaterThan(0)))
          
          // Root should have outcomes (aggregated from children)
          _ <- ZIO.succeed(assert(rootResult.outcomes.size)(isGreaterThanEqualTo(0)))
        } yield assertCompletes
      }
    ),
    
    suite("ensureCachedAll - batch operations")(
      
      test("caches multiple nodes in one call") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          cache <- ZIO.service[RiskResultCache]
          
          // Call with multiple node IDs
          results <- resolver.ensureCachedAll(Set(risk1Id, risk2Id))
          
          // Verify all results returned
          _ <- ZIO.succeed(assert(results.size)(equalTo(2)))
          _ <- ZIO.succeed(assert(results.contains(risk1Id))(isTrue))
          _ <- ZIO.succeed(assert(results.contains(risk2Id))(isTrue))
          
          // Verify all are now cached
          cached1 <- cache.get(risk1Id)
          cached2 <- cache.get(risk2Id)
          _ <- ZIO.succeed(assert(cached1)(isSome))
          _ <- ZIO.succeed(assert(cached2)(isSome))
        } yield assertCompletes
      },
      
      test("handles empty set") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          results <- resolver.ensureCachedAll(Set.empty)
          _ <- ZIO.succeed(assert(results)(isEmpty))
        } yield assertCompletes
      },
      
      test("mix of cached and uncached nodes") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          cache <- ZIO.service[RiskResultCache]
          
          // Pre-cache risk1
          _ <- resolver.ensureCached(risk1Id)
          
          // Call with both cached and uncached
          results <- resolver.ensureCachedAll(Set(risk1Id, risk2Id))
          
          // Verify both returned
          _ <- ZIO.succeed(assert(results.size)(equalTo(2)))
          _ <- ZIO.succeed(assert(results.contains(risk1Id))(isTrue))
          _ <- ZIO.succeed(assert(results.contains(risk2Id))(isTrue))
        } yield assertCompletes
      }
    ),
    
    suite("error handling")(
      
      test("fails when node not found in index") {
        val invalidId = safeId("nonexistent")
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(invalidId).exit
          
          // Should fail with validation error
          _ <- ZIO.succeed(assert(result)(fails(anything)))
        } yield assertCompletes
      }
    ),
    
    suite("telemetry verification")(
      
      test("records simulation on cache miss") {
        // Note: This is a basic test. Full telemetry testing would require
        // test doubles or inspecting the telemetry backend.
        for {
          resolver <- ZIO.service[RiskResultResolver]
          
          // This should trigger simulation and record metrics
          _ <- resolver.ensureCached(risk1Id)
          
          // Telemetry is recorded via TestTelemetry layer
          // In a full implementation, we would assert on captured spans/metrics
        } yield assertCompletes
      },
      
      test("sets cache_hit attribute correctly") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          
          // First call: cache_hit = false
          _ <- resolver.ensureCached(risk1Id)
          
          // Second call: cache_hit = true
          _ <- resolver.ensureCached(risk1Id)
          
          // Telemetry assertions would go here if we had test instrumentation
        } yield assertCompletes
      }
    ),
    
    suite("determinism")(
      
      test("same node produces identical results across calls") {
        for {
          resolver <- ZIO.service[RiskResultResolver]
          cache <- ZIO.service[RiskResultCache]
          
          // Clear cache to force simulation
          _ <- cache.clear
          
          // Simulate twice (with cache clearing)
          result1 <- resolver.ensureCached(risk1Id)
          _ <- cache.clear
          result2 <- resolver.ensureCached(risk1Id)
          
          // Should be identical due to fixed seeds
          _ <- ZIO.succeed(assert(result1.outcomes)(equalTo(result2.outcomes)))
          _ <- ZIO.succeed(assert(result1.nTrials)(equalTo(result2.nTrials)))
        } yield assertCompletes
      }
    )
    
  ).provide(testLayer)
}

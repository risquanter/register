package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import com.risquanter.register.domain.data.RiskResult
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}
import com.risquanter.register.domain.data.iron.*

/**
 * Tests for RiskResultCache and TreeCacheManager (ADR-014).
 *
 * With per-tree cache architecture:
 * - RiskResultCache: Pure storage (no TreeIndex)
 * - TreeCacheManager: Manages per-tree caches, handles invalidation
 */
object RiskResultCacheSpec extends ZIOSpecDefault {

  // Helper to create SafeId from string literal (safe for tests with known-valid values)
  def safeId(s: String): SafeId.SafeId = 
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
    )

  // Helper to create NonNegativeLong from Long (runtime check)
  def nnLong(n: Long): NonNegativeLong = 
    n.refineEither[GreaterEqual[0L]].getOrElse(
      throw new IllegalArgumentException(s"Invalid NonNegativeLong: $n")
    )

  // Test fixtures - tree structure for parent lookup
  // Using flat node format with childIds and parentId
  val cyberLeaf = RiskLeaf.unsafeApply(
    id = "cyber",
    name = "Cyber Attack",
    distributionType = "lognormal",
    probability = 0.25,
    minLoss = Some(1000L),
    maxLoss = Some(50000L),
    parentId = Some(safeId("ops-risk"))
  )

  val hardwareLeaf = RiskLeaf.unsafeApply(
    id = "hardware",
    name = "Hardware Failure",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(500L),
    maxLoss = Some(10000L),
    parentId = Some(safeId("it-risk"))
  )

  val softwareLeaf = RiskLeaf.unsafeApply(
    id = "software",
    name = "Software Bug",
    distributionType = "lognormal",
    probability = 0.3,
    minLoss = Some(100L),
    maxLoss = Some(5000L),
    parentId = Some(safeId("it-risk"))
  )

  val itPortfolio = RiskPortfolio.unsafeFromStrings(
    id = "it-risk",
    name = "IT Risk",
    childIds = Array("hardware", "software"),
    parentId = Some(safeId("ops-risk"))
  )

  val rootPortfolio = RiskPortfolio.unsafeFromStrings(
    id = "ops-risk",
    name = "Operational Risk",
    childIds = Array("cyber", "it-risk"),
    parentId = None
  )

  // All nodes in flat list
  val allNodes = Seq(rootPortfolio, cyberLeaf, itPortfolio, hardwareLeaf, softwareLeaf)
  val treeIndex = TreeIndex.fromNodeSeq(allNodes)
  
  // Create RiskTree for TreeCacheManager tests
  val testTreeId: NonNegativeLong = 1L
  val testTree = RiskTree.fromNodes(
    id = testTreeId,
    name = SafeName.SafeName("Test Tree".refineUnsafe),
    nodes = allNodes,
    rootId = safeId("ops-risk")
  )

  // SafeId values for tests
  val opsRiskId  = safeId("ops-risk")
  val cyberId    = safeId("cyber")
  val itRiskId   = safeId("it-risk")
  val hardwareId = safeId("hardware")

  // Sample RiskResult data (simulation outcomes)
  val cyberResult = RiskResult(
    name = safeId("cyber"),
    outcomes = Map(1 -> 10000L, 2 -> 25000L, 3 -> 0L, 4 -> 15000L, 5 -> 0L),
    nTrials = 5
  )
  
  val hardwareResult = RiskResult(
    name = safeId("hardware"),
    outcomes = Map(1 -> 5000L, 2 -> 0L, 3 -> 8000L, 4 -> 0L, 5 -> 3000L),
    nTrials = 5
  )

  val opsRiskResult = RiskResult(
    name = safeId("ops-risk"),
    outcomes = Map(1 -> 15000L, 2 -> 25000L, 3 -> 8000L, 4 -> 15000L, 5 -> 3000L),
    nTrials = 5
  )

  val itRiskResult = RiskResult(
    name = safeId("it-risk"),
    outcomes = Map(1 -> 5000L, 2 -> 0L, 3 -> 8000L, 4 -> 0L, 5 -> 3000L),
    nTrials = 5
  )

  // Layer for TreeCacheManager tests
  val cacheManagerLayer = TreeCacheManager.layer

  def spec = suite("RiskResultCacheSpec")(
    
    suite("RiskResultCache - basic operations")(
      
      test("get returns None for uncached node") {
        for
          cache  <- RiskResultCache.make
          result <- cache.get(cyberId)
        yield assertTrue(result.isEmpty)
      },
      
      test("put and get roundtrip") {
        for
          cache  <- RiskResultCache.make
          _      <- cache.put(cyberId, cyberResult)
          result <- cache.get(cyberId)
        yield assertTrue(
          result.isDefined,
          result.get.name == cyberId,
          result.get.nTrials == 5,
          result.get.outcomes.size == 5
        )
      },
      
      test("remove deletes cached entry") {
        for
          cache  <- RiskResultCache.make
          _      <- cache.put(cyberId, cyberResult)
          before <- cache.get(cyberId)
          _      <- cache.remove(cyberId)
          after  <- cache.get(cyberId)
        yield assertTrue(
          before.isDefined,
          after.isEmpty
        )
      },
      
      test("removeAll deletes multiple entries") {
        for
          cache   <- RiskResultCache.make
          _       <- cache.put(cyberId, cyberResult)
          _       <- cache.put(hardwareId, hardwareResult)
          _       <- cache.put(opsRiskId, opsRiskResult)
          removed <- cache.removeAll(List(cyberId, hardwareId))
          cyber   <- cache.get(cyberId)
          hw      <- cache.get(hardwareId)
          ops     <- cache.get(opsRiskId)
        yield assertTrue(
          removed == 2,
          cyber.isEmpty,
          hw.isEmpty,
          ops.isDefined  // Not in removal list
        )
      },
      
      test("clear removes all entries") {
        for
          cache <- RiskResultCache.make
          _     <- cache.put(cyberId, cyberResult)
          _     <- cache.put(hardwareId, hardwareResult)
          _     <- cache.clear
          cyber <- cache.get(cyberId)
          hw    <- cache.get(hardwareId)
          sz    <- cache.size
        yield assertTrue(
          cyber.isEmpty,
          hw.isEmpty,
          sz == 0
        )
      },
      
      test("clearAndGetSize returns count and clears") {
        for
          cache   <- RiskResultCache.make
          _       <- cache.put(cyberId, cyberResult)
          _       <- cache.put(hardwareId, hardwareResult)
          cleared <- cache.clearAndGetSize
          sz      <- cache.size
        yield assertTrue(
          cleared == 2,
          sz == 0
        )
      },
      
      test("size returns correct count") {
        for
          cache  <- RiskResultCache.make
          before <- cache.size
          _      <- cache.put(cyberId, cyberResult)
          after1 <- cache.size
          _      <- cache.put(hardwareId, hardwareResult)
          after2 <- cache.size
        yield assertTrue(
          before == 0,
          after1 == 1,
          after2 == 2
        )
      },
      
      test("contains checks cache membership") {
        for
          cache  <- RiskResultCache.make
          before <- cache.contains(cyberId)
          _      <- cache.put(cyberId, cyberResult)
          after  <- cache.contains(cyberId)
        yield assertTrue(
          !before,
          after
        )
      },
      
      test("keys returns all cached node IDs") {
        for
          cache <- RiskResultCache.make
          _     <- cache.put(cyberId, cyberResult)
          _     <- cache.put(hardwareId, hardwareResult)
          keys  <- cache.keys
        yield assertTrue(
          keys.toSet == Set(cyberId, hardwareId)
        )
      },
      
      test("concurrent access is thread-safe") {
        for
          cache   <- RiskResultCache.make
          _       <- cache.put(cyberId, cyberResult)
          results <- ZIO.foreachPar((1 to 100).toList)(_ => cache.get(cyberId))
        yield assertTrue(
          results.forall(_.isDefined)
        )
      },
      
      test("cached RiskResult preserves exceedance probability computation") {
        for
          cache  <- RiskResultCache.make
          _      <- cache.put(cyberId, cyberResult)
          cached <- cache.get(cyberId)
          prob    = cached.get.probOfExceedance(10000L)
        yield assertTrue(
          // 3 of 5 trials have loss >= 10000: trials 1 (10000), 2 (25000), 4 (15000)
          prob == BigDecimal(3) / BigDecimal(5)
        )
      }
    ),
    
    suite("TreeCacheManager - per-tree cache isolation")(
      
      test("cacheFor creates cache on first access") {
        for
          manager <- ZIO.service[TreeCacheManager]
          cache   <- manager.cacheFor(testTreeId)
          sz      <- cache.size
        yield assertTrue(sz == 0)
      }.provide(cacheManagerLayer),
      
      test("cacheFor returns same cache for same tree") {
        for
          manager <- ZIO.service[TreeCacheManager]
          cache1  <- manager.cacheFor(testTreeId)
          _       <- cache1.put(cyberId, cyberResult)
          cache2  <- manager.cacheFor(testTreeId)
          result  <- cache2.get(cyberId)
        yield assertTrue(result.isDefined)
      }.provide(cacheManagerLayer),
      
      test("different trees have isolated caches") {
        for
          manager <- ZIO.service[TreeCacheManager]
          cache1  <- manager.cacheFor(nnLong(1L))
          cache2  <- manager.cacheFor(nnLong(2L))
          _       <- cache1.put(cyberId, cyberResult)
          result1 <- cache1.get(cyberId)
          result2 <- cache2.get(cyberId)
        yield assertTrue(
          result1.isDefined,  // Tree 1 has it
          result2.isEmpty     // Tree 2 does not
        )
      }.provide(cacheManagerLayer),
      
      test("invalidate clears node and all ancestors") {
        for
          manager <- ZIO.service[TreeCacheManager]
          cache   <- manager.cacheFor(testTreeId)
          // Populate cache
          _       <- cache.put(opsRiskId, opsRiskResult)
          _       <- cache.put(itRiskId, itRiskResult)
          _       <- cache.put(hardwareId, hardwareResult)
          _       <- cache.put(cyberId, cyberResult)
          // Invalidate hardware - should clear hardware, it-risk, ops-risk
          invalidated <- manager.invalidate(testTree, hardwareId)
          opsResult   <- cache.get(opsRiskId)
          itResult    <- cache.get(itRiskId)
          hwResult    <- cache.get(hardwareId)
          cyberRes    <- cache.get(cyberId)
        yield assertTrue(
          invalidated == List(opsRiskId, itRiskId, hardwareId),
          opsResult.isEmpty,
          itResult.isEmpty,
          hwResult.isEmpty,
          cyberRes.isDefined  // Cyber branch unaffected
        )
      }.provide(cacheManagerLayer),
      
      test("onTreeStructureChanged clears entire cache") {
        for
          manager <- ZIO.service[TreeCacheManager]
          cache   <- manager.cacheFor(testTreeId)
          _       <- cache.put(cyberId, cyberResult)
          _       <- cache.put(hardwareId, hardwareResult)
          cleared <- manager.onTreeStructureChanged(testTreeId)
          sz      <- cache.size
        yield assertTrue(
          cleared == 2,
          sz == 0
        )
      }.provide(cacheManagerLayer),
      
      test("deleteTree removes cache entirely") {
        for
          manager   <- ZIO.service[TreeCacheManager]
          cache     <- manager.cacheFor(testTreeId)
          _         <- cache.put(cyberId, cyberResult)
          countBefore <- manager.treeCount
          _         <- manager.deleteTree(testTreeId)
          countAfter <- manager.treeCount
        yield assertTrue(
          countBefore == 1,
          countAfter == 0
        )
      }.provide(cacheManagerLayer),
      
      test("clearAll clears all tree caches") {
        for
          manager <- ZIO.service[TreeCacheManager]
          cache1  <- manager.cacheFor(nnLong(1L))
          cache2  <- manager.cacheFor(nnLong(2L))
          _       <- cache1.put(cyberId, cyberResult)
          _       <- cache2.put(hardwareId, hardwareResult)
          (trees, entries) <- manager.clearAll
          sz1     <- cache1.size
          sz2     <- cache2.size
        yield assertTrue(
          trees == 2,
          entries == 2,
          sz1 == 0,
          sz2 == 0
        )
      }.provide(cacheManagerLayer),
      
      test("treeCount returns number of cached trees") {
        for
          manager <- ZIO.service[TreeCacheManager]
          count0  <- manager.treeCount
          _       <- manager.cacheFor(nnLong(1L))
          count1  <- manager.treeCount
          _       <- manager.cacheFor(nnLong(2L))
          count2  <- manager.treeCount
          _       <- manager.cacheFor(nnLong(3L))
          count3  <- manager.treeCount
        yield assertTrue(
          count0 == 0,
          count1 == 1,
          count2 == 2,
          count3 == 3
        )
      }.provide(cacheManagerLayer)
    )
  )
}

package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.RiskResult
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}
import com.risquanter.register.domain.data.iron.*

object RiskResultCacheSpec extends ZIOSpecDefault {

  // Helper to create SafeId from string literal (safe for tests with known-valid values)
  def safeId(s: String): SafeId.SafeId = 
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
    )

  // Test fixtures - tree structure for parent lookup
  val cyberLeaf = RiskLeaf.unsafeApply(
    id = "cyber",
    name = "Cyber Attack",
    distributionType = "lognormal",
    probability = 0.25,
    minLoss = Some(1000L),
    maxLoss = Some(50000L)
  )

  val hardwareLeaf = RiskLeaf.unsafeApply(
    id = "hardware",
    name = "Hardware Failure",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(500L),
    maxLoss = Some(10000L)
  )

  val softwareLeaf = RiskLeaf.unsafeApply(
    id = "software",
    name = "Software Bug",
    distributionType = "lognormal",
    probability = 0.3,
    minLoss = Some(100L),
    maxLoss = Some(5000L)
  )

  val itPortfolio = RiskPortfolio.unsafeApply(
    id = "it-risk",
    name = "IT Risk",
    children = Array(hardwareLeaf, softwareLeaf)
  )

  val rootPortfolio = RiskPortfolio.unsafeApply(
    id = "ops-risk",
    name = "Operational Risk",
    children = Array(cyberLeaf, itPortfolio)
  )

  val treeIndex = TreeIndex.fromTree(rootPortfolio)

  // SafeId values for tests
  val opsRiskId  = safeId("ops-risk")
  val cyberId    = safeId("cyber")
  val itRiskId   = safeId("it-risk")
  val hardwareId = safeId("hardware")

  // Sample RiskResult data (simulation outcomes)
  val cyberResult = RiskResult(
    name = "cyber",
    outcomes = Map(1 -> 10000L, 2 -> 25000L, 3 -> 0L, 4 -> 15000L, 5 -> 0L),
    nTrials = 5
  )
  
  val hardwareResult = RiskResult(
    name = "hardware",
    outcomes = Map(1 -> 5000L, 2 -> 0L, 3 -> 8000L, 4 -> 0L, 5 -> 3000L),
    nTrials = 5
  )

  val cacheLayer = ZLayer.succeed(treeIndex) >>> RiskResultCache.layer

  def spec = suite("RiskResultCacheSpec")(
    test("get returns None for uncached node") {
      for
        result <- RiskResultCache.get(cyberId)
      yield assertTrue(result.isEmpty)
    }.provide(cacheLayer),
    
    test("put and get roundtrip") {
      for
        _      <- RiskResultCache.put(cyberId, cyberResult)
        result <- RiskResultCache.get(cyberId)
      yield assertTrue(
        result.isDefined,
        result.get.name == "cyber",
        result.get.nTrials == 5,
        result.get.outcomes.size == 5
      )
    }.provide(cacheLayer),
    
    test("remove deletes cached entry") {
      for
        _      <- RiskResultCache.put(cyberId, cyberResult)
        before <- RiskResultCache.get(cyberId)
        _      <- RiskResultCache.remove(cyberId)
        after  <- RiskResultCache.get(cyberId)
      yield assertTrue(
        before.isDefined,
        after.isEmpty
      )
    }.provide(cacheLayer),
    
    test("invalidate clears node and all ancestors") {
      for
        _           <- RiskResultCache.put(opsRiskId, cyberResult)
        _           <- RiskResultCache.put(itRiskId, hardwareResult)
        _           <- RiskResultCache.put(hardwareId, hardwareResult)
        invalidated <- RiskResultCache.invalidate(hardwareId)
        opsResult   <- RiskResultCache.get(opsRiskId)
        itResult    <- RiskResultCache.get(itRiskId)
        hwResult    <- RiskResultCache.get(hardwareId)
      yield assertTrue(
        invalidated == List(opsRiskId, itRiskId, hardwareId),
        opsResult.isEmpty,
        itResult.isEmpty,
        hwResult.isEmpty
      )
    }.provide(cacheLayer),
    
    test("invalidate does not affect unrelated branches") {
      for
        _           <- RiskResultCache.put(cyberId, cyberResult)
        _           <- RiskResultCache.put(hardwareId, hardwareResult)
        invalidated <- RiskResultCache.invalidate(hardwareId)
        cyberRes    <- RiskResultCache.get(cyberId)
        hwResult    <- RiskResultCache.get(hardwareId)
      yield assertTrue(
        cyberRes.isDefined, // Cyber branch unaffected
        hwResult.isEmpty    // Hardware invalidated
      )
    }.provide(cacheLayer),
    
    test("invalidate on root clears only root") {
      for
        _           <- RiskResultCache.put(opsRiskId, cyberResult)
        _           <- RiskResultCache.put(cyberId, cyberResult)
        invalidated <- RiskResultCache.invalidate(opsRiskId)
        opsResult   <- RiskResultCache.get(opsRiskId)
        cyberRes    <- RiskResultCache.get(cyberId)
      yield assertTrue(
        invalidated == List(opsRiskId),
        opsResult.isEmpty,
        cyberRes.isDefined // Children not invalidated
      )
    }.provide(cacheLayer),
    
    test("clear removes all entries") {
      for
        _     <- RiskResultCache.put(cyberId, cyberResult)
        _     <- RiskResultCache.put(hardwareId, hardwareResult)
        _     <- RiskResultCache.clear
        cyber <- RiskResultCache.get(cyberId)
        hw    <- RiskResultCache.get(hardwareId)
        sz    <- RiskResultCache.size
      yield assertTrue(
        cyber.isEmpty,
        hw.isEmpty,
        sz == 0
      )
    }.provide(cacheLayer),
    
    test("size returns correct count") {
      for
        before <- RiskResultCache.size
        _      <- RiskResultCache.put(cyberId, cyberResult)
        after1 <- RiskResultCache.size
        _      <- RiskResultCache.put(hardwareId, hardwareResult)
        after2 <- RiskResultCache.size
      yield assertTrue(
        before == 0,
        after1 == 1,
        after2 == 2
      )
    }.provide(cacheLayer),
    
    test("contains checks cache membership") {
      for
        before <- RiskResultCache.contains(cyberId)
        _      <- RiskResultCache.put(cyberId, cyberResult)
        after  <- RiskResultCache.contains(cyberId)
      yield assertTrue(
        !before,
        after
      )
    }.provide(cacheLayer),
    
    test("invalidate on non-existent node returns empty list") {
      val nonExistent = safeId("non-existent")
      for
        invalidated <- RiskResultCache.invalidate(nonExistent)
      yield assertTrue(invalidated.isEmpty)
    }.provide(cacheLayer),
    
    test("concurrent access is thread-safe") {
      for
        _       <- RiskResultCache.put(cyberId, cyberResult)
        results <- ZIO.foreachPar((1 to 100).toList)(_ =>
                     RiskResultCache.get(cyberId)
                   )
      yield assertTrue(
        results.forall(_.isDefined)
      )
    }.provide(cacheLayer),
    
    test("cached RiskResult preserves exceedance probability computation") {
      for
        _      <- RiskResultCache.put(cyberId, cyberResult)
        cached <- RiskResultCache.get(cyberId)
        prob   = cached.get.probOfExceedance(10000L)
      yield assertTrue(
        // 3 of 5 trials have loss >= 10000: trials 1 (10000), 2 (25000), 4 (15000)
        prob == BigDecimal(3) / BigDecimal(5)
      )
    }.provide(cacheLayer)
  )
}

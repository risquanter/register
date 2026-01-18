package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.bundle.{CurveBundle, TickDomain}
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}
import com.risquanter.register.domain.data.iron.*

object CurveBundleCacheSpec extends ZIOSpecDefault {

  // Helper to create SafeId from string literal (safe for tests with known-valid values)
  def safeId(s: String): SafeId.SafeId = 
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
    )

  // Test fixtures
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

  // Sample CurveBundle data
  val domain3 = TickDomain.fromProbabilities(Seq(0.90, 0.50, 0.10))
  
  val cyberBundle = CurveBundle.single(cyberId, domain3, Vector(100000L, 500000L, 2000000L))
  val hardwareBundle = CurveBundle.single(hardwareId, domain3, Vector(50000L, 250000L, 1000000L))

  val cacheLayer = ZLayer.succeed(treeIndex) >>> CurveBundleCache.layer

  def spec = suite("CurveBundleCacheSpec")(
    test("get returns None for uncached node") {
      for
        result <- CurveBundleCache.get(cyberId)
      yield assertTrue(result.isEmpty)
    }.provide(cacheLayer),
    
    test("set and get roundtrip") {
      for
        _      <- CurveBundleCache.set(cyberId, cyberBundle)
        result <- CurveBundleCache.get(cyberId)
      yield assertTrue(
        result.isDefined,
        result.get.contains(cyberId),
        result.get.domain.size == 3
      )
    }.provide(cacheLayer),
    
    test("remove deletes cached entry") {
      for
        _      <- CurveBundleCache.set(cyberId, cyberBundle)
        before <- CurveBundleCache.get(cyberId)
        _      <- CurveBundleCache.remove(cyberId)
        after  <- CurveBundleCache.get(cyberId)
      yield assertTrue(
        before.isDefined,
        after.isEmpty
      )
    }.provide(cacheLayer),
    
    test("invalidate clears node and all ancestors") {
      for
        _           <- CurveBundleCache.set(opsRiskId, cyberBundle)
        _           <- CurveBundleCache.set(itRiskId, hardwareBundle)
        _           <- CurveBundleCache.set(hardwareId, hardwareBundle)
        invalidated <- CurveBundleCache.invalidate(hardwareId)
        opsResult   <- CurveBundleCache.get(opsRiskId)
        itResult    <- CurveBundleCache.get(itRiskId)
        hwResult    <- CurveBundleCache.get(hardwareId)
      yield assertTrue(
        invalidated == List(opsRiskId, itRiskId, hardwareId),
        opsResult.isEmpty,
        itResult.isEmpty,
        hwResult.isEmpty
      )
    }.provide(cacheLayer),
    
    test("invalidate does not affect unrelated branches") {
      for
        _           <- CurveBundleCache.set(cyberId, cyberBundle)
        _           <- CurveBundleCache.set(hardwareId, hardwareBundle)
        invalidated <- CurveBundleCache.invalidate(hardwareId)
        cyberResult <- CurveBundleCache.get(cyberId)
        hwResult    <- CurveBundleCache.get(hardwareId)
      yield assertTrue(
        cyberResult.isDefined, // Cyber branch unaffected
        hwResult.isEmpty       // Hardware invalidated
      )
    }.provide(cacheLayer),
    
    test("invalidate on root clears only root") {
      for
        _           <- CurveBundleCache.set(opsRiskId, cyberBundle)
        _           <- CurveBundleCache.set(cyberId, cyberBundle)
        invalidated <- CurveBundleCache.invalidate(opsRiskId)
        opsResult   <- CurveBundleCache.get(opsRiskId)
        cyberResult <- CurveBundleCache.get(cyberId)
      yield assertTrue(
        invalidated == List(opsRiskId),
        opsResult.isEmpty,
        cyberResult.isDefined // Children not invalidated
      )
    }.provide(cacheLayer),
    
    test("clear removes all entries") {
      for
        _     <- CurveBundleCache.set(cyberId, cyberBundle)
        _     <- CurveBundleCache.set(hardwareId, hardwareBundle)
        _     <- CurveBundleCache.clear
        cyber <- CurveBundleCache.get(cyberId)
        hw    <- CurveBundleCache.get(hardwareId)
        sz    <- CurveBundleCache.size
      yield assertTrue(
        cyber.isEmpty,
        hw.isEmpty,
        sz == 0
      )
    }.provide(cacheLayer),
    
    test("size returns correct count") {
      for
        before <- CurveBundleCache.size
        _      <- CurveBundleCache.set(cyberId, cyberBundle)
        after1 <- CurveBundleCache.size
        _      <- CurveBundleCache.set(hardwareId, hardwareBundle)
        after2 <- CurveBundleCache.size
      yield assertTrue(
        before == 0,
        after1 == 1,
        after2 == 2
      )
    }.provide(cacheLayer),
    
    test("contains checks cache membership") {
      for
        before <- CurveBundleCache.contains(cyberId)
        _      <- CurveBundleCache.set(cyberId, cyberBundle)
        after  <- CurveBundleCache.contains(cyberId)
      yield assertTrue(
        !before,
        after
      )
    }.provide(cacheLayer),
    
    test("invalidate on non-existent node returns empty list") {
      val nonExistent = safeId("non-existent")
      for
        invalidated <- CurveBundleCache.invalidate(nonExistent)
      yield assertTrue(invalidated.isEmpty)
    }.provide(cacheLayer),
    
    test("concurrent access is thread-safe") {
      for
        _       <- CurveBundleCache.set(cyberId, cyberBundle)
        results <- ZIO.foreachPar((1 to 100).toList)(_ =>
                     CurveBundleCache.get(cyberId)
                   )
      yield assertTrue(
        results.forall(_.isDefined)
      )
    }.provide(cacheLayer)
  )
}

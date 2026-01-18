package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.{LECCurveResponse, LECPoint, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}
import com.risquanter.register.domain.data.iron.*

object LECCacheSpec extends ZIOSpecDefault {

  // Helper to create SafeId from string literal (safe for tests with known-valid values)
  // Pattern from RiskPortfolio.unsafeApply
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

  // Sample LEC data
  val cyberLEC = LECCurveResponse(
    id = "cyber",
    name = "Cyber Attack",
    curve = Vector(LECPoint(1000, 0.99), LECPoint(50000, 0.01)),
    quantiles = Map("p50" -> 5000.0, "p90" -> 25000.0)
  )

  val hardwareLEC = LECCurveResponse(
    id = "hardware",
    name = "Hardware Failure",
    curve = Vector(LECPoint(500, 0.99), LECPoint(10000, 0.01)),
    quantiles = Map("p50" -> 2000.0, "p90" -> 7000.0)
  )

  val cacheLayer = ZLayer.succeed(treeIndex) >>> LECCache.layer

  def spec = suite("LECCacheSpec")(
    test("get returns None for uncached node") {
      for
        result <- LECCache.get(cyberId)
      yield assertTrue(result.isEmpty)
    }.provide(cacheLayer),
    test("set and get roundtrip") {
      for
        _      <- LECCache.set(cyberId, cyberLEC)
        result <- LECCache.get(cyberId)
      yield assertTrue(
        result.isDefined,
        result.get.id == "cyber",
        result.get.name == "Cyber Attack"
      )
    }.provide(cacheLayer),
    test("remove deletes cached entry") {
      for
        _      <- LECCache.set(cyberId, cyberLEC)
        before <- LECCache.get(cyberId)
        _      <- LECCache.remove(cyberId)
        after  <- LECCache.get(cyberId)
      yield assertTrue(
        before.isDefined,
        after.isEmpty
      )
    }.provide(cacheLayer),
    test("invalidate clears node and all ancestors") {
      for
        _           <- LECCache.set(opsRiskId, cyberLEC.copy(id = "ops-risk"))
        _           <- LECCache.set(itRiskId, hardwareLEC.copy(id = "it-risk"))
        _           <- LECCache.set(hardwareId, hardwareLEC)
        invalidated <- LECCache.invalidate(hardwareId)
        opsResult   <- LECCache.get(opsRiskId)
        itResult    <- LECCache.get(itRiskId)
        hwResult    <- LECCache.get(hardwareId)
      yield assertTrue(
        invalidated == List(opsRiskId, itRiskId, hardwareId),
        opsResult.isEmpty,
        itResult.isEmpty,
        hwResult.isEmpty
      )
    }.provide(cacheLayer),
    test("invalidate does not affect unrelated branches") {
      for
        _           <- LECCache.set(cyberId, cyberLEC)
        _           <- LECCache.set(hardwareId, hardwareLEC)
        invalidated <- LECCache.invalidate(hardwareId)
        cyberResult <- LECCache.get(cyberId)
        hwResult    <- LECCache.get(hardwareId)
      yield assertTrue(
        cyberResult.isDefined, // Cyber branch unaffected
        hwResult.isEmpty // Hardware invalidated
      )
    }.provide(cacheLayer),
    test("invalidate on root clears only root") {
      for
        _           <- LECCache.set(opsRiskId, cyberLEC.copy(id = "ops-risk"))
        _           <- LECCache.set(cyberId, cyberLEC)
        invalidated <- LECCache.invalidate(opsRiskId)
        opsResult   <- LECCache.get(opsRiskId)
        cyberResult <- LECCache.get(cyberId)
      yield assertTrue(
        invalidated == List(opsRiskId),
        opsResult.isEmpty,
        cyberResult.isDefined // Children not invalidated
      )
    }.provide(cacheLayer),
    test("clear removes all entries") {
      for
        _      <- LECCache.set(cyberId, cyberLEC)
        _      <- LECCache.set(hardwareId, hardwareLEC)
        _      <- LECCache.clear
        cyber  <- LECCache.get(cyberId)
        hw     <- LECCache.get(hardwareId)
        sz     <- LECCache.size
      yield assertTrue(
        cyber.isEmpty,
        hw.isEmpty,
        sz == 0
      )
    }.provide(cacheLayer),
    test("size returns correct count") {
      for
        before <- LECCache.size
        _      <- LECCache.set(cyberId, cyberLEC)
        after1 <- LECCache.size
        _      <- LECCache.set(hardwareId, hardwareLEC)
        after2 <- LECCache.size
      yield assertTrue(
        before == 0,
        after1 == 1,
        after2 == 2
      )
    }.provide(cacheLayer),
    test("contains checks cache membership") {
      for
        before <- LECCache.contains(cyberId)
        _      <- LECCache.set(cyberId, cyberLEC)
        after  <- LECCache.contains(cyberId)
      yield assertTrue(
        !before,
        after
      )
    }.provide(cacheLayer),
    test("invalidate on non-existent node returns empty list") {
      val nonExistent = safeId("non-existent")
      for
        invalidated <- LECCache.invalidate(nonExistent)
      yield assertTrue(invalidated.isEmpty)
    }.provide(cacheLayer),
    test("concurrent access is thread-safe") {
      for
        _       <- LECCache.set(cyberId, cyberLEC)
        results <- ZIO.foreachPar((1 to 100).toList)(_ =>
                     LECCache.get(cyberId)
                   )
      yield assertTrue(
        results.forall(_.isDefined)
      )
    }.provide(cacheLayer)
  )
}

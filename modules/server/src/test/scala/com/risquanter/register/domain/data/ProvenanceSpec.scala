package com.risquanter.register.domain.data

import com.risquanter.register.BuildInfo
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, NonNegativeLong}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.services.cache.{RiskResultResolver, RiskResultResolverLive, TreeCacheManager}
import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import java.time.Instant
import io.github.iltotore.iron.*

/**
 * Tests for Provenance metadata structures.
 * 
 * Verifies:
 * - JSON serialization/deserialization
 * - Provenance capture during simulation via RiskResultResolver
 * - Reproduction from provenance metadata
 */
object ProvenanceSpec extends ZIOSpecDefault {
  
  // Helper to create SafeId from string literal
  private def safeId(s: String): SafeId.SafeId = 
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
    )

  // Test layer with all dependencies for provenance tests
  val testLayer: ZLayer[Any, Throwable, RiskResultResolver & TreeCacheManager] = 
    ZLayer.make[RiskResultResolver & TreeCacheManager](
      TreeCacheManager.layer,
      ZLayer.succeed(TestConfigs.simulation),
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console,
      RiskResultResolverLive.layer
    )
  
  def spec = suite("ProvenanceSpec")(
    
    suite("JSON Serialization")(
      test("NodeProvenance serializes and deserializes correctly") {
        val provenance = NodeProvenance(
          riskId = safeId("cyber-attack"),
          entityId = 42L,
          occurrenceVarId = 1042L,
          lossVarId = 2042L,
          globalSeed3 = 0L,
          globalSeed4 = 0L,
          distributionType = "lognormal",
          distributionParams = LognormalDistributionParams(
            minLoss = 1000000L,
            maxLoss = 50000000L,
            confidenceInterval = 0.90
          ),
          timestamp = Instant.parse("2026-01-04T12:34:56Z"),
          simulationUtilVersion = BuildInfo.simulationUtilVersion
        )
        
        val json = provenance.toJson
        val decoded = json.fromJson[NodeProvenance]
        
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.riskId == safeId("cyber-attack")) &&
        assertTrue(decoded.toOption.get.entityId == 42L) &&
        assertTrue(decoded.toOption.get.distributionType == "lognormal")
      },
      
      test("ExpertDistributionParams serializes correctly") {
        val params = ExpertDistributionParams(
          percentiles = Array(0.05, 0.5, 0.95),
          quantiles = Array(1000.0, 5000.0, 25000.0),
          terms = 3
        )
        
        val json = params.toJson
        val decoded = json.fromJson[ExpertDistributionParams]
        
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.percentiles.length == 3) &&
        assertTrue(decoded.toOption.get.quantiles.length == 3) &&
        assertTrue(decoded.toOption.get.terms == 3)
      },
      
      test("LognormalDistributionParams serializes correctly") {
        val params = LognormalDistributionParams(
          minLoss = 1000000L,
          maxLoss = 50000000L,
          confidenceInterval = 0.90
        )
        
        val json = params.toJson
        val decoded = json.fromJson[LognormalDistributionParams]
        
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.minLoss == 1000000L) &&
        assertTrue(decoded.toOption.get.maxLoss == 50000000L) &&
        assertTrue(decoded.toOption.get.confidenceInterval == 0.90)
      },
      
      test("TreeProvenance serializes with node provenances") {
        val nodeProvenance = NodeProvenance(
          riskId = safeId("risk1"),
          entityId = 123L,
          occurrenceVarId = 1123L,
          lossVarId = 2123L,
          globalSeed3 = 0L,
          globalSeed4 = 0L,
          distributionType = "expert",
          distributionParams = ExpertDistributionParams(
            percentiles = Array(0.1, 0.5, 0.9),
            quantiles = Array(100.0, 500.0, 2000.0),
            terms = 3
          ),
          timestamp = Instant.now(),
          simulationUtilVersion = BuildInfo.simulationUtilVersion
        )
        
        val treeProvenance = TreeProvenance(
          treeId = 1L,
          globalSeeds = (0L, 0L),
          nTrials = 10000,
          parallelism = 4,
          nodeProvenances = Map(safeId("risk1") -> nodeProvenance)
        )
        
        val json = treeProvenance.toJson
        val decoded = json.fromJson[TreeProvenance]
        
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.treeId == 1L) &&
        assertTrue(decoded.toOption.get.nTrials == 10000) &&
        assertTrue(decoded.toOption.get.nodeProvenances.size == 1) &&
        assertTrue(decoded.toOption.get.nodeProvenances.contains(safeId("risk1")))
      },
      
      test("DistributionParams sealed trait handles both subtypes") {
        val expert: DistributionParams = ExpertDistributionParams(
          percentiles = Array(0.5),
          quantiles = Array(1000.0),
          terms = 1
        )
        
        val lognormal: DistributionParams = LognormalDistributionParams(
          minLoss = 1000L,
          maxLoss = 10000L,
          confidenceInterval = 0.90
        )
        
        // Both should serialize without error
        val expertJson = expert.toJsonAST
        val lognormalJson = lognormal.toJsonAST
        
        assertTrue(expertJson.isRight) &&
        assertTrue(lognormalJson.isRight)
      }
    ),
    
    suite("Provenance Capture")(
      test("ensureCached with includeProvenance=true captures metadata") {
        val leaf = RiskLeaf.unsafeApply(
          id = "test-risk",
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L)
        )
        
        val testTree = RiskTree.singleNode(
          id = 1L,
          name = SafeName.SafeName("Test Tree".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, safeId("test-risk"), includeProvenance = true)
        } yield {
          assertTrue(result.provenances.nonEmpty) &&
          assertTrue(result.provenances.exists(_.riskId == safeId("test-risk")))
        }
      },
      
      // Resolver always captures provenance for cache consistency.
      // Filtering (based on includeProvenance flag) happens at the service layer.
      // This ensures cache keys remain simple (nodeId only) and avoids cache fragmentation.
      test("resolver always captures provenance regardless of includeProvenance flag") {
        val leaf = RiskLeaf.unsafeApply(
          id = "test-risk",
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L)
        )
        
        val testTree = RiskTree.singleNode(
          id = 2L,
          name = SafeName.SafeName("Test Tree 2".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          // Even with includeProvenance=false, resolver returns provenance
          // (service layer filters before returning to HTTP clients)
          result <- resolver.ensureCached(testTree, safeId("test-risk"), includeProvenance = false)
        } yield assertTrue(result.provenances.nonEmpty)
      },
      
      test("provenance captures correct entityId from riskId hash") {
        val riskIdStr = "cyber-attack"
        val expectedEntityId = riskIdStr.hashCode.toLong
        
        val leaf = RiskLeaf.unsafeApply(
          id = riskIdStr,
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
        
        val testTree = RiskTree.singleNode(
          id = 3L,
          name = SafeName.SafeName("Test Tree 3".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, safeId(riskIdStr), includeProvenance = true)
        } yield {
          val nodeProv = result.provenances.find(_.riskId == safeId(riskIdStr)).get
          assertTrue(nodeProv.entityId == expectedEntityId) &&
          assertTrue(nodeProv.occurrenceVarId == expectedEntityId.hashCode + 1000L) &&
          assertTrue(nodeProv.lossVarId == expectedEntityId.hashCode + 2000L)
        }
      },
      
      test("provenance captures distribution parameters for lognormal") {
        val leaf = RiskLeaf.unsafeApply(
          id = "lognormal-risk",
          name = "Lognormal Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L)
        )
        
        val testTree = RiskTree.singleNode(
          id = 4L,
          name = SafeName.SafeName("Test Tree 4".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, safeId("lognormal-risk"), includeProvenance = true)
        } yield {
          val nodeProv = result.provenances.find(_.riskId == safeId("lognormal-risk")).get
          assertTrue(nodeProv.distributionType == "lognormal") &&
          assertTrue(nodeProv.distributionParams.isInstanceOf[LognormalDistributionParams]) &&
          assertTrue(
            nodeProv.distributionParams.asInstanceOf[LognormalDistributionParams].minLoss == 1000L
          ) &&
          assertTrue(
            nodeProv.distributionParams.asInstanceOf[LognormalDistributionParams].maxLoss == 10000L
          )
        }
      },
      
      test("provenance aggregates multiple node provenances in portfolio") {
        val risk1 = RiskLeaf.unsafeApply(
          id = "risk1",
          name = "Risk 1",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(5000L),
          parentId = Some(safeId("portfolio"))
        )
        
        val risk2 = RiskLeaf.unsafeApply(
          id = "risk2",
          name = "Risk 2",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(2000L),
          maxLoss = Some(8000L),
          parentId = Some(safeId("portfolio"))
        )
        
        val portfolio = RiskPortfolio.unsafeFromStrings(
          id = "portfolio",
          name = "Test Portfolio",
          childIds = Array("risk1", "risk2"),
          parentId = None
        )
        
        val testTree = RiskTree.fromNodes(
          id = 5L,
          name = SafeName.SafeName("Test Tree 5".refineUnsafe),
          nodes = Seq(portfolio, risk1, risk2),
          rootId = safeId("portfolio")
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          // Simulate portfolio (which aggregates children)
          result <- resolver.ensureCached(testTree, safeId("portfolio"), includeProvenance = true)
        } yield {
          // Portfolio result should contain provenances from both leaves
          assertTrue(result.provenances.size == 2) &&
          assertTrue(result.provenances.exists(_.riskId == safeId("risk1"))) &&
          assertTrue(result.provenances.exists(_.riskId == safeId("risk2")))
        }
      }
    ).provideLayerShared(testLayer),
    
    suite("Reproduction Validation")(
      test("same provenance seeds produce identical results") {
        val leaf = RiskLeaf.unsafeApply(
          id = "deterministic-risk",
          name = "Deterministic Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(5000L),
          maxLoss = Some(20000L)
        )
        
        // Use different tree IDs to avoid cache hits
        val testTree1 = RiskTree.singleNode(
          id = 6L,
          name = SafeName.SafeName("Test Tree 6".refineUnsafe),
          root = leaf
        )
        
        val testTree2 = RiskTree.singleNode(
          id = 7L,
          name = SafeName.SafeName("Test Tree 7".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          // First simulation with provenance
          firstResult <- resolver.ensureCached(testTree1, safeId("deterministic-risk"), includeProvenance = true)
          // Second simulation with same parameters (different tree to avoid cache)
          secondResult <- resolver.ensureCached(testTree2, safeId("deterministic-risk"), includeProvenance = true)
        } yield {
          // Same inputs should produce identical outcomes (HDR determinism)
          assertTrue(firstResult.outcomes == secondResult.outcomes)
        }
      },
      
      test("provenance contains all information for reconstruction") {
        val leaf = RiskLeaf.unsafeApply(
          id = "test-reconstruction",
          name = "Test Reconstruction",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(15000L)
        )
        
        val testTree = RiskTree.singleNode(
          id = 8L,
          name = SafeName.SafeName("Test Tree 8".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, safeId("test-reconstruction"), includeProvenance = true)
        } yield {
          val nodeProv = result.provenances.find(_.riskId == safeId("test-reconstruction")).get
          
          // Verify all essential information is captured
          assertTrue(nodeProv.riskId == safeId("test-reconstruction")) &&
          assertTrue(nodeProv.entityId != 0L) &&
          assertTrue(nodeProv.occurrenceVarId != 0L) &&
          assertTrue(nodeProv.lossVarId != 0L) &&
          assertTrue(nodeProv.globalSeed3 == 0L) &&
          assertTrue(nodeProv.globalSeed4 == 0L) &&
          assertTrue(nodeProv.distributionType == "lognormal") &&
          assertTrue(nodeProv.distributionParams != null) &&
          assertTrue(nodeProv.timestamp != null) &&
          assertTrue(nodeProv.simulationUtilVersion.nonEmpty)
        }
      }
    ).provideLayerShared(testLayer)
  )
}

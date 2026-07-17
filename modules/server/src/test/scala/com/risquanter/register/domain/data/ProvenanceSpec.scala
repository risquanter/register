package com.risquanter.register.domain.data

import com.risquanter.register.BuildInfo
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, NonNegativeLong, SeedEntityId}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.services.cache.{RiskResultResolver, RiskResultResolverLive, TreeCacheManager}
import com.risquanter.register.simulation.SeedDerivation
import com.risquanter.register.testutil.TestHelpers.{safeId, idStr, nodeId, treeId}
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

  private val testEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

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
          riskId = nodeId("cyber-attack"),
          entityId = 42L,
          occurrenceVarId = 1042L,
          lossVarId = 2042L,
          globalSeed3 = 0L,
          globalSeed4 = 0L,
          distributionType = "lognormal",
          distributionParams = LognormalDistributionParams(
            minLoss = 1000000L.refineUnsafe,
            maxLoss = 50000000L.refineUnsafe,
            confidenceInterval = 0.90
          ),
          timestamp = Instant.parse("2026-01-04T12:34:56Z"),
          simulationUtilVersion = BuildInfo.simulationUtilVersion
        )
        
        val json = provenance.toJson
        val decoded = json.fromJson[NodeProvenance]
        
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.riskId == nodeId("cyber-attack")) &&
        assertTrue(decoded.toOption.get.entityId == 42L) &&
        assertTrue(decoded.toOption.get.distributionType == "lognormal")
      },
      
      test("ExpertDistributionParams serializes correctly") {
        val params = ExpertDistributionParams(
          percentiles = Array(0.05, 0.5, 0.95),
          quantiles = Array(1000.0, 5000.0, 25000.0),
          terms = 3.refineUnsafe
        )
        
        val json = params.toJson
        val decoded = json.fromJson[ExpertDistributionParams]
        
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.percentiles.length == 3) &&
        assertTrue(decoded.toOption.get.quantiles.length == 3) &&
        assertTrue(decoded.toOption.get.terms == 3)
      },

      test("ExpertDistributionParams rejects terms = 0") {
        val json = """{"percentiles":[0.05,0.5,0.95],"quantiles":[1000.0,5000.0,25000.0],"terms":0}"""
        assertTrue(json.fromJson[ExpertDistributionParams].isLeft)
      },

      test("ExpertDistributionParams rejects terms < 0") {
        val json = """{"percentiles":[0.05,0.5,0.95],"quantiles":[1000.0,5000.0,25000.0],"terms":-1}"""
        assertTrue(json.fromJson[ExpertDistributionParams].isLeft)
      },
      
      test("LognormalDistributionParams serializes correctly") {
        val params = LognormalDistributionParams(
          minLoss = 1000000L.refineUnsafe,
          maxLoss = 50000000L.refineUnsafe,
          confidenceInterval = 0.90
        )
        
        val json = params.toJson
        val decoded = json.fromJson[LognormalDistributionParams]
        
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.minLoss == 1000000L) &&
        assertTrue(decoded.toOption.get.maxLoss == 50000000L) &&
        assertTrue(decoded.toOption.get.confidenceInterval == 0.90)
      },

      test("LognormalDistributionParams rejects negative minLoss") {
        val json = """{"minLoss":-1,"maxLoss":50000000,"confidenceInterval":0.9}"""
        assertTrue(json.fromJson[LognormalDistributionParams].isLeft)
      },

      test("LognormalDistributionParams rejects negative maxLoss") {
        val json = """{"minLoss":1000000,"maxLoss":-1,"confidenceInterval":0.9}"""
        assertTrue(json.fromJson[LognormalDistributionParams].isLeft)
      },
      
      test("DistributionParams sealed trait handles both subtypes") {
        val expert: DistributionParams = ExpertDistributionParams(
          percentiles = Array(0.5),
          quantiles = Array(1000.0),
          terms = 1.refineUnsafe
        )
        
        val lognormal: DistributionParams = LognormalDistributionParams(
          minLoss = 1000L.refineUnsafe,
          maxLoss = 10000L.refineUnsafe,
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
          id = idStr("test-risk"),
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L),
          seedVarId = 1L
        )
        
        val testTree = RiskTree.singleNodeUnsafe(
          id = treeId("tree-1"),
          name = SafeName.SafeName("Test Tree".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, nodeId("test-risk"), testEntity, includeProvenance = true)
        } yield {
          assertTrue(result.provenances.nonEmpty) &&
          assertTrue(result.provenances.exists(_.riskId == nodeId("test-risk")))
        }
      },
      
      // Resolver always captures provenance for cache consistency.
      // Filtering (based on includeProvenance flag) happens at the service layer.
      // This ensures cache keys remain simple (nodeId only) and avoids cache fragmentation.
      test("resolver always captures provenance regardless of includeProvenance flag") {
        val leaf = RiskLeaf.unsafeApply(
          id = idStr("test-risk"),
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L),
          seedVarId = 2L
        )
        
        val testTree = RiskTree.singleNodeUnsafe(
          id = treeId("tree-2"),
          name = SafeName.SafeName("Test Tree 2".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, nodeId("test-risk"), testEntity, includeProvenance = false)
        } yield assertTrue(result.provenances.nonEmpty)
      },
      
      // PLAN-SEED-IDENTITY §11 Layer 1: recorded var-IDs equal the sampler's
      // inputs — the single derivation site makes divergence impossible (§6.2
      // killed bug 2, where provenance recorded different values than the
      // sampler consumed). ULID has no influence on any recorded seed.
      test("provenance records the derived streams: entity from workspace, (2k, 2k+1) from seedVarId") {
        val riskIdLabel = "cyber-attack"
        val riskId = nodeId(riskIdLabel)

        val leaf = RiskLeaf.unsafeApply(
          id = riskId.value,
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L),
          seedVarId = 3L
        )

        val testTree = RiskTree.singleNodeUnsafe(
          id = treeId("tree-3"),
          name = SafeName.SafeName("Test Tree 3".refineUnsafe),
          root = leaf
        )

        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, nodeId(riskIdLabel), testEntity, includeProvenance = true)
        } yield {
          val nodeProv = result.provenances.find(_.riskId == riskId).get
          val expected = SeedDerivation.streams(testEntity, leaf.seedVarId, nodeProv.globalSeed3, nodeProv.globalSeed4)
          assertTrue(nodeProv.entityId == testEntity.value) &&
          assertTrue(nodeProv.occurrenceVarId == 6L) &&   // 2 · 3
          assertTrue(nodeProv.lossVarId == 7L) &&          // 2 · 3 + 1
          assertTrue(nodeProv.entityId == expected.entityId) &&
          assertTrue(nodeProv.occurrenceVarId == expected.occurrenceVarId) &&
          assertTrue(nodeProv.lossVarId == expected.lossVarId)
        }
      },
      
      test("provenance captures distribution parameters for lognormal") {
        val leaf = RiskLeaf.unsafeApply(
          id = idStr("lognormal-risk"),
          name = "Lognormal Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L),
          seedVarId = 4L
        )
        
        val testTree = RiskTree.singleNodeUnsafe(
          id = treeId("tree-4"),
          name = SafeName.SafeName("Test Tree 4".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, nodeId("lognormal-risk"), testEntity, includeProvenance = true)
        } yield {
          val nodeProv = result.provenances.find(_.riskId == nodeId("lognormal-risk")).get
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
          id = idStr("risk1"),
          name = "Risk 1",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(5000L),
          parentId = Some(nodeId("portfolio")),
          seedVarId = 5L
        )
        
        val risk2 = RiskLeaf.unsafeApply(
          id = idStr("risk2"),
          name = "Risk 2",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(2000L),
          maxLoss = Some(8000L),
          parentId = Some(nodeId("portfolio")),
          seedVarId = 6L
        )
        
        val portfolio = RiskPortfolio.unsafeFromStrings(
          id = idStr("portfolio"),
          name = "Test Portfolio",
          childIds = Array(idStr("risk1"), idStr("risk2")),
          parentId = None
        )
        
        val testTree = RiskTree.fromNodesUnsafe(
          id = treeId("tree-5"),
          name = SafeName.SafeName("Test Tree 5".refineUnsafe),
          nodes = Seq(portfolio, risk1, risk2),
          rootId = nodeId("portfolio")
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          // Simulate portfolio (which aggregates children)
          result <- resolver.ensureCached(testTree, nodeId("portfolio"), testEntity, includeProvenance = true)
        } yield {
          // Portfolio result should contain provenances from both leaves,
          // in childIds declaration order (parallel child resolution preserves
          // list order — foreachPar returns results in input order)
          assertTrue(result.provenances.size == 2) &&
          assertTrue(result.provenances.map(_.riskId) == List(nodeId("risk1"), nodeId("risk2")))
        }
      }
    ).provideLayerShared(testLayer),
    
    suite("Reproduction Validation")(
      test("same provenance seeds produce identical results") {
        val leaf = RiskLeaf.unsafeApply(
          id = idStr("deterministic-risk"),
          name = "Deterministic Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(5000L),
          maxLoss = Some(20000L),
          seedVarId = 7L
        )
        
        // Use different tree IDs to avoid cache hits
        val testTree1 = RiskTree.singleNodeUnsafe(
          id = treeId("tree-6"),
          name = SafeName.SafeName("Test Tree 6".refineUnsafe),
          root = leaf
        )
        
        val testTree2 = RiskTree.singleNodeUnsafe(
          id = treeId("tree-7"),
          name = SafeName.SafeName("Test Tree 7".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          // First simulation with provenance
          firstResult <- resolver.ensureCached(testTree1, nodeId("deterministic-risk"), testEntity, includeProvenance = true)
          // Second simulation with same parameters (different tree to avoid cache)
          secondResult <- resolver.ensureCached(testTree2, nodeId("deterministic-risk"), testEntity, includeProvenance = true)
        } yield {
          // Same inputs should produce identical outcomes (HDR determinism)
          assertTrue(firstResult.outcomes == secondResult.outcomes)
        }
      },
      
      test("provenance contains all information for reconstruction") {
        val leaf = RiskLeaf.unsafeApply(
          id = idStr("test-reconstruction"),
          name = "Test Reconstruction",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(15000L),
          seedVarId = 8L
        )
        
        val testTree = RiskTree.singleNodeUnsafe(
          id = treeId("tree-8"),
          name = SafeName.SafeName("Test Tree 8".refineUnsafe),
          root = leaf
        )
        
        for {
          resolver <- ZIO.service[RiskResultResolver]
          result <- resolver.ensureCached(testTree, nodeId("test-reconstruction"), testEntity, includeProvenance = true)
        } yield {
          val nodeProv = result.provenances.find(_.riskId == nodeId("test-reconstruction")).get
          
          // Verify all essential information is captured
          assertTrue(nodeProv.riskId == nodeId("test-reconstruction")) &&
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

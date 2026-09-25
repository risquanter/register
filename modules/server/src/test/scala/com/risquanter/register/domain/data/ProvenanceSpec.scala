package com.risquanter.register.domain.data

import com.risquanter.register.BuildInfo
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, NonNegativeLong, SeedEntityId}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.services.cache.{CachedResultResolver, CachedResultResolverLive, ContentCacheRegistry}
import com.risquanter.register.simulation.{LossDistribution, RiskResult, RiskResultGroup, SeedDerivation}
import com.risquanter.register.testutil.TestHelpers.{safeId, idStr, nodeId, treeId, unsafeGet}
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
 * - Provenance capture during simulation via CachedResultResolver
 * - Reproduction from provenance metadata
 */
object ProvenanceSpec extends ZIOSpecDefault {

  private val testEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

  // Test layer with all dependencies for provenance tests
  val testLayer: ZLayer[Any, Throwable, CachedResultResolver & ContentCacheRegistry] =
    ZLayer.make[CachedResultResolver & ContentCacheRegistry](
      ContentCacheRegistry.layer,
      ZLayer.succeed(TestConfigs.simulation),
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console,
      CachedResultResolverLive.layer
    )

  /** Structural attribution: a leaf's records sit on its RiskResult,
    * whose nodeId is beside them — no riskId inside the record.
    */
  private def leafProvenances(result: LossDistribution): List[NodeProvenance] =
    result match {
      case r: RiskResult => r.provenances
      case _             => Nil
    }
  
  def spec = suite("ProvenanceSpec")(
    
    suite("JSON Serialization")(
      test("NodeProvenance serializes and deserializes correctly") {
        val provenance = NodeProvenance(
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
          metalogDistributionVersion = BuildInfo.metalogDistributionVersion
        )
        
        val json = provenance.toJson
        val decoded = json.fromJson[NodeProvenance]
        
        assertTrue(decoded.isRight) &&
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
        val leaf = unsafeGet(RiskLeaf.create(
          id = idStr("test-risk"),
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L),
          seedVarId = 1L
        ), "leaf")

        val testTree = unsafeGet(RiskTree.fromNodes(
          id = treeId("tree-1"),
          name = SafeName.SafeName("Test Tree".refineUnsafe),
          nodes = Seq(leaf),
          rootId = leaf.id,
          mitigations = Nil
        ), "tree")
        
        for {
          resolver <- ZIO.service[CachedResultResolver]
          result <- resolver.ensureCached(testTree, nodeId("test-risk"), testEntity, includeProvenance = true)
        } yield {
          // Structural attribution: the record carries no riskId —
          // the result's own nodeId is the attribution
          assertTrue(leafProvenances(result).nonEmpty) &&
          assertTrue(result.nodeId == nodeId("test-risk"))
        }
      },
      
      // Resolver always captures provenance for cache consistency.
      // Filtering (based on includeProvenance flag) happens at the service layer.
      // The cached value embeds the content-only record, so
      // every hit returns provenance without fragmenting the cache.
      test("resolver always captures provenance regardless of includeProvenance flag") {
        val leaf = unsafeGet(RiskLeaf.create(
          id = idStr("test-risk"),
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L),
          seedVarId = 2L
        ), "leaf")

        val testTree = unsafeGet(RiskTree.fromNodes(
          id = treeId("tree-2"),
          name = SafeName.SafeName("Test Tree 2".refineUnsafe),
          nodes = Seq(leaf),
          rootId = leaf.id,
          mitigations = Nil
        ), "tree")
        
        for {
          resolver <- ZIO.service[CachedResultResolver]
          result <- resolver.ensureCached(testTree, nodeId("test-risk"), testEntity, includeProvenance = false)
        } yield assertTrue(leafProvenances(result).nonEmpty)
      },
      
      // Recorded var-IDs equal the sampler's inputs: both read the same
      // HdrStreams value from the single derivation site, so what provenance
      // records cannot diverge from what the sampler consumed. ULID has no
      // influence on any recorded seed.
      test("provenance records the derived streams: entity from workspace, (2k, 2k+1) from seedVarId") {
        val riskIdLabel = "cyber-attack"
        val riskId = nodeId(riskIdLabel)

        val leaf = unsafeGet(RiskLeaf.create(
          id = riskId.value,
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L),
          seedVarId = 3L
        ), "leaf")

        val testTree = unsafeGet(RiskTree.fromNodes(
          id = treeId("tree-3"),
          name = SafeName.SafeName("Test Tree 3".refineUnsafe),
          nodes = Seq(leaf),
          rootId = leaf.id,
          mitigations = Nil
        ), "tree")

        for {
          resolver <- ZIO.service[CachedResultResolver]
          result <- resolver.ensureCached(testTree, nodeId(riskIdLabel), testEntity, includeProvenance = true)
        } yield {
          val nodeProv = leafProvenances(result).head
          val expected = SeedDerivation.streams(testEntity, leaf.seedVarId, nodeProv.globalSeed3, nodeProv.globalSeed4)
          assertTrue(result.nodeId == riskId) &&
          assertTrue(nodeProv.entityId == testEntity.value) &&
          assertTrue(nodeProv.occurrenceVarId == 6L) &&   // 2 · 3
          assertTrue(nodeProv.lossVarId == 7L) &&          // 2 · 3 + 1
          assertTrue(nodeProv.entityId == expected.entityId) &&
          assertTrue(nodeProv.occurrenceVarId == expected.occurrenceVarId) &&
          assertTrue(nodeProv.lossVarId == expected.lossVarId)
        }
      },
      
      test("provenance captures distribution parameters for lognormal") {
        val leaf = unsafeGet(RiskLeaf.create(
          id = idStr("lognormal-risk"),
          name = "Lognormal Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L),
          seedVarId = 4L
        ), "leaf")

        val testTree = unsafeGet(RiskTree.fromNodes(
          id = treeId("tree-4"),
          name = SafeName.SafeName("Test Tree 4".refineUnsafe),
          nodes = Seq(leaf),
          rootId = leaf.id,
          mitigations = Nil
        ), "tree")
        
        for {
          resolver <- ZIO.service[CachedResultResolver]
          result <- resolver.ensureCached(testTree, nodeId("lognormal-risk"), testEntity, includeProvenance = true)
        } yield {
          val nodeProv = leafProvenances(result).head
          assertTrue(result.nodeId == nodeId("lognormal-risk")) &&
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
        val risk1 = unsafeGet(RiskLeaf.create(
          id = idStr("risk1"),
          name = "Risk 1",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(5000L),
          parentId = Some(nodeId("portfolio")),
          seedVarId = 5L
        ), "leaf")

        val risk2 = unsafeGet(RiskLeaf.create(
          id = idStr("risk2"),
          name = "Risk 2",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(2000L),
          maxLoss = Some(8000L),
          parentId = Some(nodeId("portfolio")),
          seedVarId = 6L
        ), "leaf")

        val portfolio = unsafeGet(RiskPortfolio.createFromStrings(
          id = idStr("portfolio"),
          name = "Test Portfolio",
          childIds = Array(idStr("risk1"), idStr("risk2")),
          parentId = None
        ), "portfolio")
        
        val testTree = RiskTree.fromNodesUnsafe(
          id = treeId("tree-5"),
          name = SafeName.SafeName("Test Tree 5".refineUnsafe),
          nodes = Seq(portfolio, risk1, risk2),
          rootId = nodeId("portfolio"),
          mitigations = Nil
        )
        
        for {
          resolver <- ZIO.service[CachedResultResolver]
          // Simulate portfolio (which aggregates children)
          result <- resolver.ensureCached(testTree, nodeId("portfolio"), testEntity, includeProvenance = true)
        } yield {
          // Portfolio provenance is read structurally: walk the
          // group's children and pair each child's nodeId with its records
          // one level above any flattening — never via ids inside the records.
          val attributed = result match {
            case g: RiskResultGroup =>
              g.children.collect { case r: RiskResult => r.nodeId -> r.provenances }
            case _ => Nil
          }
          assertTrue(attributed.map(_._1) == List(nodeId("risk1"), nodeId("risk2"))) &&
          assertTrue(attributed.forall(_._2.size == 1))
        }
      }
    ).provideLayerShared(testLayer),
    
    suite("Reproduction Validation")(
      test("same provenance seeds produce identical results") {
        val leaf = unsafeGet(RiskLeaf.create(
          id = idStr("deterministic-risk"),
          name = "Deterministic Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(5000L),
          maxLoss = Some(20000L),
          seedVarId = 7L
        ), "leaf")
        
        // Two trees, same leaf content: under content addressing the second
        // read is a deliberate cache HIT — identical content must yield
        // identical outcomes either way (simulated or served from cache)
        val testTree1 = unsafeGet(RiskTree.fromNodes(
          id = treeId("tree-6"),
          name = SafeName.SafeName("Test Tree 6".refineUnsafe),
          nodes = Seq(leaf),
          rootId = leaf.id,
          mitigations = Nil
        ), "tree")

        val testTree2 = unsafeGet(RiskTree.fromNodes(
          id = treeId("tree-7"),
          name = SafeName.SafeName("Test Tree 7".refineUnsafe),
          nodes = Seq(leaf),
          rootId = leaf.id,
          mitigations = Nil
        ), "tree")
        
        for {
          resolver <- ZIO.service[CachedResultResolver]
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
        val leaf = unsafeGet(RiskLeaf.create(
          id = idStr("test-reconstruction"),
          name = "Test Reconstruction",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(15000L),
          seedVarId = 8L
        ), "leaf")

        val testTree = unsafeGet(RiskTree.fromNodes(
          id = treeId("tree-8"),
          name = SafeName.SafeName("Test Tree 8".refineUnsafe),
          nodes = Seq(leaf),
          rootId = leaf.id,
          mitigations = Nil
        ), "tree")
        
        for {
          resolver <- ZIO.service[CachedResultResolver]
          result <- resolver.ensureCached(testTree, nodeId("test-reconstruction"), testEntity, includeProvenance = true)
        } yield {
          val nodeProv = leafProvenances(result).head

          // Verify all essential information is captured; attribution is the
          // result's own nodeId — no identity inside the record
          assertTrue(result.nodeId == nodeId("test-reconstruction")) &&
          assertTrue(nodeProv.entityId != 0L) &&
          assertTrue(nodeProv.occurrenceVarId != 0L) &&
          assertTrue(nodeProv.lossVarId != 0L) &&
          assertTrue(nodeProv.globalSeed3 == 0L) &&
          assertTrue(nodeProv.globalSeed4 == 0L) &&
          assertTrue(nodeProv.distributionType == "lognormal") &&
          assertTrue(nodeProv.distributionParams != null) &&
          assertTrue(nodeProv.timestamp != null) &&
          assertTrue(nodeProv.metalogDistributionVersion.nonEmpty)
        }
      }
    ).provideLayerShared(testLayer)
  )
}

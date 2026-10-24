package com.risquanter.register.simulation

import com.risquanter.register.BuildInfo
import com.risquanter.register.domain.data.{
  DistributionParams, NodeProvenance, RiskLeaf, RiskPortfolio, RiskTree
}
import com.risquanter.register.domain.data.iron.{SafeName, SeedEntityId}
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.telemetry.TelemetryLive
import com.risquanter.register.services.cache.{CachedResultResolver, CachedResultResolverLive, ContentCacheRegistry}
import com.risquanter.register.testutil.TestHelpers.{idStr, nodeId, treeId, unsafeGet, lognormalShape}
import zio.*
import zio.test.*
import zio.json.*
import java.time.Instant
import io.github.iltotore.iron.*

/** JSON round-trip, provenance capture during simulation, and reproduction from provenance metadata. */
object ProvenanceSpec extends ZIOSpecDefault {

  private val testEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

  // Test layer with all dependencies for provenance tests
  val testLayer: ZLayer[Any, Throwable, CachedResultResolver & ContentCacheRegistry] =
    ZLayer.make[CachedResultResolver & ContentCacheRegistry](
      ContentCacheRegistry.layer,
      ZLayer.succeed(TestConfigs.simulation),
      TestConfigs.telemetryLayer >>> TelemetryLive.configured,
      CachedResultResolverLive.layer
    )

  /** Attribution is by node, never by anything inside the record: the value's
    * own `provenance` field carries the record and its `nodeId` names the node
    * that produced it. A portfolio carries none.
    */
  private def leafProvenances(result: LossDistribution): List[NodeProvenance] =
    result.provenance.toList
  
  def spec = suite("ProvenanceSpec")(
    
    suite("JSON Serialization")(
      test("NodeProvenance serializes and deserializes correctly") {
        val provenance = NodeProvenance(
          entityId = 42L,
          occurrenceVarId = 1042L,
          lossVarId = 2042L,
          globalSeed3 = 0L,
          globalSeed4 = 0L,
          distributionParams = DistributionParams.Lognormal(
            minLoss = 1000000L.refineUnsafe,
            maxLoss = 50000000L.refineUnsafe,
            confidenceInterval = 0.90
          ),
          timestamp = Instant.parse("2026-01-04T12:34:56Z"),
          metalogDistributionVersion = BuildInfo.metalogDistributionVersion
        )
        
        val json = provenance.toJson
        val decoded = json.fromJson[NodeProvenance]
        
        // The round trip has to preserve which mode the params are in, and that
        // is now the decoded case rather than a separate tag field.
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.entityId == 42L) &&
        assertTrue(decoded.toOption.get.distributionParams.isInstanceOf[DistributionParams.Lognormal])
      },
      
      test("DistributionParams.Metalog round-trips to an equal value") {
        val params = DistributionParams.Metalog(
          percentiles = Vector(0.05, 0.5, 0.95),
          quantiles = Vector(1000.0, 5000.0, 25000.0),
          terms = 3.refineUnsafe
        )

        val json = params.toJson
        val decoded = json.fromJson[DistributionParams.Metalog]

        // The anchors are compared by value, not by length: the sequences are
        // immutable, so the record's own equality is the assertion and a decoder
        // that returned the right count of wrong numbers would fail here.
        assertTrue(decoded == Right(params))
      },

      test("DistributionParams.Metalog rejects terms = 0") {
        val json = """{"percentiles":[0.05,0.5,0.95],"quantiles":[1000.0,5000.0,25000.0],"terms":0}"""
        assertTrue(json.fromJson[DistributionParams.Metalog].isLeft)
      },

      test("DistributionParams.Metalog rejects terms < 0") {
        val json = """{"percentiles":[0.05,0.5,0.95],"quantiles":[1000.0,5000.0,25000.0],"terms":-1}"""
        assertTrue(json.fromJson[DistributionParams.Metalog].isLeft)
      },
      
      test("DistributionParams.Lognormal serializes correctly") {
        val params = DistributionParams.Lognormal(
          minLoss = 1000000L.refineUnsafe,
          maxLoss = 50000000L.refineUnsafe,
          confidenceInterval = 0.90
        )
        
        val json = params.toJson
        val decoded = json.fromJson[DistributionParams.Lognormal]
        
        assertTrue(decoded.isRight) &&
        assertTrue(decoded.toOption.get.minLoss == 1000000L) &&
        assertTrue(decoded.toOption.get.maxLoss == 50000000L) &&
        assertTrue(decoded.toOption.get.confidenceInterval == 0.90)
      },

      test("DistributionParams.Lognormal rejects negative minLoss") {
        val json = """{"minLoss":-1,"maxLoss":50000000,"confidenceInterval":0.9}"""
        assertTrue(json.fromJson[DistributionParams.Lognormal].isLeft)
      },

      test("DistributionParams.Lognormal rejects a minLoss of zero") {
        val json = """{"minLoss":0,"maxLoss":50000000,"confidenceInterval":0.9}"""
        assertTrue(json.fromJson[DistributionParams.Lognormal].isLeft)
      },

      test("DistributionParams.Lognormal rejects a maxLoss of zero") {
        val json = """{"minLoss":1000000,"maxLoss":0,"confidenceInterval":0.9}"""
        assertTrue(json.fromJson[DistributionParams.Lognormal].isLeft)
      },

      test("DistributionParams.Lognormal rejects negative maxLoss") {
        val json = """{"minLoss":1000000,"maxLoss":-1,"confidenceInterval":0.9}"""
        assertTrue(json.fromJson[DistributionParams.Lognormal].isLeft)
      },
      
      test("DistributionParams sealed trait handles both subtypes") {
        val metalog: DistributionParams = DistributionParams.Metalog(
          percentiles = Vector(0.1, 0.9),
          quantiles = Vector(1000.0, 5000.0),
          terms = 2.refineUnsafe
        )
        
        val lognormal: DistributionParams = DistributionParams.Lognormal(
          minLoss = 1000L.refineUnsafe,
          maxLoss = 10000L.refineUnsafe,
          confidenceInterval = 0.90
        )
        
        // Each case carries its own discriminator tag, and decodes back to the
        // same case rather than to its sibling. Asserting the tag is what makes
        // this a test of the discriminated form: a codec that encoded both
        // cases identically would still serialize without error.
        assertTrue(
          metalog.toJson.contains("\"distributionType\":\"Metalog\""),
          lognormal.toJson.contains("\"distributionType\":\"Lognormal\""),
          metalog.toJson.fromJson[DistributionParams] == Right(metalog),
          lognormal.toJson.fromJson[DistributionParams] == Right(lognormal)
        )
      },

      test("a malformed metalog record reports the metalog decoder's own complaint") {
        // The term count is below `MetalogTermCount`'s floor, so the metalog
        // decoder refuses it. The discriminator sends the body to that decoder
        // alone, so the failure names the term count rather than reporting a
        // missing lognormal field from a fall-through to the sibling case.
        val malformed =
          """{"distributionType":"Metalog","percentiles":[0.1,0.9],"quantiles":[1000.0,5000.0],"terms":1}"""

        malformed.fromJson[DistributionParams] match
          case Left(message) =>
            assertTrue(
              message.toLowerCase.contains("terms"),
              !message.toLowerCase.contains("minloss")
            )
          case Right(_) =>
            assertTrue(false)
      }
    ),
    
    suite("Provenance Capture")(
      test("ensureCached with includeProvenance=true captures metadata") {
        val leaf = unsafeGet(RiskLeaf.create(
          id = idStr("test-risk"),
          name = "Test Risk",
          probability = 0.5,
          shape = lognormalShape(1000L, 10000L),
          parentId = None,
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
          probability = 0.5,
          shape = lognormalShape(1000L, 10000L),
          parentId = None,
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
          probability = 0.25,
          shape = lognormalShape(1000L, 50000L),
          parentId = None,
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
          probability = 0.5,
          shape = lognormalShape(1000L, 10000L),
          parentId = None,
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
          assertTrue(nodeProv.distributionParams.isInstanceOf[DistributionParams.Lognormal]) &&
          assertTrue(
            nodeProv.distributionParams.asInstanceOf[DistributionParams.Lognormal].minLoss == 1000L
          ) &&
          assertTrue(
            nodeProv.distributionParams.asInstanceOf[DistributionParams.Lognormal].maxLoss == 10000L
          )
        }
      },
      
      test("provenance aggregates multiple node provenances in portfolio") {
        val risk1 = unsafeGet(RiskLeaf.create(
          id = idStr("risk1"),
          name = "Risk 1",
          probability = 0.5,
          shape = lognormalShape(1000L, 5000L),
          parentId = Some(nodeId("portfolio")),
          seedVarId = 5L
        ), "leaf")

        val risk2 = unsafeGet(RiskLeaf.create(
          id = idStr("risk2"),
          name = "Risk 2",
          probability = 0.5,
          shape = lognormalShape(2000L, 8000L),
          parentId = Some(nodeId("portfolio")),
          seedVarId = 6L
        ), "leaf")

        val portfolio = unsafeGet(RiskPortfolio.createFromStrings(
          id = idStr("portfolio"),
          name = "Test Portfolio",
          childIds = Vector(idStr("risk1"), idStr("risk2")),
          parentId = None
        ), "portfolio")
        
        val testTree = RiskTree.fromNodesUnsafe(
          id = treeId("tree-5"),
          name = SafeName.SafeName("Test Tree 5".refineUnsafe),
          nodes = Seq(portfolio, risk1, risk2),
          rootId = nodeId("portfolio"),
          mitigations = Nil
        )
        
        val leafIds = Set(nodeId("risk1"), nodeId("risk2"))

        for {
          resolver <- ZIO.service[CachedResultResolver]
          // The portfolio itself carries no record: its trials are sums of its
          // children and it draws nothing of its own (ADR-003 §2).
          portfolioResult <- resolver.ensureCached(testTree, nodeId("portfolio"), testEntity, includeProvenance = true)
          // A subtree's records come from resolving each leaf under its OWN
          // identifier, with the leaf set taken from the tree index — nothing
          // traverses a returned value, so no value carries another node's data.
          leafResults <- resolver.ensureCachedAll(testTree, leafIds, testEntity, includeProvenance = true)
        } yield {
          val attributed = LossDistribution.leafProvenances(leafResults)
          assertTrue(portfolioResult.provenance.isEmpty) &&
          assertTrue(attributed.keySet == leafIds) &&
          assertTrue(attributed.size == 2)
        }
      }
    ).provideLayerShared(testLayer),
    
    suite("Reproduction Validation")(
      test("same provenance seeds produce identical results") {
        val leaf = unsafeGet(RiskLeaf.create(
          id = idStr("deterministic-risk"),
          name = "Deterministic Risk",
          probability = 0.5,
          shape = lognormalShape(5000L, 20000L),
          parentId = None,
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
          probability = 0.3,
          shape = lognormalShape(1000L, 15000L),
          parentId = None,
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
          assertTrue(nodeProv.distributionParams.isInstanceOf[DistributionParams.Lognormal]) &&
          assertTrue(nodeProv.timestamp != null) &&
          assertTrue(nodeProv.metalogDistributionVersion.nonEmpty)
        }
      }
    ).provideLayerShared(testLayer)
  )
}

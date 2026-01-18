package com.risquanter.register.domain.data

import com.risquanter.register.BuildInfo
import com.risquanter.register.domain.data.iron.SafeId
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import java.time.Instant
import io.github.iltotore.iron.autoRefine

/**
 * Tests for Provenance metadata structures.
 * 
 * Verifies:
 * - JSON serialization/deserialization
 * - Provenance capture during simulation
 * - Reproduction from provenance metadata
 */
object ProvenanceSpec extends ZIOSpecDefault {
  
  // Helper to create SafeId from string literal
  private def safeId(s: String): SafeId.SafeId = 
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
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
      test("simulateTree with includeProvenance=true captures metadata") {
        import com.risquanter.register.services.helper.Simulator
        
        val leaf = RiskLeaf.unsafeApply(
          id = "test-risk",
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L)
        )
        
        for {
          resultWithProv <- Simulator.simulateTree(leaf, nTrials = 100, parallelism = 1, includeProvenance = true)
          (result, provenance) = resultWithProv
        } yield {
          assertTrue(provenance.isDefined) &&
          assertTrue(provenance.get.nodeProvenances.contains(safeId("test-risk"))) &&
          assertTrue(provenance.get.nTrials == 100) &&
          assertTrue(provenance.get.parallelism == 1)
        }
      },
      
      test("simulateTree with includeProvenance=false returns None") {
        import com.risquanter.register.services.helper.Simulator
        
        val leaf = RiskLeaf.unsafeApply(
          id = "test-risk",
          name = "Test Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L)
        )
        
        for {
          resultWithProv <- Simulator.simulateTree(leaf, nTrials = 100, parallelism = 1, includeProvenance = false)
          (result, provenance) = resultWithProv
        } yield assertTrue(provenance.isEmpty)
      },
      
      test("provenance captures correct entityId from riskId hash") {
        import com.risquanter.register.services.helper.Simulator
        
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
        
        for {
          resultWithProv <- Simulator.simulateTree(leaf, nTrials = 100, parallelism = 1, includeProvenance = true)
          (_, provenance) = resultWithProv
        } yield {
          val nodeProv = provenance.get.nodeProvenances(safeId(riskIdStr))
          assertTrue(nodeProv.entityId == expectedEntityId) &&
          assertTrue(nodeProv.occurrenceVarId == expectedEntityId.hashCode + 1000L) &&
          assertTrue(nodeProv.lossVarId == expectedEntityId.hashCode + 2000L)
        }
      },
      
      test("provenance captures distribution parameters for lognormal") {
        import com.risquanter.register.services.helper.Simulator
        
        val leaf = RiskLeaf.unsafeApply(
          id = "lognormal-risk",
          name = "Lognormal Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(10000L)
        )
        
        for {
          resultWithProv <- Simulator.simulateTree(leaf, nTrials = 100, parallelism = 1, includeProvenance = true)
          (_, provenance) = resultWithProv
        } yield {
          val nodeProv = provenance.get.nodeProvenances(safeId("lognormal-risk"))
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
        import com.risquanter.register.services.helper.Simulator
        
        val risk1 = RiskLeaf.unsafeApply(
          id = "risk1",
          name = "Risk 1",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(5000L)
        )
        
        val risk2 = RiskLeaf.unsafeApply(
          id = "risk2",
          name = "Risk 2",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(2000L),
          maxLoss = Some(8000L)
        )
        
        val portfolio = RiskPortfolio.unsafeApply(
          id = "portfolio",
          name = "Test Portfolio",
          children = Array(risk1, risk2)
        )
        
        for {
          resultWithProv <- Simulator.simulateTree(portfolio, nTrials = 200, parallelism = 2, includeProvenance = true)
          (_, provenance) = resultWithProv
        } yield {
          assertTrue(provenance.isDefined) &&
          assertTrue(provenance.get.nodeProvenances.size == 2) &&
          assertTrue(provenance.get.nodeProvenances.contains(safeId("risk1"))) &&
          assertTrue(provenance.get.nodeProvenances.contains(safeId("risk2")))
        }
      }
    ),
    
    suite("Reproduction Validation")(
      test("same provenance seeds produce identical results") {
        import com.risquanter.register.services.helper.Simulator
        
        val leaf = RiskLeaf.unsafeApply(
          id = "deterministic-risk",
          name = "Deterministic Risk",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(5000L),
          maxLoss = Some(20000L)
        )
        
        for {
          // First simulation with provenance
          firstRun <- Simulator.simulateTree(leaf, nTrials = 500, parallelism = 1, includeProvenance = true)
          (firstResult, _) = firstRun
          
          // Second simulation with same parameters
          secondRun <- Simulator.simulateTree(leaf, nTrials = 500, parallelism = 1, includeProvenance = true)
          (secondResult, _) = secondRun
        } yield {
          // Same inputs should produce identical outcomes
          assertTrue(firstResult.result.outcomes == secondResult.result.outcomes)
        }
      },
      
      test("provenance contains all information for reconstruction") {
        import com.risquanter.register.services.helper.Simulator
        
        val leaf = RiskLeaf.unsafeApply(
          id = "test-reconstruction",
          name = "Test Reconstruction",
          distributionType = "lognormal",
          probability = 0.3,
          minLoss = Some(1000L),
          maxLoss = Some(15000L)
        )
        
        for {
          resultWithProv <- Simulator.simulateTree(leaf, nTrials = 300, parallelism = 1, includeProvenance = true)
          (result, provenance) = resultWithProv
        } yield {
          val nodeProv = provenance.get.nodeProvenances(safeId("test-reconstruction"))
          
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
          assertTrue(nodeProv.simulationUtilVersion.nonEmpty) &&
          assertTrue(provenance.get.nTrials == 300) &&
          assertTrue(provenance.get.parallelism == 1)
        }
      }
    )
  )
}

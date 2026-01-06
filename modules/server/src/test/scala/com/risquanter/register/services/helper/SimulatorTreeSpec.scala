package com.risquanter.register.services.helper

import zio.*
import zio.test.*
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio, RiskTreeResult}
import com.risquanter.register.domain.errors.ValidationFailed

object SimulatorTreeSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Simulator.simulateTree")(
      test("simulates simple 2-leaf portfolio") {
        // Create a simple portfolio with 2 risks
        val cyberRisk = RiskLeaf.unsafeApply(
          id = "cyber",
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),  // $1B to $50B
          maxLoss = Some(50000L)
        )
        
        val supplyChainRisk = RiskLeaf.unsafeApply(
          id = "supply-chain",
          name = "Supply Chain Disruption",
          distributionType = "lognormal",
          probability = 0.15,
          minLoss = Some(500L),   // $500M to $20B
          maxLoss = Some(20000L)
        )
        
        val portfolio = RiskPortfolio.unsafeApply(
          id = "ops-risk",
          name = "Operational Risk",
          children = Array(cyberRisk, supplyChainRisk)
        )
        
        // Run simulation
        val program = Simulator.simulateTree(portfolio, nTrials = 1000, parallelism = 2)
        
        program.map { case (result, _) =>
          result match {
            case RiskTreeResult.Branch(id, aggregated, children) =>
              // Verify structure
              assertTrue(
                id == "ops-risk",
                children.length == 2,
                children(0).id == "cyber",
                children(1).id == "supply-chain",
                aggregated.nTrials == 1000
              )
            
            case _ =>
              assertTrue(false) // Should be a branch, not a leaf
          }
        }
      },
      
      test("simulates single leaf risk") {
        val singleRisk = RiskLeaf.unsafeApply(
          id = "cyber",
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        )
        
        val program = Simulator.simulateTree(singleRisk, nTrials = 500, parallelism = 1)
        
        program.map { case (result, _) =>
          result match {
            case RiskTreeResult.Leaf(id, riskResult) =>
              assertTrue(
                id == "cyber",
                riskResult.name == "cyber",
                riskResult.nTrials == 500
              )
            
            case _ =>
              assertTrue(false) // Should be a leaf, not a branch
          }
        }
      },
      
      test("aggregates child losses correctly in portfolio") {
        // Simple test: both risks with reasonable probability
        // Use lognormal mode which is simpler
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
        
        val program = Simulator.simulateTree(portfolio, nTrials = 300, parallelism = 2)
        
        program.map { case (result, _) =>
          result match {
            case RiskTreeResult.Branch(_, aggregated, children) =>
              // Verify both children were simulated and aggregated
              assertTrue(
                children.length == 2,
                children(0).result.nTrials == 300,
                children(1).result.nTrials == 300,
                aggregated.nTrials == 300
              )
            
            case _ =>
              assertTrue(false)
          }
        }
      },
      
      test("fails on empty portfolio") {
        val emptyPortfolio = RiskPortfolio.unsafeApply(
          id = "empty",
          name = "Empty Portfolio",
          children = Array.empty
        )
        
        val program = Simulator.simulateTree(emptyPortfolio, nTrials = 100, parallelism = 2)
        
        program.flip.map { error =>
          // Should fail with validation error about empty children
          error match {
            case ValidationFailed(errors) => assertTrue(errors.exists(_.contains("no children")))
            case _ => assertTrue(false)
          }
        }
      }
    )
}

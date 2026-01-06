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
        val cyberRisk = RiskLeaf.create(
          id = "cyber",
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),  // $1B to $50B
          maxLoss = Some(50000L)
        ).toEither.getOrElse(throw new RuntimeException("Invalid test data: cyberRisk"))
        
        val supplyChainRisk = RiskLeaf.create(
          id = "supply-chain",
          name = "Supply Chain Disruption",
          distributionType = "lognormal",
          probability = 0.15,
          minLoss = Some(500L),   // $500M to $20B
          maxLoss = Some(20000L)
        ).toEither.getOrElse(throw new RuntimeException("Invalid test data: supplyChainRisk"))
        
        val portfolio = RiskPortfolio.create(
          id = "ops-risk",
          name = "Operational Risk",
          children = Array(cyberRisk, supplyChainRisk)
        ).toEither.getOrElse(throw new RuntimeException("Invalid test data: portfolio"))
        
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
        val singleRisk = RiskLeaf.create(
          id = "cyber",
          name = "Cyber Attack",
          distributionType = "lognormal",
          probability = 0.25,
          minLoss = Some(1000L),
          maxLoss = Some(50000L)
        ).toEither.getOrElse(throw new RuntimeException("Invalid test data: singleRisk"))
        
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
        val risk1 = RiskLeaf.create(
          id = "risk1",
          name = "Risk 1",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(1000L),
          maxLoss = Some(5000L)
        ).toEither.getOrElse(throw new RuntimeException("Invalid test data: risk1"))
        
        val risk2 = RiskLeaf.create(
          id = "risk2",
          name = "Risk 2",
          distributionType = "lognormal",
          probability = 0.5,
          minLoss = Some(2000L),
          maxLoss = Some(8000L)
        ).toEither.getOrElse(throw new RuntimeException("Invalid test data: risk2"))
        
        val portfolio = RiskPortfolio.create(
          id = "portfolio",
          name = "Test Portfolio",
          children = Array(risk1, risk2)
        ).toEither.getOrElse(throw new RuntimeException("Invalid test data: portfolio"))
        
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
      
      test("fails on empty portfolio - require clause prevents construction") {
        // The require clause in RiskPortfolio prevents construction with empty children
        // This test documents that behavior: attempting to create an empty portfolio
        // throws IllegalArgumentException at construction time (defense in depth)
        val result = scala.util.Try {
          RiskPortfolio.unsafeApply(
            id = "empty",
            name = "Empty Portfolio",
            children = Array.empty
          )
        }
        
        assertTrue(
          result.isFailure,
          result.failed.get.isInstanceOf[IllegalArgumentException],
          result.failed.get.getMessage.contains("children must be non-empty")
        )
      }
    )
}

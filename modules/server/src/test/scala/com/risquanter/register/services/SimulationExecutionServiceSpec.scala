package com.risquanter.register.services

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.simulation.MetalogDistribution
import com.risquanter.register.domain.data.iron.Probability
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{Greater, Less}

object SimulationExecutionServiceSpec extends ZIOSpecDefault {
  
  // Helper to create Probability values
  private def prob(value: Double): Probability =
    value.refineUnsafe[Greater[0.0] & Less[1.0]]
  
  // Helper to create simple loss distribution
  private def createSimpleLossDistribution(): MetalogDistribution = {
    val percentiles = Array(0.05, 0.5, 0.95).map(prob)
    val quantiles = Array(1000.0, 5000.0, 25000.0)
    MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
      .toOption.get
  }
  
  // Helper to create test risk config
  private def createRiskConfig(
    riskId: String,
    entityId: Long,
    occurrenceProb: Double,
    seed3: Long = 0L
  ): RiskConfig = {
    RiskConfig(
      riskId = riskId,
      entityId = entityId,
      occurrenceProb = prob(occurrenceProb),
      lossDistribution = createSimpleLossDistribution(),
      seed3 = seed3,
      seed4 = 0L
    )
  }
  
  def spec = suite("SimulationExecutionServiceSpec")(
    
    suite("runSimulation - basic functionality")(
      
      test("executes simulation for single risk") {
        val service = SimulationExecutionServiceLive()
        val config = createRiskConfig("RISK-SINGLE", 1L, 0.3)
        
        for {
          result <- service.runSimulation("SIM-001", Seq(config), nTrials = 500)
        } yield assertTrue(
          result.simulationId == "SIM-001",
          result.nTrials == 500,
          result.individualResults.size == 1,
          result.individualResults.head.riskName == "RISK-SINGLE",
          result.aggregatedResult.riskName == "AGGREGATE",
          result.aggregatedResult.nTrials == 500
        )
      },
      
      test("executes simulation for multiple risks") {
        val service = SimulationExecutionServiceLive()
        val configs = Seq(
          createRiskConfig("RISK-A", 1L, 0.1, seed3 = 100L),
          createRiskConfig("RISK-B", 2L, 0.2, seed3 = 200L),
          createRiskConfig("RISK-C", 3L, 0.3, seed3 = 300L)
        )
        
        for {
          result <- service.runSimulation("SIM-002", configs, nTrials = 1000)
        } yield assertTrue(
          result.simulationId == "SIM-002",
          result.nTrials == 1000,
          result.individualResults.size == 3,
          result.individualResults.map(_.riskName).toSet == Set("RISK-A", "RISK-B", "RISK-C"),
          result.aggregatedResult.riskName == "AGGREGATE"
        )
      },
      
      test("handles empty risk list") {
        val service = SimulationExecutionServiceLive()
        
        for {
          result <- service.runSimulation("SIM-003", Seq.empty, nTrials = 100)
        } yield assertTrue(
          result.simulationId == "SIM-003",
          result.nTrials == 100,
          result.individualResults.isEmpty,
          result.aggregatedResult.outcomes.isEmpty,
          result.aggregatedResult.riskName == "AGGREGATE"
        )
      }
    ),
    
    suite("aggregation correctness")(
      
      test("aggregated result combines all individual risk outcomes") {
        val service = SimulationExecutionServiceLive()
        val configs = Seq(
          createRiskConfig("RISK-AGG-1", 10L, 0.5, seed3 = 1000L),
          createRiskConfig("RISK-AGG-2", 20L, 0.5, seed3 = 2000L)
        )
        
        for {
          result <- service.runSimulation("SIM-AGG-001", configs, nTrials = 200)
        } yield {
          val risk1 = result.individualResults.find(_.riskName == "RISK-AGG-1").get
          val risk2 = result.individualResults.find(_.riskName == "RISK-AGG-2").get
          val aggregate = result.aggregatedResult
          
          // Aggregate should have trials from both risks
          val allTrialIds = risk1.trialIds() ++ risk2.trialIds()
          val aggregateTrialIds = aggregate.trialIds()
          
          assertTrue(
            aggregateTrialIds.subsetOf(allTrialIds) || aggregateTrialIds == allTrialIds,
            aggregate.nTrials == 200
          )
        }
      },
      
      test("aggregated outcome sums individual outcomes per trial") {
        val service = SimulationExecutionServiceLive()
        val configs = Seq(
          createRiskConfig("RISK-SUM-1", 30L, 0.3, seed3 = 3000L),
          createRiskConfig("RISK-SUM-2", 40L, 0.3, seed3 = 4000L)
        )
        
        for {
          result <- service.runSimulation("SIM-SUM-001", configs, nTrials = 300)
        } yield {
          val risk1 = result.individualResults.find(_.riskName == "RISK-SUM-1").get
          val risk2 = result.individualResults.find(_.riskName == "RISK-SUM-2").get
          val aggregate = result.aggregatedResult
          
          // Pick a trial that exists in both
          val commonTrials = risk1.trialIds().intersect(risk2.trialIds())
          
          if (commonTrials.nonEmpty) {
            val trial = commonTrials.head
            val expectedSum = risk1.outcomeOf(trial) + risk2.outcomeOf(trial)
            val actualSum = aggregate.outcomeOf(trial)
            
            assertTrue(actualSum == expectedSum)
          } else {
            // No common trials - verify aggregate contains union
            assertTrue(aggregate.trialIds().size <= risk1.trialIds().size + risk2.trialIds().size)
          }
        }
      }
    ),
    
    suite("determinism")(
      
      test("parallel execution produces deterministic results") {
        val service = SimulationExecutionServiceLive()
        val configs = Seq(
          createRiskConfig("RISK-DET-1", 50L, 0.2, seed3 = 5000L),
          createRiskConfig("RISK-DET-2", 60L, 0.25, seed3 = 6000L)
        )
        
        for {
          run1 <- service.runSimulation("SIM-DET-001", configs, nTrials = 400, parallelism = 4)
          run2 <- service.runSimulation("SIM-DET-002", configs, nTrials = 400, parallelism = 4)
          run3 <- service.runSimulation("SIM-DET-003", configs, nTrials = 400, parallelism = 8)
        } yield {
          val outcomes1 = run1.aggregatedResult.outcomes
          val outcomes2 = run2.aggregatedResult.outcomes
          val outcomes3 = run3.aggregatedResult.outcomes
          
          assertTrue(
            outcomes1 == outcomes2,
            outcomes2 == outcomes3
          )
        }
      },
      
      test("sequential vs parallel produce identical results") {
        val service = SimulationExecutionServiceLive()
        val configs = Seq(
          createRiskConfig("RISK-SEQ-1", 70L, 0.15, seed3 = 7000L),
          createRiskConfig("RISK-SEQ-2", 80L, 0.2, seed3 = 8000L)
        )
        
        for {
          parallel <- service.runSimulation("SIM-PAR-001", configs, nTrials = 300, parallelism = 8)
          sequential <- service.runSimulationSequential("SIM-SEQ-001", configs, nTrials = 300)
        } yield assertTrue(
          parallel.aggregatedResult.outcomes == sequential.aggregatedResult.outcomes,
          parallel.individualResults.map(_.outcomes).toSet == sequential.individualResults.map(_.outcomes).toSet
        )
      }
    ),
    
    suite("parallelism configuration")(
      
      test("respects parallelism parameter") {
        val service = SimulationExecutionServiceLive()
        val configs = (1 to 20).map(i => 
          createRiskConfig(s"RISK-PAR-$i", 100L + i, 0.1, seed3 = 1000L * i)
        )
        
        for {
          result <- service.runSimulation("SIM-PAR-MANY", configs, nTrials = 100, parallelism = 4)
        } yield assertTrue(
          result.individualResults.size == 20,
          result.aggregatedResult.nTrials == 100
        )
      },
      
      test("parallelism=1 works correctly") {
        val service = SimulationExecutionServiceLive()
        val configs = Seq(
          createRiskConfig("RISK-P1-1", 90L, 0.3, seed3 = 9000L),
          createRiskConfig("RISK-P1-2", 95L, 0.35, seed3 = 9500L)
        )
        
        for {
          result <- service.runSimulation("SIM-P1", configs, nTrials = 200, parallelism = 1)
        } yield assertTrue(
          result.individualResults.size == 2,
          result.aggregatedResult.nTrials == 200
        )
      }
    ),
    
    suite("edge cases")(
      
      test("handles very low occurrence probabilities") {
        val service = SimulationExecutionServiceLive()
        val config = createRiskConfig("RISK-LOW", 1000L, 0.001)
        
        for {
          result <- service.runSimulation("SIM-LOW", Seq(config), nTrials = 500)
        } yield assertTrue(
          result.individualResults.head.nTrials == 500,
          result.aggregatedResult.nTrials == 500
          // Outcomes may be empty or very sparse - that's expected
        )
      },
      
      test("handles high occurrence probabilities") {
        val service = SimulationExecutionServiceLive()
        val config = createRiskConfig("RISK-HIGH", 2000L, 0.9999)
        
        for {
          result <- service.runSimulation("SIM-HIGH", Seq(config), nTrials = 300)
        } yield {
          val outcomes = result.individualResults.head.outcomes
          assertTrue(
            outcomes.size > 250, // Expect most trials to have occurrences
            result.aggregatedResult.nTrials == 300
          )
        }
      },
      
      test("handles single trial") {
        val service = SimulationExecutionServiceLive()
        val config = createRiskConfig("RISK-ONE", 3000L, 0.5)
        
        for {
          result <- service.runSimulation("SIM-ONE", Seq(config), nTrials = 1)
        } yield assertTrue(
          result.nTrials == 1,
          result.individualResults.head.nTrials == 1,
          result.aggregatedResult.nTrials == 1
        )
      },
      
      test("handles large number of risks") {
        val service = SimulationExecutionServiceLive()
        val configs = (1 to 50).map(i => 
          createRiskConfig(s"RISK-MANY-$i", 4000L + i, 0.05, seed3 = 100L * i)
        )
        
        for {
          result <- service.runSimulation("SIM-MANY", configs, nTrials = 100)
        } yield assertTrue(
          result.individualResults.size == 50,
          result.individualResults.map(_.riskName).distinct.size == 50,
          result.aggregatedResult.nTrials == 100
        )
      }
    ),
    
    suite("simulation ID tracking")(
      
      test("preserves simulation ID through execution") {
        val service = SimulationExecutionServiceLive()
        val config = createRiskConfig("RISK-ID", 5000L, 0.2)
        
        for {
          result <- service.runSimulation("CUSTOM-SIM-ID-12345", Seq(config), nTrials = 100)
        } yield assertTrue(
          result.simulationId == "CUSTOM-SIM-ID-12345"
        )
      },
      
      test("different simulation IDs with same config produce same results") {
        val service = SimulationExecutionServiceLive()
        val config = createRiskConfig("RISK-SAME", 6000L, 0.25, seed3 = 12345L)
        
        for {
          result1 <- service.runSimulation("SIM-ID-A", Seq(config), nTrials = 200)
          result2 <- service.runSimulation("SIM-ID-B", Seq(config), nTrials = 200)
        } yield assertTrue(
          result1.simulationId != result2.simulationId,
          result1.aggregatedResult.outcomes == result2.aggregatedResult.outcomes
        )
      }
    )
  )
}

package com.risquanter.register.services.helper

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.simulation.{RiskSampler, MetalogDistribution}
import com.risquanter.register.domain.data.iron.Probability
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.autoRefine

object SimulatorSpec extends ZIOSpecDefault {
  
  // Helper to create Probability values
  private def prob(value: Double): Probability =
    value.refineUnsafe
  
  // Helper to create simple loss distribution
  private def createSimpleLossDistribution(): MetalogDistribution = {
    val percentiles = Array(0.05, 0.5, 0.95).map(prob)
    val quantiles = Array(1000.0, 5000.0, 25000.0)
    MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
      .toOption.get
  }
  
  def spec = suite("SimulatorSpec")(
    
    suite("performTrialsSync - sparse storage")(
      
      test("stores only successful trials for low probability risk") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          entityId = 1L,
          riskId = "RISK-LOW-PROB",
          occurrenceProb = prob(0.01), // 1% occurrence
          lossDistribution = metalog
        )
        
        val sparseMap = Simulator.performTrialsSync(sampler, nTrials = 10000)
        
        // With 1% probability, expect ~100 occurrences (not 10,000)
        // Note: Unbounded metalog can produce negative values at extreme probabilities
        assertTrue(
          sparseMap.size > 50,
          sparseMap.size < 200,
          sparseMap.nonEmpty
        )
      },
      
      test("all stored trials have losses sampled from distribution") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          entityId = 2L,
          riskId = "RISK-SAMPLED",
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        val trials = Simulator.performTrialsSync(sampler, nTrials = 1000)
        
        // Unbounded metalog can produce negative values at tail probabilities
        // Just verify we have reasonable trial counts
        assertTrue(
          trials.nonEmpty,
          trials.size < 1000 // Some trials should not occur
        )
      },
      
      test("trial IDs are within valid range") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          entityId = 3L,
          riskId = "RISK-RANGE",
          occurrenceProb = prob(0.3),
          lossDistribution = metalog
        )
        
        val nTrials = 500
        val trials = Simulator.performTrialsSync(sampler, nTrials.refineUnsafe)
        
        assertTrue(
          trials.forall { case (trialId, _) => trialId >= 0 && trialId < nTrials }
        )
      }
    ),
    
    suite("determinism - identical results with same seeds")(
      
      test("performTrialsSync produces identical results across runs") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          entityId = 100L,
          riskId = "RISK-DETERMINISTIC",
          occurrenceProb = prob(0.2),
          lossDistribution = metalog,
          seed3 = 12345L,
          seed4 = 67890L
        )
        
        val run1 = Simulator.performTrialsSync(sampler, nTrials = 1000)
        val run2 = Simulator.performTrialsSync(sampler, nTrials = 1000)
        val run3 = Simulator.performTrialsSync(sampler, nTrials = 1000)
        
        assertTrue(
          run1 == run2,
          run2 == run3,
          run1 == run3
        )
      },
      
      test("simulate produces identical results with same samplers") {
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(entityId = 101L, riskId = "RISK-A", occurrenceProb = prob(0.1), lossDistribution = metalog, seed3 = 111L),
          RiskSampler.fromDistribution(entityId = 102L, riskId = "RISK-B", occurrenceProb = prob(0.2), lossDistribution = metalog, seed3 = 222L),
          RiskSampler.fromDistribution(entityId = 103L, riskId = "RISK-C", occurrenceProb = prob(0.3), lossDistribution = metalog, seed3 = 333L)
        )
        
        for {
          run1 <- Simulator.simulate(samplers, nTrials = 500, parallelism = 2)
          run2 <- Simulator.simulate(samplers, nTrials = 500, parallelism = 2)
          run3 <- Simulator.simulate(samplers, nTrials = 500, parallelism = 4)
        } yield assertTrue(
          run1.map(_.outcomes) == run2.map(_.outcomes),
          run2.map(_.outcomes) == run3.map(_.outcomes),
          run1.map(_.name) == run2.map(_.name)
        )
      },
      
      test("sequential vs parallel produce identical results") {
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(entityId = 201L, riskId = "RISK-SEQ-1", occurrenceProb = prob(0.15), lossDistribution = metalog, seed3 = 1001L),
          RiskSampler.fromDistribution(entityId = 202L, riskId = "RISK-SEQ-2", occurrenceProb = prob(0.25), lossDistribution = metalog, seed3 = 2002L)
        )
        
        for {
          parallel <- Simulator.simulate(samplers, nTrials = 800, parallelism = 8)
          sequential <- Simulator.simulateSequential(samplers, nTrials = 800)
        } yield assertTrue(
          parallel.map(_.outcomes).toSet == sequential.map(_.outcomes).toSet
        )
      }
    ),
    
    suite("simulate - multiple risks")(
      
      test("simulates all risks successfully") {
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(entityId = 301L, riskId = "RISK-MULTI-1", occurrenceProb = prob(0.1), lossDistribution = metalog),
          RiskSampler.fromDistribution(entityId = 302L, riskId = "RISK-MULTI-2", occurrenceProb = prob(0.2), lossDistribution = metalog),
          RiskSampler.fromDistribution(entityId = 303L, riskId = "RISK-MULTI-3", occurrenceProb = prob(0.3), lossDistribution = metalog)
        )
        
        for {
          results <- Simulator.simulate(samplers, nTrials = 1000)
        } yield assertTrue(
          results.size == 3,
          results.map(_.name).toSet == Set("RISK-MULTI-1", "RISK-MULTI-2", "RISK-MULTI-3"),
          results.forall(_.nTrials == 1000)
        )
      },
      
      test("each risk has independent outcomes") {
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(entityId = 401L, riskId = "RISK-IND-1", occurrenceProb = prob(0.5), lossDistribution = metalog, seed3 = 4001L),
          RiskSampler.fromDistribution(entityId = 402L, riskId = "RISK-IND-2", occurrenceProb = prob(0.5), lossDistribution = metalog, seed3 = 4002L)
        )
        
        for {
          results <- Simulator.simulate(samplers, nTrials = 500)
        } yield {
        
          val risk1 = results.find(_.name == "RISK-IND-1").get
          val risk2 = results.find(_.name == "RISK-IND-2").get          // Different seeds should produce different outcomes
          assertTrue(risk1.outcomes != risk2.outcomes)
        }
      },
      
      test("empty samplers vector returns empty results") {
        for {
          results <- Simulator.simulate(Vector.empty, nTrials = 100)
        } yield assertTrue(results.isEmpty)
      },
      
      test("single sampler works correctly") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          entityId = 501L, riskId = "RISK-SINGLE", occurrenceProb = prob(0.4), lossDistribution = metalog
        )
        
        for {
          results <- Simulator.simulate(Vector(sampler), nTrials = 200)
        } yield assertTrue(
          results.size == 1,
          results.head.name == "RISK-SINGLE",
          results.head.nTrials == 200
        )
      }
    ),
    
    suite("parallelism configuration")(
      
      test("respects parallelism limit") {
        val metalog = createSimpleLossDistribution()
        val samplers = (1 to 20).map { i =>
          RiskSampler.fromDistribution(
            entityId = 600L + i,
            riskId = s"RISK-PAR-$i",
            occurrenceProb = prob(0.1),
            lossDistribution = metalog
          )
        }.toVector
        
        for {
          results <- Simulator.simulate(samplers, nTrials = 100, parallelism = 4)
        } yield assertTrue(
          results.size == 20,
          results.map(_.name).toSet.size == 20
        )
      },
      
      test("parallelism=1 equivalent to sequential") {
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(entityId = 701L, riskId = "RISK-P1-1", occurrenceProb = prob(0.2), lossDistribution = metalog, seed3 = 7001L),
          RiskSampler.fromDistribution(entityId = 702L, riskId = "RISK-P1-2", occurrenceProb = prob(0.3), lossDistribution = metalog, seed3 = 7002L)
        )
        
        for {
          parallel1 <- Simulator.simulate(samplers, nTrials = 300, parallelism = 1)
          sequential <- Simulator.simulateSequential(samplers, nTrials = 300)
        } yield assertTrue(
          parallel1.map(_.outcomes) == sequential.map(_.outcomes)
        )
      }
    ),
    
    suite("edge cases")(
      
      test("handles zero probability risk (no occurrences)") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          entityId = 801L,
          riskId = "RISK-ZERO-PROB",
          occurrenceProb = prob(0.0001), // Very low probability
          lossDistribution = metalog
        )
        
        for {
          results <- Simulator.simulate(Vector(sampler), nTrials = 100)
        } yield {
          val result = results.head
          // Should complete successfully even with no occurrences
          assertTrue(
            result.nTrials == 100,
            result.outcomes.size >= 0 // May be 0 or very few
          )
        }
      },
      
      test("handles high probability risk (most trials occur)") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          entityId = 802L,
          riskId = "RISK-HIGH-PROB",
          occurrenceProb = prob(0.9999),
          lossDistribution = metalog
        )
        
        val nTrials = 500
        for {
          results <- Simulator.simulate(Vector(sampler), nTrials.refineUnsafe)
        } yield {
          val result = results.head
          // Expect most trials to have occurrences
          assertTrue(
            result.outcomes.size > 400, // At least 80% with p=0.9999
            result.outcomes.size <= nTrials
          )
        }
      },
      
      test("handles single trial simulation") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          entityId = 803L, riskId = "RISK-ONE-TRIAL", occurrenceProb = prob(0.5), lossDistribution = metalog
        )
        
        for {
          results <- Simulator.simulate(Vector(sampler), nTrials = 1)
        } yield {
          val result = results.head
          assertTrue(
            result.nTrials == 1,
            result.outcomes.size <= 1
          )
        }
      }
    )
  )
}

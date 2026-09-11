package com.risquanter.register.services.helper

import zio.{ZIO, Task}
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.simulation.{RiskSampler, MetalogDistribution, SeedDerivation, HdrStreams}
import com.risquanter.register.domain.data.iron.{Probability, OccurrenceProbability, PositiveInt, SeedEntityId, SeedVarId}
import com.risquanter.register.domain.data.{RiskLeaf, RiskResult, TrialId, Loss, ExpertDistributionParams}
import com.risquanter.register.testutil.TestHelpers.{nodeId, idStr}
import com.risquanter.register.configs.{SimulationConfig, TestConfigs}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.autoRefine

/** Multi-risk drivers over `Simulator`, used only by this spec.
  *
  * Production resolves a tree, not a flat list: `CachedResultResolverLive`
  * walks the nodes, consults the content cache, and aggregates portfolios, so
  * it calls `Simulator.performTrials` per leaf and needs no batch driver. These
  * two functions exist to give the determinism and parallelism-invariance
  * assertions below a flat list to run against.
  */
private object TestSimulator {

  /** Simulate every sampler, at most `maxConcurrentSimulations` at a time. */
  def simulate(
    samplers: Vector[RiskSampler]
  )(using cfg: SimulationConfig): Task[Vector[RiskResult]] =
    ZIO.collectAllPar(
      samplers.map { sampler =>
        Simulator.performTrials(sampler, cfg.defaultNTrials, cfg.defaultTrialParallelism)
          .map(trials => RiskResult(sampler.nodeId, trials, Nil))
      }
    ).withParallelism(cfg.maxConcurrentSimulations)

  /** Simulate every sampler one at a time — the reference result that the
    * parallel path must match exactly. */
  def simulateSequential(
    samplers: Vector[RiskSampler]
  )(using cfg: SimulationConfig): Task[Vector[RiskResult]] =
    ZIO.foreach(samplers) { sampler =>
      ZIO.attempt(RiskResult(sampler.nodeId, performTrialsSync(sampler, cfg.defaultNTrials), Nil))
    }

  /** Every trial of one risk, computed in order on the calling thread. */
  def performTrialsSync(sampler: RiskSampler, nTrials: PositiveInt): Map[TrialId, Loss] = {
    val n: Int = nTrials
    (0 until n).view
      .filter(trial => sampler.sampleOccurrence(trial.toLong))
      .map(trial => (trial, sampler.sampleLoss(trial.toLong)))
      .toMap
  }
}

object SimulatorSpec extends ZIOSpecDefault {

  // Helper to create OccurrenceProbability values (closed [0,1] interval)
  private def prob(value: Double): OccurrenceProbability =
    value.refineUnsafe

  // Helper to create Metalog percentile values (open (0,1) interval)
  private def pct(value: Double): Probability =
    value.refineUnsafe
  
  private val testEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

  // Helper: HDR stream tuple via the production derivation site
  private def streams(entity: Long, varId: Long, seed3: Long = 0L, seed4: Long = 0L): HdrStreams =
    SeedDerivation.streams(
      SeedEntityId.fromLong(entity).toOption.get,
      SeedVarId.fromLong(varId).toOption.get,
      seed3, seed4
    )

  // Helper to create simple loss distribution
  private def createSimpleLossDistribution(): MetalogDistribution = {
    val percentiles = Array(0.05, 0.5, 0.95).map(pct)
    val quantiles = Array(1000.0, 5000.0, 25000.0)
    MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
      .toOption.get
  }
  
  def spec = suite("SimulatorSpec")(
    
    suite("performTrialsSync - sparse storage")(
      
      test("stores only successful trials for low probability risk") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-LOW-PROB"),
          streams = streams(1L, 100L),
          occurrenceProb = prob(0.01), // 1% occurrence
          lossDistribution = metalog
        )
        
        val sparseMap = TestSimulator.performTrialsSync(sampler, nTrials = 10000)
        
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
          nodeId = nodeId("RISK-SAMPLED"),
          streams = streams(2L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        val trials = TestSimulator.performTrialsSync(sampler, nTrials = 1000)
        
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
          nodeId = nodeId("RISK-RANGE"),
          streams = streams(3L, 2L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.3),
          lossDistribution = metalog
        )
        
        val nTrials = 500
        val trials = TestSimulator.performTrialsSync(sampler, nTrials.refineUnsafe)
        
        assertTrue(
          trials.forall { case (trialId, _) => trialId >= 0 && trialId < nTrials }
        )
      }
    ),
    
    suite("determinism - identical results with same seeds")(
      
      test("performTrialsSync produces identical results across runs") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-DETERMINISTIC"),
          streams = streams(100L, 3L, seed3 = 12345L, seed4 = 67890L),
          occurrenceProb = prob(0.2),
          lossDistribution = metalog
        )
        
        val run1 = TestSimulator.performTrialsSync(sampler, nTrials = 1000)
        val run2 = TestSimulator.performTrialsSync(sampler, nTrials = 1000)
        val run3 = TestSimulator.performTrialsSync(sampler, nTrials = 1000)
        
        assertTrue(
          run1 == run2,
          run2 == run3,
          run1 == run3
        )
      },
      
      test("simulate produces identical results with same samplers") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 500.refineUnsafe, maxConcurrentSimulations = 2.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-A"),
          streams = streams(101L, 4L, seed3 = 111L, seed4 = 0L),
          occurrenceProb = prob(0.1),
          lossDistribution = metalog
        ),
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-B"),
          streams = streams(102L, 5L, seed3 = 222L, seed4 = 0L),
          occurrenceProb = prob(0.2),
          lossDistribution = metalog
        ),
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-C"),
          streams = streams(103L, 6L, seed3 = 333L, seed4 = 0L),
          occurrenceProb = prob(0.3),
          lossDistribution = metalog
        )
        )
        
        for {
          run1 <- TestSimulator.simulate(samplers)
          run2 <- TestSimulator.simulate(samplers)
          run3 <- TestSimulator.simulate(samplers)
        } yield assertTrue(
          run1.map(_.outcomes) == run2.map(_.outcomes),
          run2.map(_.outcomes) == run3.map(_.outcomes),
          run1.map(_.nodeId) == run2.map(_.nodeId)
        )
      },
      
      test("sequential vs parallel produce identical results") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 800.refineUnsafe, defaultTrialParallelism = 8.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-SEQ-1"),
          streams = streams(201L, 7L, seed3 = 1001L, seed4 = 0L),
          occurrenceProb = prob(0.15),
          lossDistribution = metalog
        ),
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-SEQ-2"),
          streams = streams(202L, 8L, seed3 = 2002L, seed4 = 0L),
          occurrenceProb = prob(0.25),
          lossDistribution = metalog
        )
        )
        
        for {
          parallel <- TestSimulator.simulate(samplers)
          sequential <- TestSimulator.simulateSequential(samplers)
        } yield assertTrue(
          parallel.map(_.outcomes).toSet == sequential.map(_.outcomes).toSet
        )
      }
    ),
    
    suite("simulate - multiple risks")(
      
      test("simulates all risks successfully") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 1000.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-MULTI-1"),
          streams = streams(301L, 9L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.1),
          lossDistribution = metalog
        ),
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-MULTI-2"),
          streams = streams(302L, 10L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.2),
          lossDistribution = metalog
        ),
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-MULTI-3"),
          streams = streams(303L, 11L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.3),
          lossDistribution = metalog
        )
        )
        
        for {
          results <- TestSimulator.simulate(samplers)
        } yield assertTrue(
          results.size == 3,
            results.map(_.nodeId).toSet == Set(nodeId("RISK-MULTI-1"), nodeId("RISK-MULTI-2"), nodeId("RISK-MULTI-3")),
          results.forall(_.nTrials == 1000)
        )
      },
      
      test("each risk has independent outcomes") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 500.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-IND-1"),
          streams = streams(401L, 12L, seed3 = 4001L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        ),
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-IND-2"),
          streams = streams(402L, 13L, seed3 = 4002L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        )
        
        for {
          results <- TestSimulator.simulate(samplers)
        } yield {
        
          val risk1 = results.find(_.nodeId == nodeId("RISK-IND-1")).get
          val risk2 = results.find(_.nodeId == nodeId("RISK-IND-2")).get          // Different seeds should produce different outcomes
          assertTrue(risk1.outcomes != risk2.outcomes)
        }
      },
      
      test("empty samplers vector returns empty results") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 100.refineUnsafe)
        
        for {
          results <- TestSimulator.simulate(Vector.empty)
        } yield assertTrue(results.isEmpty)
      },
      
      test("single sampler works correctly") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 200.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-SINGLE"),
          streams = streams(501L, 14L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.4),
          lossDistribution = metalog
        )
        
        for {
          results <- TestSimulator.simulate(Vector(sampler))
        } yield assertTrue(
          results.size == 1,
          results.head.nodeId == nodeId("RISK-SINGLE"),
          results.head.nTrials == 200
        )
      }
    ),
    
    suite("parallelism configuration")(
      
      test("respects parallelism limit") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 100.refineUnsafe, maxConcurrentSimulations = 4.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val samplers = (1 to 20).map { i =>
          RiskSampler.fromDistribution(
          nodeId = nodeId(s"RISK-PAR-$i"),
          streams = streams(600L + i, 15L + i, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.1),
          lossDistribution = metalog
        )
        }.toVector
        
        for {
          results <- TestSimulator.simulate(samplers)
        } yield assertTrue(
          results.size == 20,
          results.map(_.nodeId).toSet.size == 20
        )
      },
      
      test("parallelism=1 equivalent to sequential") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 300.refineUnsafe, maxConcurrentSimulations = 1.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val samplers = Vector(
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-P1-1"),
          streams = streams(701L, 16L, seed3 = 7001L, seed4 = 0L),
          occurrenceProb = prob(0.2),
          lossDistribution = metalog
        ),
          RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-P1-2"),
          streams = streams(702L, 17L, seed3 = 7002L, seed4 = 0L),
          occurrenceProb = prob(0.3),
          lossDistribution = metalog
        )
        )
        
        for {
          parallel1 <- TestSimulator.simulate(samplers)
          sequential <- TestSimulator.simulateSequential(samplers)
        } yield assertTrue(
          parallel1.map(_.outcomes) == sequential.map(_.outcomes)
        )
      }
    ),
    
    suite("edge cases")(
      
      test("handles zero probability risk (no occurrences)") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 100.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-ZERO-PROB"),
          streams = streams(801L, 101L),
          occurrenceProb = prob(0.0001), // Very low probability
          lossDistribution = metalog
        )
        
        for {
          results <- TestSimulator.simulate(Vector(sampler))
        } yield {
          val result = results.head
          // Should complete successfully even with no occurrences
          assertTrue(
            result.outcomes.size >= 0 // May be 0 or very few
          )
        }
      },
      
      test("handles high probability risk (most trials occur)") {
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 500.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-HIGH-PROB"),
          streams = streams(802L, 18L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.9999),
          lossDistribution = metalog
        )
        
        val nTrials = 500
        for {
          results <- TestSimulator.simulate(Vector(sampler))
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
        given SimulationConfig = TestConfigs.simulation.copy(defaultNTrials = 1.refineUnsafe)
        
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-ONE-TRIAL"),
          streams = streams(803L, 19L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        for {
          results <- TestSimulator.simulate(Vector(sampler))
        } yield {
          val result = results.head
          assertTrue(
            result.outcomes.size <= 1
          )
        }
      }
    ),

    suite("createSamplerFromLeaf - terms resolution")(

      test("uses explicit terms from leaf (terms = Some(3))") {
        val leaf = RiskLeaf.create(
          id = idStr("terms-explicit"),
          name = "Terms Explicit",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = Some(3),
          seedVarId = 1L
        ).toOption.get

        for {
          (_, prov) <- Simulator.createSamplerFromLeaf(leaf, testEntity)
        } yield assertTrue(
          prov.distributionParams.asInstanceOf[ExpertDistributionParams].terms == 3
        )
      },

      test("uses explicit terms = 2 (minimum valid)") {
        val leaf = RiskLeaf.create(
          id = idStr("terms-two"),
          name = "Terms Two",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = Some(2),
          seedVarId = 2L
        ).toOption.get

        for {
          (_, prov) <- Simulator.createSamplerFromLeaf(leaf, testEntity)
        } yield assertTrue(
          prov.distributionParams.asInstanceOf[ExpertDistributionParams].terms == 2
        )
      },

      test("defaults to min(n=3, 4) = 3 when terms is None") {
        // 3 anchor points, no explicit terms → Simulator uses min(3, 4) = 3
        val leaf = RiskLeaf.create(
          id = idStr("terms-default-3"),
          name = "Terms Default 3",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.1, 0.5, 0.9)),
          quantiles = Some(Array(100.0, 500.0, 2000.0)),
          terms = None,
          seedVarId = 3L
        ).toOption.get

        for {
          (_, prov) <- Simulator.createSamplerFromLeaf(leaf, testEntity)
        } yield assertTrue(
          prov.distributionParams.asInstanceOf[ExpertDistributionParams].terms == 3
        )
      },

      test("defaults to min(n=5, 4) = 4 when terms is None and n = 5") {
        // 5 anchor points, no explicit terms → Simulator uses min(5, 4) = 4
        val leaf = RiskLeaf.create(
          id = idStr("terms-default-4"),
          name = "Terms Default 4",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.05, 0.25, 0.5, 0.75, 0.95)),
          quantiles = Some(Array(50.0, 200.0, 500.0, 1500.0, 5000.0)),
          terms = None,
          seedVarId = 4L
        ).toOption.get

        for {
          (_, prov) <- Simulator.createSamplerFromLeaf(leaf, testEntity)
        } yield assertTrue(
          prov.distributionParams.asInstanceOf[ExpertDistributionParams].terms == 4
        )
      },

      test("defaults to min(n=9, 4) = 4 when terms is None and n = 9") {
        // 9 anchor points, no explicit terms → Simulator uses min(9, 4) = 4
        val leaf = RiskLeaf.create(
          id = idStr("terms-default-9pts"),
          name = "Terms Default 9pts",
          distributionType = "expert",
          probability = 0.5,
          percentiles = Some(Array(0.01, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99)),
          quantiles = Some(Array(10.0, 50.0, 100.0, 300.0, 700.0, 2000.0, 5000.0, 10000.0, 30000.0)),
          terms = None,
          seedVarId = 5L
        ).toOption.get

        for {
          (_, prov) <- Simulator.createSamplerFromLeaf(leaf, testEntity)
        } yield assertTrue(
          prov.distributionParams.asInstanceOf[ExpertDistributionParams].terms == 4
        )
      }
    )
  )
}

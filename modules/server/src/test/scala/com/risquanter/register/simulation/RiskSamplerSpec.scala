package com.risquanter.register.simulation

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.iron.{Probability, OccurrenceProbability, SeedEntityId, SeedVarId}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.testutil.TestHelpers.nodeId
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

object RiskSamplerSpec extends ZIOSpecDefault {
  
  // Helper to create OccurrenceProbability from raw double (closed [0,1] interval)
  private def prob(value: Double): OccurrenceProbability =
    value.refineUnsafe
  
  // Helper to create Probability arrays from raw doubles (open (0,1) interval, for Metalog percentiles)
  private def probArray(values: Double*): Array[Probability] =
    values.toArray.map(_.refineUnsafe)
  
  // Helper: HDR stream tuple via the production derivation site
  private def streams(entity: Long, varId: Long, seed3: Long = 0L, seed4: Long = 0L): HdrStreams =
    SeedDerivation.streams(
      SeedEntityId.fromLong(entity).toOption.get,
      SeedVarId.fromLong(varId).toOption.get,
      seed3, seed4
    )

  // Helper to create a simple loss distribution
  private def createSimpleLossDistribution(): MetalogDistribution = {
    val percentiles = probArray(0.05, 0.5, 0.95)
    val quantiles = Array(1000.0, 5000.0, 25000.0)
    MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
      .toOption.get
  }
  
  // Helper to create a bounded loss distribution (always positive)
  private def createBoundedLossDistribution(): MetalogDistribution = {
    val percentiles = probArray(0.05, 0.5, 0.95)
    val quantiles = Array(1000.0, 5000.0, 25000.0)
    MetalogDistribution.fromPercentiles(
      percentiles, 
      quantiles, 
      terms = 3,
      lower = Some(0.0),
      upper = Some(100000.0)
    ).toOption.get
  }
  
  def spec = suite("RiskSamplerSpec")(
    suite("fromDistribution - basic functionality")(
      test("creates sampler with correct ID") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        assertTrue(sampler.nodeId == nodeId("RISK-001"))
      },
      test("sampleOccurrence returns Boolean") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val occurred = sampler.sampleOccurrence(0L)
        assertTrue(occurred == true || occurred == false)
      },
      test("sampleLoss returns positive values") {
        // Bounded fixture (lower = 0): positivity is a distribution property,
        // not a seed accident — the unbounded metalog can go negative in the
        // tails, which the old seeds merely never happened to hit at trial 0.
        val metalog = createBoundedLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val loss = sampler.sampleLoss(0L)
        assertTrue(loss > 0L)
      },
      test("sample returns Option[Long]") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val result = sampler.sample(0L)
        assertTrue(result.isEmpty || result.nonEmpty)
      }
    ),
    
    suite("occurrence probability correctness")(
      test("zero probability never occurs") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-ZERO"),
          streams = streams(1L, 10L),
          occurrenceProb = prob(0.0001),  // Very small but valid
          lossDistribution = metalog
        )
        val occurrences = (0L until 100L).count(sampler.sampleOccurrence)
        // With p=0.0001, expect 0-1 occurrences in 100 trials
        assertTrue(occurrences <= 5)
      },
      test("high probability occurs frequently") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-HIGH"),
          streams = streams(1L, 2L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.9999),
          lossDistribution = metalog
        )
        val occurrences = (0L until 100L).count(sampler.sampleOccurrence)
        // With p=0.9999, expect 99-100 occurrences
        assertTrue(occurrences >= 95)
      },
      
      test("occurrence rate approximates probability over many trials") {
        val metalog = createSimpleLossDistribution()
        val targetProb: OccurrenceProbability = 0.3.refineUnsafe
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-30PCT"),
          streams = streams(1L, 3L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = targetProb,
          lossDistribution = metalog
        )
        val trials = 1000L
        val occurrences = (0L until trials).count(sampler.sampleOccurrence)
        val observedRate = occurrences.toDouble / trials.toDouble
        // Should be within ~5% of target (binomial variance allows this)
        val lowerBound = 0.25
        val upperBound = 0.35
        assertTrue(observedRate >= lowerBound && observedRate <= upperBound)
      }
    ),
    
    suite("loss distribution correctness")(
      test("unbounded distribution can produce negative values at extremes") {
        val metalog = createSimpleLossDistribution()  // Unbounded
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-UNBOUNDED"),
          streams = streams(1L, 4L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        val losses = (0L until 100L).map(sampler.sampleLoss).map(_.toLong)
        
        // Unbounded Metalog can produce negative values at extreme probabilities
        // Just verify we get a distribution of values
        val uniqueLosses = losses.distinct.size
        val minLoss = losses.min
        val maxLoss = losses.max
        
        // Should have variety (not all the same value)
        assertTrue(uniqueLosses > 10) &&
        // And reasonable spread based on input percentiles (P05=1k, P50=5k, P95=25k)
        assertTrue(maxLoss > minLoss)
      },
      
      test("bounded distribution produces only positive values") {
        val metalog = createBoundedLossDistribution()  // Bounded [0, 100k]
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-BOUNDED"),
          streams = streams(1L, 5L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val losses = (0L until 100L).map(sampler.sampleLoss).map(_.toLong)
        // Bounded distribution should never produce negative values
        val allPositive = losses.forall(_ >= 0L)
        val withinBounds = losses.forall(l => l >= 0L && l <= 100000L)
        assertTrue(allPositive) &&
        assertTrue(withinBounds)
      },
      
      test("loss values are sampled from distribution") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val losses = (0L until 100L).map(sampler.sampleLoss).map(_.toLong)
        // Metalog is unbounded, so can produce negative values at extreme probabilities
        // Just verify we get a distribution of values
        val uniqueLosses = losses.distinct.size
        val minLoss = losses.min
        val maxLoss = losses.max
        // Should have variety (not all the same value)
        assertTrue(uniqueLosses > 10) &&
        // And reasonable spread based on input percentiles (P05=1k, P50=5k, P95=25k)
        assertTrue(maxLoss > minLoss)
      },
      
      test("loss distribution has expected percentile properties") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L),
          occurrenceProb = prob(0.9999),  // Almost always occurs
          lossDistribution = metalog
        )
        
        val losses = (0L until 1000L).map(sampler.sampleLoss).map(_.toLong).sorted
        
        val p50 = losses(500)
        val p95 = losses(950)
        
        // P50 should be around 5000, P95 around 25000
        assertTrue(math.abs(p50 - 5000L) < 2000L) &&
        assertTrue(math.abs(p95 - 25000L) < 5000L)
      }
    ),
    
    suite("determinism properties")(
      test("same inputs produce identical sequences") {
        val metalog = createSimpleLossDistribution()
        
        val sampler1 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-DET"),
          streams = streams(1L, 6L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        val sampler2 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-DET"),
          streams = streams(1L, 6L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        
        assertTrue(seq1 == seq2)
      },
      
      test("different entity IDs produce different sequences") {
        val metalog = createSimpleLossDistribution()
        val sampler1 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val sampler2 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(2L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        assertTrue(seq1 != seq2)
      },
      
      test("different seedVarIds produce different sequences") {
        val metalog = createSimpleLossDistribution()
        val sampler1 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val sampler2 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-002"),
          streams = streams(1L, 7L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        assertTrue(seq1 != seq2)
      },
      
      test("different seed3 produces different sequences") {
        val metalog = createSimpleLossDistribution()
        val sampler1 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 42L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val sampler2 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 43L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        assertTrue(seq1 != seq2)
      },
      
      test("different seed4 produces different sequences") {
        val metalog = createSimpleLossDistribution()
        val sampler1 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 100L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val sampler2 = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 200L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        assertTrue(seq1 != seq2)
      }
    ),
    
    suite("seed offset isolation")(
      test("occurrence and loss use independent RNG streams") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        // Sample occurrence and loss for same trial
        val occurred0 = sampler.sampleOccurrence(0L)
        val loss0 = sampler.sampleLoss(0L)
        val occurred1 = sampler.sampleOccurrence(1L)
        val loss1 = sampler.sampleLoss(1L)
        // They should vary independently (not perfectly correlated)
        // This is probabilistic, but with offsetted seeds they should differ
        val occurrences = (0L until 100L).map(sampler.sampleOccurrence)
        val losses = (0L until 100L).map(sampler.sampleLoss)
        // Check that not all occurrences map to same loss pattern
        val uniqueLosses = losses.distinct.size
        assertTrue(uniqueLosses > 50) // Should have variety
      }
    ),
    
    suite("sample method integration")(
      test("sample returns None when not occurred") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-NEVER"),
          streams = streams(1L, 8L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.0001),
          lossDistribution = metalog
        )
        
        val results = (0L until 100L).map(sampler.sample)
        val noneCount = results.count(_.isEmpty)
        
        // Should be mostly None with very low probability
        assertTrue(noneCount >= 95)
      },
      
      test("sample returns Some(loss) when occurred") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-ALWAYS"),
          streams = streams(1L, 9L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.9999),
          lossDistribution = metalog
        )
        
        val results = (0L until 100L).map(sampler.sample)
        val someCount = results.count(_.nonEmpty)
        
        // Should be mostly Some with very high probability
        assertTrue(someCount >= 95)
      },
      
      test("sample produces expected loss distribution") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L),
          occurrenceProb = prob(0.9999),  // Almost always occurs
          lossDistribution = metalog
        )
        
        val losses = (0L until 1000L).flatMap(sampler.sample).toVector.sorted(Ordering.Long)
        
        // Check percentiles match expected distribution
        val p50 = losses(500)
        assertTrue(math.abs(p50 - 5000L) < 2000L)
      }
    ),
    
    suite("edge cases")(
      test("handles trial counter = 0") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        val result = sampler.sample(0L)
        assertTrue(result.isEmpty || result.nonEmpty)
      },
      
      test("handles large trial counters") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(1L, 1L, seed3 = 0L, seed4 = 0L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        val result = sampler.sample(1000000L)
        assertTrue(result.isEmpty || result.nonEmpty)
      },
      
      test("handles boundary entity/var IDs (top of the HDR budget)") {
        // Negative/garbage seeds are unrepresentable since SeedEntityId/SeedVarId
        // (Iron-refined) — this test keeps the old test's spirit at the extreme
        // *legal* inputs instead.
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromDistribution(
          nodeId = nodeId("RISK-001"),
          streams = streams(99999999L, 49999999L),
          occurrenceProb = prob(0.5),
          lossDistribution = metalog
        )
        
        val result = sampler.sample(0L)
        assertTrue(result.isEmpty || result.nonEmpty)
      }
    )
  )
}

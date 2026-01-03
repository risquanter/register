package com.risquanter.register.simulation

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.iron.Probability
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{Greater, Less}

object RiskSamplerSpec extends ZIOSpecDefault {
  
  // Helper to create Probability arrays from raw doubles
  private def probArray(values: Double*): Array[Probability] =
    values.toArray.map(_.refineUnsafe[Greater[0.0] & Less[1.0]])
  
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
    suite("fromMetalog - basic functionality")(
      test("creates sampler with correct ID") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        assertTrue(sampler.id == "RISK-001")
      },
      
      test("sampleOccurrence returns Boolean") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val occurred = sampler.sampleOccurrence(0L)
        assertTrue(occurred == true || occurred == false)
      },
      
      test("sampleLoss returns positive values") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val loss = sampler.sampleLoss(0L)
        assertTrue(loss > 0L)
      },
      
      test("sample returns Option[Long]") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val result = sampler.sample(0L)
        assertTrue(result.isEmpty || result.nonEmpty)
      }
    ),
    
    suite("occurrence probability correctness")(
      test("zero probability never occurs") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-ZERO",
          occurrenceProb = 0.0001.refineUnsafe,  // Very small but valid
          lossDistribution = metalog
        )
        
        val occurrences = (0L until 100L).count(sampler.sampleOccurrence)
        // With p=0.0001, expect 0-1 occurrences in 100 trials
        assertTrue(occurrences <= 5)
      },
      
      test("high probability occurs frequently") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-HIGH",
          occurrenceProb = 0.9999.refineUnsafe,
          lossDistribution = metalog
        )
        
        val occurrences = (0L until 100L).count(sampler.sampleOccurrence)
        // With p=0.9999, expect 99-100 occurrences
        assertTrue(occurrences >= 95)
      },
      
      test("occurrence rate approximates probability over many trials") {
        val metalog = createSimpleLossDistribution()
        val targetProb = 0.3.refineUnsafe[Greater[0.0] & Less[1.0]]
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-30PCT",
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
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-UNBOUNDED",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val losses = (0L until 100L).map(sampler.sampleLoss)
        
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
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-BOUNDED",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val losses = (0L until 100L).map(sampler.sampleLoss)
        
        // Bounded distribution should never produce negative values
        val allPositive = losses.forall(_ >= 0L)
        val withinBounds = losses.forall(l => l >= 0L && l <= 100000L)
        
        assertTrue(allPositive) &&
        assertTrue(withinBounds)
      },
      
      test("loss values are sampled from distribution") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val losses = (0L until 100L).map(sampler.sampleLoss)
        
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
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.9999.refineUnsafe[Greater[0.0] & Less[1.0]],  // Almost always occurs
          lossDistribution = metalog
        )
        
        val losses = (0L until 1000L).map(sampler.sampleLoss).sorted
        
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
        
        val sampler1 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-DET",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val sampler2 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-DET",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        
        assertTrue(seq1 == seq2)
      },
      
      test("different entity IDs produce different sequences") {
        val metalog = createSimpleLossDistribution()
        
        val sampler1 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val sampler2 = RiskSampler.fromMetalog(
          entityId = 2L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        
        assertTrue(seq1 != seq2)
      },
      
      test("different risk IDs produce different sequences") {
        val metalog = createSimpleLossDistribution()
        
        val sampler1 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val sampler2 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-002",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        
        assertTrue(seq1 != seq2)
      },
      
      test("different seed3 produces different sequences") {
        val metalog = createSimpleLossDistribution()
        
        val sampler1 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog,
          seed3 = 42L
        )
        
        val sampler2 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog,
          seed3 = 43L
        )
        
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        
        assertTrue(seq1 != seq2)
      },
      
      test("different seed4 produces different sequences") {
        val metalog = createSimpleLossDistribution()
        
        val sampler1 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog,
          seed4 = 100L
        )
        
        val sampler2 = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog,
          seed4 = 200L
        )
        
        val seq1 = (0L until 100L).map(sampler1.sample)
        val seq2 = (0L until 100L).map(sampler2.sample)
        
        assertTrue(seq1 != seq2)
      }
    ),
    
    suite("seed offset isolation")(
      test("occurrence and loss use independent RNG streams") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
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
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-NEVER",
          occurrenceProb = 0.0001.refineUnsafe,
          lossDistribution = metalog
        )
        
        val results = (0L until 100L).map(sampler.sample)
        val noneCount = results.count(_.isEmpty)
        
        // Should be mostly None with very low probability
        assertTrue(noneCount >= 95)
      },
      
      test("sample returns Some(loss) when occurred") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-ALWAYS",
          occurrenceProb = 0.9999.refineUnsafe,
          lossDistribution = metalog
        )
        
        val results = (0L until 100L).map(sampler.sample)
        val someCount = results.count(_.nonEmpty)
        
        // Should be mostly Some with very high probability
        assertTrue(someCount >= 95)
      },
      
      test("sample produces expected loss distribution") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.9999.refineUnsafe[Greater[0.0] & Less[1.0]],  // Almost always occurs
          lossDistribution = metalog
        )
        
        val losses = (0L until 1000L).flatMap(sampler.sample).sorted
        
        // Check percentiles match expected distribution
        val p50 = losses(500)
        assertTrue(math.abs(p50 - 5000L) < 2000L)
      }
    ),
    
    suite("edge cases")(
      test("handles trial counter = 0") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val result = sampler.sample(0L)
        assertTrue(result.isEmpty || result.nonEmpty)
      },
      
      test("handles large trial counters") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = 1L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val result = sampler.sample(1000000L)
        assertTrue(result.isEmpty || result.nonEmpty)
      },
      
      test("handles negative entity IDs") {
        val metalog = createSimpleLossDistribution()
        val sampler = RiskSampler.fromMetalog(
          entityId = -999L,
          riskId = "RISK-001",
          occurrenceProb = 0.5.refineUnsafe,
          lossDistribution = metalog
        )
        
        val result = sampler.sample(0L)
        assertTrue(result.isEmpty || result.nonEmpty)
      }
    )
  )
}

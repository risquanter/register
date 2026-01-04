package com.risquanter.register.simulation

import zio.test.*
import com.risquanter.register.domain.data.iron.Probability
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{Greater, Less}

/** Mathematical correctness tests for distribution implementations
  * 
  * Incremental test plan:
  * 
  * Phase 1: Metalog (Expert Opinion Mode) - CURRENT
  *   ✓ Basic 3-point fit compiles and succeeds
  *   ✓ Interpolation accuracy at input percentiles < 1%
  *   ✓ Monotonicity property holds
  * 
  * Phase 2: Distribution Trait (TODO)
  *   - Create Distribution trait with sample() and quantile()
  *   - MetalogDistribution extends Distribution
  *   - Tests still pass after refactor
  * 
  * Phase 3: Lognormal Wrapper (TODO)
  *   - Create LognormalDistribution wrapper around Apache Commons Math
  *   - Implements Distribution trait
  *   - Basic construction test (compiles, doesn't throw)
  * 
  * Phase 4: Lognormal Math Validation (TODO)
  *   - Verify BCG formula: meanLog, varLog calculation
  *   - Test P10/P90 match input bounds (determine if 80% or 90% CI)
  *   - Verify lognormal properties (right-skewed, geometric mean)
  * 
  * Phase 5: RiskSampler Integration (TODO)
  *   - Update RiskSampler to accept Distribution trait
  *   - Test with both Metalog and Lognormal
  *   - Verify sampling produces expected quantiles
  */
object DistributionMathSpec extends ZIOSpecDefault {
  
  // Helper to create Probability arrays from raw doubles
  private def probArray(values: Double*): Array[Probability] =
    values.toArray.map(_.refineUnsafe[Greater[0.0] & Less[1.0]])
  
  def spec = suite("DistributionMathSpec")(
    
    suite("Phase 1: Metalog (Expert Opinion)")(
      
      test("basic 3-point fit succeeds") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isRight)
      },
      
      test("interpolation - fitted quantiles match input within 1%") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(1000.0, 5000.0, 20000.0)
        
        MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3) match {
          case Right(metalog) =>
            val p10 = metalog.quantile(0.1)
            val p50 = metalog.quantile(0.5)
            val p90 = metalog.quantile(0.9)
            
            val error10 = math.abs(p10 - 1000.0) / 1000.0
            val error50 = math.abs(p50 - 5000.0) / 5000.0
            val error90 = math.abs(p90 - 20000.0) / 20000.0
            
            assertTrue(error10 < 0.01 && error50 < 0.01 && error90 < 0.01)
          case Left(_) =>
            assertTrue(false)
        }
      },
      
      test("monotonicity - quantile function increases") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(1000.0, 5000.0, 20000.0)
        
        MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3) match {
          case Right(metalog) =>
            val q1 = metalog.quantile(0.25)
            val q2 = metalog.quantile(0.50)
            val q3 = metalog.quantile(0.75)
            
            assertTrue(q1 <= q2 && q2 <= q3)
          case Left(_) =>
            assertTrue(false)
        }
      }
    ),
    
    suite("Phase 3: Lognormal Wrapper")(
      
      test("constructs from valid parameters") {
        val meanLog = 8.0
        val stdLog = 1.5
        
        val dist = LognormalDistribution(meanLog, stdLog)
        
        assertTrue(dist != null)
      },
      
      test("constructs from confidence interval bounds") {
        val lowerBound = 1000L
        val upperBound = 50000L
        
        val result = LognormalDistribution.fromConfidenceInterval(lowerBound, upperBound)
        
        assertTrue(result.isRight)
      },
      
      test("quantile returns positive values") {
        val dist = LognormalDistribution(meanLog = 8.0, stdLog = 1.5)
        
        val q10 = dist.quantile(0.10)
        val q50 = dist.quantile(0.50)
        val q90 = dist.quantile(0.90)
        
        assertTrue(q10 > 0 && q50 > 0 && q90 > 0)
      }
    ),
    
    suite("Phase 4: Lognormal Math Validation")(
      
      test("BCG formula - P05 matches lower bound") {
        val lowerBound = 1000L
        val upperBound = 50000L
        
        LognormalDistribution.fromConfidenceInterval(lowerBound, upperBound) match {
          case Right(dist) =>
            val p05 = dist.quantile(0.05)
            val error = math.abs(p05 - lowerBound) / lowerBound
            
            // Should match within 1% since we derived parameters from these bounds
            assertTrue(error < 0.01)
          case Left(_) =>
            assertTrue(false)
        }
      },
      
      test("BCG formula - P95 matches upper bound") {
        val lowerBound = 1000L
        val upperBound = 50000L
        
        LognormalDistribution.fromConfidenceInterval(lowerBound, upperBound) match {
          case Right(dist) =>
            val p95 = dist.quantile(0.95)
            val error = math.abs(p95 - upperBound) / upperBound
            
            // Should match within 1% since we derived parameters from these bounds
            assertTrue(error < 0.01)
          case Left(_) =>
            assertTrue(false)
        }
      },
      
      test("lognormal is right-skewed") {
        val dist = LognormalDistribution(meanLog = 8.0, stdLog = 1.5)
        
        val p10 = dist.quantile(0.10)
        val p50 = dist.quantile(0.50)
        val p90 = dist.quantile(0.90)
        
        val lowerSpread = p50 - p10
        val upperSpread = p90 - p50
        
        // Right-skewed: upper tail is longer
        assertTrue(upperSpread > lowerSpread)
      },
      
      test("median equals exp(meanLog)") {
        val meanLog = 8.0
        val dist = LognormalDistribution(meanLog = meanLog, stdLog = 1.5)
        
        val median = dist.quantile(0.50)
        val expected = math.exp(meanLog)
        val error = math.abs(median - expected) / expected
        
        // For lognormal, median = exp(μ) exactly
        assertTrue(error < 0.001)
      }
    )
    
    // Phase 5: RiskSampler Integration - TODO
  )
}

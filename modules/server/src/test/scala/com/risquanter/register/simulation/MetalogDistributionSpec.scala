package com.risquanter.register.simulation

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.iron.Probability
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{Greater, Less}

object MetalogDistributionSpec extends ZIOSpecDefault {
  
  // Helper to create Probability arrays from raw doubles for testing
  private def probArray(values: Double*): Array[Probability] =
    values.toArray.map(_.refineUnsafe[Greater[0.0] & Less[1.0]])
  
  def spec = suite("MetalogDistributionSpec")(
    suite("fromPercentiles - valid inputs")(
      test("successfully fits simple 3-point distribution") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isRight) &&
        assertTrue {
          result.exists { metalog =>
            // Verify quantiles approximately match input
            val p05 = metalog.quantile(0.05)
            val p50 = metalog.quantile(0.5)
            val p95 = metalog.quantile(0.95)
            
            math.abs(p05 - 1000.0) < 100.0 &&
            math.abs(p50 - 5000.0) < 100.0 &&
            math.abs(p95 - 25000.0) < 100.0
          }
        }
      },
      
      test("successfully fits unbounded distribution") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(10.0, 50.0, 200.0)
        
        val result = MetalogDistribution.fromPercentiles(
          percentiles, 
          quantiles, 
          terms = 3,
          lower = None,
          upper = None
        )
        
        assertTrue(result.isRight)
      },
      
      test("successfully fits bounded distribution") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(10.0, 50.0, 90.0)
        
        val result = MetalogDistribution.fromPercentiles(
          percentiles,
          quantiles,
          terms = 3,
          lower = Some(0.0),
          upper = Some(100.0)
        )
        
        assertTrue(result.isRight)
      },
      
      test("successfully fits semi-bounded (lower only) distribution") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(10.0, 50.0, 200.0)
        
        val result = MetalogDistribution.fromPercentiles(
          percentiles,
          quantiles,
          terms = 3,
          lower = Some(0.0),
          upper = None
        )
        
        assertTrue(result.isRight)
      },
      
      test("successfully fits semi-bounded (upper only) distribution") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(10.0, 50.0, 90.0)
        
        val result = MetalogDistribution.fromPercentiles(
          percentiles,
          quantiles,
          terms = 3,
          lower = None,
          upper = Some(100.0)
        )
        
        assertTrue(result.isRight)
      },
      
      test("handles 9-term fit") {
        val percentiles = probArray(0.01, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99)
        val quantiles = Array(100.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 20000.0, 30000.0, 50000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 9)
        
        assertTrue(result.isRight)
      }
    ),
    
    suite("fromPercentiles - defensive validation")(
      test("rejects empty percentiles array") {
        val percentiles = Array.empty[Probability]
        val quantiles = Array.empty[Double]
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isLeft) &&
        assertTrue(result.fold(err => err.message.contains("empty"), _ => false))
      },
      
      test("rejects unsorted percentiles") {
        val percentiles = probArray(0.5, 0.1, 0.9)  // Not sorted!
        val quantiles = Array(5000.0, 1000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isLeft) &&
        assertTrue(result.fold(err => err.message.contains("sorted"), _ => false))
      },
      
      test("rejects mismatched array lengths") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0)  // Only 2 elements!
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isLeft) &&
        assertTrue(result.fold(err => err.message.contains("same length"), _ => false))
      },
      
      test("rejects terms exceeding data points") {
        val percentiles = probArray(0.05, 0.5, 0.95)  // 3 points
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 5)  // Terms > 3!
        
        assertTrue(result.isLeft) &&
        assertTrue(result.fold(err => err.message.contains("exceed"), _ => false))
      },
      
      test("rejects invalid bounds (lower >= upper)") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(10.0, 50.0, 90.0)
        
        val result = MetalogDistribution.fromPercentiles(
          percentiles,
          quantiles,
          terms = 3,
          lower = Some(100.0),
          upper = Some(50.0)  // Lower > upper!
        )
        
        assertTrue(result.isLeft) &&
        assertTrue(result.fold(err => err.message.contains("Lower bound"), _ => false))
      },
      
      test("rejects invalid bounds (lower == upper)") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(10.0, 50.0, 90.0)
        
        val result = MetalogDistribution.fromPercentiles(
          percentiles,
          quantiles,
          terms = 3,
          lower = Some(50.0),
          upper = Some(50.0)  // Equal bounds!
        )
        
        assertTrue(result.isLeft)
      }
    ),
    
    suite("fromPercentilesUnsafe - raw double validation")(
      test("successfully converts valid doubles to Probability") {
        val percentiles = Array(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentilesUnsafe(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isRight)
      },
      
      test("rejects percentiles < 0") {
        val percentiles = Array(-0.1, 0.5, 0.95)  // Negative!
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentilesUnsafe(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isLeft) &&
        assertTrue(result.fold(err => err.message.contains("(0.0, 1.0)"), _ => false))
      },
      
      test("rejects percentiles > 1") {
        val percentiles = Array(0.05, 0.5, 1.5)  // Greater than 1!
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentilesUnsafe(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isLeft) &&
        assertTrue(result.fold(err => err.message.contains("(0.0, 1.0)"), _ => false))
      },
      
      test("rejects non-positive terms") {
        val percentiles = Array(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentilesUnsafe(percentiles, quantiles, terms = 0)
        
        assertTrue(result.isLeft) &&
        assertTrue(result.fold(err => err.message.contains("positive"), _ => false))
      }
    ),
    
    suite("quantile and sample methods")(
      test("quantile returns monotonic increasing values") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isRight) &&
        assertTrue {
          result.exists { metalog =>
            val q10 = metalog.quantile(0.1)
            val q50 = metalog.quantile(0.5)
            val q90 = metalog.quantile(0.9)
            
            q10 < q50 && q50 < q90
          }
        }
      },
      
      test("sample is equivalent to quantile") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isRight) &&
        assertTrue {
          result.exists { metalog =>
            val uniform = 0.75
            metalog.sample(uniform) == metalog.quantile(uniform)
          }
        }
      },
      
      test("quantile handles near-edge probabilities") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(result.isRight) &&
        assertTrue {
          result.exists { metalog =>
            val q01 = metalog.quantile(0.01)
            val q99 = metalog.quantile(0.99)
            
            q01 < q99  // Should still be monotonic at near-edge values
          }
        }
      }
    ),
    
    suite("integration with HDRWrapper")(
      test("can sample using HDR-generated uniform values") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val metalogResult = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3)
        
        assertTrue(metalogResult.isRight) &&
        assertTrue {
          metalogResult.exists { metalog =>
            val rng = HDRWrapper.createGenerator(entityId = 1L, varId = 100L)
            
            // Sample 100 values using HDR
            val samples = (0L until 100L).map { trial =>
              val uniform = rng(trial)
              metalog.sample(uniform)
            }
            
            // All samples should be reasonable
            samples.forall(s => s >= 0.0 && s < 100000.0)
          }
        }
      }
    )
  )
}

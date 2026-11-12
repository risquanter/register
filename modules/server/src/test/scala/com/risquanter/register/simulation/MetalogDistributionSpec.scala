package com.risquanter.register.simulation

import zio.test.*
import com.risquanter.register.domain.data.iron.{OpenProbability, OpenProbabilityConstraint}
import io.github.iltotore.iron.*

object MetalogDistributionSpec extends ZIOSpecDefault {

  // Helper to create OpenProbability arrays from raw doubles for testing
  private def probArray(values: Double*): Array[OpenProbability] =
    values.toArray.map(_.refineUnsafe[OpenProbabilityConstraint])

  /** Whether the wrapper's own checks refused the inputs with a reason naming `text`. */
  private def refusedFor(result: Either[MetalogFitRefusal, MetalogDistribution], text: String): Boolean =
    result match
      case Left(MetalogFitRefusal.InputsRefused(reasons)) => reasons.exists(_.contains(text))
      case _                                              => false

  def spec = suite("MetalogDistributionSpec")(
    suite("fromPercentiles - valid inputs")(
      test("successfully fits simple 3-point distribution") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
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
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 9, lower = None, upper = None)
        
        assertTrue(result.isRight)
      },
      
      test("boundary percentiles 0.0 and 1.0 are rejected by QPFitter") {
        // The underlying QPFitter requires exclusive (0,1) bounds
        // This test documents this constraint for future reference
        val percentiles = probArray(0.05, 0.5, 0.95)  // Valid exclusive range
        val quantiles = Array(10.0, 50.0, 90.0)
        
        val result = MetalogDistribution.fromPercentiles(
          percentiles,
          quantiles,
          terms = 3,
          lower = Some(0.0),
          upper = Some(100.0)
        )
        
        assertTrue(result.isRight)
      }
    ),
    
    suite("fromPercentiles - zero quantile in lower-bounded mode")(
      // Lower-bounded mode applies Keelin's transform z = ln(x - L). With
      // L = 0.0 that is ln(x), so a quantile of exactly 0 has no finite
      // transform. The fitter refuses a quantile on its bound as malformed
      // input, which is returned as its own case. An anchor's `PositiveDouble`
      // loss excludes a zero quantile before any fit, so a caller fitting a
      // shape treats this case as a defect. This covers the library edge directly.
      test("a zero quantile is the fitter's malformed-input refusal") {
        val percentiles = probArray(0.1, 0.5, 0.9)
        val quantiles = Array(0.0, 50.0, 200.0)

        val result = MetalogDistribution.fromPercentiles(
          percentiles,
          quantiles,
          terms = 3,
          lower = Some(0.0),
          upper = None
        )

        assertTrue(result match
          case Left(MetalogFitRefusal.InputsMalformed(_)) => true
          case _                                          => false)
      }
    ),

    suite("fromPercentiles - defensive validation")(
      test("rejects empty percentiles array") {
        val percentiles = Array.empty[OpenProbability]
        val quantiles = Array.empty[Double]
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
        assertTrue(result.isLeft) &&
        assertTrue(refusedFor(result,"empty"))
      },
      
      test("rejects unsorted percentiles") {
        val percentiles = probArray(0.5, 0.1, 0.9)  // Not sorted!
        val quantiles = Array(5000.0, 1000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
        assertTrue(result.isLeft) &&
        assertTrue(refusedFor(result,"sorted"))
      },
      
      test("rejects mismatched array lengths") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0)  // Only 2 elements!
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
        assertTrue(result.isLeft) &&
        assertTrue(refusedFor(result,"same length"))
      },
      
      test("rejects terms exceeding data points") {
        val percentiles = probArray(0.05, 0.5, 0.95)  // 3 points
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 5, lower = None, upper = None)  // Terms > 3!
        
        assertTrue(result.isLeft) &&
        assertTrue(refusedFor(result,"exceed"))
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
        assertTrue(refusedFor(result,"Lower bound"))
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
      test("successfully converts valid doubles to OpenProbability") {
        val percentiles = Array(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentilesUnsafe(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
        assertTrue(result.isRight)
      },
      
      test("rejects percentiles < 0") {
        val percentiles = Array(-0.1, 0.5, 0.95)  // Negative!
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentilesUnsafe(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
        assertTrue(result.isLeft) &&
        assertTrue(refusedFor(result,"(0.0, 1.0)"))
      },
      
      test("rejects percentiles > 1") {
        val percentiles = Array(0.05, 0.5, 1.5)  // Greater than 1!
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentilesUnsafe(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
        assertTrue(result.isLeft) &&
        assertTrue(refusedFor(result,"(0.0, 1.0)"))
      },
      
      test("rejects non-positive terms") {
        val percentiles = Array(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentilesUnsafe(percentiles, quantiles, terms = 0, lower = None, upper = None)
        
        assertTrue(result.isLeft) &&
        assertTrue(refusedFor(result,"positive"))
      }
    ),
    
    suite("quantile and sample methods")(
      test("quantile returns monotonic increasing values") {
        val percentiles = probArray(0.05, 0.5, 0.95)
        val quantiles = Array(1000.0, 5000.0, 25000.0)
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
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
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
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
        
        val result = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
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
        
        val metalogResult = MetalogDistribution.fromPercentiles(percentiles, quantiles, terms = 3, lower = None, upper = None)
        
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

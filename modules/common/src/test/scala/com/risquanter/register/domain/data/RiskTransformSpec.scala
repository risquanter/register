package com.risquanter.register.domain.data

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Identity
import com.risquanter.register.domain.PreludeInstances.given
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.testutil.TestHelpers.{safeId, genSafeId}

/**
 * Property-based tests for RiskTransform Identity laws and mitigation strategies.
 * 
 * Verifies:
 * - Identity laws: associativity, left/right identity
 * - Mitigation strategies: deductible, cap, scaling, insurance policy
 * - Composition correctness: order matters for non-commutative operations
 */
object RiskTransformSpec extends ZIOSpecDefault {
  
  // ══════════════════════════════════════════════════════════════════
  // Generators
  // ══════════════════════════════════════════════════════════════════
  
  /** Generate RiskResult for testing transformations */
  val genRiskResult: Gen[Any, RiskResult] = for {
    name <- genSafeId
    nTrials <- Gen.int(100, 1000)
    numTrials <- Gen.int(5, 20)  // Smaller for readable test output
    trialIds <- Gen.listOfN(numTrials)(Gen.int(0, nTrials - 1))
    losses <- Gen.listOfN(numTrials)(Gen.long(1000L, 100000L))
  } yield RiskResult(name, trialIds.zip(losses).toMap, nTrials)
  
  /** Generate positive Loss values */
  val genLoss: Gen[Any, Loss] = Gen.long(100L, 50000L)
  
  /** Generate scale factors */
  val genScaleFactor: Gen[Any, Double] = Gen.double(0.1, 2.0)
  
  /** Generate simple RiskTransform (deductible, cap, or scale) */
  val genSimpleTransform: Gen[Any, RiskTransform] = Gen.oneOf(
    genLoss.map(RiskTransform.applyDeductible),
    genLoss.map(RiskTransform.capLosses),
    genScaleFactor.map(RiskTransform.scaleLosses)
  )
  
  // ══════════════════════════════════════════════════════════════════
  // Identity Law Tests
  // ══════════════════════════════════════════════════════════════════
  
  def spec = suite("RiskTransformSpec")(
    
    suite("Identity Laws - Property Tests")(
      test("associativity: combine(a, combine(b, c)) == combine(combine(a, b), c)") {
        check(genSimpleTransform, genSimpleTransform, genSimpleTransform, genRiskResult) { 
          (t1, t2, t3, result) =>
            val left = Identity[RiskTransform].combine(
              t1,
              Identity[RiskTransform].combine(t2, t3)
            )
            val right = Identity[RiskTransform].combine(
              Identity[RiskTransform].combine(t1, t2),
              t3
            )
            
            // Apply both to same input and compare results
            val leftResult = left.run(result)
            val rightResult = right.run(result)
            
            assertTrue(leftResult.outcomes == rightResult.outcomes)
        }
      },
      
      test("left identity: combine(identity, a) == a") {
        check(genSimpleTransform, genRiskResult) { (transform, result) =>
          val combined = Identity[RiskTransform].combine(
            Identity[RiskTransform].identity,
            transform
          )
          
          val directResult = transform.run(result)
          val combinedResult = combined.run(result)
          
          assertTrue(directResult.outcomes == combinedResult.outcomes)
        }
      },
      
      test("right identity: combine(a, identity) == a") {
        check(genSimpleTransform, genRiskResult) { (transform, result) =>
          val combined = Identity[RiskTransform].combine(
            transform,
            Identity[RiskTransform].identity
          )
          
          val directResult = transform.run(result)
          val combinedResult = combined.run(result)
          
          assertTrue(directResult.outcomes == combinedResult.outcomes)
        }
      },
      
      test("identity transformation leaves result unchanged") {
        check(genRiskResult) { result =>
          val transformed = RiskTransform.identityTransform.run(result)
          
          assertTrue(
            transformed.outcomes == result.outcomes,
            transformed.nTrials == result.nTrials,
            transformed.name == result.name
          )
        }
      }
    ),
    
    // ══════════════════════════════════════════════════════════════════
    // Mitigation Strategy Tests
    // ══════════════════════════════════════════════════════════════════
    
    suite("Deductible Transformation")(
      test("applyDeductible reduces losses by deductible amount") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 50000L, 2 -> 10000L, 3 -> 5000L),
          100
        )
        val transform = RiskTransform.applyDeductible(10000L)
        val mitigated = transform.run(result)
        
        assertTrue(
          mitigated.outcomeOf(1) == 40000L,  // 50000 - 10000
          mitigated.outcomeOf(2) == 0L,      // 10000 - 10000
          mitigated.outcomeOf(3) == 0L       // 5000 - 10000 -> 0
        )
      },
      
      test("deductible removes trials below threshold (sparse)") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 50000L, 2 -> 8000L),
          100
        )
        val transform = RiskTransform.applyDeductible(10000L)
        val mitigated = transform.run(result)
        
        assertTrue(
          mitigated.outcomes.size == 1,  // Only trial 1 remains
          mitigated.outcomes.contains(1),
          !mitigated.outcomes.contains(2)
        )
      },
      
      test("zero deductible is identity") {
        check(genRiskResult) { result =>
          val transform = RiskTransform.applyDeductible(0L)
          val mitigated = transform.run(result)
          
          assertTrue(mitigated.outcomes == result.outcomes)
        }
      }
    ),
    
    suite("Cap Transformation")(
      test("capLosses limits each trial to maximum") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 5000000L, 2 -> 500000L, 3 -> 100000L),
          100
        )
        val transform = RiskTransform.capLosses(1000000L)
        val capped = transform.run(result)
        
        assertTrue(
          capped.outcomeOf(1) == 1000000L,  // 5M capped to 1M
          capped.outcomeOf(2) == 500000L,   // 500K unchanged
          capped.outcomeOf(3) == 100000L    // 100K unchanged
        )
      },
      
      test("cap preserves all trials (even if modified)") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 2000000L, 2 -> 500000L),
          100
        )
        val transform = RiskTransform.capLosses(1000000L)
        val capped = transform.run(result)
        
        assertTrue(capped.outcomes.size == 2)
      },
      
      test("very high cap is identity") {
        check(genRiskResult) { result =>
          val maxLoss = if (result.outcomes.isEmpty) 0L 
                       else result.outcomes.values.max
          val transform = RiskTransform.capLosses(maxLoss * 10)
          val capped = transform.run(result)
          
          assertTrue(capped.outcomes == result.outcomes)
        }
      }
    ),
    
    suite("Scale Transformation")(
      test("scaleLosses multiplies each loss by factor") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 100000L, 2 -> 50000L),
          100
        )
        val transform = RiskTransform.scaleLosses(0.8)
        val scaled = transform.run(result)
        
        assertTrue(
          scaled.outcomeOf(1) == 80000L,   // 100000 * 0.8
          scaled.outcomeOf(2) == 40000L    // 50000 * 0.8
        )
      },
      
      test("scale by 1.0 is identity") {
        check(genRiskResult) { result =>
          val transform = RiskTransform.scaleLosses(1.0)
          val scaled = transform.run(result)
          
          assertTrue(scaled.outcomes == result.outcomes)
        }
      },
      
      test("scale by 0.0 removes all losses") {
        check(genRiskResult) { result =>
          val transform = RiskTransform.scaleLosses(0.0)
          val scaled = transform.run(result)
          
          assertTrue(scaled.outcomes.isEmpty)
        }
      },
      
      test("scale removes trials that round to zero") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 100L, 2 -> 50000L),
          100
        )
        val transform = RiskTransform.scaleLosses(0.001)  // 0.1%
        val scaled = transform.run(result)
        
        // 100 * 0.001 = 0.1 rounds to 0, removed
        // 50000 * 0.001 = 50
        assertTrue(
          !scaled.outcomes.contains(1),
          scaled.outcomeOf(2) == 50L
        )
      }
    ),
    
    suite("Insurance Policy (Combined)")(
      test("insurancePolicy applies deductible then cap") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 2000000L, 2 -> 50000L, 3 -> 5000L),
          100
        )
        val transform = RiskTransform.insurancePolicy(
          deductible = 10000L,
          cap = 1000000L
        )
        val insured = transform.run(result)
        
        assertTrue(
          insured.outcomeOf(1) == 1000000L,  // (2M - 10K) capped to 1M
          insured.outcomeOf(2) == 40000L,    // 50K - 10K = 40K
          insured.outcomeOf(3) == 0L         // 5K - 10K = 0 (removed)
        )
      },
      
      test("insurancePolicy validation: cap > deductible (property test)") {
        check(genLoss, genLoss) { (loss1, loss2) =>
          val deductible = Math.max(loss1, loss2)
          val cap = Math.min(loss1, loss2)
          
          // When cap <= deductible, should fail
          val result = ZIO.attempt(RiskTransform.insurancePolicy(deductible, cap))
          assertZIO(result.exit)(fails(isSubtype[IllegalArgumentException](anything)))
        }
      },
      
      test("insurancePolicy validation: negative deductible fails") {
        check(genLoss) { cap =>
          val result = ZIO.attempt(RiskTransform.insurancePolicy(-1000L, cap))
          assertZIO(result.exit)(fails(isSubtype[IllegalArgumentException](anything)))
        }
      },
      
      test("insurancePolicy succeeds when cap > deductible") {
        check(genLoss, Gen.long(1L, 10000L)) { (baseDeductible, gap) =>
          val deductible = baseDeductible
          val cap = baseDeductible + gap
          
          // Should succeed without throwing
          val transform = RiskTransform.insurancePolicy(deductible, cap)
          assertTrue(transform != null)
        }
      }
    ),
    
    suite("Composition Tests")(
      test("andThen applies transformations in sequence") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 100000L),
          100
        )
        
        val deductible = RiskTransform.applyDeductible(10000L)
        val scale = RiskTransform.scaleLosses(0.5)
        
        // Apply deductible (100K -> 90K), then scale (90K -> 45K)
        val combined = deductible.andThen(scale)
        val transformed = combined.run(result)
        
        assertTrue(transformed.outcomeOf(1) == 45000L)
      },
      
      test("compose applies transformations in reverse") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 100000L),
          100
        )
        
        val deductible = RiskTransform.applyDeductible(10000L)
        val scale = RiskTransform.scaleLosses(0.5)
        
        // Compose: scale first (100K -> 50K), then deductible (50K -> 40K)
        val combined = deductible.compose(scale)
        val transformed = combined.run(result)
        
        assertTrue(transformed.outcomeOf(1) == 40000L)
      },
      
      test("order matters for non-commutative operations") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 100000L),
          100
        )
        
        val deductible = RiskTransform.applyDeductible(10000L)
        val scale = RiskTransform.scaleLosses(0.5)
        
        // deductible THEN scale
        val order1 = Identity[RiskTransform].combine(deductible, scale)
        val result1 = order1.run(result).outcomeOf(1)
        
        // scale THEN deductible
        val order2 = Identity[RiskTransform].combine(scale, deductible)
        val result2 = order2.run(result).outcomeOf(1)
        
        assertTrue(
          result1 == 45000L,  // (100K - 10K) * 0.5
          result2 == 40000L,  // (100K * 0.5) - 10K
          result1 != result2  // Order matters!
        )
      }
    ),
    
    suite("Edge Cases")(
      test("transform on empty result is no-op") {
        val empty = RiskResult(safeId("empty"), Map.empty, 100)
        val transform = RiskTransform.applyDeductible(10000L)
        val transformed = transform.run(empty)
        
        assertTrue(transformed.outcomes.isEmpty)
      },
      
      test("filterBelowThreshold removes small losses") {
        val result = RiskResult(
          safeId("test"),
          Map(1 -> 100000L, 2 -> 500L, 3 -> 50000L, 4 -> 200L),
          100
        )
        val transform = RiskTransform.filterBelowThreshold(1000L)
        val filtered = transform.run(result)
        
        assertTrue(
          filtered.outcomes.size == 2,
          filtered.outcomes.contains(1),
          filtered.outcomes.contains(3),
          !filtered.outcomes.contains(2),
          !filtered.outcomes.contains(4)
        )
      }
    )
  )
}

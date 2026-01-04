package com.risquanter.register.domain

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Identity
import com.risquanter.register.domain.data.{RiskResult, Loss, TrialId}
import com.risquanter.register.domain.PreludeInstances.given

/**
 * Property-based tests for Identity (Monoid) laws using ZIO Test generators.
 * 
 * Verifies algebraic properties across random inputs:
 * - Associativity: (a ⊕ b) ⊕ c = a ⊕ (b ⊕ c)
 * - Left Identity: ∅ ⊕ a = a
 * - Right Identity: a ⊕ ∅ = a
 * - Commutativity (bonus for Loss): a ⊕ b = b ⊕ a
 * 
 * Tests use ZIO Test generators to generate hundreds of random examples,
 * providing much stronger confidence than manual test cases.
 */
object IdentityPropertySpec extends ZIOSpecDefault {
  
  // ══════════════════════════════════════════════════════════════════
  // ZIO Test Generators
  // ══════════════════════════════════════════════════════════════════
  
  /** Generate random Loss values (0 to 10M) */
  val genLoss: Gen[Any, Loss] = Gen.long(0L, 10000000L)
  
  /** Generate random TrialId values (0 to 10000) */
  val genTrialId: Gen[Any, TrialId] = Gen.int(0, 10000)
  
  /** Generate sparse outcome maps (0-50 trials with losses) */
  val genOutcomes: Gen[Any, Map[TrialId, Loss]] = for {
    numTrials <- Gen.int(0, 50)
    trialIds  <- Gen.listOfN(numTrials)(genTrialId)
    losses    <- Gen.listOfN(numTrials)(genLoss)
  } yield trialIds.zip(losses).toMap
  
  /** Generate random RiskResult with consistent nTrials */
  val genRiskResult: Gen[Any, RiskResult] = for {
    name      <- Gen.alphaNumericString.map(s => if (s.isEmpty) "risk" else s)
    outcomes  <- genOutcomes
    nTrials   <- Gen.int(100, 1000)
  } yield RiskResult(name, outcomes, nTrials)
  
  /** Generate RiskResult with specific nTrials (for combining) */
  def genRiskResultWithTrials(nTrials: Int): Gen[Any, RiskResult] = for {
    name     <- Gen.alphaNumericString.map(s => if (s.isEmpty) "risk" else s)
    outcomes <- genOutcomes
  } yield RiskResult(name, outcomes, nTrials)
  
  // ══════════════════════════════════════════════════════════════════
  // Property Tests for Loss Identity
  // ══════════════════════════════════════════════════════════════════
  
  def spec = suite("IdentityPropertySpec")(
    
    suite("Loss Identity - Property Tests")(
      test("associativity property: (a + b) + c = a + (b + c)") {
        check(genLoss, genLoss, genLoss) { (a, b, c) =>
          val left  = Identity[Loss].combine(Identity[Loss].combine(a, b), c)
          val right = Identity[Loss].combine(a, Identity[Loss].combine(b, c))
          
          assertTrue(left == right)
        }
      },
      
      test("left identity property: 0 + a = a") {
        check(genLoss) { a =>
          val identity = Identity[Loss].identity
          val combined = Identity[Loss].combine(identity, a)
          
          assertTrue(combined == a)
        }
      },
      
      test("right identity property: a + 0 = a") {
        check(genLoss) { a =>
          val identity = Identity[Loss].identity
          val combined = Identity[Loss].combine(a, identity)
          
          assertTrue(combined == a)
        }
      },
      
      test("commutativity property: a + b = b + a") {
        check(genLoss, genLoss) { (a, b) =>
          val ab = Identity[Loss].combine(a, b)
          val ba = Identity[Loss].combine(b, a)
          
          assertTrue(ab == ba)
        }
      },
      
      test("combining with self doubles the value") {
        check(genLoss) { a =>
          val doubled = Identity[Loss].combine(a, a)
          
          assertTrue(doubled == a + a)
        }
      }
    ),
    
    // ══════════════════════════════════════════════════════════════════
    // Property Tests for RiskResult Identity
    // ══════════════════════════════════════════════════════════════════
    
    suite("RiskResult Identity - Property Tests")(
      test("associativity property: (a ⊕ b) ⊕ c = a ⊕ (b ⊕ c)") {
        check(genRiskResult, genRiskResult, genRiskResult) { (r1, r2, r3) =>
          // Normalize to same nTrials for valid combining
          val nTrials = r1.nTrials
          val a = r1
          val b = r2.copy(nTrials = nTrials)
          val c = r3.copy(nTrials = nTrials)
          
          val left  = Identity[RiskResult].combine(Identity[RiskResult].combine(a, b), c)
          val right = Identity[RiskResult].combine(a, Identity[RiskResult].combine(b, c))
          
          // Compare outcomes (ignore name differences from merging)
          assertTrue(left.outcomes == right.outcomes)
        }
      },
      
      test("left identity property: ∅ ⊕ a = a") {
        check(genRiskResult) { a =>
          val identity = Identity[RiskResult].identity.copy(nTrials = a.nTrials)
          val combined = Identity[RiskResult].combine(identity, a)
          
          assertTrue(combined.outcomes == a.outcomes) &&
          assertTrue(combined.nTrials == a.nTrials)
        }
      },
      
      test("right identity property: a ⊕ ∅ = a") {
        check(genRiskResult) { a =>
          val identity = Identity[RiskResult].identity.copy(nTrials = a.nTrials)
          val combined = Identity[RiskResult].combine(a, identity)
          
          assertTrue(combined.outcomes == a.outcomes) &&
          assertTrue(combined.nTrials == a.nTrials)
        }
      },
      
      test("commutativity property: a ⊕ b = b ⊕ a") {
        check(genRiskResult, genRiskResult) { (r1, r2) =>
          // Normalize to same nTrials
          val a = r1
          val b = r2.copy(nTrials = r1.nTrials)
          
          val ab = Identity[RiskResult].combine(a, b)
          val ba = Identity[RiskResult].combine(b, a)
          
          assertTrue(ab.outcomes == ba.outcomes)
        }
      },
      
      test("outer join semantics: union of trial IDs") {
        check(genRiskResult, genRiskResult) { (r1, r2) =>
          val a = r1
          val b = r2.copy(nTrials = r1.nTrials)
          
          val combined = Identity[RiskResult].combine(a, b)
          val expectedTrials = a.trialIds() ++ b.trialIds()
          
          assertTrue(combined.trialIds() == expectedTrials)
        }
      },
      
      test("loss summation per trial") {
        check(genRiskResult, genRiskResult) { (r1, r2) =>
          val a = r1
          val b = r2.copy(nTrials = r1.nTrials)
          
          val combined = Identity[RiskResult].combine(a, b)
          
          // Verify each trial's loss is sum of individual losses
          val allTrials = a.trialIds() ++ b.trialIds()
          val lossesMatch = allTrials.forall { trial =>
            val expectedLoss = a.outcomeOf(trial) + b.outcomeOf(trial)
            combined.outcomeOf(trial) == expectedLoss
          }
          
          assertTrue(lossesMatch)
        }
      },
      
      test("combining with empty result is identity") {
        check(genRiskResult) { a =>
          val empty = RiskResult.empty("empty", a.nTrials)
          val combined = Identity[RiskResult].combine(a, empty)
          
          assertTrue(combined.outcomes == a.outcomes)
        }
      },
      
      test("combining result with itself doubles all losses") {
        check(genRiskResult) { a =>
          val combined = Identity[RiskResult].combine(a, a)
          
          // All trial losses should be doubled
          val allDoubled = a.trialIds().forall { trial =>
            combined.outcomeOf(trial) == a.outcomeOf(trial) * 2
          }
          
          assertTrue(allDoubled)
        }
      }
    ),
    
    // ══════════════════════════════════════════════════════════════════
    // Edge Case Properties
    // ══════════════════════════════════════════════════════════════════
    
    suite("Edge Case Properties")(
      test("combining multiple empty results remains empty") {
        check(Gen.int(100, 1000)) { nTrials =>
          val empty1 = RiskResult.empty("e1", nTrials)
          val empty2 = RiskResult.empty("e2", nTrials)
          val empty3 = RiskResult.empty("e3", nTrials)
          
          val combined = Identity[RiskResult].combine(
            Identity[RiskResult].combine(empty1, empty2),
            empty3
          )
          
          assertTrue(combined.outcomes.isEmpty)
        }
      },
      
      test("large loss values don't overflow with reasonable aggregation") {
        // Test with losses near but not exceeding safe limits
        val largeLoss = Long.MaxValue / 100  // Safe to combine ~100 of these
        val r1 = RiskResult("r1", Map(1 -> largeLoss), 100)
        val r2 = RiskResult("r2", Map(1 -> largeLoss), 100)
        
        val combined = Identity[RiskResult].combine(r1, r2)
        
        assertTrue(
          combined.outcomeOf(1) == largeLoss * 2,
          combined.outcomeOf(1) > 0  // No overflow to negative
        )
      },
      
      test("zero losses are preserved (not filtered out)") {
        val withZero = RiskResult("test", Map(1 -> 0L, 2 -> 1000L), 100)
        val empty = RiskResult.empty("empty", 100)
        
        val combined = Identity[RiskResult].combine(withZero, empty)
        
        // Zero losses should be preserved in sparse representation
        assertTrue(combined.outcomeOf(1) == 0L)
      }
    )
  )
}

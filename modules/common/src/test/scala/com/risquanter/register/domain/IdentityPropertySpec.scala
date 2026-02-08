package com.risquanter.register.domain

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Identity
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.{RiskResult, Loss, TrialId}
import com.risquanter.register.domain.data.RiskResultIdentityInstances.given
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.domain.PreludeInstances.given
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, genSafeId, genNodeId}
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

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
  
  /** Generate sparse outcome maps with trial IDs in valid range [0, nTrials) */
  def genOutcomes(nTrials: Int): Gen[Any, Map[TrialId, Loss]] = for {
    numTrials <- Gen.int(0, Math.min(50, nTrials))
    trialIds  <- Gen.listOfN(numTrials)(Gen.int(0, nTrials - 1))
    losses    <- Gen.listOfN(numTrials)(genLoss)
  } yield trialIds.zip(losses).toMap
  
  /** Generate random RiskResult with consistent nTrials */
  val genRiskResult: Gen[Any, RiskResult] = for {
    nid       <- genNodeId
    nTrials   <- Gen.int(100, 1000)
    outcomes  <- genOutcomes(nTrials)
  } yield withCfg(nTrials) {
    RiskResult(nid, outcomes, Nil)
  }
  
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
        check(Gen.int(100, 1000).flatMap { nTrials =>
          for {
            outcomes1 <- genOutcomes(nTrials)
            outcomes2 <- genOutcomes(nTrials)
            outcomes3 <- genOutcomes(nTrials)
          } yield (nTrials, outcomes1, outcomes2, outcomes3)
        }) { case (nTrials, outcomes1, outcomes2, outcomes3) =>
          val a = withCfg(nTrials) { RiskResult(nodeId("risk-a"), outcomes1.filter(_._1 < nTrials), Nil) }
          val b = withCfg(nTrials) { RiskResult(nodeId("risk-b"), outcomes2.filter(_._1 < nTrials), Nil) }
          val c = withCfg(nTrials) { RiskResult(nodeId("risk-c"), outcomes3.filter(_._1 < nTrials), Nil) }
          
          val left  = RiskResult.combine(RiskResult.combine(a, b), c)
          val right = RiskResult.combine(a, RiskResult.combine(b, c))
          
          // Compare outcomes (ignore name differences from merging)
          assertTrue(left.outcomes == right.outcomes)
        }
      },
      
      test("left identity property: ∅ ⊕ a = a") {
        check(genRiskResult) { a =>
          withCfg(a.nTrials) {
            val id = Identity[RiskResult].identity
            val combined = Identity[RiskResult].combine(id, a)
            
            assertTrue(combined.outcomes == a.outcomes) &&
            assertTrue(combined.nTrials == a.nTrials)
          }
        }
      },
      
      test("right identity property: a ⊕ ∅ = a") {
        check(genRiskResult) { a =>
          withCfg(a.nTrials) {
            val id = Identity[RiskResult].identity
            val combined = Identity[RiskResult].combine(a, id)
            
            assertTrue(combined.outcomes == a.outcomes) &&
            assertTrue(combined.nTrials == a.nTrials)
          }
        }
      },
      
      test("commutativity property: a ⊕ b = b ⊕ a") {
        check(Gen.int(100, 1000).flatMap { nTrials =>
          for {
            outcomes1 <- genOutcomes(nTrials)
            outcomes2 <- genOutcomes(nTrials)
          } yield (nTrials, outcomes1, outcomes2)
        }) { case (nTrials, outcomes1, outcomes2) =>
          val a = withCfg(nTrials) { RiskResult(nodeId("risk-a"), outcomes1.filter(_._1 < nTrials), Nil) }
          val b = withCfg(nTrials) { RiskResult(nodeId("risk-b"), outcomes2.filter(_._1 < nTrials), Nil) }
          
          val ab = RiskResult.combine(a, b)
          val ba = RiskResult.combine(b, a)
          
          assertTrue(ab.outcomes == ba.outcomes)
        }
      },
      
      test("outer join semantics: union of trial IDs") {
        check(Gen.int(100, 1000).flatMap { nTrials =>
          for {
            outcomes1 <- genOutcomes(nTrials)
            outcomes2 <- genOutcomes(nTrials)
          } yield (nTrials, outcomes1, outcomes2)
        }) { case (nTrials, outcomes1, outcomes2) =>
          val a = withCfg(nTrials) { RiskResult(nodeId("risk-a"), outcomes1.filter(_._1 < nTrials), Nil) }
          val b = withCfg(nTrials) { RiskResult(nodeId("risk-b"), outcomes2.filter(_._1 < nTrials), Nil) }
          
          val combined = RiskResult.combine(a, b)
          val expectedTrials = a.trialIds() ++ b.trialIds()
          
          assertTrue(combined.trialIds() == expectedTrials)
        }
      },
      
      test("loss summation per trial") {
        check(Gen.int(100, 1000).flatMap { nTrials =>
          for {
            outcomes1 <- genOutcomes(nTrials)
            outcomes2 <- genOutcomes(nTrials)
          } yield (nTrials, outcomes1, outcomes2)
        }) { case (nTrials, outcomes1, outcomes2) =>
          val a = withCfg(nTrials) { RiskResult(nodeId("risk-a"), outcomes1.filter(_._1 < nTrials), Nil) }
          val b = withCfg(nTrials) { RiskResult(nodeId("risk-b"), outcomes2.filter(_._1 < nTrials), Nil) }
          
          val combined = RiskResult.combine(a, b)
          
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
          val empty = withCfg(a.nTrials) { RiskResult.empty(nodeId("empty-risk")) }
          val combined = RiskResult.combine(a, empty)
          
          assertTrue(combined.outcomes == a.outcomes)
        }
      },
      
      test("combining result with itself doubles all losses") {
        check(genRiskResult) { a =>
          val combined = RiskResult.combine(a, a)
          
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
          val empty1 = withCfg(nTrials) { RiskResult.empty(nodeId("empty-1")) }
          val empty2 = withCfg(nTrials) { RiskResult.empty(nodeId("empty-2")) }
          val empty3 = withCfg(nTrials) { RiskResult.empty(nodeId("empty-3")) }

          withCfg(nTrials) {
            val combined = Identity[RiskResult].combine(
              Identity[RiskResult].combine(empty1, empty2),
              empty3
            )
            
            assertTrue(combined.outcomes.isEmpty)
          }
        }
      },
      
      test("large loss values don't overflow with reasonable aggregation") {
        // Test with losses near but not exceeding safe limits
        val largeLoss = Long.MaxValue / 100  // Safe to combine ~100 of these
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> largeLoss), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> largeLoss), Nil) }
        
        val combined = RiskResult.combine(r1, r2)
        
        assertTrue(
          combined.outcomeOf(1) == largeLoss * 2,
          combined.outcomeOf(1) > 0  // No overflow to negative
        )
      },
      
      test("zero losses are preserved (not filtered out)") {
        val withZero = withCfg(100) { RiskResult(nodeId("test-zero"), Map(1 -> 0L, 2 -> 1000L), Nil) }
        val empty = withCfg(100) { RiskResult.empty(nodeId("empty-risk")) }
        
        val combined = RiskResult.combine(withZero, empty)
        
        // Zero losses should be preserved in sparse representation
        assertTrue(combined.outcomeOf(1) == 0L)
      }
    )
  )
}

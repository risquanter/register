package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*

object SimulationResultSpec extends ZIOSpecDefault {
  
  def spec = suite("SimulationResultSpec")(
    suite("RiskResult - basic functionality")(
      test("empty result has zero losses") {
        val result = RiskResult.empty("RISK-001", nTrials = 1000)
        
        assertTrue(result.outcomes.isEmpty) &&
        assertTrue(result.maxLoss == 0L) &&
        assertTrue(result.minLoss == 0L) &&
        assertTrue(result.outcomeCount.isEmpty)
      },
      
      test("single outcome is captured") {
        val result = RiskResult("RISK-001", Map(5 -> 1000L), nTrials = 1000)
        
        assertTrue(result.outcomeOf(5) == 1000L) &&
        assertTrue(result.outcomeOf(10) == 0L) &&
        assertTrue(result.maxLoss == 1000L) &&
        assertTrue(result.minLoss == 1000L)
      },
      
      test("multiple outcomes create frequency distribution") {
        val result = RiskResult(
          "RISK-001",
          Map(1 -> 1000L, 2 -> 2000L, 3 -> 1000L, 4 -> 3000L),
          nTrials = 1000
        )
        
        val expected = Map(1000L -> 2, 2000L -> 1, 3000L -> 1)
        
        assertTrue(result.outcomeCount == expected) &&
        assertTrue(result.maxLoss == 3000L) &&
        assertTrue(result.minLoss == 1000L)
      },
      
      test("outcomeCount is sorted by loss") {
        val result = RiskResult(
          "RISK-001",
          Map(1 -> 3000L, 2 -> 1000L, 3 -> 2000L),
          nTrials = 1000
        )
        
        val keys = result.outcomeCount.keys.toList
        assertTrue(keys == List(1000L, 2000L, 3000L))
      }
    ),
    
    suite("RiskResult - probability of exceedance")(
      test("probOfExceedance with no outcomes returns 0") {
        val result = RiskResult.empty("RISK-001", nTrials = 1000)
        
        assertTrue(result.probOfExceedance(1000L) == BigDecimal(0))
      },
      
      test("probOfExceedance calculates correctly") {
        val result = RiskResult(
          "RISK-001",
          Map(
            1 -> 1000L,   // Below threshold
            2 -> 2000L,   // Below threshold
            3 -> 5000L,   // At threshold
            4 -> 10000L,  // Above threshold
            5 -> 15000L   // Above threshold
          ),
          nTrials = 1000
        )
        
        // Threshold 5000: includes trials 3, 4, 5 = 3 outcomes
        val prob = result.probOfExceedance(5000L)
        
        assertTrue(prob == BigDecimal(3) / BigDecimal(1000))
      },
      
      test("probOfExceedance handles threshold above max loss") {
        val result = RiskResult(
          "RISK-001",
          Map(1 -> 1000L, 2 -> 2000L),
          nTrials = 1000
        )
        
        val prob = result.probOfExceedance(10000L)
        assertTrue(prob == BigDecimal(0))
      }
    ),
    
    suite("SimulationResult.merge - outer join semantics")(
      test("merges disjoint trial IDs") {
        val r1 = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val r2 = RiskResult("R2", Map(3 -> 3000L, 4 -> 4000L), nTrials = 100)
        
        val merged = SimulationResult.merge(r1, r2)
        
        assertTrue(merged == Map(1 -> 1000L, 2 -> 2000L, 3 -> 3000L, 4 -> 4000L))
      },
      
      test("merges overlapping trial IDs by summing losses") {
        val r1 = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val r2 = RiskResult("R2", Map(1 -> 500L, 3 -> 3000L), nTrials = 100)
        
        val merged = SimulationResult.merge(r1, r2)
        
        assertTrue(merged == Map(1 -> 1500L, 2 -> 2000L, 3 -> 3000L))
      },
      
      test("merges with empty result is identity") {
        val r1 = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val empty = RiskResult.empty("EMPTY", nTrials = 100)
        
        val merged = SimulationResult.merge(r1, empty)
        
        assertTrue(merged == r1.outcomes)
      },
      
      test("merges three results correctly") {
        val r1 = RiskResult("R1", Map(1 -> 1000L), nTrials = 100)
        val r2 = RiskResult("R2", Map(1 -> 2000L, 2 -> 500L), nTrials = 100)
        val r3 = RiskResult("R3", Map(2 -> 1500L, 3 -> 3000L), nTrials = 100)
        
        val merged = SimulationResult.merge(r1, r2, r3)
        
        assertTrue(merged == Map(1 -> 3000L, 2 -> 2000L, 3 -> 3000L))
      }
    ),
    
    suite("Identity[RiskResult] - laws")(
      test("identity law: combine(identity, a) == a") {
        val a = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val identity = RiskResult.identity.identity
        
        val combined = RiskResult.identity.combine(identity.copy(nTrials = 100), a)
        
        assertTrue(combined.outcomes == a.outcomes) &&
        assertTrue(combined.nTrials == a.nTrials)
      },
      
      test("identity law: combine(a, identity) == a") {
        val a = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val identity = RiskResult.identity.identity
        
        val combined = RiskResult.identity.combine(a, identity.copy(nTrials = 100))
        
        assertTrue(combined.outcomes == a.outcomes) &&
        assertTrue(combined.nTrials == a.nTrials)
      },
      
      test("associativity: combine(a, combine(b, c)) == combine(combine(a, b), c)") {
        val a = RiskResult("R1", Map(1 -> 1000L), nTrials = 100)
        val b = RiskResult("R2", Map(1 -> 2000L, 2 -> 500L), nTrials = 100)
        val c = RiskResult("R3", Map(2 -> 1500L, 3 -> 3000L), nTrials = 100)
        
        val left = RiskResult.identity.combine(a, RiskResult.identity.combine(b, c))
        val right = RiskResult.identity.combine(RiskResult.identity.combine(a, b), c)
        
        assertTrue(left.outcomes == right.outcomes)
      },
      
      test("commutativity (bonus property): combine(a, b) == combine(b, a)") {
        val a = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val b = RiskResult("R2", Map(1 -> 500L, 3 -> 3000L), nTrials = 100)
        
        val ab = RiskResult.identity.combine(a, b)
        val ba = RiskResult.identity.combine(b, a)
        
        assertTrue(ab.outcomes == ba.outcomes)
      },
      
      test("rejects combining results with different trial counts") {
        val a = RiskResult("R1", Map(1 -> 1000L), nTrials = 100)
        val b = RiskResult("R2", Map(1 -> 2000L), nTrials = 200)
        
        assertTrue(
          try {
            RiskResult.identity.combine(a, b)
            false
          } catch {
            case _: IllegalArgumentException => true
          }
        )
      }
    ),
    
    suite("RiskResultGroup - aggregation")(
      test("empty group has no outcomes") {
        val group = RiskResultGroup("TOTAL", nTrials = 1000)
        
        assertTrue(group.children.isEmpty) &&
        assertTrue(group.outcomes.isEmpty) &&
        assertTrue(group.maxLoss == 0L)
      },
      
      test("single child group equals child") {
        val child = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val group = RiskResultGroup("TOTAL", nTrials = 100, child)
        
        assertTrue(group.outcomes == child.outcomes) &&
        assertTrue(group.maxLoss == child.maxLoss) &&
        assertTrue(group.children == List(child))
      },
      
      test("multiple children are aggregated") {
        val r1 = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val r2 = RiskResult("R2", Map(1 -> 500L, 3 -> 3000L), nTrials = 100)
        
        val group = RiskResultGroup("TOTAL", nTrials = 100, r1, r2)
        
        // Aggregated outcomes: trial 1 = 1500, trial 2 = 2000, trial 3 = 3000
        assertTrue(group.outcomes == Map(1 -> 1500L, 2 -> 2000L, 3 -> 3000L)) &&
        assertTrue(group.maxLoss == 3000L) &&
        assertTrue(group.children == List(r1, r2))
      },
      
      test("flatten returns hierarchy") {
        val r1 = RiskResult("R1", Map(1 -> 1000L), nTrials = 100)
        val r2 = RiskResult("R2", Map(2 -> 2000L), nTrials = 100)
        val group = RiskResultGroup("TOTAL", nTrials = 100, r1, r2)
        
        val flattened = group.flatten
        
        assertTrue(flattened.size == 3) &&
        assertTrue(flattened(0) == group) &&
        assertTrue(flattened.tail.toSet == Set(r1, r2))
      },
      
      test("rejects children with mismatched trial counts") {
        val r1 = RiskResult("R1", Map(1 -> 1000L), nTrials = 100)
        val r2 = RiskResult("R2", Map(2 -> 2000L), nTrials = 200)
        
        assertTrue(
          try {
            RiskResultGroup("TOTAL", nTrials = 100, r1, r2)
            false
          } catch {
            case _: IllegalArgumentException => true
          }
        )
      }
    ),
    
    suite("RiskResult - flatten")(
      test("single result flattens to itself") {
        val result = RiskResult("R1", Map(1 -> 1000L), nTrials = 100)
        val flattened = result.flatten
        
        assertTrue(flattened == Vector(result))
      }
    ),
    
    suite("RiskResult - equality")(
      test("equal results are equal") {
        val r1 = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        val r2 = RiskResult("R1", Map(1 -> 1000L, 2 -> 2000L), nTrials = 100)
        
        assertTrue(Equal[RiskResult].equal(r1, r2))
      },
      
      test("different outcomes are not equal") {
        val r1 = RiskResult("R1", Map(1 -> 1000L), nTrials = 100)
        val r2 = RiskResult("R1", Map(1 -> 2000L), nTrials = 100)
        
        assertTrue(!Equal[RiskResult].equal(r1, r2))
      },
      
      test("different trial counts are not equal") {
        val r1 = RiskResult("R1", Map(1 -> 1000L), nTrials = 100)
        val r2 = RiskResult("R1", Map(1 -> 1000L), nTrials = 200)
        
        assertTrue(!Equal[RiskResult].equal(r1, r2))
      }
    ),
    
    suite("edge cases")(
      test("handles large trial IDs") {
        val result = RiskResult("R1", Map(1000000 -> 1000L), nTrials = 2000000)
        
        assertTrue(result.outcomeOf(1000000) == 1000L)
      },
      
      test("handles large loss values") {
        val largeLoss = Long.MaxValue / 2
        val result = RiskResult("R1", Map(1 -> largeLoss), nTrials = 100)
        
        assertTrue(result.maxLoss == largeLoss)
      },
      
      test("merge handles potential overflow scenario") {
        // Note: This doesn't prevent overflow, just documents the behavior
        val r1 = RiskResult("R1", Map(1 -> Long.MaxValue / 2), nTrials = 100)
        val r2 = RiskResult("R2", Map(1 -> Long.MaxValue / 2), nTrials = 100)
        
        val merged = SimulationResult.merge(r1, r2)
        
        // This will overflow in current implementation
        // In production, consider BigInt or bounds checking
        assertTrue(merged.contains(1))
      }
    )
  )
}

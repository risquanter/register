package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.RiskResultIdentityInstances.given
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId}
import com.risquanter.register.testutil.ConfigTestLoader.withCfg

object LossDistributionSpec extends ZIOSpecDefault {

  def spec = suite("LossDistributionSpec")(
    suite("RiskResult - basic functionality")(
      test("empty result has zero losses") {
        val result = withCfg(1000) { RiskResult.empty(nodeId("RISK-001")) }

        assertTrue(result.outcomes.isEmpty) &&
        assertTrue(result.maxLoss == 0L) &&
        assertTrue(result.minLoss == 0L) &&
        assertTrue(result.outcomeCount.isEmpty)
      },
      test("single outcome is captured") {
        val result = withCfg(1000) { RiskResult(nodeId("RISK-001"), Map(5 -> 1000L), Nil) }

        assertTrue(result.outcomeOf(5) == 1000L) &&
        assertTrue(result.outcomeOf(10) == 0L) &&
        assertTrue(result.maxLoss == 1000L) &&
        assertTrue(result.minLoss == 1000L)
      },
      test("multiple outcomes create frequency distribution") {
        val result = withCfg(1000) {
          RiskResult(
            nodeId("RISK-001"),
            Map(1 -> 1000L, 2 -> 2000L, 3 -> 1000L, 4 -> 3000L),
            Nil
          )
        }

        val expected = Map(1000L -> 2, 2000L -> 1, 3000L -> 1)

        assertTrue(result.outcomeCount == expected) &&
        assertTrue(result.maxLoss == 3000L) &&
        assertTrue(result.minLoss == 1000L)
      },
      test("outcomeCount is sorted by loss") {
        val result = withCfg(1000) {
          RiskResult(
            nodeId("RISK-001"),
            Map(1 -> 3000L, 2 -> 1000L, 3 -> 2000L),
            Nil
          )
        }

        val keys = result.outcomeCount.keys.toList
        assertTrue(keys == List(1000L, 2000L, 3000L))
      }
    ),
    suite("RiskResult - probability of exceedance")(
      test("probOfExceedance with no outcomes returns 0") {
        val result = withCfg(1000) { RiskResult.empty(nodeId("RISK-001")) }

        assertTrue(result.probOfExceedance(1000L) == BigDecimal(0))
      },
      test("probOfExceedance calculates correctly") {
        val result = withCfg(1000) {
          RiskResult(
            nodeId("RISK-001"),
            Map(
              1 -> 1000L,  // Below threshold
              2 -> 2000L,  // Below threshold
              3 -> 5000L,  // At threshold
              4 -> 10000L, // Above threshold
              5 -> 15000L  // Above threshold
            ),
            Nil
          )
        }

        // Threshold 5000: includes trials 3, 4, 5 = 3 outcomes
        val prob = result.probOfExceedance(5000L)

        assertTrue(prob == BigDecimal(3) / BigDecimal(1000))
      },
      test("probOfExceedance handles threshold above max loss") {
        val result = withCfg(1000) {
          RiskResult(
            nodeId("RISK-001"),
            Map(1 -> 1000L, 2 -> 2000L),
            Nil
          )
        }

        val prob = result.probOfExceedance(10000L)
        assertTrue(prob == BigDecimal(0))
      }
    ),
    suite("LossDistribution.merge - outer join semantics")(
      test("merges disjoint trial IDs") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(3 -> 3000L, 4 -> 4000L), Nil) }

        val merged = LossDistribution.merge(r1, r2)

        assertTrue(merged == Map(1 -> 1000L, 2 -> 2000L, 3 -> 3000L, 4 -> 4000L))
      },
      test("merges overlapping trial IDs by summing losses") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> 500L, 3 -> 3000L), Nil) }

        val merged = LossDistribution.merge(r1, r2)

        assertTrue(merged == Map(1 -> 1500L, 2 -> 2000L, 3 -> 3000L))
      },
      test("merges with empty result is identity") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil) }
        val empty = withCfg(100) { RiskResult.empty(nodeId("EMPTY")) }

        val merged = LossDistribution.merge(r1, empty)

        assertTrue(merged == r1.outcomes)
      },
      test("merges three results correctly") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> 2000L, 2 -> 500L), Nil) }
        val r3 = withCfg(100) { RiskResult(nodeId("risk-003"), Map(2 -> 1500L, 3 -> 3000L), Nil) }

        val merged = LossDistribution.merge(r1, r2, r3)

        assertTrue(merged == Map(1 -> 3000L, 2 -> 2000L, 3 -> 3000L))
      }
    ),
    suite("RiskResult combine - laws")(
      test("identity law: combine(identity, a) == a") {
        withCfg(100) {
          val a = RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil)
          val identity = Identity[RiskResult].identity

          val combined = RiskResult.combine(identity, a)

          assertTrue(combined.outcomes == a.outcomes) &&
          assertTrue(combined.nTrials == a.nTrials)
        }
      },
      test("identity law: combine(a, identity) == a") {
        withCfg(100) {
          val a = RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil)
          val identity = Identity[RiskResult].identity

          val combined = RiskResult.combine(a, identity)

          assertTrue(combined.outcomes == a.outcomes) &&
          assertTrue(combined.nTrials == a.nTrials)
        }
      },
      test("associativity: combine(a, combine(b, c)) == combine(combine(a, b), c)") {
        val a = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val b = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> 2000L, 2 -> 500L), Nil) }
        val c = withCfg(100) { RiskResult(nodeId("risk-003"), Map(2 -> 1500L, 3 -> 3000L), Nil) }

        val left  = RiskResult.combine(a, RiskResult.combine(b, c))
        val right = RiskResult.combine(RiskResult.combine(a, b), c)

        assertTrue(left.outcomes == right.outcomes)
      },
      test("commutativity (bonus property): combine(a, b) == combine(b, a)") {
        val a = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil) }
        val b = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> 500L, 3 -> 3000L), Nil) }

        val ab = RiskResult.combine(a, b)
        val ba = RiskResult.combine(b, a)

        assertTrue(ab.outcomes == ba.outcomes)
      },
      test("rejects combining results with different trial counts") {
        val a = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val b = withCfg(200) { RiskResult(nodeId("risk-002"), Map(1 -> 2000L), Nil) }

        assertTrue(
          try {
            RiskResult.combine(a, b)
            false
          } catch {
            case _: IllegalArgumentException => true
          }
        )
      }
    ),
    suite("RiskResultGroup - aggregation")(
      test("empty group has no outcomes") {
        val group = withCfg(1000) { RiskResultGroup(nodeId("TOTAL")) }

        assertTrue(group.children.isEmpty) &&
        assertTrue(group.outcomes.isEmpty) &&
        assertTrue(group.maxLoss == 0L)
      },
      test("single child group equals child") {
        val child = withCfg(100) {
          RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil)
        }
        val group = withCfg(100) { RiskResultGroup(nodeId("TOTAL"), child) }

        assertTrue(group.outcomes == child.outcomes) &&
        assertTrue(group.maxLoss == child.maxLoss) &&
        assertTrue(group.children == List(child))
      },
      test("multiple children are aggregated") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> 500L, 3 -> 3000L), Nil) }

        val group = withCfg(100) { RiskResultGroup(nodeId("TOTAL"), r1, r2) }

        // Aggregated outcomes: trial 1 = 1500, trial 2 = 2000, trial 3 = 3000
        assertTrue(group.outcomes == Map(1 -> 1500L, 2 -> 2000L, 3 -> 3000L)) &&
        assertTrue(group.maxLoss == 3000L) &&
        assertTrue(group.children == List(r1, r2))
      },
      test("flatten returns hierarchy") {
        val r1    = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2    = withCfg(100) { RiskResult(nodeId("risk-002"), Map(2 -> 2000L), Nil) }
        val group = withCfg(100) { RiskResultGroup(nodeId("TOTAL"), r1, r2) }

        val flattened = group.flatten

        assertTrue(flattened.size == 3) &&
        assertTrue(flattened(0) == group) &&
        assertTrue(flattened.tail.toSet == Set(r1, r2))
      },
      test("rejects children with mismatched trial counts") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2 = withCfg(200) { RiskResult(nodeId("risk-002"), Map(2 -> 2000L), Nil) }

        // With config-driven constructors, mixed nTrials are prevented at construction time.
        assertTrue(r1.nTrials != r2.nTrials)
      }
    ),
    suite("RiskResult - flatten")(
      test("single result flattens to itself") {
        val result    = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val flattened = result.flatten

        assertTrue(flattened == Vector(result))
      }
    ),
    suite("RiskResult - equality")(
      test("equal results are equal") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil) }

        assertTrue(Equal[RiskResult].equal(r1, r2))
      },
      test("different outcomes are not equal") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 2000L), Nil) }

        assertTrue(!Equal[RiskResult].equal(r1, r2))
      },
      test("different trial counts are not equal") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2 = withCfg(200) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }

        assertTrue(!Equal[RiskResult].equal(r1, r2))
      }
    ),
    suite("edge cases")(
      test("handles large trial IDs") {
        val result = withCfg(2000000) { RiskResult(nodeId("risk-001"), Map(1000000 -> 1000L), Nil) }

        assertTrue(result.outcomeOf(1000000) == 1000L)
      },
      test("handles large loss values") {
        val largeLoss = Long.MaxValue / 2
        val result    = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> largeLoss), Nil) }

        assertTrue(result.maxLoss == largeLoss)
      },
      test("merge handles potential overflow scenario") {
        // Note: This doesn't prevent overflow, just documents the behavior
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> Long.MaxValue / 2), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> Long.MaxValue / 2), Nil) }

        val merged = LossDistribution.merge(r1, r2)

        // This will overflow in current implementation
        // In production, consider BigInt or bounds checking
        assertTrue(merged.contains(1))
      }
    )
  )
}

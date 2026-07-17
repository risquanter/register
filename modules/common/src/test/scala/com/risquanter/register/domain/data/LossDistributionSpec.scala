package com.risquanter.register.domain.data

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.testutil.TestHelpers.nodeId
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

        assertTrue(result.probOfExceedance(1000L) == 0.0)
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

        assertTrue(prob == 3.0 / 1000.0)
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
        assertTrue(prob == 0.0)
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
    suite("RiskResultGroup - aggregation")(
      test("empty group has no outcomes") {
        val group = withCfg(1000) { RiskResultGroup.create(nodeId("TOTAL")).toEither.toOption.get }

        assertTrue(group.children.isEmpty) &&
        assertTrue(group.outcomes.isEmpty) &&
        assertTrue(group.maxLoss == 0L)
      },
      test("single child group equals child") {
        val child = withCfg(100) {
          RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil)
        }
        val group = withCfg(100) { RiskResultGroup.create(nodeId("TOTAL"), child).toEither.toOption.get }

        assertTrue(group.outcomes == child.outcomes) &&
        assertTrue(group.maxLoss == child.maxLoss) &&
        assertTrue(group.children == List(child))
      },
      test("multiple children are aggregated") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> 500L, 3 -> 3000L), Nil) }

        val group = withCfg(100) { RiskResultGroup.create(nodeId("TOTAL"), r1, r2).toEither.toOption.get }

        // Aggregated outcomes: trial 1 = 1500, trial 2 = 2000, trial 3 = 3000
        assertTrue(group.outcomes == Map(1 -> 1500L, 2 -> 2000L, 3 -> 3000L)) &&
        assertTrue(group.maxLoss == 3000L) &&
        assertTrue(group.children == List(r1, r2))
      },
      test("flatten returns hierarchy") {
        val r1    = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2    = withCfg(100) { RiskResult(nodeId("risk-002"), Map(2 -> 2000L), Nil) }
        val group = withCfg(100) { RiskResultGroup.create(nodeId("TOTAL"), r1, r2).toEither.toOption.get }

        val flattened = group.flatten

        assertTrue(flattened.size == 3) &&
        assertTrue(flattened(0) == group) &&
        assertTrue(flattened.tail.toSet == Set(r1, r2))
      },
      test("rejects children with mismatched trial counts") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2 = withCfg(200) { RiskResult(nodeId("risk-002"), Map(2 -> 2000L), Nil) }

        // Alignment guard: misaligned children are a programming error, so the
        // require propagates through create as an exception (not a ValidationError)
        assertTrue(
          try {
            withCfg(100) { RiskResultGroup.create(nodeId("TOTAL"), r1, r2) }
            false
          } catch {
            case _: IllegalArgumentException => true
          }
        )
      },
      test("create converts aggregation overflow to a ValidationError") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> Long.MaxValue), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> 1L), Nil) }

        val parentId = nodeId("TOTAL")
        val result = withCfg(100) { RiskResultGroup.create(parentId, r1, r2) }

        result.toEither match {
          case Left(errors) =>
            assertTrue(
              errors.head.code == ValidationErrorCode.CONSTRAINT_VIOLATION,
              errors.head.field == s"riskPortfolio.${parentId.value}"
            )
          case Right(_) => assertTrue(false)
        }
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
      },
      test("equal outcomes with differing provenances are equal (provenance is audit metadata, not identity)") {
        import com.risquanter.register.domain.data.{NodeProvenance, LognormalDistributionParams}
        import java.time.Instant
        import io.github.iltotore.iron.refineUnsafe
        val params = LognormalDistributionParams(1000L.refineUnsafe, 5000L.refineUnsafe, 0.9)
        val prov1 = NodeProvenance(
          riskId = nodeId("risk-001"),
          entityId = 1L,
          occurrenceVarId = 1001L,
          lossVarId = 2001L,
          globalSeed3 = 0L,
          globalSeed4 = 0L,
          distributionType = "lognormal",
          distributionParams = params,
          timestamp = Instant.parse("2026-01-01T00:00:00Z"),
          simulationUtilVersion = "1.0.0"
        )
        val prov2 = prov1.copy(timestamp = Instant.parse("2026-06-18T12:00:00Z"), simulationUtilVersion = "1.1.0")
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), List(prov1)) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), List(prov2)) }

        assertTrue(Equal[RiskResult].equal(r1, r2))
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
        // Long.MaxValue/2 + Long.MaxValue/2 = Long.MaxValue - 1: the largest
        // sum that still fits, so the checked addition must accept it
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> Long.MaxValue / 2), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> Long.MaxValue / 2), Nil) }

        val merged = LossDistribution.merge(r1, r2)

        assertTrue(merged.contains(1))
      },
      test("merge throws on Long overflow (checked addition)") {
        val r1 = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> Long.MaxValue), Nil) }
        val r2 = withCfg(100) { RiskResult(nodeId("risk-002"), Map(1 -> 1L), Nil) }

        assertTrue(
          try { LossDistribution.merge(r1, r2); false }
          catch { case _: ArithmeticException => true }
        )
      }
    )
  )
}

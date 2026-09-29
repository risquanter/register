package com.risquanter.register.simulation

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.*
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.{
  LognormalDistributionParams, MitigationApplicationRecord, MitigationPrecedence,
  MitigationSpec, NodeProvenance, ResultTransformSpec, TransformPipeline
}
import com.risquanter.register.domain.data.iron.{NodeId, ValidationUtil}
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.mitigation.MitigationApplication
import com.risquanter.register.testutil.TestHelpers.{mitigationId, nodeId}
import com.risquanter.register.testutil.ConfigTestLoader.withCfg
import com.risquanter.register.testutil.RiskResultTestSupport.leafOf
import java.time.Instant
import io.github.iltotore.iron.refineUnsafe

/** The public valuation type: the members consumers read, the layer `decorate`
  * applies, and the equality relation. The aggregate's own properties are in
  * `services.cache.NodeLossesSpec`, which can name the types they exercise.
  */
object LossDistributionSpec extends ZIOSpecDefault {

  /** A result-stage layer record capping every trial's loss. `resolvedScope` is
    * empty because nothing here reads it — the layer is what is under test. */
  private def capRecord(label: String, cap: Long): MitigationApplicationRecord =
    MitigationApplicationRecord(
      mitigationId(label),
      MitigationSpec.ResultStage(TransformPipeline(List(
        ResultTransformSpec.CapLosses(ValidationUtil.refineNonNegativeLong(cap).toOption.get)))),
      Set.empty,
      MitigationPrecedence.default
    )

  private def scaleRecord(label: String, factor: Double): MitigationApplicationRecord =
    MitigationApplicationRecord(
      mitigationId(label),
      MitigationSpec.ResultStage(TransformPipeline(List(
        ResultTransformSpec.ScaleLosses(factor.refineUnsafe)))),
      Set.empty,
      MitigationPrecedence.default
    )

  private val sampleProvenance: NodeProvenance = NodeProvenance(
    entityId = 1L,
    occurrenceVarId = 1001L,
    lossVarId = 2001L,
    globalSeed3 = 0L,
    globalSeed4 = 0L,
    distributionType = "lognormal",
    distributionParams = LognormalDistributionParams(1000L.refineUnsafe, 5000L.refineUnsafe, 0.9),
    timestamp = Instant.parse("2026-01-01T00:00:00Z"),
    metalogDistributionVersion = "1.0.0"
  )

  /** Build a value with a non-empty layer through the production factory. */
  private def decorated(
    id: NodeId,
    outcomes: Map[Int, Long],
    applied: List[MitigationApplicationRecord]
  )(using cfg: SimulationConfig) =
    LossDistribution.decorate(
      id,
      TrialOutcomes(cfg.defaultNTrials, outcomes),
      None,
      applied,
      MitigationApplication.run(applied, _)
    )

  def spec = suite("LossDistributionSpec")(
    suite("basic functionality")(
      test("empty result has zero losses") {
        val result = withCfg(1000) { leafOf(nodeId("RISK-001"), Map.empty) }

        assertTrue(result.outcomes.isEmpty) &&
        assertTrue(result.maxLoss == 0L) &&
        assertTrue(result.minLoss == 0L) &&
        assertTrue(result.outcomeCount.isEmpty)
      },
      test("single outcome is captured") {
        val result = withCfg(1000) { leafOf(nodeId("RISK-001"), Map(5 -> 1000L)) }

        assertTrue(result.outcomeOf(5) == 1000L) &&
        assertTrue(result.outcomeOf(10) == 0L) &&
        assertTrue(result.maxLoss == 1000L) &&
        assertTrue(result.minLoss == 1000L)
      },
      test("multiple outcomes create frequency distribution") {
        val result = withCfg(1000) {
          leafOf(nodeId("RISK-001"), Map(1 -> 1000L, 2 -> 2000L, 3 -> 1000L, 4 -> 3000L))
        }

        val expected = Map(1000L -> 2, 2000L -> 1, 3000L -> 1)

        assertTrue(result.outcomeCount == expected) &&
        assertTrue(result.maxLoss == 3000L) &&
        assertTrue(result.minLoss == 1000L)
      },
      test("outcomeCount is sorted by loss") {
        val result = withCfg(1000) {
          leafOf(nodeId("RISK-001"), Map(1 -> 3000L, 2 -> 1000L, 3 -> 2000L))
        }

        val keys = result.outcomeCount.keys.toList
        assertTrue(keys == List(1000L, 2000L, 3000L))
      }
    ),
    suite("probability of exceedance")(
      test("probOfExceedance with no outcomes returns 0") {
        val result = withCfg(1000) { leafOf(nodeId("RISK-001"), Map.empty) }

        assertTrue(result.probOfExceedance(1000L) == 0.0)
      },
      test("probOfExceedance calculates correctly") {
        val result = withCfg(1000) {
          leafOf(
            nodeId("RISK-001"),
            Map(
              1 -> 1000L,  // Below threshold
              2 -> 2000L,  // Below threshold
              3 -> 5000L,  // At threshold
              4 -> 10000L, // Above threshold
              5 -> 15000L  // Above threshold
            )
          )
        }

        // Threshold 5000: includes trials 3, 4, 5 = 3 outcomes
        val prob = result.probOfExceedance(5000L)

        assertTrue(prob == 3.0 / 1000.0)
      },
      test("probOfExceedance handles threshold above max loss") {
        val result = withCfg(1000) {
          leafOf(nodeId("RISK-001"), Map(1 -> 1000L, 2 -> 2000L))
        }

        val prob = result.probOfExceedance(10000L)
        assertTrue(prob == 0.0)
      }
    ),
    // The outer-join laws are stated at the layer that owns them:
    // `TrialOutcomes.combine`, which is what the portfolio factory folds with.
    suite("TrialOutcomes.combine - outer join semantics")(
      test("combines disjoint trial IDs") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-002"), Map(3 -> 3000L, 4 -> 4000L)) }

        val merged = TrialOutcomes.combine(r1.trials, r2.trials).outcomes

        assertTrue(merged == Map(1 -> 1000L, 2 -> 2000L, 3 -> 3000L, 4 -> 4000L))
      },
      test("combines overlapping trial IDs by summing losses") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-002"), Map(1 -> 500L, 3 -> 3000L)) }

        val merged = TrialOutcomes.combine(r1.trials, r2.trials).outcomes

        assertTrue(merged == Map(1 -> 1500L, 2 -> 2000L, 3 -> 3000L))
      },
      test("combining with an empty result is identity") {
        val r1    = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L)) }
        val empty = withCfg(100) { leafOf(nodeId("EMPTY"), Map.empty) }

        val merged = TrialOutcomes.combine(r1.trials, empty.trials).outcomes

        assertTrue(merged == r1.outcomes)
      },
      test("combines three results correctly") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-002"), Map(1 -> 2000L, 2 -> 500L)) }
        val r3 = withCfg(100) { leafOf(nodeId("risk-003"), Map(2 -> 1500L, 3 -> 3000L)) }

        val merged = List(r1, r2, r3).map(_.trials).reduce(TrialOutcomes.combine).outcomes

        assertTrue(merged == Map(1 -> 3000L, 2 -> 2000L, 3 -> 3000L))
      }
    ),
    suite("decorate - the node's own layer")(
      test("an empty layer leaves the figures unchanged by reference") {
        val value = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L)) }

        // Reference equality, not structural: an unmitigated node holds one
        // outcome map, not two.
        assertTrue(value.applied.isEmpty) &&
        assertTrue(value.trials eq value.source)
      },
      test("a binding layer changes trials and leaves source at the pre-layer figure") {
        val applied = List(capRecord("cap-a", 1500L))
        val value   = withCfg(100) {
          decorated(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), applied).toEither.toOption.get
        }

        assertTrue(value.applied == applied) &&
        assertTrue(value.source.outcomes == Map(1 -> 1000L, 2 -> 2000L)) &&
        assertTrue(value.trials.outcomes == Map(1 -> 1000L, 2 -> 1500L)) &&
        assertTrue(!(value.trials eq value.source))
      },
      test("a layer of two records composes them in the order given") {
        // Cap at 1500 then scale by 2 gives 3000; scaling first would give 1500.
        val applied = List(capRecord("cap-a", 1500L), scaleRecord("scale-a", 2.0))
        val value   = withCfg(100) {
          decorated(nodeId("risk-001"), Map(1 -> 4000L), applied).toEither.toOption.get
        }

        assertTrue(value.trials.outcomes == Map(1 -> 3000L))
      },
      test("a layer that overflows fails with CONSTRAINT_VIOLATION rather than throwing") {
        // scaleLosses throws on the overflow; decorate converts it (ADR-033 §3).
        val id     = nodeId("risk-001")
        val result = withCfg(100) {
          decorated(id, Map(1 -> Long.MaxValue), List(scaleRecord("scale-a", 2.0)))
        }

        result.toEither match {
          case Left(errors) =>
            assertTrue(
              errors.head.code == ValidationErrorCode.CONSTRAINT_VIOLATION,
              errors.head.field == s"mitigatedResult.${id.value}"
            )
          case Right(_) => assertTrue(false)
        }
      },
      test("a leaf carries exactly one provenance record and a portfolio carries none") {
        val leaf = withCfg(100) {
          leafOf(nodeId("risk-001"), Map(1 -> 1000L), Some(sampleProvenance))
        }
        val portfolioShaped = withCfg(100) {
          leafOf(nodeId("TOTAL"), Map(1 -> 1000L))
        }

        assertTrue(leaf.provenance.contains(sampleProvenance)) &&
        assertTrue(portfolioShaped.provenance.isEmpty)
      },
      test("leafProvenances keys each record by the node that carries it") {
        val (leafA, leafB, portfolio) = withCfg(100) {
          (
            leafOf(nodeId("risk-001"), Map(1 -> 1000L), Some(sampleProvenance)),
            leafOf(nodeId("risk-002"), Map(1 -> 2000L), Some(sampleProvenance)),
            leafOf(nodeId("TOTAL"), Map(1 -> 3000L))
          )
        }

        val attributed = LossDistribution.leafProvenances(
          Map(leafA.nodeId -> leafA, leafB.nodeId -> leafB, portfolio.nodeId -> portfolio)
        )

        assertTrue(attributed.keySet == Set(leafA.nodeId, leafB.nodeId)) &&
        assertTrue(attributed.values.forall(_ == sampleProvenance))
      }
    ),
    suite("equality")(
      test("equal results are equal") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L)) }

        assertTrue(Equal[LossDistribution].equal(r1, r2))
      },
      test("different outcomes are not equal") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 2000L)) }

        assertTrue(!Equal[LossDistribution].equal(r1, r2))
      },
      test("different trial counts are not equal") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L)) }
        val r2 = withCfg(200) { leafOf(nodeId("risk-001"), Map(1 -> 1000L)) }

        assertTrue(!Equal[LossDistribution].equal(r1, r2))
      },
      test("equal outcomes with differing provenance are not equal (the relation is structural over every field)") {
        val prov2 = sampleProvenance.copy(
          timestamp = Instant.parse("2026-06-18T12:00:00Z"),
          metalogDistributionVersion = "1.1.0"
        )
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Some(sampleProvenance)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L), Some(prov2)) }

        assertTrue(!Equal[LossDistribution].equal(r1, r2))
      }
    ),
    suite("edge cases")(
      test("handles large trial IDs") {
        val result = withCfg(2000000) { leafOf(nodeId("risk-001"), Map(1000000 -> 1000L)) }

        assertTrue(result.outcomeOf(1000000) == 1000L)
      },
      test("handles large loss values") {
        val largeLoss = Long.MaxValue / 2
        val result    = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> largeLoss)) }

        assertTrue(result.maxLoss == largeLoss)
      },
      test("combine handles potential overflow scenario") {
        // Long.MaxValue/2 + Long.MaxValue/2 = Long.MaxValue - 1: the largest
        // sum that still fits, so the checked addition must accept it
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> Long.MaxValue / 2)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-002"), Map(1 -> Long.MaxValue / 2)) }

        val merged = TrialOutcomes.combine(r1.trials, r2.trials).outcomes

        assertTrue(merged.contains(1))
      },
      test("combine throws on Long overflow (checked addition)") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> Long.MaxValue)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-002"), Map(1 -> 1L)) }

        assertTrue(
          try { TrialOutcomes.combine(r1.trials, r2.trials); false }
          catch { case _: ArithmeticException => true }
        )
      }
    )
  )
}

package com.risquanter.register.services.cache

import zio.test.*
import zio.test.Assertion.*
import zio.prelude.Ord
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.PreludeInstances.given
import com.risquanter.register.domain.data.{
  LognormalDistributionParams, MitigationApplicationRecord, MitigationPrecedence,
  MitigationSpec, NodeProvenance, ResultTransformSpec, TransformPipeline
}
import com.risquanter.register.domain.data.iron.{NodeId, ValidationUtil}
import com.risquanter.register.domain.errors.ValidationErrorCode
import com.risquanter.register.mitigation.MitigationApplication
import com.risquanter.register.simulation.{LossDistribution, TrialOutcomes}
import com.risquanter.register.testutil.TestHelpers.{mitigationId, nodeId}
import com.risquanter.register.testutil.ConfigTestLoader.withCfg
import com.risquanter.register.testutil.RiskResultTestSupport.leafOf
import java.time.Instant
import io.github.iltotore.iron.refineUnsafe

/** The internal valuation family. This spec lives in `services.cache` because
  * `NodeLosses` and its cases are `private[cache]` and cannot be named
  * elsewhere.
  */
object NodeLossesSpec extends ZIOSpecDefault {

  private def capRecord(label: String, cap: Long): MitigationApplicationRecord =
    MitigationApplicationRecord(
      mitigationId(label),
      MitigationSpec.ResultStage(TransformPipeline(List(
        ResultTransformSpec.CapLosses(ValidationUtil.refineLossCap(cap).toOption.get)))),
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

  // The fold's two clauses, mirroring `CachedResultResolverLive.distributionOf`
  // without the cache or the tree walk.

  private def leafValue(
    id: NodeId,
    loss: Long,
    applied: List[MitigationApplicationRecord]
  )(using cfg: SimulationConfig): LossDistribution = {
    val losses = NodeLosses.leaf(id, TrialOutcomes(cfg.defaultNTrials, Map(1 -> loss)), sampleProvenance)
    LossDistribution.decorate(
      losses.nodeId, losses.trials, Some(losses.provenance), applied,
      MitigationApplication.run(applied, _)
    ).toEither.toOption.get
  }

  private def portfolioValue(
    id: NodeId,
    children: List[LossDistribution],
    applied: List[MitigationApplicationRecord]
  ): LossDistribution = {
    val losses = NodeLosses.portfolio(id, children).toEither.toOption.get
    LossDistribution.decorate(
      losses.nodeId, losses.trials, None, applied,
      MitigationApplication.run(applied, _)
    ).toEither.toOption.get
  }

  def spec = suite("NodeLossesSpec")(

    suite("LeafLosses.create")(
      test("carries the figures and the record through without altering either") {
        val trials = withCfg(100) { TrialOutcomes(summon[SimulationConfig].defaultNTrials, Map(1 -> 1000L)) }
        val losses = NodeLosses.leaf(nodeId("risk-001"), trials, sampleProvenance)

        assertTrue(losses.nodeId == nodeId("risk-001")) &&
        assertTrue(losses.trials eq trials) &&
        assertTrue(losses.provenance == sampleProvenance)
      }
    ),

    suite("PortfolioLosses.create - the derived aggregate")(
      test("derives trials as the combine of exactly its children") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-002"), Map(1 -> 500L, 3 -> 3000L)) }

        val losses = NodeLosses.portfolio(nodeId("TOTAL"), List(r1, r2)).toEither.toOption.get

        assertTrue(losses.trials.outcomes == Map(1 -> 1500L, 2 -> 2000L, 3 -> 3000L)) &&
        assertTrue(losses.trials == TrialOutcomes.combine(r1.trials, r2.trials)) &&
        assertTrue(losses.children == List(r1, r2))
      },
      test("the trial count comes from the children, never from SimulationConfig") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-002"), Map(1 -> 2000L)) }

        // A config naming 999 is in scope and is not read. The count is the
        // denominator of probOfExceedance, so a wrong one corrupts every
        // probability drawn from this value.
        val losses = withCfg(999) { NodeLosses.portfolio(nodeId("TOTAL"), List(r1, r2)) }
          .toEither.toOption.get

        assertTrue(losses.trials.nTrials == 100)
      },
      test("children at differing trial counts are a programming error and throw") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L)) }
        val r2 = withCfg(200) { leafOf(nodeId("risk-002"), Map(2 -> 2000L)) }

        // The resolver builds every child under one config, so a mismatch is a
        // defect and propagates rather than becoming a ValidationError (ADR-010).
        assertTrue(
          try { NodeLosses.portfolio(nodeId("TOTAL"), List(r1, r2)); false }
          catch { case _: IllegalArgumentException => true }
        )
      },
      test("a combine overflow becomes a CONSTRAINT_VIOLATION") {
        val r1 = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> Long.MaxValue)) }
        val r2 = withCfg(100) { leafOf(nodeId("risk-002"), Map(1 -> 1L)) }

        val parentId = nodeId("TOTAL")
        NodeLosses.portfolio(parentId, List(r1, r2)).toEither match {
          case Left(errors) =>
            assertTrue(
              errors.head.code == ValidationErrorCode.CONSTRAINT_VIOLATION,
              errors.head.field == s"riskPortfolio.${parentId.value}"
            )
          case Right(_) => assertTrue(false)
        }
      },
      test("an empty child list is refused with EMPTY_COLLECTION") {
        // Nothing supplies a trial count. A childless portfolio is already
        // refused by RiskPortfolio.create, the topology check, the JSON decoder
        // and the resolver.
        val parentId = nodeId("empty-risk")
        NodeLosses.portfolio(parentId, Nil).toEither match {
          case Left(errors) =>
            assertTrue(
              errors.head.code == ValidationErrorCode.EMPTY_COLLECTION,
              errors.head.field == s"riskPortfolio.${parentId.value}.childIds"
            )
          case Right(_) => assertTrue(false)
        }
      },
      test("a single child aggregate equals that child") {
        val child  = withCfg(100) { leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L)) }
        val losses = NodeLosses.portfolio(nodeId("TOTAL"), List(child)).toEither.toOption.get

        assertTrue(losses.trials.outcomes == child.outcomes) &&
        assertTrue(losses.children == List(child))
      }
    ),

    suite("aggregated values - Ord[Loss] with TreeMap")(
      test("maxLoss uses Ord[Loss] for aggregated results") {
        val value = withCfg(10) {
          val child1 = leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L))
          val child2 = leafOf(nodeId("risk-002"), Map(1 -> 3000L, 2 -> 4000L))
          portfolioValue(nodeId("total-risk"), List(child1, child2), Nil)
        }

        // Trial 1: 1000 + 3000 = 4000
        // Trial 2: 2000 + 4000 = 6000 <- Max
        assertTrue(value.maxLoss == 6000L)
      },
      test("minLoss uses Ord[Loss] for aggregated results") {
        val value = withCfg(10) {
          val child1 = leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L))
          val child2 = leafOf(nodeId("risk-002"), Map(1 -> 3000L, 2 -> 4000L))
          portfolioValue(nodeId("total-risk"), List(child1, child2), Nil)
        }

        // Trial 1: 1000 + 3000 = 4000 <- Min
        // Trial 2: 2000 + 4000 = 6000
        assertTrue(value.minLoss == 4000L)
      },
      test("outcomeCount sorted with aggregated losses") {
        val value = withCfg(10) {
          val child1 = leafOf(nodeId("risk-001"), Map(1 -> 1000L, 2 -> 2000L))
          val child2 = leafOf(nodeId("risk-002"), Map(1 -> 500L, 2 -> 3000L))
          portfolioValue(nodeId("total-risk"), List(child1, child2), Nil)
        }

        val losses = value.outcomeCount.keys.toVector

        // Trial 1: 1000 + 500 = 1500
        // Trial 2: 2000 + 3000 = 5000
        assertTrue(losses == Vector(1500L, 5000L))
      }
    ),

    suite("the mitigated fold, end to end")(
      // Group                    (portfolio)
      // |-- Servers              (portfolio)   insurance policy: cap the total at 18
      // |   |-- DiskFailure      (leaf)  raw  9   cap this leaf at 6
      // |   \-- PowerLoss        (leaf)  raw 14
      // \-- Fraud                (leaf)  raw  3
      test("the inherent reading is the identity instance of the same fold") {
        withCfg(1) {
          val disk  = leafValue(nodeId("DiskFailure"), 9L, Nil)
          val power = leafValue(nodeId("PowerLoss"), 14L, Nil)
          val serv  = portfolioValue(nodeId("Servers"), List(disk, power), Nil)
          val fraud = leafValue(nodeId("Fraud"), 3L, Nil)
          val group = portfolioValue(nodeId("Group"), List(serv, fraud), Nil)

          // Every layer empty, and every node's figures the same object before
          // and after its identity step.
          assertTrue(
            disk.outcomeOf(1) == 9L,
            power.outcomeOf(1) == 14L,
            serv.outcomeOf(1) == 23L,
            fraud.outcomeOf(1) == 3L,
            group.outcomeOf(1) == 26L,
            List(disk, power, serv, fraud, group).forall(v => v.applied.isEmpty && (v.trials eq v.source))
          )
        }
      },
      test("the mitigated reading folds the mitigated children, then applies each node's layer") {
        withCfg(1) {
          val capDisk = List(capRecord("cap-disk", 6L))
          val capServ = List(capRecord("cap-servers", 18L))

          val disk  = leafValue(nodeId("DiskFailure"), 9L, capDisk)
          val power = leafValue(nodeId("PowerLoss"), 14L, Nil)
          val serv  = portfolioValue(nodeId("Servers"), List(disk, power), capServ)
          val fraud = leafValue(nodeId("Fraud"), 3L, Nil)
          val group = portfolioValue(nodeId("Group"), List(serv, fraud), Nil)

          assertTrue(
            disk.outcomeOf(1) == 6L,
            power.outcomeOf(1) == 14L,
            // cap18(6 + 14) = 18: the mitigated children combine first.
            serv.source.outcomeOf(1) == 20L,
            serv.outcomeOf(1) == 18L,
            fraud.outcomeOf(1) == 3L,
            // Group has no layer, so a policy two levels down still reaches it.
            group.outcomeOf(1) == 21L,
            group.applied.isEmpty,
            group.trials eq group.source
          )
        }
      },
      test("a transformed portfolio keeps its children") {
        withCfg(1) {
          val capServ = List(capRecord("cap-servers", 18L))
          val disk    = leafValue(nodeId("DiskFailure"), 9L, Nil)
          val power   = leafValue(nodeId("PowerLoss"), 14L, Nil)
          val losses  = NodeLosses.portfolio(nodeId("Servers"), List(disk, power)).toEither.toOption.get
          val serv    = LossDistribution.decorate(
            losses.nodeId, losses.trials, None, capServ,
            MitigationApplication.run(capServ, _)
          ).toEither.toOption.get

          // The layer sits outside the aggregate claim, so the aggregate still
          // holds its children and still equals their combine.
          assertTrue(
            serv.outcomeOf(1) == 18L,
            losses.children == List(disk, power),
            losses.trials == TrialOutcomes.combine(disk.trials, power.trials)
          )
        }
      },
      test("capping the mitigated children differs from capping the raw aggregate") {
        withCfg(1) {
          val capDisk = List(capRecord("cap-disk", 6L))
          val capServ = List(capRecord("cap-servers", 18L))

          // A different trial of the same tree: DiskFailure draws 20, PowerLoss 4.
          val disk  = leafValue(nodeId("DiskFailure"), 20L, capDisk)
          val power = leafValue(nodeId("PowerLoss"), 4L, Nil)
          val serv  = portfolioValue(nodeId("Servers"), List(disk, power), capServ)

          // cap18(cap6(20) + 4) = 10, not cap18(24) = 18. Capping the raw total
          // would miss that DiskFailure was already pulled from 20 to 6.
          assertTrue(
            disk.outcomeOf(1) == 6L,
            serv.source.outcomeOf(1) == 10L,
            serv.outcomeOf(1) == 10L
          )
        }
      }
    )
  )
}

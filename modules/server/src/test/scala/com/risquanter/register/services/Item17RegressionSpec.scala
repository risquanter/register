package com.risquanter.register.services

import zio.*
import zio.test.*

import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest, RiskTreeUpdateRequest, RiskPortfolioUpdateRequest, RiskLeafUpdateRequest}
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{WorkspaceId, SeedEntityId}
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory
import com.risquanter.register.services.cache.RiskResultResolver
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.testutil.TestHelpers.safeId
import com.risquanter.register.auth.{Checked, Permission, TestChecked}

/** Phase A acceptance probe for TODO item 17 (tactical fix skipped by
  * decision, 2026-07-18 — the content-addressed cache retires the bug class).
  *
  * The bug: `computeAffectedNodes` treated "reparented" and "data changed"
  * as exclusive branches, so a leaf that was BOTH reparented and
  * param-changed in one PUT was never self-invalidated — its stale result
  * was folded into every ancestor re-simulation. Live repro measured root
  * exceedance ≈ 0.58 (stale) where ≈ 0.78 (fresh) is correct.
  *
  * Under content addressing the leaf's new params hash to a new key and
  * simply miss, so the aggregate must match the analytic value for the NEW
  * params: P(any loss) = 1 − ∏(1 − pᵢ). This spec drives the full service
  * path (create → LEC → combined reparent+param update → LEC) and pins the
  * root figure against that analytic value — if any stale entry were served,
  * the figure would sit near the stale product instead.
  */
object Item17RegressionSpec extends ZIOSpecDefault {
  private given Checked[Permission] = TestChecked.value

  private type Env = RiskTreeService & RiskResultResolver

  private val wsId: WorkspaceId = WorkspaceId(safeId("item17-ws"))
  private val entity1: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

  private def service[A](f: RiskTreeService => Task[A]): ZIO[Env, Throwable, A] =
    ZIO.serviceWithZIO[RiskTreeService](f)

  private def shape(minLoss: Long = 1000L, maxLoss: Long = 50000L): DistributionShapeRequest =
    DistributionShapeRequest(
      distributionType = "lognormal",
      percentiles = None, quantiles = None, terms = None,
      minLoss = Some(minLoss), maxLoss = Some(maxLoss)
    )

  private def leafDef(name: String, parent: String, probability: Double): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name = name,
      parentName = Some(parent),
      probability = probability,
      distributionShape = shape(),
      seedVarId = None
    )

  /** Root
    *   ├─ Sub
    *   │   ├─ Cyber  (p = 0.25)  ← will be reparented to Root AND p → 0.6
    *   │   └─ Hw     (p = 0.25)
    *   └─ Fraud      (p = 0.25)
    */
  private val createReq = RiskTreeDefinitionRequest(
    name = "Item17 Tree",
    portfolios = Seq(
      RiskPortfolioDefinitionRequest("Root", None),
      RiskPortfolioDefinitionRequest("Sub", Some("Root"))
    ),
    leaves = Seq(
      leafDef("Cyber", "Sub", 0.25),
      leafDef("Hw", "Sub", 0.25),
      leafDef("Fraud", "Root", 0.25)
    )
  )

  private def leafIdByName(tree: RiskTree, name: String): String =
    tree.nodes.collectFirst { case l: RiskLeaf if l.name.value == name => l.id.value }.get

  private def portfolioUpd(tree: RiskTree, name: String, parent: Option[String]): RiskPortfolioUpdateRequest =
    val id = tree.nodes.collectFirst { case p: RiskPortfolio if p.name.value == name => p.id.value }.get
    RiskPortfolioUpdateRequest(id = id, name = name, parentName = parent)

  private def leafUpd(id: String, name: String, parent: String, probability: Double): RiskLeafUpdateRequest =
    RiskLeafUpdateRequest(
      id = id,
      name = name,
      parentName = Some(parent),
      probability = probability,
      distributionShape = shape()
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Item17Regression (Phase A acceptance probe)")(

      test("combined reparent + param change in ONE update yields the analytic root exceedance for the NEW params") {
        // All leaves have minLoss ≥ 1000, so any occurrence produces loss ≥ 1:
        // P(root loss ≥ 1) = 1 − ∏(1 − pᵢ)
        val staleAnalytic   = 1.0 - math.pow(1.0 - 0.25, 3)                  // 0.578… (bug signature)
        val correctAnalytic = 1.0 - (1.0 - 0.6) * (1.0 - 0.25) * (1.0 - 0.25) // 0.775

        for
          created <- service(_.create(wsId, createReq))
          resolver <- ZIO.service[RiskResultResolver]

          // Simulate the whole tree BEFORE the edit (warm every cache path —
          // this is what made the live bug bite)
          before <- resolver.ensureCached(created, created.rootId, entity1)

          // ONE update: Cyber moves Sub → Root AND its probability 0.25 → 0.6
          updated <- service(_.update(wsId, created.id, RiskTreeUpdateRequest(
            name = "Item17 Tree",
            portfolios = Seq(
              portfolioUpd(created, "Root", None),
              portfolioUpd(created, "Sub", Some("Root"))
            ),
            leaves = Seq(
              leafUpd(leafIdByName(created, "Cyber"), "Cyber", "Root", probability = 0.6),
              leafUpd(leafIdByName(created, "Hw"), "Hw", "Sub", probability = 0.25),
              leafUpd(leafIdByName(created, "Fraud"), "Fraud", "Root", probability = 0.25)
            ),
            newPortfolios = Seq.empty,
            newLeaves = Seq.empty
          )))

          after   <- resolver.ensureCached(updated, updated.rootId, entity1)
          measured = after.probOfExceedance(1L)
        yield assertTrue(
          // 10K trials ⇒ MC σ ≈ 0.004; ±0.02 pins the correct value…
          math.abs(measured - correctAnalytic) < 0.02,
          // …and is 10σ+ away from the stale-figure signature (≈ 0.578)
          math.abs(measured - staleAnalytic) > 0.1,
          before.probOfExceedance(1L) > 0.0
        )
      }
    ).provide(
      RiskTreeServiceLive.layer,
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.configs.TestConfigs.simulationLayer,
      com.risquanter.register.services.cache.RiskResultResolverLive.layer,
      com.risquanter.register.services.cache.CacheScope.layer,
      com.risquanter.register.services.pipeline.InvalidationHandler.live,
      com.risquanter.register.services.sse.SSEHub.live,
      com.risquanter.register.configs.TestConfigs.simulationLayer >>> SimulationSemaphore.layer,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> TracingLive.console,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> MetricsLive.console
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}

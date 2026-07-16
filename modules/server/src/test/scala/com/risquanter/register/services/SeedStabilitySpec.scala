package com.risquanter.register.services

import zio.*
import zio.test.*

import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest, RiskTreeUpdateRequest, RiskPortfolioUpdateRequest, RiskLeafUpdateRequest}
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio, TrialId, Loss}
import com.risquanter.register.domain.data.iron.{WorkspaceId, SeedEntityId}
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory
import com.risquanter.register.services.cache.RiskResultResolver
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.testutil.TestHelpers.safeId
import com.risquanter.register.auth.{Checked, Permission, TestChecked}

/** Layer 4 of the seed-identity test plan (PLAN-SEED-IDENTITY §11):
  * reproducibility integration at the **figure** level. Where
  * `RiskTreeServiceLiveSpec` proves that recreation yields the same *seed
  * IDs*, this spec proves the property those IDs exist for: the simulated
  * outcomes themselves are stable under recreation and rename, respond to
  * edits with common-random-numbers locality, differ across entities, and
  * survive an export → import round trip.
  *
  * "Figures" here are `RiskResult.outcomes` (trial → loss maps): node IDs are
  * ULIDs and legitimately differ between tree instances, so outcome maps are
  * the byte-identical payload the plan pins.
  */
object SeedStabilitySpec extends ZIOSpecDefault {
  private given Checked[Permission] = TestChecked.value

  private type Env = RiskTreeService & RiskResultResolver

  private val wsId: WorkspaceId = WorkspaceId(safeId("seed-stability-ws"))
  private def entity(v: Long): SeedEntityId.SeedEntityId = SeedEntityId.fromLong(v).toOption.get
  private val entity1 = entity(1L)
  private val entity2 = entity(2L)

  /** Key used for the root node's figures in the per-tree figure map; leaf
    * figures are keyed by leaf name (SafeName forbids angle brackets, so no
    * collision is possible).
    */
  private val RootKey = "<root>"

  private def service[A](f: RiskTreeService => Task[A]): ZIO[Env, Throwable, A] =
    ZIO.serviceWithZIO[RiskTreeService](f)

  /** Resolve every leaf's and the root's outcomes, keyed by leaf name / RootKey. */
  private def figures(tree: RiskTree, entity: SeedEntityId.SeedEntityId): ZIO[Env, Throwable, Map[String, Map[TrialId, Loss]]] =
    ZIO.serviceWithZIO[RiskResultResolver] { resolver =>
      val leaves = tree.nodes.collect { case l: RiskLeaf => l }
      for
        leafFigs <- ZIO.foreach(leaves)(l => resolver.ensureCached(tree, l.id, entity).map(r => l.name.value -> r.outcomes))
        root     <- resolver.ensureCached(tree, tree.rootId, entity)
      yield leafFigs.toMap + (RootKey -> root.outcomes)
    }

  private def shape(minLoss: Long, maxLoss: Long): DistributionShapeRequest =
    DistributionShapeRequest(
      distributionType = "lognormal",
      percentiles = None, quantiles = None, terms = None,
      minLoss = Some(minLoss), maxLoss = Some(maxLoss)
    )

  private def leafDef(name: String, parent: String, minLoss: Long = 1000L, seedVarId: Option[Long] = None): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name = name,
      parentName = Some(parent),
      probability = 0.25,
      distributionShape = shape(minLoss, 50000L),
      seedVarId = seedVarId
    )

  /** Two-leaf tree under "Stable Root": "Cyber Attack" and "Fraud". */
  private def treeReq(treeName: String): RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = treeName,
      portfolios = Seq(RiskPortfolioDefinitionRequest("Stable Root", None)),
      leaves = Seq(leafDef("Cyber Attack", "Stable Root"), leafDef("Fraud", "Stable Root"))
    )

  private def seedsByName(tree: RiskTree): Map[String, Long] =
    tree.nodes.collect { case l: RiskLeaf => l.name.value -> l.seedVarId.value }.toMap

  private def leafIdByName(tree: RiskTree, name: String): String =
    tree.nodes.collectFirst { case l: RiskLeaf if l.name.value == name => l.id.value }.get

  private def portfolioUpd(tree: RiskTree, name: String): RiskPortfolioUpdateRequest =
    val id = tree.nodes.collectFirst { case p: RiskPortfolio if p.name.value == name => p.id.value }.get
    RiskPortfolioUpdateRequest(id = id, name = name, parentName = None)

  private def leafUpd(id: String, name: String, parent: String, minLoss: Long = 1000L): RiskLeafUpdateRequest =
    RiskLeafUpdateRequest(
      id = id,
      name = name,
      parentName = Some(parent),
      probability = 0.25,
      distributionShape = shape(minLoss, 50000L)
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("SeedStability (PLAN §11 Layer 4)")(

      test("same logical tree created twice (different ULIDs) → byte-identical figures at every node") {
        for
          a  <- service(_.create(wsId, treeReq("Recreate Figures A")))
          b  <- service(_.create(wsId, treeReq("Recreate Figures B")))
          fa <- figures(a, entity1)
          fb <- figures(b, entity1)
        yield assertTrue(
          fa == fb,
          // guard against vacuous equality of empty outcome maps
          fa(RootKey).nonEmpty
        )
      },

      test("editing one leaf's loss distribution leaves untouched leaves byte-identical and keeps the edited leaf's occurrence trials (CRN)") {
        for
          created <- service(_.create(wsId, treeReq("CRN Edit Tree")))
          before  <- figures(created, entity1)
          updated <- service(_.update(wsId, created.id, RiskTreeUpdateRequest(
            name = "CRN Edit Tree",
            portfolios = Seq(portfolioUpd(created, "Stable Root")),
            leaves = Seq(
              leafUpd(leafIdByName(created, "Cyber Attack"), "Cyber Attack", "Stable Root", minLoss = 5000L),
              leafUpd(leafIdByName(created, "Fraud"), "Fraud", "Stable Root")
            ),
            newPortfolios = Seq.empty,
            newLeaves = Seq.empty
          )))
          after <- figures(updated, entity1)
        yield assertTrue(
          // untouched leaf: byte-identical
          after("Fraud") == before("Fraud"),
          // edited leaf: same random stream → same occurrence trials, different losses
          after("Cyber Attack").keySet == before("Cyber Attack").keySet,
          after("Cyber Attack") != before("Cyber Attack"),
          // the aggregate reflects the edit
          after(RootKey) != before(RootKey),
          before("Cyber Attack").nonEmpty
        )
      },

      test("renaming the tree and a leaf changes no figure") {
        for
          created <- service(_.create(wsId, treeReq("Rename Figures Tree")))
          before  <- figures(created, entity1)
          updated <- service(_.update(wsId, created.id, RiskTreeUpdateRequest(
            name = "Rename Figures Tree v2",
            portfolios = Seq(portfolioUpd(created, "Stable Root")),
            leaves = Seq(
              leafUpd(leafIdByName(created, "Cyber Attack"), "Cyber Attack Renamed", "Stable Root"),
              leafUpd(leafIdByName(created, "Fraud"), "Fraud", "Stable Root")
            ),
            newPortfolios = Seq.empty,
            newLeaves = Seq.empty
          )))
          after <- figures(updated, entity1)
        yield assertTrue(
          after("Cyber Attack Renamed") == before("Cyber Attack"),
          after("Fraud") == before("Fraud"),
          after(RootKey) == before(RootKey),
          before(RootKey).nonEmpty
        )
      },

      test("same spec under two seedEntityIds → different figures (entity isolation)") {
        for
          a  <- service(_.create(wsId, treeReq("Entity Isolation A")))
          b  <- service(_.create(wsId, treeReq("Entity Isolation B")))
          fa <- figures(a, entity1)
          fb <- figures(b, entity2)
        yield assertTrue(
          fa("Cyber Attack") != fb("Cyber Attack"),
          fa("Fraud") != fb("Fraud"),
          fa(RootKey) != fb(RootKey),
          fa(RootKey).nonEmpty && fb(RootKey).nonEmpty
        )
      },

      test("export → import with provided seedVarIds and matching entity → identical figures (§8 round trip)") {
        for
          // Build a tree whose surviving seedVarIds are non-contiguous ({1,3}) so
          // that a fresh auto-assignment ({1,2}) could NOT reproduce them — the
          // round trip genuinely depends on the provided IDs.
          created <- service(_.create(wsId, RiskTreeDefinitionRequest(
            name = "Export Source Tree",
            portfolios = Seq(RiskPortfolioDefinitionRequest("Stable Root", None)),
            leaves = Seq(leafDef("Alpha", "Stable Root"), leafDef("Beta", "Stable Root"), leafDef("Gamma", "Stable Root"))
          )))
          pruned <- service(_.update(wsId, created.id, RiskTreeUpdateRequest(
            name = "Export Source Tree",
            portfolios = Seq(portfolioUpd(created, "Stable Root")),
            leaves = Seq(
              leafUpd(leafIdByName(created, "Alpha"), "Alpha", "Stable Root"),
              leafUpd(leafIdByName(created, "Gamma"), "Gamma", "Stable Root")
            ),
            newPortfolios = Seq.empty,
            newLeaves = Seq.empty
          )))
          exported = seedsByName(pruned)
          sourceFigs <- figures(pruned, entity1)

          imported <- service(_.create(wsId, RiskTreeDefinitionRequest(
            name = "Import Target Tree",
            portfolios = Seq(RiskPortfolioDefinitionRequest("Stable Root", None)),
            leaves = Seq(
              leafDef("Alpha", "Stable Root", seedVarId = Some(exported("Alpha"))),
              leafDef("Gamma", "Stable Root", seedVarId = Some(exported("Gamma")))
            )
          )))
          importedFigs <- figures(imported, entity1)
        yield assertTrue(
          exported == Map("Alpha" -> 1L, "Gamma" -> 3L),
          seedsByName(imported) == exported,
          importedFigs == sourceFigs,
          sourceFigs(RootKey).nonEmpty
        )
      }
    ).provide(
      RiskTreeServiceLive.layer,
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.configs.TestConfigs.simulationLayer,
      com.risquanter.register.services.cache.RiskResultResolverLive.layer,
      com.risquanter.register.services.cache.TreeCacheManager.layer,
      com.risquanter.register.services.pipeline.InvalidationHandler.live,
      com.risquanter.register.services.sse.SSEHub.live,
      com.risquanter.register.configs.TestConfigs.simulationLayer >>> SimulationSemaphore.layer,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> TracingLive.console,
      com.risquanter.register.configs.TestConfigs.telemetryLayer >>> MetricsLive.console
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}

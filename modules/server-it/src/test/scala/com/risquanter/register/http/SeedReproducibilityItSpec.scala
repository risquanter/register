package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*

import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, LECPoint, TrialId, Loss}
import com.risquanter.register.domain.data.iron.{WorkspaceId, SeedEntityId, NodeId}
import com.risquanter.register.http.HttpTestHarness.HarnessConfig
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest}
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse}
import com.risquanter.register.http.support.{SttpClientFixture, DemoSpecSupport}
import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.infra.irmin.IrminClientLive
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.services.{RiskTreeService, RiskTreeServiceLive, SimulationSemaphore}
import com.risquanter.register.services.cache.{RiskResultResolver, RiskResultResolverLive, CacheScope}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.safeId

/** System-level reproducibility integration (PLAN-SEED-IDENTITY §11):
  *
  *   1. persist → "restart" → re-simulate → identical figures: the same Irmin
  *      store is read back through a completely fresh server stack (new repo,
  *      client, cache, resolver — the in-process equivalent of a restarted
  *      server container), and re-simulation reproduces every figure.
  *   2. The §8 export → import round trip at system level: seed identities
  *      read from one server's API reproduce identical figures on a different
  *      server instance when the seedEntityId is pinned.
  *   3. Order-independence: the demo-simple and demo-enterprise trees produce
  *      identical figures regardless of creation order — the original item-12
  *      symptom, asserted directly.
  */
object SeedReproducibilityItSpec extends ZIOSpecDefault:
  private given Checked[Permission] = TestChecked.value

  private val RootKey = "<root>"
  private val wsId: WorkspaceId = WorkspaceId(safeId("seed-repro-it-ws"))
  private val entity1: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

  // ── Part 1: restart-equivalent persistence (Irmin-backed service stack) ──

  private type Stack = RiskTreeService & RiskResultResolver & RiskTreeRepository

  /** A complete, independent service stack over the given Irmin instance —
    * building it twice models two server processes sharing one store.
    */
  private def freshStack(cfg: IrminConfig): ZLayer[Any, Throwable, Stack] =
    ZLayer.make[Stack](
      ZLayer.succeed(cfg),
      IrminClientLive.layer,
      RiskTreeRepositoryIrmin.layer,
      RiskTreeServiceLive.layer,
      TestConfigs.simulationLayer,
      RiskResultResolverLive.layer,
      CacheScope.layer,
      InvalidationHandler.live,
      SSEHub.live,
      TestConfigs.simulationLayer >>> SimulationSemaphore.layer,
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console
    )

  /** Every leaf's and the root's outcomes, keyed by leaf name / RootKey. */
  private def figures(resolver: RiskResultResolver)(tree: RiskTree, entity: SeedEntityId.SeedEntityId): Task[Map[String, Map[TrialId, Loss]]] =
    val leaves = tree.nodes.collect { case l: RiskLeaf => l }
    for
      leafFigs <- ZIO.foreach(leaves)(l => resolver.ensureCached(tree, l.id, entity).map(r => l.name.value -> r.outcomes))
      root     <- resolver.ensureCached(tree, tree.rootId, entity)
    yield leafFigs.toMap + (RootKey -> root.outcomes)

  private def leafDef(name: String, parent: String): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name = name,
      parentName = Some(parent),
      probability = 0.25,
      distributionShape = DistributionShapeRequest(
        distributionType = "lognormal",
        percentiles = None, quantiles = None, terms = None,
        minLoss = Some(1000L), maxLoss = Some(50000L)
      )
    )

  private def treeReq(treeName: String): RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = treeName,
      portfolios = Seq(RiskPortfolioDefinitionRequest("Repro Root", None)),
      leaves = Seq(leafDef("Cyber Attack", "Repro Root"), leafDef("Fraud", "Repro Root"))
    )

  // ── Parts 2 & 3: full HTTP servers (in-memory repo, production sim config) ──

  private val prodHarness = HarnessConfig(simulation = DemoSpecSupport.productionSimulationConfig)

  /** Run `f` against a freshly started HTTP server, then tear it down. */
  private def withServer[A](f: SttpClientFixture.Client => Task[A]): Task[A] =
    ZIO.scoped {
      ZLayer.makeSome[Scope, SttpClientFixture.Client](
        HttpTestHarness.inMemoryServer(prodHarness),
        SttpClientFixture.layer
      ).build.map(_.get[SttpClientFixture.Client]).flatMap(f)
    }

  private def bootstrap(client: SttpClientFixture.Client, req: RiskTreeDefinitionRequest, seedEntityId: Option[Long] = None): Task[WorkspaceBootstrapResponse] =
    val u = seedEntityId match
      case Some(e) => uri"${client.baseUrl}/workspaces?seedEntityId=$e"
      case None    => uri"${client.baseUrl}/workspaces"
    basicRequest.post(u).body(req)
      .response(asJson[WorkspaceBootstrapResponse]).send(client.backend)
      .flatMap(r => ZIO.fromEither(r.body))

  private def addTree(client: SttpClientFixture.Client, key: String, req: RiskTreeDefinitionRequest): Task[SimulationResponse] =
    basicRequest.post(uri"${client.baseUrl}/w/$key/risk-trees").body(req)
      .response(asJson[SimulationResponse]).send(client.backend)
      .flatMap(r => ZIO.fromEither(r.body))

  private def structure(client: SttpClientFixture.Client, key: String, treeId: String): Task[RiskTree] =
    basicRequest.get(uri"${client.baseUrl}/w/$key/risk-trees/$treeId/structure")
      .response(asJson[Option[RiskTree]]).send(client.backend)
      .flatMap(r => ZIO.fromEither(r.body))
      .flatMap(opt => ZIO.fromOption(opt).orElseFail(new RuntimeException(s"structure not found for tree $treeId")))

  /** LEC curves + quantiles for the root and every leaf, keyed by node name —
    * node IDs are ULIDs and differ across servers, names are the stable key.
    */
  private given zio.json.JsonEncoder[List[NodeId]] = zio.json.JsonEncoder.list[NodeId]

  private def curvesByName(client: SttpClientFixture.Client, key: String, tree: RiskTree): Task[Map[String, (Vector[LECPoint], Map[String, Double])]] =
    val nodeIds: List[NodeId] = tree.rootId :: tree.nodes.collect { case l: RiskLeaf => l.id }.toList
    basicRequest.post(uri"${client.baseUrl}/w/$key/risk-trees/${tree.id.value}/nodes/lec-multi")
      .body(nodeIds)
      .response(asJson[Map[NodeId, LECNodeCurve]]).send(client.backend)
      .flatMap(r => ZIO.fromEither(r.body))
      .map(_.values.map(c => c.name -> (c.curve, c.quantiles)).toMap)

  override def spec =
    suite("SeedReproducibilityItSpec (PLAN §11 system level)")(

      test("figures survive a restart: persist to Irmin, reload through a fresh stack, re-simulate → identical") {
        for
          cfg <- ZIO.service[IrminConfig]
          persisted <- ZIO.scoped {
            freshStack(cfg).build.flatMap { env =>
              for
                tree <- env.get[RiskTreeService].create(wsId, treeReq("Restart Reload Tree"))
                figs <- figures(env.get[RiskResultResolver])(tree, entity1)
              yield (tree.id, figs)
            }
          }
          (treeId, before) = persisted
          after <- ZIO.scoped {
            freshStack(cfg).build.flatMap { env =>
              for
                treeOpt <- env.get[RiskTreeRepository].getById(wsId, treeId)
                tree    <- ZIO.fromOption(treeOpt).orElseFail(new RuntimeException(s"tree ${treeId.value} not found after reload"))
                figs    <- figures(env.get[RiskResultResolver])(tree, entity1)
              yield figs
            }
          }
        yield assertTrue(after == before, before(RootKey).nonEmpty)
      },

      test("export → import onto a fresh server with pinned seedEntityId reproduces identical figures (§8 round trip, system level)") {
        val entityPin = 4242L
        val sourceReq = treeReq("Round Trip Tree")
        for
          exported <- withServer { client =>
            for
              boot   <- bootstrap(client, sourceReq, seedEntityId = Some(entityPin))
              key     = boot.workspaceKey.reveal
              tree   <- structure(client, key, boot.tree.id.value)
              seeds   = tree.nodes.collect { case l: RiskLeaf => (l.name.value: String) -> l.seedVarId.value }.toMap
              curves <- curvesByName(client, key, tree)
            yield (seeds, curves)
          }
          (seeds, sourceCurves) = exported
          importedCurves <- withServer { client =>
            val importReq = sourceReq.copy(
              leaves = sourceReq.leaves.map(l => l.copy(seedVarId = Some(seeds(l.name))))
            )
            for
              boot   <- bootstrap(client, importReq, seedEntityId = Some(entityPin))
              key     = boot.workspaceKey.reveal
              tree   <- structure(client, key, boot.tree.id.value)
              curves <- curvesByName(client, key, tree)
            yield curves
          }
        yield assertTrue(
          importedCurves == sourceCurves,
          sourceCurves.values.exists(_._1.nonEmpty)
        )
      },

      test("demo-simple and demo-enterprise trees yield identical figures in both creation orders (original item-12 symptom)") {
        def run(first: RiskTreeDefinitionRequest, second: RiskTreeDefinitionRequest): Task[Map[String, Map[String, (Vector[LECPoint], Map[String, Double])]]] =
          withServer { client =>
            for
              boot    <- bootstrap(client, first)
              key      = boot.workspaceKey.reveal
              added   <- addTree(client, key, second)
              tree1   <- structure(client, key, boot.tree.id.value)
              tree2   <- structure(client, key, added.id.value)
              curves1 <- curvesByName(client, key, tree1)
              curves2 <- curvesByName(client, key, tree2)
            yield Map(first.name -> curves1, second.name -> curves2)
          }
        for
          simpleFirst     <- run(DemoSimpleScriptSpec.demoTreeRequest, DemoEnterpriseScriptSpec.demoTreeRequest)
          enterpriseFirst <- run(DemoEnterpriseScriptSpec.demoTreeRequest, DemoSimpleScriptSpec.demoTreeRequest)
        yield assertTrue(
          simpleFirst == enterpriseFirst,
          simpleFirst.values.forall(_.values.exists(_._1.nonEmpty))
        )
      }
    ).provideLayerShared(IrminCompose.irminConfigLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

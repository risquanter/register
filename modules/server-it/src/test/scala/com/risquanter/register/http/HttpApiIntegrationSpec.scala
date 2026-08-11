package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.ziojson.*
import sttp.model.StatusCode
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest, CreateScenarioRequest, ScenarioSourceDto}
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse, ScenarioResponse}
import com.risquanter.register.domain.data.{RiskLeaf, RiskTree}
import com.risquanter.register.domain.data.iron.{ScenarioName, BranchChoice}
import com.risquanter.register.http.support.SttpClientFixture
import com.risquanter.register.testcontainers.IrminCompose
import io.github.iltotore.iron.*

/** Integration tests exercising the live HTTP server with Irmin-backed persistence.
  *
  * All tree operations use the workspace-scoped API surface (ADR-021 §3):
  *   - `POST /workspaces`                          → bootstrap workspace + first tree
  *   - `GET  /w/{key}/risk-trees`                   → list trees in workspace
  *   - `GET  /w/{key}/risk-trees/{treeId}/structure` → full tree structure
  *
  * Auth layers: `UserContextExtractor.noOp` + `AuthorizationServiceNoOp` (Wave 1).
  * No `x-user-id` header is sent — capability-only mode (Layer 0).
  *
  * @see ADR-021 (Capability URLs), AUTHORIZATION-PLAN.md (Wave 1 regression gate)
  */
object HttpApiIntegrationSpec extends ZIOSpecDefault:

  private val harnessLayer =
    ZLayer.makeSome[Scope, SttpClientFixture.Client](
      HttpTestHarness.irminServer(IrminCompose.irminConfigLayer),
      SttpClientFixture.layer
    )

  private def sampleRequest(suffix: String = java.util.UUID.randomUUID().toString.take(8)): RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = s"Tree-$suffix",
      portfolios = Seq(
        RiskPortfolioDefinitionRequest(name = "Root", parentName = None)
      ),
      leaves = Seq(
        RiskLeafDefinitionRequest(
          name = "Leaf 1",
          parentName = Some("Root"),
          probability = 0.1,
          distributionShape = DistributionShapeRequest(
            distributionType = "lognormal",
            minLoss = Some(1000L), maxLoss = Some(2000L),
            percentiles = None, quantiles = None, terms = None
          )
        ),
        RiskLeafDefinitionRequest(
          name = "Leaf 2",
          parentName = Some("Root"),
          probability = 0.2,
          distributionShape = DistributionShapeRequest(
            distributionType = "lognormal",
            minLoss = Some(1500L), maxLoss = Some(3000L),
            percentiles = None, quantiles = None, terms = None
          )
        )
      )
    )

  override def spec =
    suite("HttpApiIntegrationSpec")(
      test("health endpoint returns OK (Irmin-backed server)") {
        for
          client   <- ZIO.service[SttpClientFixture.Client]
          response <- basicRequest.header("X-Branch", "main").get(uri"${client.baseUrl}/health").send(client.backend)
        yield assertTrue(response.code.isSuccess) && assertTrue(response.body.exists(_.contains("healthy")))
      },
      test("workspace bootstrap, list, and get structure via workspace-scoped API") {
        val request = sampleRequest()
        for
          client <- ZIO.service[SttpClientFixture.Client]

          // 1) Bootstrap workspace (POST /workspaces) → creates workspace + first tree
          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(request)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          createdTree = bootstrap.tree

          // 2) List trees in workspace (GET /w/{key}/risk-trees)
          listResp <- basicRequest.header("X-Branch", "main")
            .get(uri"${client.baseUrl}/w/$key/risk-trees")
            .response(asJson[List[SimulationResponse]])
            .send(client.backend)
          listed <- ZIO.fromEither(listResp.body)

          // 3) Get full tree structure (GET /w/{key}/risk-trees/{treeId}/structure)
          structResp <- basicRequest.header("X-Branch", "main")
            .get(uri"${client.baseUrl}/w/$key/risk-trees/${createdTree.id.value}/structure")
            .response(asJson[Option[RiskTree]])
            .send(client.backend)
          structure <- ZIO.fromEither(structResp.body)
        yield assertTrue(createdTree.name == request.name) &&
          assertTrue(listed.exists(_.id == createdTree.id)) &&
          assertTrue(structure.exists(_.id == createdTree.id))
      },
      test("bootstrap honours provided seedEntityId and seedVarId; structure exposes assigned seeds (PLAN §7)") {
        // Export→import round-trip prerequisite: caller pins both seed axes and
        // reads them back from the response / structure endpoint.
        val base = sampleRequest()
        val request = base.copy(
          leaves = base.leaves.zipWithIndex.map { case (l, i) => l.copy(seedVarId = Some(41L + i)) }
        )
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces?seedEntityId=4242")
            .body(request)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal

          structResp <- basicRequest.header("X-Branch", "main")
            .get(uri"${client.baseUrl}/w/$key/risk-trees/${bootstrap.tree.id.value}/structure")
            .response(asJson[Option[RiskTree]])
            .send(client.backend)
          structure <- ZIO.fromEither(structResp.body)
          leafSeeds  = structure.toSeq.flatMap(_.nodes.collect { case l: RiskLeaf => l.seedVarId.value }).sorted
        yield assertTrue(bootstrap.seedEntityId.value == 4242L) &&
          assertTrue(leafSeeds == Seq(41L, 42L))
      },
      test("create additional tree in workspace via POST /w/{key}/risk-trees") {
        val bootstrapReq = sampleRequest()
        val secondTreeReq = sampleRequest()
        for
          client <- ZIO.service[SttpClientFixture.Client]

          // Bootstrap workspace with first tree
          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(bootstrapReq)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal

          // Create additional tree (POST /w/{key}/risk-trees)
          createResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/w/$key/risk-trees")
            .body(secondTreeReq)
            .response(asJson[SimulationResponse])
            .send(client.backend)
          created <- ZIO.fromEither(createResp.body)

          // Verify both trees appear in listing
          listResp <- basicRequest.header("X-Branch", "main")
            .get(uri"${client.baseUrl}/w/$key/risk-trees")
            .response(asJson[List[SimulationResponse]])
            .send(client.backend)
          listed <- ZIO.fromEither(listResp.body)
        yield assertTrue(createResp.code.isSuccess) &&
          assertTrue(created.name == secondTreeReq.name) &&
          assertTrue(listed.length == 2) &&
          assertTrue(listed.exists(_.id == bootstrap.tree.id)) &&
          assertTrue(listed.exists(_.id == created.id))
      },
      test("get tree summary by ID via GET /w/{key}/risk-trees/{treeId}") {
        val request = sampleRequest()
        for
          client <- ZIO.service[SttpClientFixture.Client]

          // Bootstrap workspace
          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(request)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id

          // Get tree summary (GET /w/{key}/risk-trees/{treeId})
          summaryResp <- basicRequest.header("X-Branch", "main")
            .get(uri"${client.baseUrl}/w/$key/risk-trees/${treeId.value}")
            .response(asJson[Option[SimulationResponse]])
            .send(client.backend)
          summary <- ZIO.fromEither(summaryResp.body)
        yield assertTrue(summaryResp.code.isSuccess) &&
          assertTrue(summary.exists(_.id == treeId)) &&
          assertTrue(summary.exists(_.name == request.name))
      },
      test("tree-name uniqueness is checked against the write branch, honouring fork inheritance (§C1)") {
        val treeName     = "Shared Risk Tree"
        val bootstrapReq = sampleRequest().copy(name = treeName)   // tree N on main
        val dupReq       = sampleRequest().copy(name = treeName)   // same name, re-used for each scenario create
        val scenarioName = "c1-scenario"
        for
          client <- ZIO.service[SttpClientFixture.Client]

          // Bootstrap workspace on main with tree N.
          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(bootstrapReq)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key             = bootstrap.workspaceKey.reveal
          inheritedTreeId = bootstrap.tree.id

          // Fork a scenario from main — it inherits tree N.
          scenarioResp <- basicRequest
            .post(uri"${client.baseUrl}/w/$key/scenarios")
            .body(CreateScenarioRequest(
              name = ScenarioName.fromString(scenarioName).toOption.get,
              source = ScenarioSourceDto.Branch(BranchChoice.Main)
            ))
            .response(asJson[ScenarioResponse])
            .send(client.backend)
          _ <- ZIO.fromEither(scenarioResp.body)

          // Creating N on the scenario is rejected while the inherited tree exists.
          dupWhileInheritedResp <- basicRequest.header("X-Branch", scenarioName)
            .post(uri"${client.baseUrl}/w/$key/risk-trees")
            .body(dupReq)
            .send(client.backend)

          // Delete the inherited tree on the scenario, freeing the name there.
          deleteResp <- basicRequest.header("X-Branch", scenarioName)
            .delete(uri"${client.baseUrl}/w/$key/risk-trees/${inheritedTreeId.value}")
            .send(client.backend)

          // N is now free on the scenario → create succeeds.
          freedCreateResp <- basicRequest.header("X-Branch", scenarioName)
            .post(uri"${client.baseUrl}/w/$key/risk-trees")
            .body(dupReq)
            .response(asJson[SimulationResponse])
            .send(client.backend)
          freedCreated <- ZIO.fromEither(freedCreateResp.body)

          // A second N on the scenario is rejected again.
          dupAgainResp <- basicRequest.header("X-Branch", scenarioName)
            .post(uri"${client.baseUrl}/w/$key/risk-trees")
            .body(dupReq)
            .send(client.backend)
        yield assertTrue(dupWhileInheritedResp.code == StatusCode.BadRequest) &&
          assertTrue(deleteResp.code.isSuccess) &&
          assertTrue(freedCreateResp.code.isSuccess) &&
          assertTrue(freedCreated.name == treeName) &&
          assertTrue(dupAgainResp.code == StatusCode.BadRequest)
      }
    ).provideLayerShared(harnessLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock


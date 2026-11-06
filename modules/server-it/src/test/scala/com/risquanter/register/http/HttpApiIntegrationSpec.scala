package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*
import sttp.model.StatusCode
import zio.json.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest, CreateScenarioRequest, ScenarioSourceDto, RiskTreeUpdateRequest}
import com.risquanter.register.http.responses.{ScenarioResponse, TreeListResponse, TreeSummaryResponse, WorkspaceBootstrapResponse}
import com.risquanter.register.http.errors.{NotFoundKind, RejectionDetail, WireError}
import com.risquanter.register.domain.data.{RiskLeaf, RiskTree}
import com.risquanter.register.domain.data.iron.{ScenarioName, BranchChoice}
import com.risquanter.register.http.support.SttpClientFixture
import com.risquanter.register.testcontainers.IrminCompose

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
          distributionShape = DistributionShapeRequest.Lognormal(minLoss = 1000L, maxLoss = 2000L)
        ),
        RiskLeafDefinitionRequest(
          name = "Leaf 2",
          parentName = Some("Root"),
          probability = 0.2,
          distributionShape = DistributionShapeRequest.Lognormal(minLoss = 1500L, maxLoss = 3000L)
        )
      )
    )

  override def spec =
    suite("HttpApiIntegrationSpec")(
      test("health endpoint returns OK (Irmin-backed server)") {
        for
          client   <- ZIO.service[SttpClientFixture.Client]
          response <- basicRequest.header("X-Branch", "main").get(uri"${client.baseUrl}/health").send(client.backend)
        yield assertTrue(response.code.isSuccess) && assertTrue(response.body.exists(_.contains("Healthy")))
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
            .response(asJson[TreeListResponse])
            .send(client.backend)
          listed <- ZIO.fromEither(listResp.body)

          // 3) Get full tree structure (GET /w/{key}/risk-trees/{treeId}/structure)
          structResp <- basicRequest.header("X-Branch", "main")
            .get(uri"${client.baseUrl}/w/$key/risk-trees/${createdTree.id.value}/structure")
            .response(asJson[RiskTree])
            .send(client.backend)
          structure <- ZIO.fromEither(structResp.body)
        yield assertTrue(
          // The bootstrap is a create: it names the workspace it made, as a
          // reference relative to `/workspaces`.
          bootstrapResp.code == StatusCode.Created,
          bootstrapResp.header("Location").contains(s"w/$key"),
          createdTree.name.value == request.name,
          listed.trees.exists(_.id == createdTree.id),
          structure.id == createdTree.id
        )
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
            .response(asJson[RiskTree])
            .send(client.backend)
          structure <- ZIO.fromEither(structResp.body)
          leafSeeds  = structure.nodes.collect { case l: RiskLeaf => l.seedVarId.value }.sorted
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
            .response(asJson[TreeSummaryResponse])
            .send(client.backend)
          created <- ZIO.fromEither(createResp.body)

          // Verify both trees appear in listing
          listResp <- basicRequest.header("X-Branch", "main")
            .get(uri"${client.baseUrl}/w/$key/risk-trees")
            .response(asJson[TreeListResponse])
            .send(client.backend)
          listed <- ZIO.fromEither(listResp.body)
        yield assertTrue(
          createResp.code == StatusCode.Created,
          // The new tree's reference is relative to the collection, so the
          // header never carries the workspace key.
          createResp.header("Location").contains(s"risk-trees/${created.id.value}"),
          created.name.value == secondTreeReq.name,
          listed.trees.length == 2,
          listed.trees.exists(_.id == bootstrap.tree.id),
          listed.trees.exists(_.id == created.id)
        )
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
            .response(asJson[TreeSummaryResponse])
            .send(client.backend)
          summary <- ZIO.fromEither(summaryResp.body)
        yield assertTrue(summaryResp.code.isSuccess) &&
          assertTrue(summary.id == treeId) &&
          assertTrue(summary.name.value == request.name)
      },
      /** A tree created on main after a scenario forked is held by the workspace
        * and absent on that scenario. The answer names which absence, and the
        * branch it was looked for on, so the browser can say so; it carries
        * neither identifier.
        */
      test("a held tree absent on the named branch answers 404 naming the branch") {
        val scenarioName = "fork-before-tree"
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(sampleRequest())
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal

          scenarioResp <- basicRequest
            .post(uri"${client.baseUrl}/w/$key/scenarios")
            .body(CreateScenarioRequest(
              name = ScenarioName.fromString(scenarioName).toOption.get,
              source = ScenarioSourceDto.Branch(BranchChoice.Main)
            ))
            .response(asJson[ScenarioResponse])
            .send(client.backend)
          _ <- ZIO.fromEither(scenarioResp.body)

          // Created on main after the fork, so the scenario never saw it.
          createResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/w/$key/risk-trees")
            .body(sampleRequest())
            .response(asJson[TreeSummaryResponse])
            .send(client.backend)
          created <- ZIO.fromEither(createResp.body)

          absentResp <- basicRequest.header("X-Branch", scenarioName)
            .get(uri"${client.baseUrl}/w/$key/risk-trees/${created.id.value}/structure")
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = absentResp.body
          absence   <- ZIO.fromEither(
            rawBody.fromJson[WireError.NotFound]
              .left.map(err => s"Failed to decode a not-found answer: $err\nBody was: $rawBody")
          )
        yield assertTrue(
          absentResp.code == StatusCode.NotFound,
          absence.absence == NotFoundKind.TreeNotOnBranch(
            BranchChoice.Scenario(ScenarioName.fromString(scenarioName).toOption.get)),
          !rawBody.contains(key),
          !rawBody.contains(created.id.value)
        )
      },
      /** The route names a tree, so content it refuses is that tree's content
        * rather than an unreadable request, and the two answer different
        * statuses. The body names the tree because the caller put it in the
        * path of this same request (ADR-036 §4).
        */
      test("content refused on a tree-addressed route answers 422 naming that tree") {
        val emptyUpdate = RiskTreeUpdateRequest(
          name = "Tree with nothing in it",
          portfolios = Seq.empty,
          leaves = Seq.empty,
          newPortfolios = Seq.empty,
          newLeaves = Seq.empty
        )
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(sampleRequest())
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id

          // A tree with no nodes has no root, which the topology rule refuses
          // after the request has decoded — so it is content, not shape.
          updateResp <- basicRequest.header("X-Branch", "main")
            .put(uri"${client.baseUrl}/w/$key/risk-trees/${treeId.value}")
            .body(emptyUpdate)
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = updateResp.body
          refusal   <- ZIO.fromEither(
            rawBody.fromJson[WireError.ValidationRejected]
              .left.map(err => s"Failed to decode a content rejection: $err\nBody was: $rawBody")
          )
        yield assertTrue(
          updateResp.code.code == 422,
          refusal.tree == treeId,
          refusal.detail match
            case RejectionDetail.Fields(errors) => errors.nonEmpty
            case RejectionDetail.Query(_)       => false
        ) && assertTrue(!rawBody.contains(key))
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
            .response(asJson[TreeSummaryResponse])
            .send(client.backend)
          freedCreated <- ZIO.fromEither(freedCreateResp.body)

          // A second N on the scenario is rejected again.
          dupAgainResp <- basicRequest.header("X-Branch", scenarioName)
            .post(uri"${client.baseUrl}/w/$key/risk-trees")
            .body(dupReq)
            .send(client.backend)
        yield assertTrue(dupWhileInheritedResp.code == StatusCode.BadRequest) &&
          assertTrue(deleteResp.code == StatusCode.NoContent) &&
          assertTrue(freedCreateResp.code == StatusCode.Created) &&
          assertTrue(freedCreated.name.value == treeName) &&
          assertTrue(dupAgainResp.code == StatusCode.BadRequest)
      }
    ).provideLayerShared(harnessLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock


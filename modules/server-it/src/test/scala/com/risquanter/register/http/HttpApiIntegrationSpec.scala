package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse}
import com.risquanter.register.domain.data.RiskTree
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
          distributionType = "lognormal",
          probability = 0.1,
          minLoss = Some(1000L),
          maxLoss = Some(2000L),
          percentiles = None,
          quantiles = None
        ),
        RiskLeafDefinitionRequest(
          name = "Leaf 2",
          parentName = Some("Root"),
          distributionType = "lognormal",
          probability = 0.2,
          minLoss = Some(1500L),
          maxLoss = Some(3000L),
          percentiles = None,
          quantiles = None
        )
      )
    )

  override def spec =
    suite("HttpApiIntegrationSpec")(
      test("health endpoint returns OK (Irmin-backed server)") {
        for
          client   <- ZIO.service[SttpClientFixture.Client]
          response <- basicRequest.get(uri"${client.baseUrl}/health").send(client.backend)
        yield assertTrue(response.code.isSuccess) && assertTrue(response.body.exists(_.contains("healthy")))
      },
      test("workspace bootstrap, list, and get structure via workspace-scoped API") {
        val request = sampleRequest()
        for
          client <- ZIO.service[SttpClientFixture.Client]

          // 1) Bootstrap workspace (POST /workspaces) → creates workspace + first tree
          bootstrapResp <- basicRequest
            .post(uri"${client.baseUrl}/workspaces")
            .body(request)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          createdTree = bootstrap.tree

          // 2) List trees in workspace (GET /w/{key}/risk-trees)
          listResp <- basicRequest
            .get(uri"${client.baseUrl}/w/$key/risk-trees")
            .response(asJson[List[SimulationResponse]])
            .send(client.backend)
          listed <- ZIO.fromEither(listResp.body)

          // 3) Get full tree structure (GET /w/{key}/risk-trees/{treeId}/structure)
          structResp <- basicRequest
            .get(uri"${client.baseUrl}/w/$key/risk-trees/${createdTree.id.value}/structure")
            .response(asJson[Option[RiskTree]])
            .send(client.backend)
          structure <- ZIO.fromEither(structResp.body)
        yield assertTrue(createdTree.name == request.name) &&
          assertTrue(listed.exists(_.id == createdTree.id)) &&
          assertTrue(structure.exists(_.id == createdTree.id))
      },
      test("create additional tree in workspace via POST /w/{key}/risk-trees") {
        val bootstrapReq = sampleRequest()
        val secondTreeReq = sampleRequest()
        for
          client <- ZIO.service[SttpClientFixture.Client]

          // Bootstrap workspace with first tree
          bootstrapResp <- basicRequest
            .post(uri"${client.baseUrl}/workspaces")
            .body(bootstrapReq)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal

          // Create additional tree (POST /w/{key}/risk-trees)
          createResp <- basicRequest
            .post(uri"${client.baseUrl}/w/$key/risk-trees")
            .body(secondTreeReq)
            .response(asJson[SimulationResponse])
            .send(client.backend)
          created <- ZIO.fromEither(createResp.body)

          // Verify both trees appear in listing
          listResp <- basicRequest
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
          bootstrapResp <- basicRequest
            .post(uri"${client.baseUrl}/workspaces")
            .body(request)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id

          // Get tree summary (GET /w/{key}/risk-trees/{treeId})
          summaryResp <- basicRequest
            .get(uri"${client.baseUrl}/w/$key/risk-trees/${treeId.value}")
            .response(asJson[Option[SimulationResponse]])
            .send(client.backend)
          summary <- ZIO.fromEither(summaryResp.body)
        yield assertTrue(summaryResp.code.isSuccess) &&
          assertTrue(summary.exists(_.id == treeId)) &&
          assertTrue(summary.exists(_.name == request.name))
      }
    ).provideLayerShared(harnessLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock


package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*
import sttp.model.StatusCode
import com.risquanter.register.configs.ApiConfig
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.http.responses.{SimulationResponse, WorkspaceBootstrapResponse}
import com.risquanter.register.http.support.SttpClientFixture

/** A17 config gate tests for `GET /risk-trees` (list-all endpoint).
  *
  * The unscoped `GET /risk-trees` endpoint is sealed by ADR-017 action item A17:
  * it returns 403 Forbidden when `listAllTreesEnabled = false` (the production default)
  * and is only active when explicitly opted in via configuration.
  *
  * These tests verify both branches of the config gate using in-memory servers
  * with different `ApiConfig` settings, ensuring that:
  *   - The default (deny) path returns 403 with an `AccessDenied` error body
  *   - The enabled path returns 200 with a valid list of trees
  *
  * Uses `HttpTestHarness.inMemoryServer` (no Docker/Irmin required).
  *
  * @see RiskTreeController.getAll (server logic with config gate)
  * @see RouteSecurityRegressionSpec (structural route checks)
  * @see ADR-017, ADR-021 §3
  */
object A17ConfigGateSpec extends ZIOSpecDefault:

  private def sampleRequest: RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = s"Gate-${java.util.UUID.randomUUID().toString.take(8)}",
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
        )
      )
    )

  // ── Layer: default config (listAllTreesEnabled = false) ───────────

  private val deniedLayer =
    ZLayer.makeSome[Scope, SttpClientFixture.Client](
      HttpTestHarness.inMemoryServer(
        HttpTestHarness.HarnessConfig(api = ApiConfig(listAllTreesEnabled = false))
      ),
      SttpClientFixture.layer
    )

  // ── Layer: enabled config (listAllTreesEnabled = true) ────────────

  private val allowedLayer =
    ZLayer.makeSome[Scope, SttpClientFixture.Client](
      HttpTestHarness.inMemoryServer(
        HttpTestHarness.HarnessConfig(api = ApiConfig(listAllTreesEnabled = true))
      ),
      SttpClientFixture.layer
    )

  override def spec = suite("A17 config gate — GET /risk-trees")(

    suite("listAllTreesEnabled = false (production default)")(
      test("returns 403 Forbidden") {
        for
          client   <- ZIO.service[SttpClientFixture.Client]
          response <- basicRequest
            .get(uri"${client.baseUrl}/risk-trees")
            .response(asStringAlways)
            .send(client.backend)
        yield assertTrue(response.code == StatusCode.Forbidden)
      },
      test("response body contains ACCESS_DENIED error") {
        for
          client   <- ZIO.service[SttpClientFixture.Client]
          response <- basicRequest
            .get(uri"${client.baseUrl}/risk-trees")
            .response(asStringAlways)
            .send(client.backend)
        yield assertTrue(response.code == StatusCode.Forbidden) &&
          assertTrue(response.body.contains("ACCESS_DENIED"))
      }
    ).provideLayerShared(deniedLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock,

    suite("listAllTreesEnabled = true (opt-in)")(
      test("returns 200 OK with list") {
        for
          client <- ZIO.service[SttpClientFixture.Client]

          // First create a tree via workspace bootstrap so there's data
          _ <- basicRequest
            .post(uri"${client.baseUrl}/workspaces")
            .body(sampleRequest)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)

          // Now GET /risk-trees should return the list
          response <- basicRequest
            .get(uri"${client.baseUrl}/risk-trees")
            .response(asJson[List[SimulationResponse]])
            .send(client.backend)
          listed <- ZIO.fromEither(response.body)
        yield assertTrue(response.code == StatusCode.Ok) &&
          assertTrue(listed.nonEmpty)
      }
    ).provideLayerShared(allowedLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

  )

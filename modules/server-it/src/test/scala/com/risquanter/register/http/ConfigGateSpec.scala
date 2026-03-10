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

/** The unscoped `GET /risk-trees` endpoint is sealed by ADR-017 action item A17:
  * it returns 403 Forbidden when `listAllTreesEnabled = false` (the production default)
  * and is only active when explicitly opted in via configuration.
  *
  * The deny suite uses `ApiConfig()` with no arguments — intentionally relying on the
  * default field value — so that any change to that default will cause these tests to
  * fail and block the build before reaching production.
  *
  * The allow suite uses `ApiConfig(listAllTreesEnabled = true)` to verify the gate can
  * be opened for admin/debug use, and returns a valid list when enabled.
  *
  * Uses `HttpTestHarness.inMemoryServer` (no Docker/Irmin required).
  *
  * @see RiskTreeController.getAll (server logic with config gate)
  * @see RouteSecurityRegressionSpec (structural route checks)
  * @see ADR-017, ADR-021 §3
  */
object ConfigGateSpec extends ZIOSpecDefault:

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

  // ── Layer: default config — NO explicit ApiConfig override ───────
  // Intentionally uses ApiConfig() with no arguments to prove the
  // production default seals the endpoint.  If someone changes
  // ApiConfig.listAllTreesEnabled's default to `true`, these tests
  // will fail and block the build before it reaches production.

  private val deniedLayer =
    ZLayer.makeSome[Scope, SttpClientFixture.Client](
      HttpTestHarness.inMemoryServer(
        HttpTestHarness.HarnessConfig(api = ApiConfig())
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

    suite("default ApiConfig seals the endpoint (listAllTreesEnabled must default to false)")(
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

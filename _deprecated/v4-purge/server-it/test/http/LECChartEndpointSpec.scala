package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*
import sttp.model.StatusCode
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.CurvePalette
import com.risquanter.register.http.requests.{
  LECChartRequest, LECChartCurveEntry,
  RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest
}
import com.risquanter.register.http.responses.WorkspaceBootstrapResponse
import com.risquanter.register.http.support.SttpClientFixture
import io.github.iltotore.iron.*

/** Integration tests for `POST /w/{key}/risk-trees/{treeId}/lec-chart`.
  *
  * Uses `HttpTestHarness.inMemoryServer` — no Docker required.
  * Validates the full HTTP roundtrip: JSON codec for `LECChartRequest`,
  * lazy simulation, palette colour assignment, and Vega-Lite spec generation.
  *
  * @see ConfigGateSpec (same in-memory server pattern)
  * @see LECChartSpecBuilderSpec (unit-level spec builder tests)
  * @see AssignPaletteColoursSpec (unit-level palette assignment tests)
  */
object LECChartEndpointSpec extends ZIOSpecDefault:

  private val harnessLayer =
    ZLayer.makeSome[Scope, SttpClientFixture.Client](
      HttpTestHarness.inMemoryServer(),
      SttpClientFixture.layer
    )

  private def sampleRequest(
      suffix: String = java.util.UUID.randomUUID().toString.take(8)
  ): RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = s"LECChart-$suffix",
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

  /** Bootstrap workspace and extract (client, workspaceKey, treeId, leafNodeIds). */
  private def bootstrapWithStructure =
    for
      client <- ZIO.service[SttpClientFixture.Client]

      // Bootstrap workspace
      bootstrapResp <- basicRequest
        .post(uri"${client.baseUrl}/workspaces")
        .body(sampleRequest())
        .response(asJson[WorkspaceBootstrapResponse])
        .send(client.backend)
      bootstrap <- ZIO.fromEither(bootstrapResp.body)
      key        = bootstrap.workspaceKey.reveal
      treeId     = bootstrap.tree.id

      // Get full structure to discover node IDs
      structResp <- basicRequest
        .get(uri"${client.baseUrl}/w/$key/risk-trees/${treeId.value}/structure")
        .response(asJson[Option[RiskTree]])
        .send(client.backend)
      structure <- ZIO.fromEither(structResp.body)
      tree      <- ZIO.fromOption(structure)
                     .orElseFail(new RuntimeException("tree structure missing"))

      // Leaf IDs = all nodes except the root portfolio
      leafIds = tree.nodes.map(_.id).filterNot(_ == tree.rootId).toList
    yield (client, key, treeId, leafIds)

  override def spec = suite("POST /w/{key}/risk-trees/{treeId}/lec-chart")(

    test("happy path — returns 200 with valid Vega-Lite v6 JSON spec") {
      for
        (client, key, treeId, leafIds) <- bootstrapWithStructure

        request = LECChartRequest(
          leafIds.map(nid => LECChartCurveEntry(nid, CurvePalette.Green))
        )

        response <- basicRequest
          .post(uri"${client.baseUrl}/w/$key/risk-trees/${treeId.value}/lec-chart")
          .body(request)
          .response(asStringAlways)
          .send(client.backend)
      yield assertTrue(
        response.code == StatusCode.Ok,
        response.body.contains("$schema"),
        response.body.contains("vega-lite/v6")
      )
    },

    test("palette colours from Green and Aqua appear in returned spec") {
      for
        (client, key, treeId, leafIds) <- bootstrapWithStructure

        // One leaf per palette → rank 0 → darkest shade
        entries = leafIds.zipWithIndex.map { (nid, idx) =>
          LECChartCurveEntry(nid, if idx == 0 then CurvePalette.Green else CurvePalette.Aqua)
        }
        request = LECChartRequest(entries)

        response <- basicRequest
          .post(uri"${client.baseUrl}/w/$key/risk-trees/${treeId.value}/lec-chart")
          .body(request)
          .response(asStringAlways)
          .send(client.backend)
      yield assertTrue(
        response.code == StatusCode.Ok,
        // 1 curve per palette → rank 0 → darkest shade
        // Green(0) = #03170b, Aqua(0) = #00121a
        response.body.contains("#03170b"),
        response.body.contains("#00121a")
      )
    },

    test("empty curves list returns 400 with EMPTY_COLLECTION validation error") {
      for
        (client, key, treeId, _) <- bootstrapWithStructure

        response <- basicRequest
          .post(uri"${client.baseUrl}/w/$key/risk-trees/${treeId.value}/lec-chart")
          .body(LECChartRequest(List.empty))
          .response(asStringAlways)
          .send(client.backend)
      yield assertTrue(
        response.code == StatusCode.BadRequest,
        response.body.contains("EMPTY_COLLECTION"),
        response.body.contains("nodeIds")
      )
    }

  ).provideLayerShared(harnessLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

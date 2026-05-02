package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, QueryRequest}
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, QueryResponse}
import com.risquanter.register.domain.errors.{JsonHttpError, ValidationErrorCode, ErrorResponse}
import com.risquanter.register.http.support.SttpClientFixture
import com.risquanter.register.testcontainers.IrminCompose
import io.github.iltotore.iron.*

/** Integration tests for POST /w/{key}/risk-trees/{treeId}/query
  *
  * Exercises the full HTTP stack using HttpTestHarness (in-process ZIO HTTP server)
  * with Irmin-backed persistence (IrminCompose spins up only the irmin container).
  *
  * H1 — valid quoted-name query returns 200 with correct satisfyingCount
  * H2 — malformed (unterminated) quoted string returns 400 PARSE_ERROR; response
  *      body must not contain workspace key or internal identifiers (ADR-022 §4/§6)
  * H3 — unknown quoted node name returns 400 UNKNOWN_REFERENCE
  */
object QueryEndpointSpec extends ZIOSpecDefault:

  private val harnessLayer =
    ZLayer.makeSome[Scope, SttpClientFixture.Client](
      HttpTestHarness.irminServer(IrminCompose.irminConfigLayer),
      SttpClientFixture.layer
    )

  /** The simple demo tree — two leaves under "IT Risk", two under "Third Party Risk". */
  private val demoTreeRequest = RiskTreeDefinitionRequest(
    name = "Operational Risk Model",
    portfolios = Seq(
      RiskPortfolioDefinitionRequest(name = "Operations",         parentName = None),
      RiskPortfolioDefinitionRequest(name = "IT Risk",           parentName = Some("Operations")),
      RiskPortfolioDefinitionRequest(name = "Third Party Risk",  parentName = Some("Operations"))
    ),
    leaves = Seq(
      RiskLeafDefinitionRequest(
        name             = "Cyber Breach",
        parentName       = Some("IT Risk"),
        distributionType = "lognormal",
        probability      = 0.20,
        minLoss          = Some(500000L),
        maxLoss          = Some(8000000L),
        percentiles      = None,
        quantiles        = None
      ),
      RiskLeafDefinitionRequest(
        name             = "Ransomware",
        parentName       = Some("IT Risk"),
        distributionType = "lognormal",
        probability      = 0.10,
        minLoss          = Some(200000L),
        maxLoss          = Some(4000000L),
        percentiles      = None,
        quantiles        = None
      ),
      RiskLeafDefinitionRequest(
        name             = "Supply Chain Disruption",
        parentName       = Some("Third Party Risk"),
        distributionType = "lognormal",
        probability      = 0.15,
        minLoss          = Some(300000L),
        maxLoss          = Some(3000000L),
        percentiles      = None,
        quantiles        = None
      ),
      RiskLeafDefinitionRequest(
        name             = "Regulatory Fine",
        parentName       = Some("Third Party Risk"),
        distributionType = "lognormal",
        probability      = 0.08,
        minLoss          = Some(100000L),
        maxLoss          = Some(2000000L),
        percentiles      = None,
        quantiles        = None
      )
    )
  )

  override def spec =
    suite("QueryEndpointSpec")(
      test("H1: valid quoted-name scope query returns 200 with correctly scoped range") {
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest
            .post(uri"${client.baseUrl}/workspaces")
            .body(demoTreeRequest)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id.value

          queryResp <- basicRequest
            .post(uri"${client.baseUrl}/w/$key/risk-trees/$treeId/query")
            .body(QueryRequest("""Q[>=]^{1/2} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 1000))"""))
            .response(asJson[QueryResponse])
            .send(client.backend)
          result <- ZIO.fromEither(queryResp.body)
        yield assertTrue(queryResp.code.code == 200) &&
          // rangeSize is deterministic (2 leaf descendants of "IT Risk" always exist)
          assertTrue(result.rangeSize == 2)
      },
      test("H2: malformed quoted string returns 400 PARSE_ERROR without leaking internals") {
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest
            .post(uri"${client.baseUrl}/workspaces")
            .body(demoTreeRequest)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id.value

          queryResp <- basicRequest
            .post(uri"${client.baseUrl}/w/$key/risk-trees/$treeId/query")
            .body(QueryRequest("""Q[>=]^{1/2} x (leaf_descendant_of(x, "unterminated), gt_loss(p95(x), 1000))"""))
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = queryResp.body
          errorBody <- ZIO.fromEither(
            ErrorResponse.codec.decoder.decodeJson(rawBody)
              .map(_.error)
              .left.map(err => s"Failed to decode error response: $err\nBody was: $rawBody")
          )
        yield assertTrue(queryResp.code.code == 400) &&
          assertTrue(errorBody.errors.exists(_.code == ValidationErrorCode.PARSE_ERROR)) &&
          // ADR-022 §4/§6: error body must not contain the workspace key
          assertTrue(!rawBody.contains(key))
      },
      test("H3: unknown quoted node name returns 400 UNKNOWN_REFERENCE") {
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest
            .post(uri"${client.baseUrl}/workspaces")
            .body(demoTreeRequest)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id.value

          queryResp <- basicRequest
            .post(uri"${client.baseUrl}/w/$key/risk-trees/$treeId/query")
            .body(QueryRequest("""Q[>=]^{1/2} x (leaf_descendant_of(x, "NonExistentNode"), gt_loss(p95(x), 1000))"""))
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = queryResp.body
          errorBody <- ZIO.fromEither(
            ErrorResponse.codec.decoder.decodeJson(rawBody)
              .map(_.error)
              .left.map(err => s"Failed to decode error response: $err\nBody was: $rawBody")
          )
        yield assertTrue(queryResp.code.code == 400) &&
          assertTrue(errorBody.errors.exists(_.code == ValidationErrorCode.UNKNOWN_REFERENCE))
      }
    ).provideLayerShared(harnessLayer) @@
      TestAspect.withLiveClock @@
      TestAspect.sequential @@
      TestAspect.timeout(120.seconds)

end QueryEndpointSpec

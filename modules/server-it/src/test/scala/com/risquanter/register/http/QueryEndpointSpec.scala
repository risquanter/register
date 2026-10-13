package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, QueryRequest, DistributionShapeRequest}
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, QueryResponse}
import com.risquanter.register.http.errors.{QueryProblem, WireError}
import zio.json.*
import com.risquanter.register.http.support.SttpClientFixture
import com.risquanter.register.testcontainers.IrminCompose
import io.github.iltotore.iron.*

/** Integration tests for POST /w/{key}/risk-trees/{treeId}/query
  *
  * Exercises the full HTTP stack using HttpTestHarness (in-process ZIO HTTP server)
  * with Irmin-backed persistence (IrminCompose spins up only the irmin container).
  *
  * H1 — valid quoted-name query returns 200 with correct satisfyingCount
  * H2 — malformed (unterminated) quoted string returns 422 with an Unparseable
  *      problem; response body must not contain the workspace key or any
  *      internal identifier (ADR-035, ADR-036)
  * H3 — unknown quoted node name returns 422 with an UnknownReferences problem
  *      naming the reference that did not resolve
  *
  * A refused query answers 422 rather than 400 because a well-formed request
  * body is being rejected on its content, not on its shape.
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
        probability      = 0.20,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(500000L), maxLoss = Some(8000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name             = "Ransomware",
        parentName       = Some("IT Risk"),
        probability      = 0.10,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(200000L), maxLoss = Some(4000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name             = "Supply Chain Disruption",
        parentName       = Some("Third Party Risk"),
        probability      = 0.15,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(300000L), maxLoss = Some(3000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name             = "Regulatory Fine",
        parentName       = Some("Third Party Risk"),
        probability      = 0.08,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(100000L), maxLoss = Some(2000000L),
          percentiles = None, quantiles = None, terms = None
        )
      )
    )
  )

  override def spec =
    suite("QueryEndpointSpec")(
      test("H1: valid quoted-name scope query returns 200 with correctly scoped range") {
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(demoTreeRequest)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id.value

          queryResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/w/$key/risk-trees/$treeId/query")
            .body(QueryRequest("""Q[>=]^{1/2} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x, "inherent"), 1000))"""))
            .response(asJson[QueryResponse])
            .send(client.backend)
          result <- ZIO.fromEither(queryResp.body)
        yield assertTrue(queryResp.code.code == 200) &&
          // rangeSize is deterministic (2 leaf descendants of "IT Risk" always exist)
          assertTrue(result.rangeSize == 2)
      },
      test("H2: malformed quoted string returns 422 Unparseable without leaking internals") {
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(demoTreeRequest)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id.value

          queryResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/w/$key/risk-trees/$treeId/query")
            .body(QueryRequest("""Q[>=]^{1/2} x (leaf_descendant_of(x, "unterminated), gt_loss(p95(x, "inherent"), 1000))"""))
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = queryResp.body
          // A refused query is a `QueryRejected`, not a field-validation
          // failure: the problem carries a parse position, which a validation
          // error has nowhere to put.
          refusal   <- ZIO.fromEither(
            rawBody.fromJson[WireError.QueryRejected]
              .left.map(err => s"Failed to decode a query rejection: $err\nBody was: $rawBody")
          )
        yield assertTrue(queryResp.code.code == 422) &&
          assertTrue(refusal.problem match
            case QueryProblem.Unparseable(_, _) => true
            case _                              => false) &&
          // ADR-022 §4/§6: error body must not contain the workspace key
          assertTrue(!rawBody.contains(key))
      },
      test("H3: unknown quoted node name returns 422 UnknownReferences") {
        for
          client <- ZIO.service[SttpClientFixture.Client]

          bootstrapResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(demoTreeRequest)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
          bootstrap <- ZIO.fromEither(bootstrapResp.body)
          key        = bootstrap.workspaceKey.reveal
          treeId     = bootstrap.tree.id.value

          queryResp <- basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/w/$key/risk-trees/$treeId/query")
            .body(QueryRequest("""Q[>=]^{1/2} x (leaf_descendant_of(x, "NonExistentNode"), gt_loss(p95(x, "inherent"), 1000))"""))
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = queryResp.body
          refusal   <- ZIO.fromEither(
            rawBody.fromJson[WireError.QueryRejected]
              .left.map(err => s"Failed to decode a query rejection: $err\nBody was: $rawBody")
          )
        yield assertTrue(queryResp.code.code == 422) &&
          // The unresolved name is listed, so the client can say which one.
          assertTrue(refusal.problem match
            case QueryProblem.UnknownReferences(messages) =>
              messages.exists(_.contains("NonExistentNode"))
            case _ => false)
      }
    ).provideLayerShared(harnessLayer) @@
      TestAspect.withLiveClock @@
      TestAspect.sequential @@
      TestAspect.timeout(120.seconds)

end QueryEndpointSpec

package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, QueryRequest, DistributionShapeRequest}
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, QueryResponse}
import com.risquanter.register.http.errors.{BindProblem, QueryProblem, RejectionDetail, WireError}
import zio.json.*
import com.risquanter.register.http.support.SttpClientFixture
import com.risquanter.register.domain.data.iron.QueryText
import com.risquanter.register.testcontainers.IrminCompose

/** Integration tests for POST /w/{key}/risk-trees/{treeId}/query
  *
  * Exercises the full HTTP stack using HttpTestHarness (in-process ZIO HTTP server)
  * with Irmin-backed persistence (IrminCompose spins up only the irmin container).
  *
  * H1 — valid quoted-name query returns 200 with correct satisfyingCount
  * H2 — malformed (unterminated) quoted string returns 422 with an Unparseable
  *      problem; response body must not contain the workspace key or any
  *      internal identifier (ADR-035, ADR-036)
  * H3 — unknown quoted node name returns 422 with an Unbound problem naming
  *      the literal no node matches
  * H4 — a query naming an answer variable returns 422 with an
  *      AnswerVariablesNotSupported problem naming the variable
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
  /** The request for a text within the length bound. */
  private def request(text: String): QueryRequest =
    QueryRequest(QueryText.fromString(text).fold(reason => throw new IllegalArgumentException(reason), identity))

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
        distributionShape = DistributionShapeRequest.Lognormal(minLoss = 500000L, maxLoss = 8000000L),
        seedVarId        = None
      ),
      RiskLeafDefinitionRequest(
        name             = "Ransomware",
        parentName       = Some("IT Risk"),
        probability      = 0.10,
        distributionShape = DistributionShapeRequest.Lognormal(minLoss = 200000L, maxLoss = 4000000L),
        seedVarId        = None
      ),
      RiskLeafDefinitionRequest(
        name             = "Supply Chain Disruption",
        parentName       = Some("Third Party Risk"),
        probability      = 0.15,
        distributionShape = DistributionShapeRequest.Lognormal(minLoss = 300000L, maxLoss = 3000000L),
        seedVarId        = None
      ),
      RiskLeafDefinitionRequest(
        name             = "Regulatory Fine",
        parentName       = Some("Third Party Risk"),
        probability      = 0.08,
        distributionShape = DistributionShapeRequest.Lognormal(minLoss = 100000L, maxLoss = 2000000L),
        seedVarId        = None
      )
    ),
    newMitigations = Nil
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
            .body(request("""Q[>=]^{1/2} x (leaf_descendant_of(x, node_named("IT Risk")), gt_loss(p95(x, "inherent"), 1000))"""))
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
            .body(request("""Q[>=]^{1/2} x (leaf_descendant_of(x, "unterminated), gt_loss(p95(x, "inherent"), 1000))"""))
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = queryResp.body
          // The query names a tree, so its content is refused against that
          // tree and the detail says which kind of refusal it was. A query
          // problem carries a parse position, which a field error has nowhere
          // to put.
          refusal   <- ZIO.fromEither(
            rawBody.fromJson[WireError.ValidationRejected]
              .left.map(err => s"Failed to decode a query rejection: $err\nBody was: $rawBody")
          )
        yield assertTrue(queryResp.code.code == 422) &&
          assertTrue(refusal.detail match
            case RejectionDetail.Query(QueryProblem.Unparseable(_)) => true
            case _                                                  => false) &&
          // ADR-022 §4/§6: error body must not contain the workspace key
          assertTrue(!rawBody.contains(key))
      },
      test("H3: unknown node name returns 422 Unbound, naming the literal no node matches") {
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
            .body(request("""Q[>=]^{1/2} x (leaf_descendant_of(x, node_named("NonExistentNode")), gt_loss(p95(x, "inherent"), 1000))"""))
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = queryResp.body
          refusal   <- ZIO.fromEither(
            rawBody.fromJson[WireError.ValidationRejected]
              .left.map(err => s"Failed to decode a query rejection: $err\nBody was: $rawBody")
          )
        yield assertTrue(queryResp.code.code == 422) &&
          // The unresolved name is listed, so the client can say which one.
          assertTrue(refusal.detail match
            case RejectionDetail.Query(QueryProblem.Unbound(problems)) =>
              problems.toList.exists(_.problem match
                case BindProblem.UnreadableLiteral(text, _) => text.value == "NonExistentNode"
                case _                                      => false)
            case _ => false)
      },
      test("H4: a query naming an answer variable returns 422 AnswerVariablesNotSupported naming it") {
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
            .body(request("""Q[>=]^{1/2} x (child_of(x, y), leaf(x)) (y)"""))
            .response(asStringAlways)
            .send(client.backend)
          rawBody    = queryResp.body
          refusal   <- ZIO.fromEither(
            rawBody.fromJson[WireError.ValidationRejected]
              .left.map(err => s"Failed to decode a query rejection: $err\nBody was: $rawBody")
          )
        yield assertTrue(queryResp.code.code == 422) &&
          assertTrue(refusal.detail match
            case RejectionDetail.Query(QueryProblem.AnswerVariablesNotSupported(variables)) =>
              variables.toList.map(_.problem.value) == List("y")
            case _ => false)
      },
      test("H5: a query over the length bound returns 400 before the handler runs") {
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
            .contentType("application/json")
            .body(s"""{"query":"${"x" * (QueryText.MaxLength + 1)}"}""")
            .response(asStringAlways)
            .send(client.backend)
        yield assertTrue(queryResp.code.code == 400)
      }
    ).provideLayerShared(harnessLayer) @@
      TestAspect.withLiveClock @@
      TestAspect.sequential @@
      TestAspect.timeout(120.seconds)

end QueryEndpointSpec

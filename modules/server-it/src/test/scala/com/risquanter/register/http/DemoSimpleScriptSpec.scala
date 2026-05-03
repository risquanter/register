package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, QueryResponse}
import com.risquanter.register.http.support.{SttpClientFixture, DemoSpecSupport}
import io.github.iltotore.iron.*

/** Regression tests reproducing every query in demo-simple-{httpie,curl}.sh.
  * Verdicts and range sizes are pinned to production-equivalent simulation results
  * (10 000 trials). Each test asserts both `satisfied` and `rangeSize`.
  */
object DemoSimpleScriptSpec extends ZIOSpecDefault:

  private val harnessLayer = DemoSpecSupport.harnessLayer

  /** Reproduces the tree from Step 1 of demo-simple-{httpie,curl}.sh */
  private val demoTreeRequest = RiskTreeDefinitionRequest(
    name = "Operational Risk Model",
    portfolios = Seq(
      RiskPortfolioDefinitionRequest(name = "Operations",       parentName = None),
      RiskPortfolioDefinitionRequest(name = "IT Risk",          parentName = Some("Operations")),
      RiskPortfolioDefinitionRequest(name = "Third Party Risk", parentName = Some("Operations"))
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
        distributionType = "expert",
        probability      = 0.10,
        minLoss          = None,
        maxLoss          = None,
        percentiles      = Some(Array(0.25, 0.50, 0.75, 0.95)),
        quantiles        = Some(Array(200000.0, 1000000.0, 4000000.0, 15000000.0))
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

  private def query = DemoSpecSupport.query

  override def spec =
    suite("DemoSimpleScriptSpec")(
      test("all queries match pinned simulation results") {
        for
          client <- ZIO.service[SttpClientFixture.Client]
          boot   <- basicRequest
                      .post(uri"${client.baseUrl}/workspaces")
                      .body(demoTreeRequest)
                      .response(asJson[WorkspaceBootstrapResponse])
                      .send(client.backend)
                      .flatMap(r => ZIO.fromEither(r.body))
          key     = boot.workspaceKey.reveal
          treeId  = boot.tree.id.value

          // Q1: fewer than half of the 4 leaves have unconditional P95 > $2M (only Cyber Breach qualifies)
          q1 <- query(client, key, treeId)("""Q[>=]^{1/2} x (leaf(x), gt_loss(p95(x), 2000000))""")

          // Q2: at least 1/3 of the 4 leaves have unconditional P99 > $5M (Cyber Breach + Ransomware)
          q2 <- query(client, key, treeId)("""Q[>=]^{1/3} x (leaf(x), gt_loss(p99(x), 5000000))""")

          // Q3: only 1/4 leaves have >5% chance of exceeding $2M, satisfying the <=1/2 bar
          q3 <- query(client, key, treeId)("""Q[<=]^{1/2} x (leaf(x), gt_prob(lec(x, 2000000), 0.05))""")

          // Q4: at least 2/3 of portfolios have ≥1 direct child with P95 > $1M (all 3 qualify)
          q4 <- query(client, key, treeId)("""Q[>=]^{2/3} x (portfolio(x), exists y . (child_of(y, x) /\ gt_loss(p95(y), 1000000)))""")

          // Q5: at least 2/3 of portfolios have ALL direct children with P95 > $1M — satisfies >=1/2; margin=0.17 ✓
          q5 <- query(client, key, treeId)("""Q[>=]^{1/2} x (portfolio(x), forall y . (child_of(y, x) ==> gt_loss(p95(y), 1000000)))""")

          // Q-S1: exactly 1/2 of IT Risk leaves (Cyber Breach) have P95 > $2M — meets >=1/2 bar
          qs1 <- query(client, key, treeId)("""Q[>=]^{1/2} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 2000000))""")

          // Q-S2: no Third Party Risk leaf has P95 > $2M — same bar as Q-S1 but lighter-tailed sub-portfolio
          qs2 <- query(client, key, treeId)("""Q[>=]^{1/2} x (leaf_descendant_of(x, "Third Party Risk"), gt_loss(p95(x), 2000000))""")

          // Q-S3: both direct children of IT Risk have P99 > $5M
          qs3 <- query(client, key, treeId)("""Q[>=]^{1/2} x (child_of(x, "IT Risk"), gt_loss(p99(x), 5000000))""")

        yield assertTrue(q1.rangeSize == 4, !q1.satisfied) &&
          assertTrue(q2.rangeSize == 4, q2.satisfied) &&
          assertTrue(q3.rangeSize == 4, q3.satisfied) &&
          assertTrue(q4.rangeSize == 3, q4.satisfied) &&
          assertTrue(q5.rangeSize == 3, q5.satisfied) &&         // 2/3≈67% sat Q[>=]^{1/2}; margin=0.17 ✓
          assertTrue(qs1.rangeSize == 2, qs1.satisfied) &&
          assertTrue(qs2.rangeSize == 2, !qs2.satisfied) &&
          assertTrue(qs3.rangeSize == 2, qs3.satisfied)
      }
    ).provideLayerShared(harnessLayer) @@
      TestAspect.withLiveClock @@
      TestAspect.sequential @@
      TestAspect.timeout(300.seconds)

end DemoSimpleScriptSpec

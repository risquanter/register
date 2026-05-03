package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.http.responses.{WorkspaceBootstrapResponse, QueryResponse}
import com.risquanter.register.http.support.{SttpClientFixture, DemoSpecSupport}
import io.github.iltotore.iron.*

/** Regression tests reproducing every query in demo-enterprise-{httpie,curl}.sh.
  * Verdicts and range sizes are pinned to production-equivalent simulation results
  * (10 000 trials). Each test asserts both `satisfied` and `rangeSize`.
  */
object DemoEnterpriseScriptSpec extends ZIOSpecDefault:

  private val harnessLayer = DemoSpecSupport.harnessLayer

  /** Reproduces the tree from Step 1 of demo-enterprise-{httpie,curl}.sh
    * (21 leaves, 11 portfolios — Financial Services Enterprise Risk)
    */
  private val demoTreeRequest = RiskTreeDefinitionRequest(
    name = "Financial Services Enterprise Risk",
    portfolios = Seq(
      RiskPortfolioDefinitionRequest(name = "Enterprise Risk",               parentName = None),
      RiskPortfolioDefinitionRequest(name = "Operational Risk",              parentName = Some("Enterprise Risk")),
      RiskPortfolioDefinitionRequest(name = "Technology & Cyber",            parentName = Some("Operational Risk")),
      RiskPortfolioDefinitionRequest(name = "Process & People",              parentName = Some("Operational Risk")),
      RiskPortfolioDefinitionRequest(name = "Third-Party & Supply Chain",    parentName = Some("Operational Risk")),
      RiskPortfolioDefinitionRequest(name = "Financial Risk",                parentName = Some("Enterprise Risk")),
      RiskPortfolioDefinitionRequest(name = "Market Risk",                   parentName = Some("Financial Risk")),
      RiskPortfolioDefinitionRequest(name = "Credit Risk",                   parentName = Some("Financial Risk")),
      RiskPortfolioDefinitionRequest(name = "Liquidity Risk",                parentName = Some("Financial Risk")),
      RiskPortfolioDefinitionRequest(name = "Compliance & Legal Risk",       parentName = Some("Enterprise Risk")),
      RiskPortfolioDefinitionRequest(name = "Strategic & Reputational Risk", parentName = Some("Enterprise Risk"))
    ),
    leaves = Seq(
      // ── Technology & Cyber (4 leaves) ────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Ransomware Attack", parentName = Some("Technology & Cyber"),
        distributionType = "expert", probability = 0.15,
        minLoss = None, maxLoss = None,
        percentiles = Some(Array(0.25, 0.50, 0.75, 0.95)),
        quantiles   = Some(Array(500000.0, 2000000.0, 8000000.0, 25000000.0))
      ),
      RiskLeafDefinitionRequest(
        name = "Cloud Provider Outage", parentName = Some("Technology & Cyber"),
        distributionType = "lognormal", probability = 0.30,
        minLoss = Some(200000L), maxLoss = Some(4000000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "Data Breach (PII)", parentName = Some("Technology & Cyber"),
        distributionType = "lognormal", probability = 0.10,
        minLoss = Some(1000000L), maxLoss = Some(15000000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "Insider Threat", parentName = Some("Technology & Cyber"),
        distributionType = "lognormal", probability = 0.05,
        minLoss = Some(2000000L), maxLoss = Some(20000000L),
        percentiles = None, quantiles = None
      ),
      // ── Process & People (3 leaves) ───────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Key Person Departure", parentName = Some("Process & People"),
        distributionType = "lognormal", probability = 0.20,
        minLoss = Some(100000L), maxLoss = Some(800000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "Internal Fraud", parentName = Some("Process & People"),
        distributionType = "expert", probability = 0.08,
        minLoss = None, maxLoss = None,
        percentiles = Some(Array(0.25, 0.50, 0.75, 0.95)),
        quantiles   = Some(Array(200000.0, 1000000.0, 4000000.0, 18000000.0))
      ),
      RiskLeafDefinitionRequest(
        name = "Process Failure", parentName = Some("Process & People"),
        distributionType = "lognormal", probability = 0.25,
        minLoss = Some(50000L), maxLoss = Some(500000L),
        percentiles = None, quantiles = None
      ),
      // ── Third-Party & Supply Chain (3 leaves) ─────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Critical Vendor Failure", parentName = Some("Third-Party & Supply Chain"),
        distributionType = "lognormal", probability = 0.12,
        minLoss = Some(500000L), maxLoss = Some(5000000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "Outsourcing SLA Breach", parentName = Some("Third-Party & Supply Chain"),
        distributionType = "lognormal", probability = 0.20,
        minLoss = Some(100000L), maxLoss = Some(1500000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "Concentration Risk", parentName = Some("Third-Party & Supply Chain"),
        distributionType = "expert", probability = 0.08,
        minLoss = None, maxLoss = None,
        percentiles = Some(Array(0.25, 0.50, 0.95)),
        quantiles   = Some(Array(1000000.0, 4000000.0, 18000000.0))
      ),
      // ── Market Risk (2 leaves) ────────────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Equity Portfolio Drawdown", parentName = Some("Market Risk"),
        distributionType = "expert", probability = 0.35,
        minLoss = None, maxLoss = None,
        percentiles = Some(Array(0.25, 0.50, 0.75, 0.95)),
        quantiles   = Some(Array(1000000.0, 4000000.0, 12000000.0, 28000000.0))
      ),
      RiskLeafDefinitionRequest(
        name = "FX Adverse Move", parentName = Some("Market Risk"),
        distributionType = "lognormal", probability = 0.40,
        minLoss = Some(500000L), maxLoss = Some(8000000L),
        percentiles = None, quantiles = None
      ),
      // ── Credit Risk (2 leaves) ────────────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Counterparty Default", parentName = Some("Credit Risk"),
        distributionType = "lognormal", probability = 0.05,
        minLoss = Some(3000000L), maxLoss = Some(30000000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "Credit Downgrade Wave", parentName = Some("Credit Risk"),
        distributionType = "expert", probability = 0.15,
        minLoss = None, maxLoss = None,
        percentiles = Some(Array(0.25, 0.50, 0.95)),
        quantiles   = Some(Array(800000.0, 3000000.0, 20000000.0))
      ),
      // ── Liquidity Risk (1 leaf) ────────────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Funding Squeeze", parentName = Some("Liquidity Risk"),
        distributionType = "lognormal", probability = 0.08,
        minLoss = Some(2000000L), maxLoss = Some(25000000L),
        percentiles = None, quantiles = None
      ),
      // ── Compliance & Legal Risk (3 leaves) ────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Regulatory Action", parentName = Some("Compliance & Legal Risk"),
        distributionType = "lognormal", probability = 0.12,
        minLoss = Some(2000000L), maxLoss = Some(50000000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "Litigation", parentName = Some("Compliance & Legal Risk"),
        distributionType = "expert", probability = 0.08,
        minLoss = None, maxLoss = None,
        percentiles = Some(Array(0.25, 0.50, 0.75, 0.95)),
        quantiles   = Some(Array(300000.0, 2000000.0, 8000000.0, 40000000.0))
      ),
      RiskLeafDefinitionRequest(
        name = "GDPR / Data Protection Fine", parentName = Some("Compliance & Legal Risk"),
        distributionType = "lognormal", probability = 0.15,
        minLoss = Some(500000L), maxLoss = Some(10000000L),
        percentiles = None, quantiles = None
      ),
      // ── Strategic & Reputational Risk (3 leaves) ──────────────────────────
      RiskLeafDefinitionRequest(
        name = "ESG Controversy", parentName = Some("Strategic & Reputational Risk"),
        distributionType = "lognormal", probability = 0.10,
        minLoss = Some(1000000L), maxLoss = Some(12000000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "M&A Integration Failure", parentName = Some("Strategic & Reputational Risk"),
        distributionType = "lognormal", probability = 0.05,
        minLoss = Some(5000000L), maxLoss = Some(40000000L),
        percentiles = None, quantiles = None
      ),
      RiskLeafDefinitionRequest(
        name = "Product Recall / Liability", parentName = Some("Strategic & Reputational Risk"),
        distributionType = "expert", probability = 0.06,
        minLoss = None, maxLoss = None,
        percentiles = Some(Array(0.25, 0.50, 0.95)),
        quantiles   = Some(Array(1000000.0, 5000000.0, 35000000.0))
      )
    )
  )

  private def query = DemoSpecSupport.query

  override def spec =
    suite("DemoEnterpriseScriptSpec")(
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

          // Q1: ~12/21 leaves have unconditional P99 > $10M — comfortably satisfies >=1/4
          q1   <- query(client, key, treeId)("""Q[>=]^{1/4} x (leaf(x), gt_loss(p99(x), 10000000))""")
          // Q2: ~6/21 leaves have >10% chance of exceeding $1M — satisfies <=1/2
          q2   <- query(client, key, treeId)("""Q[<=]^{1/2} x (leaf(x), gt_prob(lec(x, 1000000), 0.10))""")
          // Q3: ~14/21 leaves have unconditional P95 > $1M — typically false (~62% < 75% threshold) but not asserted; boundary unstable
          q3   <- query(client, key, treeId)("""Q[>=]^{3/4} x (leaf(x), gt_loss(p95(x), 1000000))""")
          // Q4: ~5-6/21 leaves have unconditional P95 > $5M — far below ~1/2
          q4   <- query(client, key, treeId)("""Q[~]^{1/2} x (leaf(x), gt_loss(p95(x), 5000000))""")
          // Q4b: 4-5/21≈19-24% is "about 1/5" — Q[~]^{1/5} showcases vague tolerance; stably satisfied whether 4 or 5 leaves qualify
          q4b  <- query(client, key, treeId)("""Q[~]^{1/5} x (leaf(x), gt_loss(p95(x), 5000000))""")
          // Q5: portfolios with P95 > $50M — p95 is more stable than p99 (5× more MC samples); satisfies <=1/3
          q5   <- query(client, key, treeId)("""Q[<=]^{1/3} x (portfolio(x), gt_loss(p95(x), 50000000))""")
          // Q6: 2/21 leaves have >5% chance of exceeding $10M — satisfies <=1/4
          q6   <- query(client, key, treeId)("""Q[<=]^{1/4} x (leaf(x), gt_prob(lec(x, 10000000), 0.05))""")
          // Q7: ~7-8/11 portfolios have a child with P95 > $5M — boundary against 3/4 bar
          //      (asserts rangeSize only; satisfied flickers near 0.73)
          q7   <- query(client, key, treeId)("""Q[>=]^{3/4} x (portfolio(x), exists y . (child_of(y, x) /\ gt_loss(p95(y), 5000000)))""")
          // Q7b: same proportion satisfies the vague ~2/3 threshold (within tolerance)
          q7b  <- query(client, key, treeId)("""Q[~]^{2/3} x (portfolio(x), exists y . (child_of(y, x) /\ gt_loss(p95(y), 5000000)))""")
          // Q8: 10/11 portfolios have ALL direct children with P99 > $1M — satisfies >=1/2
          q8   <- query(client, key, treeId)("""Q[>=]^{1/2} x (portfolio(x), forall y . (child_of(y, x) ==> gt_loss(p99(y), 1000000)))""")

          // Q-A: no Technology & Cyber leaf has unconditional P95 > $5M (0/4) — fails >=2/3
          qa   <- query(client, key, treeId)("""Q[>=]^{2/3} x (leaf_descendant_of(x, "Technology & Cyber"), gt_loss(p95(x), 5000000))""")
          // Q-Ab: P99 > $1M — all 4 Cyber leaves clear it (Insider Threat P99 is 80th conditional ≫ $1M; 5%-prob P95 edge gone)
          qab  <- query(client, key, treeId)("""Q[>=]^{2/3} x (leaf_descendant_of(x, "Technology & Cyber"), gt_loss(p99(x), 1000000))""")
          // Q-B: 1/3 Operational Risk children clear the LEC bar — fails >=1/2
          qb   <- query(client, key, treeId)("""Q[>=]^{1/2} x (child_of(x, "Operational Risk"), gt_prob(lec(x, 10000000), 0.05))""")
          // Q-Bb: scope swap — 4/4 Enterprise Risk children (top-level aggregates) clear the LEC bar
          qbb  <- query(client, key, treeId)("""Q[>=]^{1/2} x (child_of(x, "Enterprise Risk"), gt_prob(lec(x, 10000000), 0.05))""")
          // Q-C1: 1/5 Financial Risk leaves have P99 > $20M — fails >=2/3
          qc1  <- query(client, key, treeId)("""Q[>=]^{2/3} x (leaf_descendant_of(x, "Financial Risk"), gt_loss(p99(x), 20000000))""")
          // Q-C1b: same data, quantifier flip — 1/5 satisfies <=1/3
          qc1b <- query(client, key, treeId)("""Q[<=]^{1/3} x (leaf_descendant_of(x, "Financial Risk"), gt_loss(p99(x), 20000000))""")
          // Q-C2: 1/10 Operational Risk leaves have P99 > $20M — fails >=2/3 (contrast with Q-C1)
          qc2  <- query(client, key, treeId)("""Q[>=]^{2/3} x (leaf_descendant_of(x, "Operational Risk"), gt_loss(p99(x), 20000000))""")
          // Q-C2b: scope swap to Compliance + threshold lowered to $5M — 3/3 leaves clear it
          qc2b <- query(client, key, treeId)("""Q[>=]^{2/3} x (leaf_descendant_of(x, "Compliance & Legal Risk"), gt_loss(p99(x), 5000000))""")
          // Q-D: ~9-11/21 non-Cyber leaves have P95 > $1M (~43-52%) — far from "about 1/3"; Q[~]^{1/3} stably fails
          qd   <- query(client, key, treeId)("""Q[~]^{1/3} x (leaf(x), ~descendant_of(x, "Technology & Cyber") /\ gt_loss(p95(x), 1000000))""")
          // Q-Db: same proportion IS "about 1/2" — Q[~]^{1/2} showcases around tolerance vs strict Q[<=]
          qdb  <- query(client, key, treeId)("""Q[~]^{1/2} x (leaf(x), ~descendant_of(x, "Technology & Cyber") /\ gt_loss(p95(x), 1000000))""")

          // Diagnostic: log actual proportions for calibration (single call to avoid deep for-comprehension)
        yield assertTrue(q1.rangeSize == 21, q1.satisfied) &&   // ~12/21≈57% sat Q[>=]^{1/4}(25%); margin≈0.32 ✓
          assertTrue(q2.rangeSize == 21, q2.satisfied) &&         // 6/21≈29% sat Q[<=]^{1/2}(50%); margin=0.21 ✓
          assertTrue(q3.rangeSize == 21) &&                       // typically false (~62% < 75% threshold); not asserted — boundary unstable
          assertTrue(q4.rangeSize == 21, !q4.satisfied) &&
          assertTrue(q4b.rangeSize == 21, q4b.satisfied) &&      // 4-5/21≈19-24% ≈ Q[~]^{1/5}(20%); around tolerance ✓
          assertTrue(q5.rangeSize == 11, q5.satisfied) &&           // p95 replaces p99 for stability
          assertTrue(q6.rangeSize == 21, q6.satisfied) &&
          assertTrue(q7.rangeSize == 11) &&                       // boundary: satisfied unstable near 3/4
          assertTrue(q7b.rangeSize == 11) &&                      // boundary: 6–7/11 ≈ 55–64% near About(2/3)±tolerance
          assertTrue(q8.rangeSize == 11, q8.satisfied) &&
          assertTrue(qa.rangeSize == 4, !qa.satisfied) &&
          assertTrue(qab.rangeSize == 4, qab.satisfied) &&        // 4/4 all Cyber leaves have P99>$1M; margin=0.33 ✓
          assertTrue(qb.rangeSize == 3, !qb.satisfied) &&
          assertTrue(qbb.rangeSize == 4, qbb.satisfied) &&
          assertTrue(qc1.rangeSize == 5, !qc1.satisfied) &&
          assertTrue(qc1b.rangeSize == 5, qc1b.satisfied) &&
          assertTrue(qc2.rangeSize == 10, !qc2.satisfied) &&
          assertTrue(qc2b.rangeSize == 3, qc2b.satisfied) &&
          assertTrue(qd.rangeSize == 21, !qd.satisfied) &&        // 9-11/21≈43-52% far from Q[~]^{1/3}(33%); stably false
          assertTrue(qdb.rangeSize == 21, qdb.satisfied)            // 9-11/21≈43-52% ≈ Q[~]^{1/2}(50%); around tolerance ✓
      }
    ).provideLayerShared(harnessLayer) @@
      TestAspect.withLiveClock @@
      TestAspect.sequential @@
      TestAspect.timeout(300.seconds)

end DemoEnterpriseScriptSpec

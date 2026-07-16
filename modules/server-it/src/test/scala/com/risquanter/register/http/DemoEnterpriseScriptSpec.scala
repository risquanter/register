package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest}
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
    * (21 leaves, 11 portfolios — Financial Services Enterprise Risk).
    * Shared with SeedReproducibilityItSpec (order-independence, PLAN-SEED-IDENTITY §11).
    */
  private[http] val demoTreeRequest = RiskTreeDefinitionRequest(
    name = "Financial Services Enterprise Risk",
    portfolios = Seq(
      RiskPortfolioDefinitionRequest(name = "Enterprise Risk",               parentName = None),
      RiskPortfolioDefinitionRequest(name = "Operational Risk",              parentName = Some("Enterprise Risk")),
      RiskPortfolioDefinitionRequest(name = "Technology and Cyber",            parentName = Some("Operational Risk")),
      RiskPortfolioDefinitionRequest(name = "Process and People",              parentName = Some("Operational Risk")),
      RiskPortfolioDefinitionRequest(name = "Third-Party and Supply Chain",    parentName = Some("Operational Risk")),
      RiskPortfolioDefinitionRequest(name = "Financial Risk",                parentName = Some("Enterprise Risk")),
      RiskPortfolioDefinitionRequest(name = "Market Risk",                   parentName = Some("Financial Risk")),
      RiskPortfolioDefinitionRequest(name = "Credit Risk",                   parentName = Some("Financial Risk")),
      RiskPortfolioDefinitionRequest(name = "Liquidity Risk",                parentName = Some("Financial Risk")),
      RiskPortfolioDefinitionRequest(name = "Compliance and Legal Risk",       parentName = Some("Enterprise Risk")),
      RiskPortfolioDefinitionRequest(name = "Strategic and Reputational Risk", parentName = Some("Enterprise Risk"))
    ),
    leaves = Seq(
      // ── Technology and Cyber (4 leaves) ────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Ransomware Attack", parentName = Some("Technology and Cyber"),
        probability = 0.15,
        distributionShape = DistributionShapeRequest(
          distributionType = "expert",
          percentiles = Some(Array(0.25, 0.50, 0.75, 0.95)),
          quantiles   = Some(Array(500000.0, 2000000.0, 8000000.0, 25000000.0)),
          minLoss = None, maxLoss = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Cloud Provider Outage", parentName = Some("Technology and Cyber"),
        probability = 0.30,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(200000L), maxLoss = Some(4000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Data Breach - PII", parentName = Some("Technology and Cyber"),
        probability = 0.10,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(1000000L), maxLoss = Some(15000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Insider Threat", parentName = Some("Technology and Cyber"),
        probability = 0.05,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(2000000L), maxLoss = Some(20000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      // ── Process and People (3 leaves) ───────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Key Person Departure", parentName = Some("Process and People"),
        probability = 0.20,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(100000L), maxLoss = Some(800000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Internal Fraud", parentName = Some("Process and People"),
        probability = 0.08,
        distributionShape = DistributionShapeRequest(
          distributionType = "expert",
          percentiles = Some(Array(0.25, 0.50, 0.75, 0.95)),
          quantiles   = Some(Array(200000.0, 1000000.0, 4000000.0, 18000000.0)),
          minLoss = None, maxLoss = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Process Failure", parentName = Some("Process and People"),
        probability = 0.25,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(50000L), maxLoss = Some(500000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      // ── Third-Party and Supply Chain (3 leaves) ─────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Critical Vendor Failure", parentName = Some("Third-Party and Supply Chain"),
        probability = 0.12,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(500000L), maxLoss = Some(5000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Outsourcing SLA Breach", parentName = Some("Third-Party and Supply Chain"),
        probability = 0.20,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(100000L), maxLoss = Some(1500000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Concentration Risk", parentName = Some("Third-Party and Supply Chain"),
        probability = 0.08,
        distributionShape = DistributionShapeRequest(
          distributionType = "expert",
          percentiles = Some(Array(0.25, 0.50, 0.95)),
          quantiles   = Some(Array(1000000.0, 4000000.0, 18000000.0)),
          minLoss = None, maxLoss = None, terms = None
        )
      ),
      // ── Market Risk (2 leaves) ────────────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Equity Portfolio Drawdown", parentName = Some("Market Risk"),
        probability = 0.35,
        distributionShape = DistributionShapeRequest(
          distributionType = "expert",
          percentiles = Some(Array(0.25, 0.50, 0.75, 0.95)),
          quantiles   = Some(Array(1000000.0, 4000000.0, 12000000.0, 28000000.0)),
          minLoss = None, maxLoss = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "FX Adverse Move", parentName = Some("Market Risk"),
        probability = 0.40,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(500000L), maxLoss = Some(8000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      // ── Credit Risk (2 leaves) ────────────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Counterparty Default", parentName = Some("Credit Risk"),
        probability = 0.05,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(3000000L), maxLoss = Some(30000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Credit Downgrade Wave", parentName = Some("Credit Risk"),
        probability = 0.15,
        distributionShape = DistributionShapeRequest(
          distributionType = "expert",
          percentiles = Some(Array(0.25, 0.50, 0.95)),
          quantiles   = Some(Array(800000.0, 3000000.0, 20000000.0)),
          minLoss = None, maxLoss = None, terms = None
        )
      ),
      // ── Liquidity Risk (1 leaf) ────────────────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Funding Squeeze", parentName = Some("Liquidity Risk"),
        probability = 0.08,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(2000000L), maxLoss = Some(25000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      // ── Compliance and Legal Risk (3 leaves) ────────────────────────────────
      RiskLeafDefinitionRequest(
        name = "Regulatory Action", parentName = Some("Compliance and Legal Risk"),
        probability = 0.12,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(2000000L), maxLoss = Some(50000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Litigation", parentName = Some("Compliance and Legal Risk"),
        probability = 0.08,
        distributionShape = DistributionShapeRequest(
          distributionType = "expert",
          percentiles = Some(Array(0.25, 0.50, 0.75, 0.95)),
          quantiles   = Some(Array(300000.0, 2000000.0, 8000000.0, 40000000.0)),
          minLoss = None, maxLoss = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "GDPR / Data Protection Fine", parentName = Some("Compliance and Legal Risk"),
        probability = 0.15,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(500000L), maxLoss = Some(10000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      // ── Strategic and Reputational Risk (3 leaves) ──────────────────────────
      RiskLeafDefinitionRequest(
        name = "ESG Controversy", parentName = Some("Strategic and Reputational Risk"),
        probability = 0.10,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(1000000L), maxLoss = Some(12000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "M and A Integration Failure", parentName = Some("Strategic and Reputational Risk"),
        probability = 0.05,
        distributionShape = DistributionShapeRequest(
          distributionType = "lognormal",
          minLoss = Some(5000000L), maxLoss = Some(40000000L),
          percentiles = None, quantiles = None, terms = None
        )
      ),
      RiskLeafDefinitionRequest(
        name = "Product Recall / Liability", parentName = Some("Strategic and Reputational Risk"),
        probability = 0.06,
        distributionShape = DistributionShapeRequest(
          distributionType = "expert",
          percentiles = Some(Array(0.25, 0.50, 0.95)),
          quantiles   = Some(Array(1000000.0, 5000000.0, 35000000.0)),
          minLoss = None, maxLoss = None, terms = None
        )
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

          // Q-A: no Technology and Cyber leaf has unconditional P95 > $5M (0/4) — fails >=2/3
          qa   <- query(client, key, treeId)("""Q[>=]^{2/3} x (leaf_descendant_of(x, "Technology and Cyber"), gt_loss(p95(x), 5000000))""")
          // Q-Ab: P99 > $1M — all 4 Cyber leaves clear it (Insider Threat P99 is 80th conditional ≫ $1M; 5%-prob P95 edge gone)
          qab  <- query(client, key, treeId)("""Q[>=]^{2/3} x (leaf_descendant_of(x, "Technology and Cyber"), gt_loss(p99(x), 1000000))""")
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
          qc2b <- query(client, key, treeId)("""Q[>=]^{2/3} x (leaf_descendant_of(x, "Compliance and Legal Risk"), gt_loss(p99(x), 5000000))""")
          // Q-D: 9/21 non-Cyber leaves have P95 > $1M (42.86%) — a full count-step outside
          //      About(1/4,0.1)'s band [0.15,0.35]. A 1/3 quantifier would sit 0.005 inside
          //      the band (knife-edge); 1/4 keeps the verdict margin-assertable.
          qd   <- query(client, key, treeId)("""Q[~]^{1/4} x (leaf(x), ~descendant_of(x, "Technology and Cyber") /\ gt_loss(p95(x), 1000000))""")
          // Q-Db: same proportion IS "about 1/2" — Q[~]^{1/2} showcases around tolerance vs strict Q[<=]
          qdb  <- query(client, key, treeId)("""Q[~]^{1/2} x (leaf(x), ~descendant_of(x, "Technology and Cyber") /\ gt_loss(p95(x), 1000000))""")

          // With boundary-assigned seed identities every figure below is deterministic:
          // verdicts re-recorded once (PLAN §11), and the three threshold-straddling
          // queries (Q1, Q7b, Q-D) carry explicit margin assertions — the proportion
          // must sit a stated distance from its quantifier boundary, so a future
          // figure shift fails loudly instead of silently flipping a verdict.
        yield assertTrue(q1.rangeSize == 21, q1.satisfied) &&   // 13/21=0.619 sat Q[>=]^{1/4}(0.25)
          assertTrue(q1.proportion >= 0.25 + 0.10) &&             // Q1 margin: ≥0.10 above the bar (actual 0.37)
          assertTrue(q2.rangeSize == 21, q2.satisfied) &&         // 6/21=0.286 sat Q[<=]^{1/2}(0.50); margin 0.21
          assertTrue(q3.rangeSize == 21, !q3.satisfied) &&        // 13/21=0.619 < 0.75; margin 0.13 (was unstable pre-redesign)
          assertTrue(q4.rangeSize == 21, !q4.satisfied) &&        // 5/21=0.238 outside About(1/2,0.1)
          assertTrue(q4b.rangeSize == 21, q4b.satisfied) &&       // 5/21=0.238 inside About(1/5,0.1)
          assertTrue(q5.rangeSize == 11, q5.satisfied) &&         // 0/11 sat Q[<=]^{1/3}
          assertTrue(q6.rangeSize == 21, q6.satisfied) &&         // 2/21=0.095 sat Q[<=]^{1/4}
          assertTrue(q7.rangeSize == 11, !q7.satisfied) &&        // 7/11=0.636 < 0.75; margin 0.11 (was unstable pre-redesign)
          assertTrue(q7b.rangeSize == 11, q7b.satisfied) &&       // 7/11=0.636 inside About(2/3,0.1) band [0.567,0.767]
          assertTrue(                                             // Q7b margin: ≥0.05 from both band edges
            q7b.proportion >= (2.0 / 3 - 0.1) + 0.05,
            q7b.proportion <= (2.0 / 3 + 0.1) - 0.05
          ) &&
          assertTrue(q8.rangeSize == 11, q8.satisfied) &&         // 10/11=0.909 sat Q[>=]^{1/2}
          assertTrue(qa.rangeSize == 4, !qa.satisfied) &&         // 0/4
          assertTrue(qab.rangeSize == 4, qab.satisfied) &&        // 4/4 all Cyber leaves have P99>$1M; margin 0.33
          assertTrue(qb.rangeSize == 3, !qb.satisfied) &&         // 1/3
          assertTrue(qbb.rangeSize == 4, qbb.satisfied) &&        // 4/4
          assertTrue(qc1.rangeSize == 5, !qc1.satisfied) &&       // 1/5
          assertTrue(qc1b.rangeSize == 5, qc1b.satisfied) &&      // 1/5 sat Q[<=]^{1/3}
          assertTrue(qc2.rangeSize == 10, !qc2.satisfied) &&      // 1/10
          assertTrue(qc2b.rangeSize == 3, qc2b.satisfied) &&      // 3/3
          assertTrue(qd.rangeSize == 21, !qd.satisfied) &&        // 9/21=0.4286 outside About(1/4,0.1) band [0.15,0.35]
          assertTrue(qd.proportion >= (0.25 + 0.1) + 0.05) &&     // Q-D margin: ≥0.05 above the band's upper edge (actual 0.079)
          assertTrue(qdb.rangeSize == 21, qdb.satisfied)          // 9/21=0.4286 inside About(1/2,0.1); around-tolerance contrast
      }
    ).provideLayerShared(harnessLayer) @@
      TestAspect.withLiveClock @@
      TestAspect.sequential @@
      TestAspect.timeout(300.seconds)

end DemoEnterpriseScriptSpec

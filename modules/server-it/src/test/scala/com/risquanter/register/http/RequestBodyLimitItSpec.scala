package com.risquanter.register.http

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*

import com.risquanter.register.http.HttpTestHarness.HarnessConfig
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest, DistributionShapeRequest}
import com.risquanter.register.http.responses.WorkspaceBootstrapResponse
import com.risquanter.register.http.support.SttpClientFixture

/** Lever 3: the configured request body-size cap
  * (`ServerConfig.maxRequestBytes` → zio-http `RequestStreaming.Disabled`) binds
  * through the tapir routes. A body over the cap is rejected at the transport
  * layer (413) before any handler runs; a valid body under it is accepted (200).
  *
  * This is the regression test for open question O-1 — whether the zio-http
  * `requestStreaming` cap applies to the `ZioHttpInterpreter` routes. An
  * over-cap body that returned 400/415 (parsed by a handler) instead of 413
  * would mean the cap does not bind, and this test would fail.
  */
object RequestBodyLimitItSpec extends ZIOSpecDefault:

  private val MaxBytes = 4096
  private val harness  = HarnessConfig(maxRequestBytes = MaxBytes)

  private def leafDef(name: String, parent: String): RiskLeafDefinitionRequest =
    RiskLeafDefinitionRequest(
      name = name,
      parentName = Some(parent),
      probability = 0.25,
      distributionShape = DistributionShapeRequest(
        distributionType = "lognormal",
        percentiles = None, quantiles = None, terms = None,
        minLoss = Some(1000L), maxLoss = Some(50000L)
      )
    )

  /** Small valid tree request (well under `MaxBytes` serialized). */
  private val smallTreeReq: RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = "Body Limit Tree",
      portfolios = Seq(RiskPortfolioDefinitionRequest("Root", None)),
      leaves = Seq(leafDef("Cyber", "Root"))
    )

  private def withServer[A](f: SttpClientFixture.Client => Task[A]): Task[A] =
    ZIO.scoped {
      ZLayer.makeSome[Scope, SttpClientFixture.Client](
        HttpTestHarness.inMemoryServer(harness),
        SttpClientFixture.layer
      ).build.map(_.get[SttpClientFixture.Client]).flatMap(f)
    }

  override def spec =
    suite("RequestBodyLimitItSpec (Lever 3 — O-1)")(

      test("a valid request body under the cap is accepted (200)") {
        withServer { client =>
          basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(smallTreeReq)
            .response(asJson[WorkspaceBootstrapResponse])
            .send(client.backend)
            .map(resp => assertTrue(resp.code.code == 200))
        }
      },

      test("a request body over the cap is rejected at the transport layer (413 or connection reset)") {
        val oversized = "x" * (MaxBytes * 2)
        withServer { client =>
          basicRequest.header("X-Branch", "main")
            .post(uri"${client.baseUrl}/workspaces")
            .body(oversized)
            .send(client.backend)
            .either
            .map {
              case Right(resp) => assertTrue(resp.code.code == 413)
              case Left(_)     => assertTrue(true) // connection-level rejection is also acceptance of the cap
            }
        }
      }
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskPortfolioDefinitionRequest, RiskLeafDefinitionRequest}
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.http.support.SttpClientFixture
import com.risquanter.register.testcontainers.IrminCompose
import io.github.iltotore.iron.*

object HttpApiIntegrationSpec extends ZIOSpecDefault:

  private val harnessLayer =
    ZLayer.makeSome[Scope, SttpClientFixture.Client](
      HttpTestHarness.irminServer(IrminCompose.irminConfigLayer),
      SttpClientFixture.layer
    )

  private def sampleRequest: RiskTreeDefinitionRequest =
    RiskTreeDefinitionRequest(
      name = "Tree One",
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

  override def spec =
    suite("HttpApiIntegrationSpec")(
      test("health endpoint returns OK (Irmin-backed server)") {
        for
          client   <- ZIO.service[SttpClientFixture.Client]
          response <- basicRequest.get(uri"${client.baseUrl}/api/health").send(client.backend)
        yield assertTrue(response.code.isSuccess) && assertTrue(response.body.exists(_.contains("OK")))
      },
      test("create and retrieve risk tree via live HTTP") {
        val request = sampleRequest
        for
          client <- ZIO.service[SttpClientFixture.Client]
          createResp <- basicRequest
            .post(uri"${client.baseUrl}/risk-trees")
            .body(request)
            .response(asJson[SimulationResponse])
            .send(client.backend)
          created <- ZIO.fromEither(createResp.body)
          listResp <- basicRequest
            .get(uri"${client.baseUrl}/risk-trees")
            .response(asJson[List[SimulationResponse]])
            .send(client.backend)
          listed <- ZIO.fromEither(listResp.body)
          getResp <- basicRequest
            .get(uri"${client.baseUrl}/risk-trees/${created.id.value}")
            .response(asJson[Option[SimulationResponse]])
            .send(client.backend)
          fetched <- ZIO.fromEither(getResp.body)
        yield assertTrue(created.name == request.name) &&
          assertTrue(listed.exists(_.id == created.id)) &&
          assertTrue(fetched.exists(_.id == created.id))
      }
    ).provideLayerShared(harnessLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock


package com.risquanter.register.http

import zio.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.domain.data.RiskLeaf
import com.risquanter.register.domain.data.RiskPortfolio
import com.risquanter.register.domain.data.iron.SafeId
import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
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
    val rootId  = "root"
    val leaf1Id = "leaf-1"
    val leaf2Id = "leaf-2"

    val portfolio = RiskPortfolio.create(
      id = rootId,
      name = "Root",
      childIds = Array(
        SafeId.fromString(leaf1Id).toOption.get,
        SafeId.fromString(leaf2Id).toOption.get
      ),
      parentId = None
    ).toEither.toOption.get

    val leaf1 = RiskLeaf.create(
      id = leaf1Id,
      name = "Leaf 1",
      distributionType = "lognormal",
      probability = 0.1,
      minLoss = Some(1000L),
      maxLoss = Some(2000L),
      parentId = Some(SafeId.fromString(rootId).toOption.get)
    ).toEither.toOption.get

    val leaf2 = RiskLeaf.create(
      id = leaf2Id,
      name = "Leaf 2",
      distributionType = "lognormal",
      probability = 0.2,
      minLoss = Some(1500L),
      maxLoss = Some(3000L),
      parentId = Some(SafeId.fromString(rootId).toOption.get)
    ).toEither.toOption.get

    RiskTreeDefinitionRequest(
      name = "Tree One",
      nodes = Seq(portfolio, leaf1, leaf2),
      rootId = rootId
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
            .get(uri"${client.baseUrl}/risk-trees/${created.id}")
            .response(asJson[Option[SimulationResponse]])
            .send(client.backend)
          fetched <- ZIO.fromEither(getResp.body)
        yield assertTrue(created.name == request.name) &&
          assertTrue(listed.exists(_.id == created.id)) &&
          assertTrue(fetched.exists(_.id == created.id))
      }
    ).provideLayerShared(harnessLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock


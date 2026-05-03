package com.risquanter.register.http.support

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.ziojson.*
import com.risquanter.register.http.requests.QueryRequest
import com.risquanter.register.http.responses.QueryResponse
import com.risquanter.register.http.HttpTestHarness
import com.risquanter.register.http.HttpTestHarness.HarnessConfig
import com.risquanter.register.configs.SimulationConfig
import io.github.iltotore.iron.*

/** Shared fixtures for DemoSimpleScriptSpec and DemoEnterpriseScriptSpec. */
object DemoSpecSupport:

  /** Production-equivalent simulation config (mirrors application.conf defaults). */
  val productionSimulationConfig: SimulationConfig = SimulationConfig(
    defaultNTrials           = 10000.refineUnsafe,
    maxTreeDepth             = 5.refineUnsafe,
    defaultTrialParallelism  = 8.refineUnsafe,
    maxConcurrentSimulations = 4.refineUnsafe,
    maxNTrials               = 1000000.refineUnsafe,
    maxParallelism           = 16.refineUnsafe,
    defaultSeed3             = 0L,
    defaultSeed4             = 0L
  )

  val harnessLayer: ZLayer[Scope, Throwable, SttpClientFixture.Client] =
    ZLayer.makeSome[Scope, SttpClientFixture.Client](
      HttpTestHarness.inMemoryServer(
        HarnessConfig(simulation = productionSimulationConfig)
      ),
      SttpClientFixture.layer
    )

  def query(client: SttpClientFixture.Client, key: String, treeId: String)(q: String): Task[QueryResponse] =
    basicRequest
      .post(uri"${client.baseUrl}/w/$key/risk-trees/$treeId/query")
      .body(QueryRequest(q))
      .response(asJson[QueryResponse])
      .send(client.backend)
      .flatMap(r => ZIO.fromEither(r.body))

package com.risquanter.register

import zio.*
import zio.http.Server
import com.risquanter.register.http.HttpApi
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory

object EndpointsCheck extends ZIOAppDefault {
  override def run = {
    val program = for {
      _         <- ZIO.logInfo("EndpointsCheck: Getting endpoints...")
      endpoints <- HttpApi.endpointsZIO
      _         <- ZIO.logInfo(s"EndpointsCheck: Got ${endpoints.length} endpoints")
    } yield ()

    program.provide(
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.services.SimulationExecutionService.live,
      RiskTreeServiceLive.layer,
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    )
  }
}

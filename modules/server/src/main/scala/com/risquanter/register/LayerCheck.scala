package com.risquanter.register

import zio.*
import zio.http.Server
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory

object LayerCheck extends ZIOAppDefault {
  override def run =
    ZIO.logInfo("LayerCheck: start") *>
    ZIO.unit.provide(
      Server.default,
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.services.SimulationExecutionService.live,
      RiskTreeServiceLive.layer,
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    ) *>
    ZIO.logInfo("LayerCheck: finished")
}

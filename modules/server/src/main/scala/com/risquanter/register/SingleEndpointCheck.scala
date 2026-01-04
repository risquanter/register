package com.risquanter.register

import zio.*
import com.risquanter.register.http.controllers.RiskTreeController
import com.risquanter.register.services.RiskTreeServiceLive
import com.risquanter.register.repositories.RiskTreeRepositoryInMemory

object SingleEndpointCheck extends ZIOAppDefault {
  override def run = {
    val program = for {
      _          <- ZIO.logInfo("Getting controller...")
      controller <- ZIO.service[RiskTreeController]
      _          <- ZIO.logInfo("Got controller")
      _          <- ZIO.logInfo("Getting create endpoint...")
      _           = controller.create
      _          <- ZIO.logInfo("Got create endpoint")
      _          <- ZIO.logInfo("Getting computeLEC endpoint...")
      _           = controller.computeLEC
      _          <- ZIO.logInfo("Got computeLEC endpoint")
      _          <- ZIO.logInfo("Getting getAll endpoint...")
      _           = controller.getAll
      _          <- ZIO.logInfo("Got getAll endpoint")
      _          <- ZIO.logInfo("Getting getById endpoint...")
      _           = controller.getById
      _          <- ZIO.logInfo("Got getById endpoint")
      _          <- ZIO.logInfo("All endpoints OK!")
    } yield ()

    program.provide(
      RiskTreeRepositoryInMemory.layer,
      com.risquanter.register.services.SimulationExecutionService.live,
      RiskTreeServiceLive.layer,
      ZLayer.fromZIO(RiskTreeController.makeZIO)
    )
  }
}

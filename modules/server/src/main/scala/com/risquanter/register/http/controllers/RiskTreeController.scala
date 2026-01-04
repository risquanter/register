package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint
import com.risquanter.register.http.endpoints.RiskTreeEndpoints
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.http.responses.SimulationResponse
import io.github.iltotore.iron.autoRefine

/** Controller for RiskTree HTTP endpoints
  * Handles request validation and delegates to service layer
  * 
  * Pattern: POST creates + executes risk tree, returns LEC embedded in response
  */
class RiskTreeController private (riskTreeService: RiskTreeService)
    extends BaseController
    with RiskTreeEndpoints {

  val health: ServerEndpoint[Any, Task] = healthEndpoint.serverLogicSuccess { _ =>
    ZIO.succeed(Map("status" -> "healthy", "service" -> "risk-register"))
  }

  val create: ServerEndpoint[Any, Task] = createEndpoint.serverLogic { req =>
    // POST creates config only (no LEC computation)
    val program = riskTreeService.create(req).map { result =>
      SimulationResponse.fromRiskTree(result)
    }

    program.either
  }

  val computeLEC: ServerEndpoint[Any, Task] = computeLECEndpoint.serverLogic {
    case (idStr, nTrialsOverride, parallelism, depth) =>
      val program = for {
        id <- ZIO.attempt(idStr.toLong)
        result <- riskTreeService.computeLEC(id, nTrialsOverride, parallelism, depth)
      } yield SimulationResponse.withLEC(
        result.riskTree,
        result.quantiles,
        result.vegaLiteSpec,
        result.individualRisks
      )

      program.either
  }

  val getAll: ServerEndpoint[Any, Task] = getAllEndpoint.serverLogicSuccess { _ =>
    riskTreeService.getAll.map(_.map(SimulationResponse.fromRiskTree))
  }

  val getById: ServerEndpoint[Any, Task] = getByIdEndpoint.serverLogicSuccess { idStr =>
    ZIO
      .attempt(idStr.toLong)
      .flatMap(riskTreeService.getById)
      .map(_.map(SimulationResponse.fromRiskTree))
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(health, create, getAll, computeLEC, getById)
}

object RiskTreeController {
  val makeZIO: ZIO[RiskTreeService, Nothing, RiskTreeController] = for {
    riskTreeService <- ZIO.service[RiskTreeService]
  } yield new RiskTreeController(riskTreeService)
}

package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint
import com.risquanter.register.http.endpoints.RiskTreeEndpoints
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.domain.data.iron.{PositiveInt, IronConstants}
import IronConstants.Four

/** Controller for RiskTree HTTP endpoints.
  * 
  * Controllers are "dumb"—they wire endpoints to services.
  * Validation happens at the HTTP layer (Tapir codecs), so controllers
  * receive already-validated Iron types and pass them to services.
  * 
  * Pattern: HTTP Request → Tapir Codec (validates) → Controller (wires) → Service (trusts types)
  */
class RiskTreeController private (
  riskTreeService: RiskTreeService,
  invalidationHandler: InvalidationHandler
) extends BaseController
    with RiskTreeEndpoints {

  // Default parallelism when not specified by client
  private val DefaultParallelism: PositiveInt = Four
  
  val health: ServerEndpoint[Any, Task] = healthEndpoint.serverLogicSuccess { _ =>
    ZIO.succeed(Map("status" -> "healthy", "service" -> "risk-register"))
  }

  val create: ServerEndpoint[Any, Task] = createEndpoint.serverLogic { req =>
    riskTreeService.create(req).map(SimulationResponse.fromRiskTree).either
  }

  val getAll: ServerEndpoint[Any, Task] = getAllEndpoint.serverLogicSuccess { _ =>
    riskTreeService.getAll.map(_.map(SimulationResponse.fromRiskTree))
  }

  val getById: ServerEndpoint[Any, Task] = getByIdEndpoint.serverLogicSuccess { id =>
    riskTreeService.getById(id)
      .map(_.map(SimulationResponse.fromRiskTree))
  }

  /** Manual cache invalidation for testing SSE pipeline. */
  val invalidateCache: ServerEndpoint[Any, Task] = invalidateCacheEndpoint.serverLogicSuccess {
    case (treeId, nodeId) =>
      invalidationHandler.handleNodeChange(treeId, nodeId)
        .map(count => Map("invalidatedCount" -> count))
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(health, create, getAll, getById, invalidateCache)
}

object RiskTreeController {
  val makeZIO: ZIO[RiskTreeService & InvalidationHandler, Nothing, RiskTreeController] = for {
    riskTreeService <- ZIO.service[RiskTreeService]
    invalidationHandler <- ZIO.service[InvalidationHandler]
  } yield new RiskTreeController(riskTreeService, invalidationHandler)
}

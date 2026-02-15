package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint
import com.risquanter.register.http.endpoints.{RiskTreeEndpoints, InvalidationResponse}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.pipeline.{InvalidationHandler, InvalidationResult}
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.domain.data.{LECPoint, LECNodeCurve, LECCurveResponse}
import com.risquanter.register.domain.data.iron.{PositiveInt, IronConstants, SafeId, SafeIdStr, NodeId}
import com.risquanter.register.configs.ApiConfig
import com.risquanter.register.domain.errors.AccessDenied
import io.github.iltotore.iron.refineUnsafe
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
  invalidationHandler: InvalidationHandler,
  apiConfig: ApiConfig
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

  val update: ServerEndpoint[Any, Task] = updateEndpoint.serverLogic {
    case (id, req) =>
      riskTreeService.update(id, req).map(SimulationResponse.fromRiskTree).either
  }

  /** List all risk trees.
    * A17: Sealed with config gate — returns 403 Forbidden when disabled (default).
    * This prevents unauthenticated enumeration of all trees across all workspaces.
    */
  val getAll: ServerEndpoint[Any, Task] = getAllEndpoint.serverLogic { _ =>
    if apiConfig.listAllTreesEnabled then
      riskTreeService.getAll.map(_.map(SimulationResponse.fromRiskTree)).either
    else
      ZIO.left(AccessDenied("GET /risk-trees is disabled by configuration") : Throwable)
  }

  val getById: ServerEndpoint[Any, Task] = getByIdEndpoint.serverLogicSuccess { id =>
    riskTreeService.getById(id)
      .map(_.map(SimulationResponse.fromRiskTree))
  }

  /** Full tree structure for frontend rendering (expandable node hierarchy). */
  val getTreeStructure: ServerEndpoint[Any, Task] = getTreeStructureEndpoint.serverLogicSuccess { id =>
    riskTreeService.getById(id)
  }

  /** Manual cache invalidation for testing SSE pipeline. */
  val invalidateCache: ServerEndpoint[Any, Task] = invalidateCacheEndpoint.serverLogicSuccess {
    case (treeId, nodeId) =>
      invalidationHandler.handleNodeChange(treeId, nodeId)
        .map(r => InvalidationResponse(
          invalidatedNodes = r.invalidatedNodes.map(_.value.toString),
          subscribersNotified = r.subscribersNotified
        ))
  }
  
  // ========================================
  // LEC Query Endpoints
  // ========================================
  
  /** Get LEC curve for a single node. */
  val getLECCurve: ServerEndpoint[Any, Task] = getLECCurveEndpoint.serverLogicSuccess {
    case (treeId, nodeIdSafe, includeProvenance) =>
      riskTreeService.getLECCurve(treeId, nodeIdSafe, includeProvenance)
  }
  
  /** Get probability of exceeding a loss threshold. */
  val probOfExceedance: ServerEndpoint[Any, Task] = probOfExceedanceEndpoint.serverLogicSuccess {
    case (treeId, nodeIdSafe, threshold, includeProvenance) =>
      riskTreeService.probOfExceedance(treeId, nodeIdSafe, threshold, includeProvenance)
  }
  
  /** Get LEC curves for multiple nodes with shared tick domain. */
  val getLECCurvesMulti: ServerEndpoint[Any, Task] = getLECCurvesMultiEndpoint.serverLogic {
    case (treeId, includeProvenance, nodeIds) =>
      // nodeIds already validated as List[SafeId.SafeId] by JsonDecoder
      riskTreeService.getLECCurvesMulti(treeId, nodeIds.toSet, includeProvenance)
        .map(_.map { case (nodeId, nodeCurve) => (nodeId.value, nodeCurve) })
        .either
  }

  /** Get render-ready Vega-Lite chart spec for LEC visualization. */
  val getLECChart: ServerEndpoint[Any, Task] = getLECChartEndpoint.serverLogic {
    case (treeId, nodeIds) =>
      riskTreeService.getLECChart(treeId, nodeIds.toSet).either
  }

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(health, create, update, getAll, getById, getTreeStructure, invalidateCache, getLECCurve, probOfExceedance, getLECCurvesMulti, getLECChart)
}

object RiskTreeController {
  val makeZIO: ZIO[RiskTreeService & InvalidationHandler & ApiConfig, Nothing, RiskTreeController] = for {
    riskTreeService <- ZIO.service[RiskTreeService]
    invalidationHandler <- ZIO.service[InvalidationHandler]
    apiConfig <- ZIO.service[ApiConfig]
  } yield new RiskTreeController(riskTreeService, invalidationHandler, apiConfig)
}

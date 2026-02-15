package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint
import com.risquanter.register.http.endpoints.RiskTreeEndpoints
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.configs.ApiConfig
import com.risquanter.register.domain.errors.AccessDenied

/** Controller for non-workspace-scoped RiskTree endpoints.
  *
  * After workspace capability introduction (Tier 1.5), only `health` and the
  * config-gated `getAll` remain here. All tree-specific CRUD, LEC, and cache
  * operations are served exclusively via workspace-scoped paths in
  * `WorkspaceController` to prevent unauthenticated access by TreeId alone.
  */
class RiskTreeController private (
  riskTreeService: RiskTreeService,
  apiConfig: ApiConfig
) extends BaseController
    with RiskTreeEndpoints:

  val health: ServerEndpoint[Any, Task] = healthEndpoint.serverLogicSuccess { _ =>
    ZIO.succeed(Map("status" -> "healthy", "service" -> "risk-register"))
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

  override val routes: List[ServerEndpoint[Any, Task]] =
    List(health, getAll)

object RiskTreeController:
  val makeZIO: ZIO[RiskTreeService & ApiConfig, Nothing, RiskTreeController] =
    for
      riskTreeService <- ZIO.service[RiskTreeService]
      apiConfig       <- ZIO.service[ApiConfig]
    yield RiskTreeController(riskTreeService, apiConfig)

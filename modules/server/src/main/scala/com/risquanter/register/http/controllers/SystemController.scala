package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.http.endpoints.SystemEndpoints

/** System controller.
  *
  * Owns system-level routes that are intentionally outside the workspace
  * namespace.
  */
class SystemController private () extends BaseController with SystemEndpoints:

  val health: ServerEndpoint[Any, Task] = healthEndpoint.serverLogicSuccess { _ =>
    ZIO.succeed(com.risquanter.register.http.ProbeResponses.healthy)
  }

  override val routes: List[ServerEndpoint[Any, Task]] = List(health)

object SystemController:
  val makeZIO: UIO[SystemController] = ZIO.succeed(SystemController())
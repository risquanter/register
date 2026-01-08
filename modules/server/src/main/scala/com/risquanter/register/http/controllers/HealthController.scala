package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.*
import sttp.tapir.ztapir.{ZServerEndpoint, RichZEndpoint}
import sttp.tapir.server.ServerEndpoint
import com.risquanter.register.BuildInfo

/** Health check controller for monitoring and load balancer probes
  */
class HealthController extends BaseController {

  private val healthEndpoint: ZServerEndpoint[Any, Any] = endpoint
    .get
    .in("api" / "health")
    .out(stringBody)
    .description("Health check endpoint")
    .zServerLogic { _ =>
      ZIO.succeed(s"OK - Risk Register ${BuildInfo.version}")
    }

  override val routes: List[ServerEndpoint[Any, Task]] = List(healthEndpoint)
}

object HealthController {
  val live: ULayer[HealthController] = ZLayer.succeed(new HealthController)
}

package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*

import com.risquanter.register.http.responses.ProbeResponse

/** System-level endpoints.
  *
  * Endpoints here are intentionally outside the workspace namespace.
  */
trait SystemEndpoints extends BaseEndpoint:

  val healthEndpoint =
    baseEndpoint
      .tag("system")
      .name("health")
      .description("Health check endpoint")
      .in("health")
      .get
      .out(jsonBody[ProbeResponse])

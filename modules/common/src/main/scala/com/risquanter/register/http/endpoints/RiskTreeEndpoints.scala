package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Non-workspace-scoped endpoints.
  *
  * After workspace capability introduction (Tier 1.5), only `health`
  * remains in active use. All tree-specific CRUD, LEC, and cache
  * operations are served exclusively via capability-specific
  * workspace-scoped endpoint/controller pairs.
  *
  * The frontend uses capability-specific workspace endpoint traits directly.
  */
trait RiskTreeEndpoints extends BaseEndpoint:

  val healthEndpoint =
    baseEndpoint
      .tag("system")
      .name("health")
      .description("Health check endpoint")
      .in("health")
      .get
      .out(jsonBody[Map[String, String]])

  val getAllEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getAll")
      .description("Get all risk trees")
      .in("risk-trees")
      .get
      .out(jsonBody[List[SimulationResponse]])

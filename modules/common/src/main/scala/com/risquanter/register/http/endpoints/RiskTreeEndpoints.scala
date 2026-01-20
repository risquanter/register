package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.domain.data.iron.{PositiveInt, NonNegativeInt, NonNegativeLong, SafeId, IronConstants}
import IronConstants.Zero
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

/** Tapir endpoint definitions for RiskTree API
  * Extends BaseEndpoint for standardized error handling
  * 
  * Iron types (PositiveInt, NonNegativeInt) are validated at the HTTP layer
  * via Tapir codecs—invalid input returns 400 before reaching controllers.
  */
trait RiskTreeEndpoints extends BaseEndpoint {
  
  val healthEndpoint =
    baseEndpoint
      .tag("system")
      .name("health")
      .description("Health check endpoint")
      .in("health")
      .get
      .out(jsonBody[Map[String, String]])
  
  val createEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("create")
      .description("Create a new risk tree")
      .in("risk-trees")
      .post
      .in(jsonBody[RiskTreeDefinitionRequest])
      .out(jsonBody[SimulationResponse])

  val getAllEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getAll")
      .description("Get all risk trees")
      .in("risk-trees")
      .get
      .out(jsonBody[List[SimulationResponse]])

  val getByIdEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getById")
      .description("Get a risk tree by ID")
      .in("risk-trees" / path[NonNegativeLong]("id"))
      .get
      .out(jsonBody[Option[SimulationResponse]])

  /** Manual cache invalidation endpoint for testing SSE pipeline.
    * Triggers cache invalidation for a specific node and broadcasts SSE event.
    * 
    * @return Number of invalidated cache entries (node + ancestors)
    */
  val invalidateCacheEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("invalidateCache")
      .description("Manually invalidate cache for a node (triggers SSE notification)")
      .in("risk-trees" / path[NonNegativeLong]("id") / "invalidate" / path[SafeId.SafeId]("nodeId"))
      .post
      .out(jsonBody[Map[String, Int]])
}

package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.http.requests.CreateSimulationRequest
import com.risquanter.register.http.responses.SimulationResponse

/** Tapir endpoint definitions for RiskTree API
  * Extends BaseEndpoint for standardized error handling
  */
trait RiskTreeEndpoints extends BaseEndpoint {
  
  val createEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("create")
      .description("Create a new risk tree")
      .in("risk-trees")
      .post
      .in(jsonBody[CreateSimulationRequest])
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
      .in("risk-trees" / path[String]("id"))
      .get
      .out(jsonBody[Option[SimulationResponse]])

  val computeLECEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("computeLEC")
      .description("Compute LEC for an existing risk tree")
      .in("risk-trees" / path[String]("id") / "lec")
      .in(query[Option[Int]]("nTrials").description("Override number of trials"))
      .in(query[Int]("parallelism").default(1).description("Parallelism level"))
      .in(query[Int]("depth").default(0).description("Depth of hierarchy to include (0=root only, max 5)"))
      .get
      .out(jsonBody[SimulationResponse])
}

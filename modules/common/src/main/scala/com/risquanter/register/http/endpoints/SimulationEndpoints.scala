package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.http.requests.CreateSimulationRequest
import com.risquanter.register.http.responses.SimulationResponse

/** Tapir endpoint definitions for Simulation API
  * Extends BaseEndpoint for standardized error handling
  */
trait SimulationEndpoints extends BaseEndpoint {
  
  val createEndpoint =
    baseEndpoint
      .tag("simulations")
      .name("create")
      .description("Create a new simulation")
      .in("simulations")
      .post
      .in(jsonBody[CreateSimulationRequest])
      .out(jsonBody[SimulationResponse])

  val getAllEndpoint =
    baseEndpoint
      .tag("simulations")
      .name("getAll")
      .description("Get all simulations")
      .in("simulations")
      .get
      .out(jsonBody[List[SimulationResponse]])

  val getByIdEndpoint =
    baseEndpoint
      .tag("simulations")
      .name("getById")
      .description("Get a simulation by ID")
      .in("simulations" / path[String]("id"))
      .get
      .out(jsonBody[Option[SimulationResponse]])
}

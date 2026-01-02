package com.risquanter.register.http.endpoints

import zio.test.*
import sttp.tapir.*
import sttp.model.Method

object SimulationEndpointsSpec extends ZIOSpecDefault with SimulationEndpoints {

  def spec = suite("SimulationEndpoints")(
    test("createEndpoint has POST method") {
      val method = createEndpoint.method
      assertTrue(method.contains(Method.POST))
    },
    
    test("createEndpoint has name 'create'") {
      val name = createEndpoint.info.name
      assertTrue(name.contains("create"))
    },
    
    test("getAllEndpoint has GET method") {
      val method = getAllEndpoint.method
      assertTrue(method.contains(Method.GET))
    },
    
    test("getAllEndpoint has name 'getAll'") {
      val name = getAllEndpoint.info.name
      assertTrue(name.contains("getAll"))
    },
    
    test("getByIdEndpoint has GET method") {
      val method = getByIdEndpoint.method
      assertTrue(method.contains(Method.GET))
    },
    
    test("getByIdEndpoint has name 'getById'") {
      val name = getByIdEndpoint.info.name
      assertTrue(name.contains("getById"))
    },
    
    test("all endpoints use simulations tag") {
      assertTrue(
        createEndpoint.info.tags.contains("simulations"),
        getAllEndpoint.info.tags.contains("simulations"),
        getByIdEndpoint.info.tags.contains("simulations")
      )
    }
  )
}

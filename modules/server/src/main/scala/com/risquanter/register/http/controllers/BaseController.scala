package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

/** Base trait for all HTTP controllers
  * Controllers implement endpoint logic and return routes
  */
trait BaseController {
  val routes: List[ServerEndpoint[Any, Task]]
}

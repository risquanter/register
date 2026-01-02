package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.domain.errors.{JsonHttpError, ErrorResponse}

/** Base endpoint with standardized error handling
  * Maps all Throwable errors to HTTP responses with proper status codes
  */
trait BaseEndpoint {
  val baseEndpoint = endpoint
    // Error output: (StatusCode, ErrorResponse as JSON)
    .errorOut(statusCode and jsonBody[ErrorResponse])
    // Map between HTTP errors and domain Throwables
    .mapErrorOut[Throwable](ErrorResponse.decode)(ErrorResponse.encode)
}

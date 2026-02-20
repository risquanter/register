package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.domain.errors.{JsonHttpError, ErrorResponse}
import com.risquanter.register.domain.data.iron.UserId
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Base endpoint with standardized error handling
  * Maps all Throwable errors to HTTP responses with proper status codes
  */
trait BaseEndpoint {
  val baseEndpoint = endpoint
    // Error output: (StatusCode, ErrorResponse as JSON)
    .errorOut(statusCode and jsonBody[ErrorResponse])
    // Map between HTTP errors and domain Throwables
    .mapErrorOut[Throwable](ErrorResponse.decode)(ErrorResponse.encode)

  /** Authenticated base — extends baseEndpoint with the x-user-id claim header.
    *
    * Tapir decodes the header to Option[UserId] (via IronTapirCodecs):
    *   - None   = header absent (acceptable in capability-only mode; UserContextExtractor.noOp returns anonymous)
    *   - Some(u) = header present and UUID-valid (format validated at decode boundary)
    *
    * All workspace-scoped endpoints under /w/{key}/... use this base.
    * Controllers extract the effective UserId via UserContextExtractor:
    *   - Wave 1: noOp → always returns anonymous sentinel, NoOp.check() always passes
    *   - Wave 2+: requirePresent → fails closed if header is absent
    *
    * @see ADR-012: Claim Header Injection
    */
  val authedBaseEndpoint = baseEndpoint.in(header[Option[UserId]]("x-user-id"))
}

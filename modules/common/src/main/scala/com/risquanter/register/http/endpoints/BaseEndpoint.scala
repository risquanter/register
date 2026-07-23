package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.domain.errors.{JsonHttpError, ErrorResponse}
import com.risquanter.register.domain.data.iron.{BranchChoice, ScenarioName, UserId}
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
  val authedBaseEndpoint = baseEndpoint.in(header[Option[UserId.Authenticated]]("x-user-id"))

  /** Shared `X-Active-Branch` input. The wire keeps DD-8's encoding (header
    * absent = main); it is decoded here, exactly once, into the single
    * internal `BranchChoice` spelling — nothing past the Tapir boundary
    * carries the wire's bare `Option` (TODO item 22).
    */
  val activeBranchHeader: EndpointInput[BranchChoice] =
    header[Option[ScenarioName.ScenarioName]]("X-Active-Branch")
      .description("Target branch for this request — absent = main (DD-8).")
      .map(BranchChoice.fromWire)(_.toWire)

  /** `activeBranchHeader` with a site-specific wire description. */
  def activeBranchHeaderDescribed(desc: String): EndpointInput[BranchChoice] =
    header[Option[ScenarioName.ScenarioName]]("X-Active-Branch")
      .description(desc)
      .map(BranchChoice.fromWire)(_.toWire)

  /** Second branch of a pairwise operation (e.g. the scenario diff) as a
    * query parameter — a GET has no body to carry it, and reusing the header
    * for both sides would be ambiguous. Same wire encoding: absent = main.
    */
  val compareBranchQuery: EndpointInput[BranchChoice] =
    query[Option[ScenarioName.ScenarioName]]("compareBranch")
      .description("Second branch of the comparison — absent = main.")
      .map(BranchChoice.fromWire)(_.toWire)
}

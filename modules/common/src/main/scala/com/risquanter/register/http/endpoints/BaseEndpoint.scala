package com.risquanter.register.http.endpoints

import sttp.model.{Header, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.domain.data.iron.{BranchChoice, UserId}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

object BaseEndpoint {

  /** The `500` output: the status and the body `WireError.InternalFault` is sent
    * with.
    *
    * Declared here rather than inline in the `oneOf` below because two separate
    * things send it. One is that variant. The other is the catch-all that
    * answers a failure which never reached an endpoint, and so has no declared
    * output of its own to use; it reads this value, which is what stops a
    * second description of the same body existing.
    */
  val internalFaultOut: EndpointOutput[WireError.InternalFault] =
    statusCode(StatusCode.InternalServerError).and(jsonBody[WireError.InternalFault])

  /** The `400` output: the status and the body a rejected input is reported
    * with, for the endpoint's own error variant below.
    *
    * The decode-failure handler does not use this one. A rejection it answers
    * carries whichever status the rejected input decides, so it builds an
    * output whose status is a value; this is the fixed-status form, and `400`
    * is the only status the endpoint itself declares for the body.
    */
  val validationRejectedOut: EndpointOutput[WireError.ValidationRejected] =
    statusCode(StatusCode.BadRequest).and(jsonBody[WireError.ValidationRejected])

  /** The same body with the status as a value, for the decode-failure handler.
    *
    * A rejection it answers carries whichever status the rejected input decides
    * — `400` for a refused value, `415` for a `Content-Type` that does not
    * match, `413` for a body over its declared maximum — so the status cannot
    * be fixed in the output. The headers travel with it because a status can
    * require one.
    *
    * Declared beside the fixed-status form rather than in the handler so that
    * one body is described once, which is the reason the two above are here.
    */
  val rejectionOut: EndpointOutput[(StatusCode, List[Header], WireError.ValidationRejected)] =
    statusCode.and(headers).and(jsonBody[WireError.ValidationRejected])
}

/** Base endpoint. Every endpoint trait builds from one of the two values in this
  * trait, so the error output is declared once for the whole API.
  */
trait BaseEndpoint {

  /** The error output: one variant per `WireError` case, each at its own status.
    *
    * There is no `mapErrorOut`, because there is nothing to map — `WireError` is
    * already the type both sides want, exactly as a response type needs no
    * mapping on the success side (ADR-035).
    *
    * The status is declared beside the case it belongs to, so a case cannot be
    * sent with the wrong status, and the generated OpenAPI document gains one
    * schema per status instead of a single envelope for all of them. A browser
    * selects the variant by status alone, so no two variants share one. Two
    * variants read their status and body from a named output in the companion,
    * because something other than an endpoint also sends those two.
    *
    * Every variant names a concrete case rather than the enum, which is what
    * keeps a body free of a discriminator field. There is deliberately no
    * default variant: one would have to be typed as the enum itself, which
    * reintroduces the envelope, and a response this service did not write — a
    * gateway's `502`, say — carries no `WireError` body for any variant to
    * decode. The browser reports such a response as an answer it could not
    * read, naming the status, rather than as a request that never arrived.
    */
  val baseEndpoint = endpoint
    .errorOut(
      oneOf[WireError](
        oneOfVariant(BaseEndpoint.validationRejectedOut),
        oneOfVariant(StatusCode.UnprocessableEntity, jsonBody[WireError.QueryRejected]),
        oneOfVariant(StatusCode.Forbidden,           jsonBody[WireError.AccessDenied]),
        oneOfVariant(StatusCode.NotFound,            jsonBody[WireError.NotFound]),
        oneOfVariant(StatusCode.Gone,                jsonBody[WireError.WorkspaceExpired]),
        oneOfVariant(StatusCode.Conflict,            jsonBody[WireError.Conflict]),
        oneOfVariant(StatusCode.NotImplemented,      jsonBody[WireError.NotSupported]),
        oneOfVariant(StatusCode.ServiceUnavailable,  jsonBody[WireError.DependencyUnavailable]),
        oneOfVariant(BaseEndpoint.internalFaultOut)
      )
    )

  /** Authenticated base — extends baseEndpoint with the x-user-id claim header.
    *
    * Tapir decodes the header to Option[UserId] (via IronTapirCodecs):
    *   - None   = header absent (acceptable in capability-only mode; UserContextExtractor.noOp returns anonymous)
    *   - Some(u) = header present and UUID-valid (format validated at decode boundary)
    *
    * All workspace-scoped endpoints under /w/{key}/... use this base.
    * Controllers extract the effective UserId via UserContextExtractor:
    *   - `noOp` returns the anonymous sentinel, and `NoOp.check()` always passes
    *   - `requirePresent` fails closed if the header is absent
    *
    * @see ADR-012: Claim Header Injection
    */
  val authedBaseEndpoint = baseEndpoint.in(header[Option[UserId.Authenticated]]("x-user-id"))

  /** Shared `X-Branch` input. The target branch is an explicit, required
    * wire value — `"main"` or a scenario name — decoded once at the Tapir
    * boundary into the single internal `BranchChoice` spelling. Absence fails
    * decoding → 400: there is no absent-means-main state anywhere.
    */
  val branchHeader: EndpointInput[BranchChoice] =
    header[BranchChoice]("X-Branch")
      .description("Target branch: \"main\" or a scenario name. Required.")
}

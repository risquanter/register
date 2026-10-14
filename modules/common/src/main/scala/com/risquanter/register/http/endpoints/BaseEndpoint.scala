package com.risquanter.register.http.endpoints

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.http.errors.WireError
import com.risquanter.register.domain.data.iron.{BranchChoice, UserId}
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Base endpoint. Every endpoint trait builds from one of the two values here,
  * so the error output is declared once for the whole API.
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
    * selects the variant by status alone, so no two variants share one.
    *
    * Every variant names a concrete case rather than the enum, which is what
    * keeps a body free of a discriminator field. There is deliberately no
    * default variant: one would have to be typed as the enum itself, which
    * reintroduces the envelope, and a response this service did not write — a
    * gateway's `502`, say — carries no `WireError` body for any variant to
    * decode. The browser classifies that as a transport failure instead.
    */
  val baseEndpoint = endpoint
    .errorOut(
      oneOf[WireError](
        oneOfVariant(StatusCode.BadRequest,          jsonBody[WireError.ValidationRejected]),
        oneOfVariant(StatusCode.UnprocessableEntity, jsonBody[WireError.QueryRejected]),
        oneOfVariant(StatusCode.Forbidden,           jsonBody[WireError.AccessDenied]),
        oneOfVariant(StatusCode.NotFound,            jsonBody[WireError.NotFound]),
        oneOfVariant(StatusCode.Gone,                jsonBody[WireError.WorkspaceExpired]),
        oneOfVariant(StatusCode.Conflict,            jsonBody[WireError.Conflict]),
        oneOfVariant(StatusCode.NotImplemented,      jsonBody[WireError.NotSupported]),
        oneOfVariant(StatusCode.ServiceUnavailable,  jsonBody[WireError.DependencyUnavailable]),
        oneOfVariant(StatusCode.InternalServerError,  jsonBody[WireError.InternalFault])
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

  /** Shared `X-Branch` input (E7). The target branch is an explicit, required
    * wire value — `"main"` or a scenario name — decoded once at the Tapir
    * boundary into the single internal `BranchChoice` spelling. Absence fails
    * decoding → 400: there is no absent-means-main state anywhere.
    */
  val branchHeader: EndpointInput[BranchChoice] =
    header[BranchChoice]("X-Branch")
      .description("Target branch: \"main\" or a scenario name. Required.")
}

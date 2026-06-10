package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*

import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.http.requests.{DistributionShapeRequest, DistributionPreviewResponse}

/** Distribution preview endpoint.
  *
  * Path: POST /distribution/preview -- outside the workspace-scoped /w/{key}/ surface.
  *
  * Auth behaviour by layer:
  *   - L0 (capability-only): no JWT in flight; Option[UserId] decodes to None;
  *     UserContextExtractor.noOp accepts it -- endpoint is open to anyone.
  *   - L1+ (identity): mesh requires a valid JWT before the request reaches the app;
  *     userCtx.extract in the controller enforces presence -- unauthenticated callers
  *     are rejected. No mesh exemption rule is needed; the default policy applies.
  *
  * The workspace key is intentionally absent: this is a stateless pure computation
  * with no data access. Rate limiting is handled at the nginx/ingress level.
  */
trait DistributionPreviewEndpoints extends BaseEndpoint:

  val distributionPreviewEndpoint =
    authedBaseEndpoint
      .tag("distribution")
      .name("distributionPreview")
      .description("Compute a sampled distribution preview curve from parameters. No workspace key required. L0: open. L1+: JWT required.")
      .in("distribution" / "preview")
      .post
      .in(jsonBody[DistributionShapeRequest])
      .out(jsonBody[DistributionPreviewResponse])

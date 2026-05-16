package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*

import com.risquanter.register.domain.data.iron.WorkspaceKeySecret
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.http.requests.{DistributionPreviewRequest, DistributionPreviewResponse}

/** Distribution preview endpoint.
  *
  * Workspace-scoped: the workspace key is the auth gate. No tree ID — the endpoint
  * is a pure function of its distribution-parameter inputs; the workspace key
  * solely confirms a live workspace session.
  */
trait DistributionPreviewEndpoints extends BaseEndpoint:

  val distributionPreviewEndpoint =
    authedBaseEndpoint
      .tag("distribution")
      .name("distributionPreview")
      .description("Compute a sampled distribution preview curve from parameters (no tree required)")
      .in("w" / path[WorkspaceKeySecret]("key") / "distribution" / "preview")
      .post
      .in(jsonBody[DistributionPreviewRequest])
      .out(jsonBody[DistributionPreviewResponse])

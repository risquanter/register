package com.risquanter.register.http.controllers

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.UserContextExtractor
import com.risquanter.register.domain.errors.ErrorSubject
import com.risquanter.register.domain.errors.ValidationExtensions.*
import com.risquanter.register.http.endpoints.DistributionPreviewEndpoints
import com.risquanter.register.services.DistributionPreviewService
import com.risquanter.register.http.errors.TreeScope

/** Distribution preview controller.
  *
  * Handles distribution preview requests at POST /distribution/preview.
  * This endpoint performs a stateless computation (distribution fitting + sampling)
  * on the supplied parameters; it does not operate on a specific tree or workspace.
  *
  * Authorization by layer:
  *   - L0 (capability-only): [[UserContextExtractor.noOp]] always returns the anonymous
  *     sentinel -- endpoint is fully open.
  *   - L1+ (identity): [[UserContextExtractor.requirePresent]] enforces JWT presence;
  *     requests without a valid session are rejected before the service is called.
  *   - L2 (fine-grained): no resource-level authzService.check -- a stateless compute
  *     endpoint has no resource to authorize against. Not a deviation from ADR-024;
  *     PEP applies at the resource boundary, and this endpoint has none.
  *
  * Rate limiting: a per-address limit belongs at the ingress gateway, not here.
  * Only the component holding the connection knows the caller; the application
  * implements no per-address limit on any endpoint (ADR-021 §4).
  *
  * @see ADR-024 -- Application as Pure PEP
  */
class DistributionPreviewController private (
  previewService: DistributionPreviewService,
  userCtx:        UserContextExtractor,
  tracing:        Tracing
) extends BaseController
    with DistributionPreviewEndpoints:

  // `wireEither` reads the active span to name the trace a failure ran under.
  private given Tracing = tracing

  val distributionPreview: ServerEndpoint[Any, Task] =
    distributionPreviewEndpoint.serverLogic {
      case (maybeUserId, req) =>
        (for
          _      <- userCtx.extract(maybeUserId)
          result <- req.toShape(ErrorSubject.Request).toZIOValidation
                      .flatMap(previewService.preview)
        yield result).wireEither(TreeScope.NoTree)
    }

  override val routes: List[ServerEndpoint[Any, Task]] = List(distributionPreview)

object DistributionPreviewController:
  val makeZIO: ZIO[DistributionPreviewService & UserContextExtractor & Tracing, Nothing, DistributionPreviewController] =
    for
      previewService <- ZIO.service[DistributionPreviewService]
      userCtx        <- ZIO.service[UserContextExtractor]
      tracing        <- ZIO.service[Tracing]
    yield DistributionPreviewController(previewService, userCtx, tracing)

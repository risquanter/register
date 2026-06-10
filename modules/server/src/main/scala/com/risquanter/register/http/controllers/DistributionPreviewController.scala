package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.UserContextExtractor
import com.risquanter.register.domain.errors.ValidationExtensions.*
import com.risquanter.register.http.endpoints.DistributionPreviewEndpoints
import com.risquanter.register.http.requests.DistributionShapeRequest
import com.risquanter.register.services.DistributionPreviewService

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
  * Rate limiting: deferred to nginx/ingress level. Future hook: inject RateLimiter
  * following the WorkspaceLifecycleController.bootstrapWorkspace pattern if
  * per-IP limiting on this endpoint becomes necessary.
  *
  * @see AUTHORIZATION-PLAN.md -- Layered Model
  * @see ADR-024 -- Application as Pure PEP
  */
class DistributionPreviewController private (
  previewService: DistributionPreviewService,
  userCtx:        UserContextExtractor
) extends BaseController
    with DistributionPreviewEndpoints:

  val distributionPreview: ServerEndpoint[Any, Task] =
    distributionPreviewEndpoint.serverLogic {
      case (maybeUserId, req) =>
        (for
          _      <- userCtx.extract(maybeUserId)
          result <- DistributionShapeRequest.validate(req).toZIOValidation
                      .flatMap(previewService.preview)
        yield result).either
    }

  override val routes: List[ServerEndpoint[Any, Task]] = List(distributionPreview)

object DistributionPreviewController:
  val makeZIO: ZIO[DistributionPreviewService & UserContextExtractor, Nothing, DistributionPreviewController] =
    for
      previewService <- ZIO.service[DistributionPreviewService]
      userCtx        <- ZIO.service[UserContextExtractor]
    yield DistributionPreviewController(previewService, userCtx)

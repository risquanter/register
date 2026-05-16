package com.risquanter.register.http.controllers

import zio.*
import sttp.tapir.server.ServerEndpoint

import com.risquanter.register.auth.{ AuthorizationService, Permission, UserContextExtractor }
import com.risquanter.register.auth.ResourceRef.asResource
import com.risquanter.register.domain.errors.ValidationExtensions.*
import com.risquanter.register.http.endpoints.DistributionPreviewEndpoints
import com.risquanter.register.http.requests.DistributionPreviewRequest
import com.risquanter.register.services.DistributionPreviewService
import com.risquanter.register.services.workspace.WorkspaceStore

/** Distribution preview controller.
  *
  * Handles workspace-scoped distribution preview requests. This endpoint performs
  * a stateless computation (distribution fitting + sampling) on the supplied
  * parameters; it does not operate on a specific tree resource.
  *
  * Authorization layers (all three now wired; SpiceDB backend is NoOp until Phase K):
  *  - Layer 0 (capability-only): [[WorkspaceStore.resolve]] validates the workspace key.
  *  - Layer 1 (identity): [[UserContextExtractor.extract]] enforces JWT presence when
  *    `requirePresent` is injected via `register.auth.mode=identity`. The extracted
  *    [[UserId]] is passed to `authzService.check`.
  *  - Layer 2 (fine-grained): [[AuthorizationService.check]] enforces
  *    `Permission.AnalyzeRun` on the resolved workspace resource. Currently wired with
  *    [[AuthorizationServiceNoOp]] (always-permit stub); live enforcement activates in
  *    Phase K when the SpiceDB backend replaces the NoOp.
  *
  * @see AUTHORIZATION-PLAN.md — Layered Model
  * @see ADR-024 — Application as Pure PEP
  */
class DistributionPreviewController private (
  previewService: DistributionPreviewService,
  workspaceStore: WorkspaceStore,
  userCtx:        UserContextExtractor,
  authzService:   AuthorizationService
) extends BaseController
    with DistributionPreviewEndpoints:

  val distributionPreview: ServerEndpoint[Any, Task] =
    distributionPreviewEndpoint.serverLogic {
      case (maybeUserId, key, req) =>
        (for
          userId <- userCtx.extract(maybeUserId)
          ws     <- workspaceStore.resolve(key)
          _      <- authzService.check(userId, Permission.AnalyzeRun, ws.id.asResource)
          result <- DistributionPreviewRequest.validate(req).toZIOValidation
                      .flatMap(previewService.preview)
        yield result).either
    }

  override val routes: List[ServerEndpoint[Any, Task]] = List(distributionPreview)

object DistributionPreviewController:
  val makeZIO: ZIO[DistributionPreviewService & WorkspaceStore & UserContextExtractor & AuthorizationService, Nothing, DistributionPreviewController] =
    for
      previewService <- ZIO.service[DistributionPreviewService]
      workspaceStore <- ZIO.service[WorkspaceStore]
      userCtx        <- ZIO.service[UserContextExtractor]
      authzService   <- ZIO.service[AuthorizationService]
    yield DistributionPreviewController(previewService, workspaceStore, userCtx, authzService)

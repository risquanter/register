package com.risquanter.register.http.controllers

import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError
import sttp.tapir.server.stub.*
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.auth.UserContextExtractor
import com.risquanter.register.http.requests.DistributionShapeRequest
import com.risquanter.register.services.DistributionPreviewService

/** Unit tests for [[DistributionPreviewController]] L1 enforcement.
  *
  * Exercises the three observable states of the [[UserContextExtractor]] gate at
  * POST /distribution/preview.  Each test wires the controller directly (no full
  * [[HttpApi]] stack, no Irmin, no database) and drives it through [[TapirStubInterpreter]].
  *
  * Decision context (2026-06-10): The preview endpoint moved to the public surface
  * (no workspace key).  [[UserContextExtractor.requirePresent]] is the L1 gate;
  * this spec verifies it is correctly threaded by the controller and that:
  *   - [[UserContextExtractor.noOp]]          → absent header is accepted (L0 open)
  *   - [[UserContextExtractor.requirePresent]] → absent header returns 403 (L1 gate)
  *   - [[UserContextExtractor.requirePresent]] → present, valid header returns 200
  *
  * @see DistributionPreviewController — wiring and authorization commentary
  * @see PLAN-PREVIEW-PUBLIC-ENDPOINT.md — Test coverage requirements table
  */
object DistributionPreviewControllerSpec extends ZIOSpecDefault:

  private given MonadError[Task] = new RIOMonadError[Any]

  // ── Request fixture ──────────────────────────────────────────────────────────

  /** Minimal valid lognormal request — passes [[DistributionShapeRequest.validate]]. */
  private val lognormalRequest = DistributionShapeRequest(
    distributionType = "lognormal",
    percentiles      = None,
    quantiles        = None,
    terms            = None,
    minLoss          = Some(1_000L),
    maxLoss          = Some(50_000L)
  )

  /** A valid UUID v4 — accepted by the [[UserId]] opaque type codec. */
  private val validUserId = "550e8400-e29b-41d4-a716-446655440000"

  // ── Stub backend factory ─────────────────────────────────────────────────────

  /** Build a stub HTTP backend wired to a fresh controller with the given extractor. */
  private def backendWith(extractor: UserContextExtractor): ZIO[Any, Throwable, SttpBackend[Task, Any]] =
    for controller <- DistributionPreviewController.makeZIO.provide(
          DistributionPreviewService.layer,
          ZLayer.succeed(extractor)
        )
    yield TapirStubInterpreter(SttpBackendStub[Task, Any](summon[MonadError[Task]]))
      .whenServerEndpointsRunLogic(controller.routes)
      .backend()

  /** POST /distribution/preview with an optional x-user-id header value. */
  private def previewRequest(
    backend:  SttpBackend[Task, Any],
    userId:   Option[String] = None
  ): Task[Response[Either[String, String]]] =
    val base = basicRequest
      .post(uri"http://localhost/distribution/preview")
      .body(lognormalRequest.toJson)
      .contentType("application/json")
    val withHeader = userId.fold(base)(id => base.header("x-user-id", id))
    withHeader.send(backend)

  // ── Spec ─────────────────────────────────────────────────────────────────────

  def spec = suite("DistributionPreviewController L1 enforcement")(

    test("noOp extractor: absent x-user-id header is accepted (L0 open)") {
      for
        backend  <- backendWith(UserContextExtractor.noOp)
        response <- previewRequest(backend, userId = None)
      yield assertTrue(response.code.code == 200)
    },

    test("requirePresent extractor: valid x-user-id header is accepted (L1 satisfied)") {
      for
        backend  <- backendWith(UserContextExtractor.requirePresent)
        response <- previewRequest(backend, userId = Some(validUserId))
      yield assertTrue(response.code.code == 200)
    },

    test("requirePresent extractor: absent x-user-id header is rejected with 403 (L1 gate)") {
      for
        backend  <- backendWith(UserContextExtractor.requirePresent)
        response <- previewRequest(backend, userId = None)
      yield assertTrue(response.code.code == 403)
    }
  )

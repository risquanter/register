package com.risquanter.register.auth

import zio.{IO, ZIO}
import com.risquanter.register.domain.data.iron.UserId
import com.risquanter.register.domain.errors.{AppError, AuthServiceUnavailable}

/** Extracts UserId from the mesh-injected x-user-id claim header.
  *
  * The input is Option[UserId] — Tapir has already validated the UUID format at the
  * codec boundary: None = header absent, Some(userId) = present and UUID-valid.
  *
  * Implementations:
  *   - noOp:           capability-only mode — header value ignored, returns anonymous sentinel
  *   - requirePresent: identity/fine-grained mode — header required; fails closed if absent
  *
  * @see ADR-012: Minimal Service Code for Auth
  * @see AUTHORIZATION-PLAN.md — UserContextExtractor Design
  */
trait UserContextExtractor:
  def extract(maybeUserId: Option[UserId]): IO[AppError, UserId]

object UserContextExtractor:

  /** Sentinel UserId used in capability-only mode.
    * Never reaches SpiceDB — AuthorizationServiceNoOp short-circuits all checks.
    * Allows uniform check(userId, ...) call structure across all auth modes.
    */
  val anonymous: UserId =
    UserId.fromString("00000000-0000-0000-0000-000000000000")
      .getOrElse(throw RuntimeException("BUG: anonymous sentinel UserId failed UUID validation"))

  /** capability-only mode — x-user-id header value is ignored entirely.
    * All checks hit AuthorizationServiceNoOp which always succeeds.
    */
  val noOp: UserContextExtractor = _ => ZIO.succeed(anonymous)

  /** identity / fine-grained mode — header must be present.
    *
    * If absent: the request either did not pass through an authenticated waypoint
    * (mesh bypass) or the user is unauthenticated. Both cases fail closed.
    * No JWT parsing needed — the mesh has already done all signature/expiry validation.
    *
    * @see ADR-012: Claim Header Injection (required external header stripping config)
    */
  val requirePresent: UserContextExtractor = maybeUserId =>
    maybeUserId match
      case Some(userId) => ZIO.succeed(userId)
      case None =>
        ZIO.fail(AuthServiceUnavailable(
          "Missing x-user-id header — unauthenticated request or mesh bypass detected"
        ))

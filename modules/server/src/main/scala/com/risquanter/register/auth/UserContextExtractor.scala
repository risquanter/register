package com.risquanter.register.auth

import zio.{IO, UIO, ZIO}
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
  * @see ADR-012: Minimal Service Code for Auth — §7 Mesh Trust Assumptions
  * @see AUTHORIZATION-PLAN.md — UserContextExtractor Design
  */
trait UserContextExtractor:
  def extract(maybeUserId: Option[UserId]): IO[AppError, UserId]

object UserContextExtractor:

  /** The anonymous sentinel UUID.
    *
    * Used in capability-only mode (noOp) where no identity is asserted.
    * MUST NOT be granted any permission in SpiceDB — if it is, capability-only
    * mode silently carries real authorization power into fine-grained mode
    * (ADR-012 §7 — Trust Assumption T4).
    *
    * The value is all-zeros by convention:
    *   - Recognisable in logs ("this is a sentinel, not a real user")
    *   - Easily excluded by SpiceDB provisioning CI checks
    *   - Unlikely to collide with any Keycloak-issued UUID
    *
    * Current mitigation: point-in-time `zed permission check` in AuthzProvisioning CI.
    * That check only catches pollution present at migration time — not grants written
    * afterwards by ops tooling or manual `zed` commands.
    *
    * TODO [ADR-012 §7 T4 / Wave 3]: Replace point-in-time CI check with a continuous
    * invariant enforced at the SpiceDB schema layer using a CEL caveat or a
    * schema-level subject constraint that rejects `user:00000000-...` as a valid
    * subject. This MUST be in place before `fine-grained` mode is activated in
    * any non-development namespace.
    * @see docs/ADR-012.md §7 — Trust Assumption T4
    */
  val AnonymousSentinelUuid: String = "00000000-0000-0000-0000-000000000000"

  val anonymous: UserId =
    UserId.fromString(AnonymousSentinelUuid)
      .getOrElse(throw RuntimeException("BUG: anonymous sentinel UserId failed UUID validation"))

  /** Emit a structured log entry describing which auth mode is active.
    *
    * Called once at application startup (from Application.startServer).
    * Provides a clear signal in production logs that distinguishes a correctly
    * configured deployment from a silently misconfigured one (ADR-012 §7 Attack 3).
    *
    * Log line format (structured key=value for log aggregation):
    *   auth.mode=<mode> auth.extractor=<class> auth.sentinel.active=<bool>
    *
    * Operators should alert on:
    *   - auth.mode=capability-only in a production (non-free-tier) namespace
    *   - auth.extractor=noOp when auth.mode=identity or auth.mode=fine-grained
    */
  def logStartupMode(mode: String, extractor: UserContextExtractor): UIO[Unit] =
    val extractorName = extractor match
      case e if e eq noOp           => "noOp"
      case e if e eq requirePresent => "requirePresent"
      case _                        => extractor.getClass.getSimpleName

    val sentinelActive = extractor eq noOp
    val warning =
      if sentinelActive then
        " ⚠️  CAPABILITY-ONLY MODE: x-user-id header is IGNORED. " +
        "Anonymous sentinel is active. No identity verification. " +
        "Do NOT run in this mode in production with real user data."
      else ""
    ZIO.logInfo(
      s"auth.mode=$mode auth.extractor=$extractorName auth.sentinel.active=$sentinelActive$warning"
    )

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

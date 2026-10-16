package com.risquanter.register.auth

import zio.{IO, UIO, ZIO}
import io.github.iltotore.iron.autoRefine
import com.risquanter.register.configs.AuthMode
import com.risquanter.register.domain.data.iron.{UserId, UuidStr}
import com.risquanter.register.domain.errors.{AccessDenied, AppError}

/** Extracts a validated UserId from the mesh-injected x-user-id claim header.
  *
  * Tapir validates UUID format at the codec boundary before this is called.
  * None = header absent; Some(userId) = present and UUID-valid.
  *
  *   - noOp:           capability-only mode — ignores header, returns UserId.Anonymous
  *   - requirePresent: identity/fine-grained mode — header required; fails closed if absent
  *
  * @see ADR-012 §7 — Mesh Trust Assumptions
  */
trait UserContextExtractor:
  def extract(maybeUserId: Option[UserId.Authenticated]): IO[AppError, UserId]
  def requireAuthenticated(maybeUserId: Option[UserId.Authenticated]): IO[AppError, UserId.Authenticated]

object UserContextExtractor:

  /** All-zeros UUID reserved for SpiceDB CI checks (ADR-012 §7 T4).
    * Must never be granted any SpiceDB permission — enforced at the schema level
    * before fine-grained mode is activated in any non-development namespace.
    */
  val AnonymousSentinelUuid: UuidStr = "00000000-0000-0000-0000-000000000000"

  /** Appended to the startup line when the anonymous sentinel is live, which is
    * the condition an operator must notice. Leading space: it joins the
    * key-value line rather than starting one.
    */
  private val SentinelWarning: String =
    " ⚠️  CAPABILITY-ONLY MODE: x-user-id header is IGNORED. " +
      "Anonymous sentinel is active. No identity verification. " +
      "Do NOT run in this mode in production with real user data."

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
  def logStartupMode(mode: AuthMode, extractor: UserContextExtractor): UIO[Unit] =
    val modeName = mode match
      case AuthMode.CapabilityOnly => "capability-only"
      case AuthMode.Identity       => "identity"
      case AuthMode.FineGrained    => "fine-grained"
    // The three values are one classification of the extractor, so they are
    // produced together: naming it, whether the anonymous sentinel is live, and
    // the operator warning that only the sentinel case carries.
    val (extractorName, sentinelActive, warning) = extractor match
      case e if e eq noOp           => ("noOp", true, SentinelWarning)
      case e if e eq requirePresent => ("requirePresent", false, "")
      case _                        => (extractor.getClass.getSimpleName, false, "")

    ZIO.logInfo(
      s"auth.mode=$modeName auth.extractor=$extractorName auth.sentinel.active=$sentinelActive$warning"
    )

  // Used by noOp.requireAuthenticated — NoOp.check() ignores the user and always succeeds,
  // so any Authenticated value satisfies the type. Only reachable in capability-only mode.
  // AnonymousSentinelUuid is UuidStr — fromString is guaranteed to succeed.
  private val sentinelAuthenticated: UserId.Authenticated =
    UserId.fromString(AnonymousSentinelUuid).toOption.get

  /** capability-only mode — ignores the header; returns UserId.Anonymous.
    * UserId.Anonymous cannot be passed to authz.check() — compile-time enforced (ADR-012 §7 T4).
    */
  val noOp: UserContextExtractor = new UserContextExtractor:
    def extract(m: Option[UserId.Authenticated]): IO[AppError, UserId] =
      ZIO.succeed(UserId.Anonymous)
    def requireAuthenticated(m: Option[UserId.Authenticated]): IO[AppError, UserId.Authenticated] =
      ZIO.succeed(m.getOrElse(sentinelAuthenticated))

  /** identity / fine-grained mode — header must be present.
    *
    * If absent: the request either did not pass through an authenticated waypoint
    * (mesh bypass) or the user is unauthenticated. Both cases fail closed, as a
    * refusal rather than as a dependency outage — nothing this service depends
    * on has failed, so the caller is refused rather than told to retry.
    * No JWT parsing needed — the mesh has already done all signature/expiry validation.
    *
    * Which of the two it was stays in the log: the refusal's text is this
    * service's own and the projection drops it, so the caller is told only that
    * access was refused (ADR-035 §2).
    *
    * @see ADR-012: Claim Header Injection (required external header stripping config)
    */
  val requirePresent: UserContextExtractor = new UserContextExtractor:
    def extract(m: Option[UserId.Authenticated]): IO[AppError, UserId] =
      requireAuthenticated(m)
    def requireAuthenticated(m: Option[UserId.Authenticated]): IO[AppError, UserId.Authenticated] =
      m match
        case Some(userId) => ZIO.succeed(userId)
        case None =>
          ZIO.fail(AccessDenied(
            "Missing x-user-id header — unauthenticated request or mesh bypass detected"
          ))

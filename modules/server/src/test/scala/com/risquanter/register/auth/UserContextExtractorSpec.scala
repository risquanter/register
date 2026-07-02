package com.risquanter.register.auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.configs.AuthMode
import com.risquanter.register.domain.data.iron.UserId
import com.risquanter.register.domain.errors.AuthServiceUnavailable

object UserContextExtractorSpec extends ZIOSpecDefault:

  private val validUuid = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf"
  private val validUserId = UserId.fromString(validUuid).toOption.get

  def spec = suite("UserContextExtractor")(
    suite("UserId.Anonymous — capability-only identity")(
      test("Anonymous has redacted toString distinct from Authenticated") {
        assertTrue(UserId.Anonymous.toString == "UserId.Anonymous")
      },
      // ADR-012 §7 T4 — sentinel pollution guard.
      // AnonymousSentinelUuid is a valid UUID format kept for SpiceDB CI checks;
      // it must never be granted any SpiceDB permission.
      test("AnonymousSentinelUuid is a valid UUID parseable to Authenticated") {
        val parsed = UserId.fromString(UserContextExtractor.AnonymousSentinelUuid)
        assertTrue(parsed.isRight)
      },
      test("Authenticated from a real UUID is never equal to UserId.Anonymous") {
        assertTrue(validUserId != UserId.Anonymous)
      }
    ),
    suite("noOp — capability-only mode")(
      test("returns UserId.Anonymous when header is absent") {
        for
          result <- UserContextExtractor.noOp.extract(None)
        yield assertTrue(result == UserId.Anonymous)
      },
      test("ignores the provided userId and returns UserId.Anonymous") {
        for
          result <- UserContextExtractor.noOp.extract(Some(validUserId))
        yield assertTrue(result == UserId.Anonymous)
      },
      // ADR-012 §7 T4 — noOp must always produce Anonymous, never a real Authenticated.
      test("noOp always returns Anonymous regardless of header value") {
        for
          result1 <- UserContextExtractor.noOp.extract(None)
          result2 <- UserContextExtractor.noOp.extract(Some(validUserId))
        yield assertTrue(result1 == UserId.Anonymous, result2 == UserId.Anonymous)
      }
    ),
    suite("requirePresent — identity/fine-grained mode")(
      test("succeeds and returns userId when header is present") {
        for
          result <- UserContextExtractor.requirePresent.extract(Some(validUserId))
        yield assertTrue(result == validUserId)
      },
      test("fails with AuthServiceUnavailable mentioning mesh bypass when header is absent") {
        for
          result <- UserContextExtractor.requirePresent.extract(None).either
        yield result match
          case Left(err: AuthServiceUnavailable) =>
            assertTrue(
              err.getMessage.contains("Missing x-user-id header"),
              err.getMessage.contains("mesh bypass")
            )
          case other =>
            assertTrue(false)
      },
      // ADR-012 §7 T4 — requirePresent must NEVER return UserId.Anonymous.
      test("requirePresent never returns UserId.Anonymous for a real userId") {
        for
          result <- UserContextExtractor.requirePresent.extract(Some(validUserId))
        yield assertTrue(result != UserId.Anonymous)
      },
      // Edge case: if someone explicitly sends the sentinel UUID as their user id,
      // requirePresent returns it as Authenticated (valid UUID format).
      // The SpiceDB layer finds no permissions for it — T4 CI check enforces this.
      test("requirePresent passes through sentinel UUID if explicitly provided (SpiceDB has no grants for it)") {
        val sentinelAuthenticated = UserId.fromString(UserContextExtractor.AnonymousSentinelUuid).toOption.get
        for
          result <- UserContextExtractor.requirePresent.extract(Some(sentinelAuthenticated))
        yield assertTrue(result == sentinelAuthenticated)
      }
    ),
    suite("noOp.requireAuthenticated — capability-only mode")(
      test("returns sentinel Authenticated when header is absent") {
        for
          result <- UserContextExtractor.noOp.requireAuthenticated(None)
        yield assertTrue(result.value == UserContextExtractor.AnonymousSentinelUuid)
      },
      test("returns header userId when header is present") {
        for
          result <- UserContextExtractor.noOp.requireAuthenticated(Some(validUserId))
        yield assertTrue(result == validUserId)
      }
    ),
    suite("requirePresent.requireAuthenticated — identity/fine-grained mode")(
      test("returns userId when header is present") {
        for
          result <- UserContextExtractor.requirePresent.requireAuthenticated(Some(validUserId))
        yield assertTrue(result == validUserId)
      },
      test("fails with AuthServiceUnavailable when header is absent") {
        for
          result <- UserContextExtractor.requirePresent.requireAuthenticated(None).either
        yield result match
          case Left(err: AuthServiceUnavailable) =>
            assertTrue(
              err.getMessage.contains("Missing x-user-id header"),
              err.getMessage.contains("mesh bypass")
            )
          case other =>
            assertTrue(false)
      }
    ),
    suite("logStartupMode")(
      test("noOp mode log completes without error") {
        UserContextExtractor.logStartupMode(AuthMode.CapabilityOnly, UserContextExtractor.noOp)
          .as(assertTrue(true))
      },
      test("requirePresent mode log completes without error") {
        UserContextExtractor.logStartupMode(AuthMode.FineGrained, UserContextExtractor.requirePresent)
          .as(assertTrue(true))
      }
    )
  )

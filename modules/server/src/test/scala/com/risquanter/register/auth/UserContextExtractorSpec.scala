package com.risquanter.register.auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.iron.UserId
import com.risquanter.register.domain.errors.AuthServiceUnavailable

object UserContextExtractorSpec extends ZIOSpecDefault:

  private val validUuid = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf"
  private val validUserId = UserId.fromString(validUuid).toOption.get

  def spec = suite("UserContextExtractor")(
    suite("anonymous sentinel")(
      test("anonymous is a valid UserId with all-zero UUID") {
        assertTrue(
          UserContextExtractor.anonymous.value == "00000000-0000-0000-0000-000000000000"
        )
      },
      // ADR-012 §7 T4 — sentinel pollution guard.
      // If this test fails it means AnonymousSentinelUuid was changed, which must be
      // a deliberate decision with accompanying SpiceDB exclusion CI update.
      test("AnonymousSentinelUuid constant matches anonymous.value (sentinel stability)") {
        assertTrue(
          UserContextExtractor.AnonymousSentinelUuid == UserContextExtractor.anonymous.value
        )
      },
      // Ensures the sentinel UUID cannot be used as a real user identity at the Tapir
      // decode boundary — UserId.fromString must succeed (it's a valid UUID format),
      // but the value must equal the sentinel so callers can detect and reject it.
      test("anonymous sentinel is parseable and equals itself (round-trip)") {
        val reparsed = UserId.fromString(UserContextExtractor.AnonymousSentinelUuid)
        assertTrue(
          reparsed.isRight,
          reparsed.toOption.get == UserContextExtractor.anonymous
        )
      },
      test("a real user UUID is never equal to the anonymous sentinel") {
        assertTrue(validUserId != UserContextExtractor.anonymous)
      }
    ),
    suite("noOp — capability-only mode")(
      test("returns anonymous when header is absent") {
        for
          result <- UserContextExtractor.noOp.extract(None)
        yield assertTrue(result == UserContextExtractor.anonymous)
      },
      test("ignores the provided userId and returns anonymous") {
        for
          result <- UserContextExtractor.noOp.extract(Some(validUserId))
        yield assertTrue(result == UserContextExtractor.anonymous)
      },
      // ADR-012 §7 T4 — noOp must always produce the sentinel, never a real userId.
      // This is what prevents noOp mode from accidentally granting identity-scoped access.
      test("noOp always returns anonymous even when a valid userId is supplied") {
        for
          result <- UserContextExtractor.noOp.extract(Some(validUserId))
        yield assertTrue(
          result == UserContextExtractor.anonymous,
          result.value == UserContextExtractor.AnonymousSentinelUuid
        )
      }
    ),
    suite("requirePresent — identity/fine-grained mode")(
      test("succeeds and returns userId when header is present") {
        for
          result <- UserContextExtractor.requirePresent.extract(Some(validUserId))
        yield assertTrue(result == validUserId)
      },
      test("fails with AuthServiceUnavailable when header is absent") {
        for
          result <- UserContextExtractor.requirePresent.extract(None).either
        yield result match
          case Left(err: AuthServiceUnavailable) =>
            assertTrue(err.getMessage.contains("Missing x-user-id header"))
          case other =>
            assertTrue(false)
      },
      test("failure message mentions mesh bypass") {
        for
          result <- UserContextExtractor.requirePresent.extract(None).either
        yield result match
          case Left(err) => assertTrue(err.getMessage.contains("mesh bypass"))
          case Right(_)  => assertTrue(false)
      },
      // ADR-012 §7 T4 — requirePresent must NEVER return the anonymous sentinel.
      // If a real UUID is present in the header, it is returned verbatim (not replaced).
      test("requirePresent never returns the anonymous sentinel for a real userId") {
        for
          result <- UserContextExtractor.requirePresent.extract(Some(validUserId))
        yield assertTrue(result != UserContextExtractor.anonymous)
      },
      // Edge case: what if someone explicitly sends the sentinel UUID as their user id?
      // requirePresent returns it as-is (it's a valid UUID). The SpiceDB layer
      // will find no permissions for it (T4 CI check ensures this).
      // This test documents the contract: extractor doesn't filter by value, only by presence.
      test("requirePresent passes through sentinel UUID if explicitly provided (SpiceDB has no grants for it)") {
        for
          result <- UserContextExtractor.requirePresent.extract(Some(UserContextExtractor.anonymous))
        yield assertTrue(result == UserContextExtractor.anonymous)
      }
    ),
    suite("logStartupMode")(
      test("noOp mode log completes without error") {
        UserContextExtractor.logStartupMode("capability-only", UserContextExtractor.noOp)
          .as(assertTrue(true))
      },
      test("requirePresent mode log completes without error") {
        UserContextExtractor.logStartupMode("fine-grained", UserContextExtractor.requirePresent)
          .as(assertTrue(true))
      }
    )
  )

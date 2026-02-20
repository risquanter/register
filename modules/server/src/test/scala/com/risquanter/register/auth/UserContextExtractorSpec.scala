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
      }
    )
  )

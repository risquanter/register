package com.risquanter.register.domain.data.iron

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.iron.SafeId

object SafeIdSpec extends ZIOSpecDefault {

  def spec = suite("SafeId")(
    suite("Valid ULIDs")(
      test("accepts canonical uppercase ULID") {
        val result = SafeId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV")
        assertTrue(result.isRight && result.exists(_.value == "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
      },
      test("accepts lowercase input but normalizes to uppercase") {
        val result = SafeId.fromString("01arz3ndektsv4rrffq69g5fav")
        assertTrue(result.isRight && result.exists(_.value == "01ARZ3NDEKTSV4RRFFQ69G5FAV"))
      },
      test("accepts ULID with surrounding whitespace") {
        val result = SafeId.fromString(" 01ARZ3NDEKTSV4RRFFQ69G5FAV ")
        assertTrue(result.isRight)
      }
    ),
    suite("Invalid IDs")(
      test("rejects empty string") {
        val result = SafeId.fromString("")
        assertTrue(result.isLeft)
      },
      test("rejects non-ULID characters") {
        val result = SafeId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FA!")
        assertTrue(result.isLeft)
      },
      test("rejects wrong length (25 chars)") {
        val result = SafeId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FA")
        assertTrue(result.isLeft)
      },
      test("rejects wrong length (27 chars)") {
        val result = SafeId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAVX")
        assertTrue(result.isLeft)
      }
    ),
    suite("SafeId value extraction")(
      test("returns canonical uppercase ULID") {
        val result = SafeId.fromString("01arz3ndektsv4rrffq69g5fav")
        assertTrue(result.map(_.value).contains("01ARZ3NDEKTSV4RRFFQ69G5FAV"))
      }
    )
  )
}

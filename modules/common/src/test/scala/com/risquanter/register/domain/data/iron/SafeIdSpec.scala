package com.risquanter.register.domain.data.iron

import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.domain.data.iron.SafeId

object SafeIdSpec extends ZIOSpecDefault {

  def spec = suite("SafeId")(
    suite("Valid IDs")(
      test("accepts alphanumeric with hyphens") {
        val result = SafeId.fromString("cyber-attack")
        assertTrue(result.isRight)
      },
      test("accepts alphanumeric with underscores") {
        val result = SafeId.fromString("ops_risk_001")
        assertTrue(result.isRight)
      },
      test("accepts mixed case") {
        val result = SafeId.fromString("IT-Risk-2024")
        assertTrue(result.isRight)
      },
      test("accepts minimum length (3 chars)") {
        val result = SafeId.fromString("abc")
        assertTrue(result.isRight)
      },
      test("accepts maximum length (30 chars)") {
        val longId = "a" * 30
        val result = SafeId.fromString(longId)
        assertTrue(result.isRight)
      },
      test("accepts numbers only") {
        val result = SafeId.fromString("12345")
        assertTrue(result.isRight)
      },
      test("trims whitespace") {
        val result = SafeId.fromString("  cyber-attack  ")
        assertTrue(result.isRight)
      }
    ),
    suite("Invalid IDs")(
      test("rejects empty string") {
        val result = SafeId.fromString("")
        assertTrue(result.isLeft)
      },
      test("rejects blank string") {
        val result = SafeId.fromString("   ")
        assertTrue(result.isLeft)
      },
      test("rejects too short (< 3 chars)") {
        val result = SafeId.fromString("ab")
        assertTrue(result.isLeft)
      },
      test("rejects too long (> 30 chars)") {
        val longId = "a" * 31
        val result = SafeId.fromString(longId)
        assertTrue(result.isLeft)
      },
      test("rejects spaces") {
        val result = SafeId.fromString("cyber attack")
        assertTrue(result.isLeft)
      },
      test("rejects special characters (@)") {
        val result = SafeId.fromString("cyber@attack")
        assertTrue(result.isLeft)
      },
      test("rejects special characters (!)") {
        val result = SafeId.fromString("cyber-attack!")
        assertTrue(result.isLeft)
      },
      test("rejects dots") {
        val result = SafeId.fromString("cyber.attack")
        assertTrue(result.isLeft)
      },
      test("rejects forward slash") {
        val result = SafeId.fromString("cyber/attack")
        assertTrue(result.isLeft)
      }
    ),
    suite("SafeId value extraction")(
      test("can extract underlying value") {
        val result = SafeId.fromString("cyber-attack")
        assertTrue(result.map(_.value).isRight)
      },
      test("preserves original value") {
        val original = "IT-Risk-2024"
        val result = SafeId.fromString(original)
        assertTrue(result.map(_.value.toString) == Right(original))
      }
    )
  )
}

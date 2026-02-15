package com.risquanter.register.domain.data.iron

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.risquanter.register.domain.data.iron.WorkspaceKey

object WorkspaceKeySpec extends ZIOSpecDefault {

  // Base64url alphabet: A-Z, a-z, 0-9, -, _
  private val base64urlPattern = "^[A-Za-z0-9_-]{22}$".r

  def spec = suite("WorkspaceKey")(
    suite("generate")(
      test("produces a 22-character base64url string") {
        for {
          key <- WorkspaceKey.generate
        } yield assertTrue(
          key.value.length == 22,
          base64urlPattern.matches(key.value)
        )
      },
      test("produces distinct keys on successive calls") {
        for {
          k1 <- WorkspaceKey.generate
          k2 <- WorkspaceKey.generate
        } yield assertTrue(k1.value != k2.value)
      }
    ),
    suite("fromString — valid keys")(
      test("accepts a valid 22-char base64url string") {
        val result = WorkspaceKey.fromString("abcdefghijklmnopqrstuv")
        assertTrue(result.isRight && result.exists(_.value == "abcdefghijklmnopqrstuv"))
      },
      test("accepts uppercase letters") {
        val result = WorkspaceKey.fromString("ABCDEFGHIJKLMNOPQRSTUV")
        assertTrue(result.isRight)
      },
      test("accepts digits, hyphens, and underscores") {
        val result = WorkspaceKey.fromString("0123456789-_abcdefghij")
        assertTrue(result.isRight)
      }
    ),
    suite("fromString — invalid keys")(
      test("rejects empty string") {
        val result = WorkspaceKey.fromString("")
        assertTrue(result.isLeft)
      },
      test("rejects too-short string (21 chars)") {
        val result = WorkspaceKey.fromString("abcdefghijklmnopqrstu")
        assertTrue(result.isLeft)
      },
      test("rejects too-long string (23 chars)") {
        val result = WorkspaceKey.fromString("abcdefghijklmnopqrstuvw")
        assertTrue(result.isLeft)
      },
      test("rejects non-base64url characters (+ and /)") {
        val result = WorkspaceKey.fromString("abcdefghij+lmnopq/stuv")
        assertTrue(result.isLeft)
      },
      test("rejects base64 padding character (=)") {
        val result = WorkspaceKey.fromString("abcdefghijklmnopqrstu=")
        assertTrue(result.isLeft)
      },
      test("rejects whitespace") {
        val result = WorkspaceKey.fromString(" abcdefghijklmnopqrstu")
        assertTrue(result.isLeft)
      }
    ),
    suite("JSON round-trip")(
      test("encodes and decodes consistently") {
        for {
          key <- WorkspaceKey.generate
        } yield {
          val json    = key.toJson
          val decoded = json.fromJson[WorkspaceKey]
          assertTrue(
            decoded == Right(key),
            json == s"\"${key.value}\""
          )
        }
      },
      test("decodes valid JSON string") {
        val json = "\"abcdefghijklmnopqrstuv\""
        val result = json.fromJson[WorkspaceKey]
        assertTrue(result.isRight && result.exists(_.value == "abcdefghijklmnopqrstuv"))
      },
      test("rejects invalid JSON string") {
        val json = "\"too-short\""
        val result = json.fromJson[WorkspaceKey]
        assertTrue(result.isLeft)
      }
    ),
    suite("generate round-trip with fromString")(
      test("generated keys pass fromString validation") {
        for {
          key <- WorkspaceKey.generate
        } yield {
          val parsed = WorkspaceKey.fromString(key.value)
          assertTrue(parsed == Right(key))
        }
      }
    )
  )
}

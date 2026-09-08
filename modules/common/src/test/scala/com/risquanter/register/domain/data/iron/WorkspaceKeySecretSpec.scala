package com.risquanter.register.domain.data.iron

import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.risquanter.register.domain.data.iron.WorkspaceKeySecret

/** Tests for WorkspaceKeySecret (ADR-022).
  *
  * NOTE ON `.reveal` USAGE: WorkspaceKeySecret is a final class with no `unapply`,
  * no public field access, and a redacted `toString` — by design (R1–R4). In production
  * code, `.reveal` call sites are auditable security boundaries. In this test file,
  * `.reveal` is the ONLY way to inspect the underlying value for correctness assertions.
  * Every `.reveal` call below is a deliberate test-only exception, not a pattern to
  * replicate in application code. See ADR-022 Decision 1, Requirement R4.
  */
object WorkspaceKeySecretSpec extends ZIOSpecDefault {

  def spec = suite("WorkspaceKeySecret")(
    suite("R3: toString redaction")(
      test("fromString-constructed key also has redacted toString") {
        val key = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get
        assertTrue(
          key.toString == "WorkspaceKeySecret(***)",
          !key.toString.contains("abcdefghijklmnopqrstuv")
        )
      }
    ),
    suite("R6: equals and hashCode")(
      test("equal values produce equal keys") {
        val k1 = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get
        val k2 = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get
        assertTrue(
          k1 == k2,
          k1.hashCode == k2.hashCode
        )
      },
      test("distinct values produce unequal keys") {
        val k1 = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get
        val k2 = WorkspaceKeySecret.fromString("ABCDEFGHIJKLMNOPQRSTUV").toOption.get
        assertTrue(k1 != k2)
      },
      test("not equal to non-WorkspaceKeySecret values") {
        val key = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get
        assertTrue(
          !key.equals("abcdefghijklmnopqrstuv"),
          !key.equals(null),
          !key.equals(42)
        )
      }
    ),
    suite("fromString — valid keys")(
      test("accepts a valid 22-char base64url string") {
        val result = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv")
        // reveal: required to verify the stored value matches the input
        assertTrue(result.isRight && result.exists(_.reveal == "abcdefghijklmnopqrstuv"))
      },
      test("accepts uppercase letters") {
        val result = WorkspaceKeySecret.fromString("ABCDEFGHIJKLMNOPQRSTUV")
        assertTrue(result.isRight)
      },
      test("accepts digits, hyphens, and underscores") {
        val result = WorkspaceKeySecret.fromString("0123456789-_abcdefghij")
        assertTrue(result.isRight)
      }
    ),
    suite("fromString — invalid keys")(
      test("rejects empty string") {
        val result = WorkspaceKeySecret.fromString("")
        assertTrue(result.isLeft)
      },
      test("rejects too-short string (21 chars)") {
        val result = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstu")
        assertTrue(result.isLeft)
      },
      test("rejects too-long string (23 chars)") {
        val result = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuvw")
        assertTrue(result.isLeft)
      },
      test("rejects non-base64url characters (+ and /)") {
        val result = WorkspaceKeySecret.fromString("abcdefghij+lmnopq/stuv")
        assertTrue(result.isLeft)
      },
      test("rejects base64 padding character (=)") {
        val result = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstu=")
        assertTrue(result.isLeft)
      },
      test("rejects whitespace") {
        val result = WorkspaceKeySecret.fromString(" abcdefghijklmnopqrstu")
        assertTrue(result.isLeft)
      }
    ),
    suite("JSON round-trip")(
      test("decodes valid JSON string") {
        val json = "\"abcdefghijklmnopqrstuv\""
        val result = json.fromJson[WorkspaceKeySecret]
        // reveal: required to verify the decoded value matches the JSON input
        assertTrue(result.isRight && result.exists(_.reveal == "abcdefghijklmnopqrstuv"))
      },
      test("rejects invalid JSON string") {
        val json = "\"too-short\""
        val result = json.fromJson[WorkspaceKeySecret]
        assertTrue(result.isLeft)
      }
    )
  )
}

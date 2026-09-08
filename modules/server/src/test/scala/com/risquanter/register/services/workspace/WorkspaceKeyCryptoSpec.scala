package com.risquanter.register.services.workspace

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import com.risquanter.register.domain.data.iron.WorkspaceKeySecret

/** Tests for WorkspaceKeyCrypto — server-side generation and hashing of
  * workspace capability keys (ADR-021, ADR-022).
  *
  * NOTE ON `.reveal` USAGE: WorkspaceKeySecret is a final class with no `unapply`,
  * no public field access, and a redacted `toString` — by design (R1–R4). In production
  * code, `.reveal` call sites are auditable security boundaries. In this test file,
  * `.reveal` is the ONLY way to inspect the underlying value for correctness assertions.
  * Every `.reveal` call below is a deliberate test-only exception, not a pattern to
  * replicate in application code. See ADR-022 Decision 1, Requirement R4.
  */
object WorkspaceKeyCryptoSpec extends ZIOSpecDefault {

  // Base64url alphabet: A-Z, a-z, 0-9, -, _
  private val base64urlPattern = "^[A-Za-z0-9_-]{22}$".r

  def spec = suite("WorkspaceKeyCrypto")(
    suite("generate")(
      test("produces a 22-character base64url string") {
        for {
          key <- WorkspaceKeyCrypto.generate
        } yield {
          // reveal: required to verify the generated value matches the expected format
          val raw = key.reveal
          assertTrue(
            raw.length == 22,
            base64urlPattern.matches(raw)
          )
        }
      },
      test("produces distinct keys on successive calls") {
        for {
          k1 <- WorkspaceKeyCrypto.generate
          k2 <- WorkspaceKeyCrypto.generate
        } yield assertTrue(k1 != k2) // uses equals (R6)
      },
      test("generated key has redacted toString, never the raw credential") {
        for {
          key <- WorkspaceKeyCrypto.generate
        } yield assertTrue(
          key.toString == "WorkspaceKeySecret(***)",
          !key.toString.contains(key.reveal) // reveal used here only to prove absence
        )
      },
      test("generated key: string interpolation uses redacted toString") {
        for {
          key <- WorkspaceKeyCrypto.generate
        } yield {
          val interpolated = s"Resolving $key"
          assertTrue(
            interpolated == "Resolving WorkspaceKeySecret(***)",
            !interpolated.contains(key.reveal) // reveal used here only to prove absence
          )
        }
      },
      test("generated keys are usable as Map keys") {
        for {
          key <- WorkspaceKeyCrypto.generate
        } yield {
          // Reconstruct via fromString to get a structurally equal but distinct instance.
          // reveal is the only way to extract the value for round-trip reconstruction.
          val reconstructed = WorkspaceKeySecret.fromString(key.reveal).toOption.get
          val map = Map(key -> "workspace-data")
          assertTrue(map(reconstructed) == "workspace-data")
        }
      },
      test("generated keys pass fromString validation") {
        for {
          key <- WorkspaceKeyCrypto.generate
        } yield {
          // reveal: required to extract value for round-trip through fromString
          val parsed = WorkspaceKeySecret.fromString(key.reveal)
          assertTrue(parsed == Right(key)) // uses equals (R6)
        }
      },
      test("generated keys JSON round-trip encodes and decodes consistently") {
        for {
          key <- WorkspaceKeyCrypto.generate
        } yield {
          val json    = key.toJson
          val decoded = json.fromJson[WorkspaceKeySecret]
          // reveal: required to verify the JSON wire format contains the raw credential
          assertTrue(
            decoded == Right(key), // uses equals (R6)
            json == s"\"${key.reveal}\""
          )
        }
      }
    ),
    suite("hash")(
      test("is deterministic") {
        val key = WorkspaceKeySecret.fromString("abcdefghijklmnopqrstuv").toOption.get
        val h1 = WorkspaceKeyCrypto.hash(key)
        val h2 = WorkspaceKeyCrypto.hash(key)
        assertTrue(h1 == h2, h1.value.length == 64)
      }
    )
  )
}

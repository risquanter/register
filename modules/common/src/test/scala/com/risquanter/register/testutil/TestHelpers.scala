package com.risquanter.register.testutil

import zio.test.Gen
import zio.prelude.Validation
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.domain.data.iron.{SafeId, NodeId, TreeId}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.annotation.tailrec

/**
  * Shared test utilities for creating Iron-refined types in tests.
  *
  * == Purpose ==
  *
  * Iron's compile-time refinement doesn't work with runtime values.
  * Tests need to create SafeId and other refined types from string literals
  * or computed values. This trait provides convenient helper methods.
  *
  * == Usage ==
  *
  * {{{
  * object MySpec extends ZIOSpecDefault with TestHelpers {
  *   // Use safeId() to create SafeId.SafeId values
  *   val nodeId = safeId("cyber")
  * }
  * }}}
  *
  * Or import directly:
  * {{{
  * import com.risquanter.register.testutil.TestHelpers.safeId
  * val nodeId = safeId("cyber")
  * }}}
  *
  * @see ADR-001 for SafeId type design
  */
trait TestHelpers {

  /**
    * Create a SafeId.SafeId from a String.
    *
    * First tries SafeId.fromString; if validation fails, falls back to a
    * deterministic ULID derived from the label. This keeps fixtures stable
    * while avoiding repeated literal ULIDs. Use safeIdStrict when you want
    * failures for invalid inputs.
    *
    * @param s String value (any label; non-ULID labels hash to a ULID)
    * @return SafeId.SafeId refined type
    * @throws IllegalArgumentException if deterministic fallback somehow fails
    */
  def safeId(s: String): SafeId.SafeId =
    SafeId.fromString(s).getOrElse(
      deterministicUlidFromLabel(s)
    )

  /**
    * Strict SafeId constructor: fails if the input is not a valid ULID.
    */
  def safeIdStrict(s: String): SafeId.SafeId =
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
    )

  /**
    * Deterministically derive a SafeId from a human-readable label.
    * Provided as an explicit helper to avoid masking mistakes when desired.
    */
  def ulidFromLabel(label: String): SafeId.SafeId = deterministicUlidFromLabel(label)

  /**
    * Deterministic ULID string from a human-readable label, derived via safeId(label).value.
    * Use in fixtures that need a String id but must conform to SafeId/ULID constraints.
    */
  def idStr(label: String): String = safeId(label).value

  /**
    * Create a NodeId from a label (wraps safeId in NodeId case class).
    * @see ADR-018 for nominal wrapper pattern
    */
  def nodeId(s: String): NodeId = NodeId(safeId(s))

  /**
    * Create a TreeId from a label (wraps safeId in TreeId case class).
    * @see ADR-018 for nominal wrapper pattern
    */
  def treeId(s: String): TreeId = TreeId(safeId(s))
    
  /**
    * ZIO Test generator for valid SafeId values.
    *
    * Generates alpha-numeric labels (1-32 chars) and hashes them to ULIDs,
    * matching safeId/safeIdStrict expectations.
    *
    * Usage:
    * {{{
    * import com.risquanter.register.testutil.TestHelpers.genSafeId
    * 
    * check(genSafeId) { id =>
    *   assertTrue(id.value.length >= 3)
    * }
    * }}}
    */
  val genSafeId: Gen[Any, SafeId.SafeId] =
    Gen.alphaNumericStringBounded(1, 32).map(label => deterministicUlidFromLabel(label))

  /**
    * Extract validated value or throw AssertionError with accumulated messages.
    * Intended for deterministic test fixture construction.
    */
  def unsafeGet[A](v: Validation[ValidationError, A], label: String): A =
    v.toEither.fold(
      errs => throw new AssertionError(s"$label: ${errs.map(_.message).mkString("; ")}"),
      identity
    )

  /** Deterministically derive a ULID from a human-readable label (for fixtures/tests).
    * Uses SHA-256(label) -> first 16 bytes -> Crockford base32 encoding.
    */
  private def deterministicUlidFromLabel(label: String): SafeId.SafeId =
    val bytes = MessageDigest.getInstance("SHA-256").digest(label.getBytes(StandardCharsets.UTF_8)).take(16)
    val ulidString = encodeBase32(bytes)
    SafeId.fromString(ulidString).getOrElse(
      throw new IllegalStateException(s"Deterministic ULID generation failed for label: $label")
    )

  private val crockfordAlphabet: Array[Char] = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray

  private def encodeBase32(bytes: Array[Byte]): String =
    // Convert 128-bit value to 26 Crockford base32 characters
    val totalBits = bytes.length * 8
    require(totalBits == 128, s"ULID encoding expects 16 bytes, got ${bytes.length}")

    @tailrec
    def toBase32(value: BigInt, remaining: Int, acc: List[Char]): List[Char] =
      if remaining == 0 then acc
      else
        val (quot, rem) = value /% 32
        toBase32(quot, remaining - 1, crockfordAlphabet(rem.toInt) :: acc)

    val bi = BigInt(1, bytes)
    toBase32(bi, 26, Nil).mkString
}

/**
  * Object form for imports where mixing in a trait isn't convenient.
  *
  * {{{
  * import com.risquanter.register.testutil.TestHelpers.*
  * val nodeId = safeId("cyber")
  * }}}
  */
object TestHelpers extends TestHelpers

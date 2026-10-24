package com.risquanter.register.testutil

import zio.prelude.Validation
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, FieldName, ValidationError}
import com.risquanter.register.domain.data.{
  DistributionShape, MitigationSpec, ResultTransformSpec, TransformPipeline
}
import com.risquanter.register.domain.data.iron.{
  SafeId, NodeId, TreeId, MitigationId, RetentionFactor, ResidualProbability, UnitFraction
}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.{Greater, LessEqual}
import scala.annotation.tailrec
import scala.util.hashing.MurmurHash3

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
    * Create a MitigationId from a label (wraps safeId in MitigationId case class).
    * @see ADR-018 for nominal wrapper pattern
    */
  def mitigationId(s: String): MitigationId = MitigationId(safeId(s))

  /**
    * Extract validated value or throw AssertionError with accumulated messages.
    * Intended for deterministic test fixture construction.
    */
  def unsafeGet[A](v: Validation[ValidationError, A], label: String): A =
    v.toEither.fold(
      errs => throw new AssertionError(s"$label: ${errs.map(_.message).mkString("; ")}"),
      identity
    )

  /**
    * A metalog loss shape built from the two parallel sequences an author
    * states. Goes through the same gate the request decoder uses, so a fixture
    * that breaks a construction rule fails loudly here instead of producing a
    * value the domain cannot hold.
    *
    * {{{
    * metalogShape(Vector(0.1, 0.5, 0.9), Vector(1000.0, 5000.0, 25000.0), Some(3))
    * }}}
    */
  def metalogShape(
    percentiles: Vector[Double],
    quantiles:   Vector[Double],
    terms:       Option[Int]
  ): DistributionShape.Metalog =
    unsafeGet(
      DistributionShape.Metalog.fromPrimitives(percentiles, quantiles, terms, ErrorSubject.Request),
      "metalogShape"
    )

  /**
    * A lognormal loss shape built from its two bounds, through the same gate
    * the request decoder uses.
    */
  def lognormalShape(minLoss: Long, maxLoss: Long): DistributionShape.Lognormal =
    unsafeGet(
      DistributionShape.Lognormal.fromPrimitives(minLoss, maxLoss, ErrorSubject.Request),
      "lognormalShape"
    )

  /**
    * The fraction a mitigation keeps, built from a literal through the same
    * refinement the request decoder uses. A fixture outside (0, 1] fails here.
    *
    * {{{
    * retentionFactor(0.5)
    * }}}
    */
  def retentionFactor(value: Double): RetentionFactor =
    RetentionFactor(refine(value, "retentionFactor"))

  /**
    * The probability a mitigation asserts, on the same terms. It is a distinct
    * type from `retentionFactor`'s result although both refine one predicate,
    * so a factor cannot reach a parameter wanting a probability.
    */
  def residualProbability(value: Double): ResidualProbability =
    ResidualProbability(refine(value, "residualProbability"))

  private def refine(value: Double, label: String): UnitFraction =
    value
      .refineEither[Greater[0.0] & LessEqual[1.0]]
      .getOrElse(throw new IllegalArgumentException(
        s"$label fixture outside (0, 1]: $value"))

  /**
    * A result-stage spec built from its steps, through the same step-count gate
    * the request decoder uses. A fixture with no steps, or with more than the
    * pipeline allows, fails here rather than producing a spec the domain
    * refuses.
    *
    * {{{
    * resultStage(ResultTransformSpec.CapLosses(1_000L))
    * }}}
    */
  def resultStage(steps: ResultTransformSpec*): MitigationSpec.ResultStage =
    unsafeGet(
      MitigationSpec.ResultStage.create(
        TransformPipeline(steps.toList),
        ErrorLocation.requestField(FieldName.Spec)
      ),
      "resultStage"
    )

  /** Deterministically derive a ULID from a human-readable label (for fixtures/tests).
    * Fills 16 bytes from a four-round MurmurHash3 chain, then Crockford base32.
    * Pure Scala — links on both JVM and Scala.js; not cryptographic, intended only
    * for stable, distinct test-fixture identifiers.
    */
  private def deterministicUlidFromLabel(label: String): SafeId.SafeId =
    val ulidString = encodeBase32(hash128(label))
    SafeId.fromString(ulidString).getOrElse(
      throw new IllegalStateException(s"Deterministic ULID generation failed for label: $label")
    )

  /** 128-bit deterministic digest of a label as 16 big-endian bytes. */
  private def hash128(label: String): Array[Byte] =
    val h0 = MurmurHash3.stringHash(label, 0x9e3779b1)
    val h1 = MurmurHash3.stringHash(label, h0)
    val h2 = MurmurHash3.stringHash(label, h1)
    val h3 = MurmurHash3.stringHash(label, h2)
    Array(h0, h1, h2, h3).flatMap(intToBytes)

  private def intToBytes(h: Int): Array[Byte] =
    Array((h >>> 24).toByte, (h >>> 16).toByte, (h >>> 8).toByte, h.toByte)

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

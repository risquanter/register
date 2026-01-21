package com.risquanter.register.testutil

import zio.test.Gen
import zio.prelude.Validation
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.domain.data.iron.SafeId

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
    * Uses SafeId.fromString for validation - will throw at runtime
    * if the string doesn't meet SafeId constraints (MinLength[3]).
    *
    * @param s String value (must be at least 3 characters)
    * @return SafeId.SafeId refined type
    * @throws IllegalArgumentException if refinement fails
    */
  def safeId(s: String): SafeId.SafeId =
    SafeId.fromString(s).getOrElse(
      throw new IllegalArgumentException(s"Invalid SafeId in test: $s")
    )
    
  /**
    * ZIO Test generator for valid SafeId values.
    *
    * Generates alphanumeric strings between 3-30 characters,
    * satisfying SafeId constraints (MinLength[3], MaxLength[30]).
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
    Gen.alphaNumericStringBounded(3, 30).map(safeId)

  /**
    * Extract validated value or throw AssertionError with accumulated messages.
    * Intended for deterministic test fixture construction.
    */
  def unsafeGet[A](v: Validation[ValidationError, A], label: String): A =
    v.toEither.fold(
      errs => throw new AssertionError(s"$label: ${errs.map(_.message).mkString("; ")}"),
      identity
    )
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

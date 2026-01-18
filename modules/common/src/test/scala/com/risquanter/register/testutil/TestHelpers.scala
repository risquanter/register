package com.risquanter.register.testutil

import io.github.iltotore.iron.refineUnsafe
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
  * == Quality Check Note ==
  *
  * This pattern was identified as duplicated across:
  * - LECCacheSpec.scala
  * - TreeIndexSpec.scala
  * - CurveBundleSpec.scala
  *
  * Consider consolidating these in a future cleanup pass.
  *
  * @see ADR-001 for SafeId type design
  */
trait TestHelpers {

  /**
    * Create a SafeId.SafeId from a String.
    *
    * Uses refineUnsafe for test convenience - will throw at runtime
    * if the string doesn't meet SafeId constraints (MinLength[3]).
    *
    * @param s String value (must be at least 3 characters)
    * @return SafeId.SafeId refined type
    * @throws IllegalArgumentException if refinement fails
    */
  def safeId(s: String): SafeId.SafeId =
    SafeId.SafeId(s.refineUnsafe)
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

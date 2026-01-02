package com.risquanter.register.syntax

import zio.*
import zio.test.*

/** Extension methods for cleaner assertion syntax in ZIO tests
  * 
  * Provides `.assert` method on ZIO values for more concise test assertions.
  * 
  * Example:
  * {{{
  * program.assert { result =>
  *   result.name == expected && result.id > 0
  * }
  * }}}
  * 
  * Instead of:
  * {{{
  * program.map { result =>
  *   assertTrue(result.name == expected && result.id > 0)
  * }
  * }}}
  */
extension [R, E, A](zio: ZIO[R, E, A])
  /** Assert using a ZIO Test Assertion */
  def assert(assertion: Assertion[A]): ZIO[R, E, TestResult] = 
    assertZIO(zio)(assertion): ZIO[R, E, TestResult]
    
  /** Assert using a boolean predicate function */
  def assert(predicate: (=> A) => Boolean): ZIO[R, E, TestResult] = 
    assert(Assertion.assertion("test assertion")(predicate))

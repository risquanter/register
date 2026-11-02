package com.risquanter.register.http.responses

import zio.test.*

/** `Versions` is a functor, and `traverse` is its `map` for a function that
  * can fail. */
object VersionsSpec extends ZIOSpecDefault:

  private val full    = Versions(Some(1), Some(2), Some(3))
  private val partial = Versions(Some(1), None, Some(3))

  private val double: Int => Int    = _ * 2
  private val show: Int => String   = _.toString
  private val positive: Int => Either[String, Int] = n => if n > 0 then Right(n) else Left(s"$n is not positive")

  def spec = suite("Versions")(
    test("map keeps identity") {
      assertTrue(full.map(identity) == full, partial.map(identity) == partial)
    },
    test("map keeps composition") {
      assertTrue(full.map(double).map(show) == full.map(double andThen show))
    },
    test("an absent version stays absent") {
      assertTrue(
        partial.map(double) == Versions(Some(2), None, Some(6)),
        partial.traverse(positive) == Right(partial)
      )
    },
    test("traverse with a function that never fails equals map") {
      assertTrue(full.traverse(n => Right(double(n))) == Right(full.map(double)))
    },
    test("traverse returns the first failure") {
      assertTrue(Versions(Some(1), Some(-2), Some(-3)).traverse(positive) == Left("-2 is not positive"))
    }
  )

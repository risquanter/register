package app.core

import zio.test.*

/** Pure tests for `JsBoundary.orElse` — the sanctioned JS-boundary catch-all
  * (ADR-033 §4).
  */
object JsBoundarySpec extends ZIOSpecDefault:

  def spec = suite("JsBoundary.orElse")(

    test("passes the body's value through when nothing throws") {
      assertTrue(JsBoundary.orElse(0)(41 + 1) == 42)
    },

    test("returns the fallback when the body throws a RuntimeException") {
      assertTrue(JsBoundary.orElse(-1)(throw new RuntimeException("boom")) == -1)
    },

    test("returns the fallback when the body throws a java.lang.Error — the width NonFatal refuses") {
      assertTrue(JsBoundary.orElse(-1)(throw new Error("undefined behavior")) == -1)
    }
  )

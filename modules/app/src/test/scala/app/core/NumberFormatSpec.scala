package app.core

import zio.test.*

/** Pure tests for `NumberFormat.percentValue` — the single percent formatter
  * behind `RiskLeafFormState.domainToDisplayPct` and the LEC chart's no-loss
  * label.
  */
object NumberFormatSpec extends ZIOSpecDefault:

  def spec = suite("NumberFormat.percentValue")(

    test("0 decimals renders integer percentiles") {
      assertTrue(
        NumberFormat.percentValue(0.1, 0) == "10",
        NumberFormat.percentValue(0.5, 0) == "50",
        NumberFormat.percentValue(0.9, 0) == "90"
      )
    },

    test("floating-point noise is eliminated (0.1 * 100 = 10.000000000000001)") {
      assertTrue(NumberFormat.percentValue(0.1, 2) == "10")
    },

    test("2 decimals keeps precision and strips trailing zeros") {
      assertTrue(
        NumberFormat.percentValue(0.205, 2) == "20.5",
        NumberFormat.percentValue(0.2, 2) == "20",
        NumberFormat.percentValue(0.12345, 2) == "12.35"
      )
    },

    test("rounds half-up at 0 decimals") {
      assertTrue(NumberFormat.percentValue(0.005, 0) == "1")
    }
  )

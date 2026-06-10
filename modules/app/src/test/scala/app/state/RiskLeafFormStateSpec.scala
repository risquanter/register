package app.state

import zio.test.*

object RiskLeafFormStateSpec extends ZIOSpecDefault:

  def spec = suite("RiskLeafFormState companion")(

    suite("pctToDomain")(

      test("50.0 → 0.5") {
        assertTrue(RiskLeafFormState.pctToDomain(50.0) == 0.5)
      },

      test("0.0 → 0.0 (boundary)") {
        assertTrue(RiskLeafFormState.pctToDomain(0.0) == 0.0)
      },

      test("100.0 → 1.0 (boundary)") {
        assertTrue(RiskLeafFormState.pctToDomain(100.0) == 1.0)
      },

      test("round-trip: pctToDomain(domainToDisplayPct(p, 2).toDouble) ≈ p for p = 0.2") {
        val displayed = RiskLeafFormState.domainToDisplayPct(0.2, 2)
        val roundTripped = RiskLeafFormState.pctToDomain(displayed.toDouble)
        assertTrue(math.abs(roundTripped - 0.2) < 1e-9)
      }

    ),

    suite("domainToDisplayPct")(

      test("0 dp: eliminates IEEE 754 noise — 0.1 * 100 = 10, not 10.000000000000001") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.1, 0) == "10")
      },

      test("0 dp: 0.5 → \"50\"") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.5, 0) == "50")
      },

      test("0 dp: 0.9 → \"90\"") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.9, 0) == "90")
      },

      test("2 dp: 0.2 → \"20\" (trailing zeros stripped)") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.2, 2) == "20")
      },

      test("2 dp: 0.205 → \"20.5\" (trailing zero after significant digit stripped)") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.205, 2) == "20.5")
      },

      test("2 dp: 0.4012 → \"40.12\" (meaningful 2 dp preserved)") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.4012, 2) == "40.12")
      },

      test("0 dp: boundary 0.0 → \"0\"") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(0.0, 0) == "0")
      },

      test("0 dp: boundary 1.0 → \"100\"") {
        assertTrue(RiskLeafFormState.domainToDisplayPct(1.0, 0) == "100")
      }

    )

  )

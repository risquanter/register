package app.state

import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

object RiskLeafFormStateSpec extends ZIOSpecDefault:

  /** Observe a signal's current value under a throwaway owner. */
  private def current[A](signal: Signal[A]): A =
    given owner: ManualOwner = new ManualOwner
    var seen: Option[A] = None
    signal.foreach(a => seen = Some(a))
    owner.killSubscriptions()
    seen.get

  def spec = suite("RiskLeafFormState companion")(

    suite("lognormal bound positivity")(

      test("a minLoss of 0 shows the positivity error") {
        val st = new RiskLeafFormState
        st.distributionModeVar.set(DistributionMode.Lognormal)
        st.minLossVar.set("0")
        st.markTouched(RiskLeafField.MinLoss)
        assertTrue(current(st.minLossError).contains("Value must be greater than zero"))
      },

      test("a maxLoss of 0 shows the positivity error") {
        val st = new RiskLeafFormState
        st.distributionModeVar.set(DistributionMode.Lognormal)
        st.maxLossVar.set("0")
        st.markTouched(RiskLeafField.MaxLoss)
        assertTrue(current(st.maxLossError).contains("Value must be greater than zero"))
      },

      test("a minLoss of 1 is accepted") {
        val st = new RiskLeafFormState
        st.distributionModeVar.set(DistributionMode.Lognormal)
        st.minLossVar.set("1")
        st.markTouched(RiskLeafField.MinLoss)
        assertTrue(current(st.minLossError).isEmpty)
      },

      test("a zero expert quantile shows the positivity error") {
        val st = new RiskLeafFormState
        st.distributionModeVar.set(DistributionMode.Expert)
        st.quantilesVar.set("0, 5000, 20000")
        st.markTouched(RiskLeafField.Quantiles)
        assertTrue(
          current(st.quantilesError)
            .exists(_.contains("Quantile loss amounts must be greater than zero"))
        )
      }

    ),

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

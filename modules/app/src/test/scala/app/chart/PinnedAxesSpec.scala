package app.chart

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.{LECNodeCurve, LECPoint}

/** Pure tests for `PinnedAxes.fromCurves` — the shared axis extents both
  * side-by-side panels are pinned to.
  */
object PinnedAxesSpec extends ZIOSpecDefault:

  private def nid(i: Int): NodeId =
    NodeId.fromString(f"01HX9ABCDE00000000000000$i%02d").toOption.get

  private def curve(i: Int, points: (Long, Double)*): LECNodeCurve =
    LECNodeCurve(
      nid(i),
      name = s"c$i",
      curve = points.toVector.map((l, p) => LECPoint(l, p)),
      quantiles = Map.empty,
      averageAnnualLoss = 0.0,
      probabilityOfNoLoss = 1.0
    )

  def spec = suite("PinnedAxes.fromCurves")(

    test("no curves, or only empty curves, pin nothing") {
      assertTrue(
        PinnedAxes.fromCurves(Nil).isEmpty,
        PinnedAxes.fromCurves(List(curve(1))).isEmpty
      )
    },

    test("lossMax is the largest loss across ALL curves, not per curve") {
      val pinned = PinnedAxes.fromCurves(List(
        curve(1, 10L -> 0.5, 200L -> 0.1),
        curve(2, 50L -> 0.3, 800L -> 0.05)
      ))
      assertTrue(pinned.map(_.lossMax).contains(800.0))
    },

    test("probabilityMax applies the 10% headroom over the largest starting exceedance") {
      val pinned = PinnedAxes.fromCurves(List(
        curve(1, 10L -> 0.5),
        curve(2, 10L -> 0.7)
      ))
      assertTrue(pinned.map(_.probabilityMax).contains(0.7 * 1.1))
    },

    test("probabilityMax is capped at 1.0") {
      val pinned = PinnedAxes.fromCurves(List(curve(1, 10L -> 0.99)))
      assertTrue(pinned.map(_.probabilityMax).contains(1.0))
    },

    test("a curve with no points contributes nothing but doesn't suppress the others") {
      val pinned = PinnedAxes.fromCurves(List(curve(1), curve(2, 40L -> 0.2)))
      assertTrue(
        pinned.map(_.lossMax).contains(40.0),
        pinned.map(_.probabilityMax).contains(0.2 * 1.1)
      )
    },

    test("annotation values beyond the last curve tick extend lossMax (quantile rules must stay inside the pinned plot)") {
      val withQuantile = curve(1, 10L -> 0.5, 100L -> 0.1).copy(quantiles = Map("p99.5" -> 250.0))
      val pinned = PinnedAxes.fromCurves(List(withQuantile))
      assertTrue(pinned.map(_.lossMax).contains(250.0))
    },

    test("an empty curve's fallback AAL/quantiles do not extend the domain") {
      val emptyWithAal = curve(1).copy(averageAnnualLoss = 999.0)
      val pinned = PinnedAxes.fromCurves(List(emptyWithAal, curve(2, 40L -> 0.2)))
      assertTrue(pinned.map(_.lossMax).contains(40.0))
    }
  )

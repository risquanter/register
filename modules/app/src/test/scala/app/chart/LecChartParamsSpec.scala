package app.chart

import zio.test.*

/** Pure tests for the LEC chart-control model — the enums that are the single
  * source of truth for the Vega toggle signals and the `ChartParams` state the
  * control panel drives. (`ChartParams.applyTo` is a Vega-view side effect,
  * exercised in the manual pass, not here.)
  */
object LecChartParamsSpec extends ZIOSpecDefault:

  def spec = suite("LEC chart params")(

    test("ChartParams.default uses the enum defaults") {
      assertTrue(
        ChartParams.default.interpolation == Interpolation.default,
        ChartParams.default.annotations == LecAnnotation.defaults
      )
    },

    test("LecAnnotation.defaults is exactly the defaultOn cases") {
      assertTrue(
        LecAnnotation.defaults == Set(LecAnnotation.P95, LecAnnotation.AAL, LecAnnotation.NoLoss),
        LecAnnotation.values.filter(_.defaultOn).toSet == LecAnnotation.defaults
      )
    },

    test("toggle adds an off annotation, then removes it (round-trip to default)") {
      val added   = ChartParams.default.toggle(LecAnnotation.P90)
      val removed = added.toggle(LecAnnotation.P90)
      assertTrue(
        !ChartParams.default.annotations.contains(LecAnnotation.P90),
        added.annotations.contains(LecAnnotation.P90),
        removed == ChartParams.default
      )
    },

    test("toggle removes an on-by-default annotation") {
      assertTrue(!ChartParams.default.toggle(LecAnnotation.P95).annotations.contains(LecAnnotation.P95))
    },

    test("toggle leaves interpolation untouched") {
      val p = ChartParams(Interpolation.Linear, LecAnnotation.defaults)
      assertTrue(p.toggle(LecAnnotation.P90).interpolation == Interpolation.Linear)
    },

    test("Interpolation.fromSignal round-trips every case; unknown falls back to default") {
      assertTrue(
        Interpolation.values.forall(i => Interpolation.fromSignal(i.signalValue) == i),
        Interpolation.fromSignal("nonsense") == Interpolation.default
      )
    },

    test("annotation signal names are unique and non-empty") {
      val names = LecAnnotation.values.map(_.signalName).toList
      assertTrue(names.distinct.size == names.size, names.forall(_.nonEmpty))
    }
  )

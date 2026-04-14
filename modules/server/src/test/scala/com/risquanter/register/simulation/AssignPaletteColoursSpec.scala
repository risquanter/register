package com.risquanter.register.simulation

import zio.test.*

import com.risquanter.register.domain.data.{CurvePalette, LECPoint, LECNodeCurve}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.testutil.TestHelpers.nodeId

object AssignPaletteColoursSpec extends ZIOSpecDefault:

  // ── Helpers ───────────────────────────────────────────────────

  private def nid(s: String): NodeId = nodeId(s)

  private def makeCurve(id: String, name: String, p95: Double): LECNodeCurve =
    LECNodeCurve(
      id = id,
      name = name,
      curve = Vector(LECPoint(0L, 1.0), LECPoint(100L, 0.5)),
      quantiles = Map("p50" -> 50.0, "p95" -> p95)
    )

  private def makeCurveNoP95(id: String, name: String): LECNodeCurve =
    LECNodeCurve(
      id = id,
      name = name,
      curve = Vector(LECPoint(0L, 1.0), LECPoint(100L, 0.5)),
      quantiles = Map.empty
    )

  // ── Tests ─────────────────────────────────────────────────────

  def spec = suite("AssignPaletteColoursSpec")(
    test("single curve with Green palette gets darkest Green shade") {
      val n = nid("node-a")
      val curve = makeCurve("a", "Alpha", 200.0)
      val result = ColouredCurve.assignPaletteColours(
        Map(n -> curve),
        Map(n -> CurvePalette.Green)
      )
      assertTrue(
        result.size == 1,
        result.head.curve == curve,
        result.head.hexColor.value == "#03170b" // shade index 0 (darkest)
      )
    },
    test("two curves in Green palette sorted by p95 descending") {
      val nidHigh = nid("node-high")
      val nidLow  = nid("node-low")
      val highP95 = makeCurve("high", "High Risk", 500.0)
      val lowP95  = makeCurve("low", "Low Risk", 100.0)
      val result = ColouredCurve.assignPaletteColours(
        Map(nidHigh -> highP95, nidLow -> lowP95),
        Map(nidHigh -> CurvePalette.Green, nidLow -> CurvePalette.Green)
      )
      assertTrue(
        result.size == 2,
        result(0).curve.name == "High Risk",   // p95=500 first
        result(1).curve.name == "Low Risk",    // p95=100 second
        result(0).hexColor.value == "#03170b", // shade 0 (darkest)
        result(1).hexColor.value == "#052914"  // shade 1
      )
    },
    test("curves in different palettes get shades from their respective palette") {
      val nidG = nid("node-g")
      val nidA = nid("node-a")
      val curveG = makeCurve("g", "Green Curve", 300.0)
      val curveA = makeCurve("a", "Aqua Curve", 200.0)
      val result = ColouredCurve.assignPaletteColours(
        Map(nidG -> curveG, nidA -> curveA),
        Map(nidG -> CurvePalette.Green, nidA -> CurvePalette.Aqua)
      )
      val greenShade0 = CurvePaletteRegistry.shades(CurvePalette.Green).head.value
      val aquaShade0  = CurvePaletteRegistry.shades(CurvePalette.Aqua).head.value
      val resultColours = result.map(_.hexColor.value).toSet
      assertTrue(
        result.size == 2,
        resultColours.contains(greenShade0),
        resultColours.contains(aquaShade0)
      )
    },
    test("missing palette map entry defaults to Green") {
      val n = nid("node-missing")
      val curve = makeCurve("m", "Missing Palette", 100.0)
      // Empty paletteMap — should default to Green
      val result = ColouredCurve.assignPaletteColours(
        Map(n -> curve),
        Map.empty
      )
      val greenShade0 = CurvePaletteRegistry.shades(CurvePalette.Green).head.value
      assertTrue(
        result.size == 1,
        result.head.hexColor.value == greenShade0
      )
    },
    test("curves without p95 treated as p95=0 (sorted last within group)") {
      val nidHigh = nid("node-high")
      val nidNone = nid("node-none")
      val highP95 = makeCurve("high", "Has P95", 500.0)
      val noP95   = makeCurveNoP95("none", "No P95")
      val result = ColouredCurve.assignPaletteColours(
        Map(nidHigh -> highP95, nidNone -> noP95),
        Map(nidHigh -> CurvePalette.Green, nidNone -> CurvePalette.Green)
      )
      assertTrue(
        result(0).curve.name == "Has P95",  // p95=500 → rank 0
        result(1).curve.name == "No P95"    // p95=0   → rank 1
      )
    },
    test("more than 13 curves clamp to lightest shade") {
      // Create 15 curves, all Green
      val entries: Vector[(NodeId, LECNodeCurve)] = (1 to 15).toVector.map { i =>
        val n = nid(s"node-$i")
        val curve = makeCurve(s"id-$i", s"Curve $i", (15 - i).toDouble * 100)
        (n, curve)
      }
      val curves: Map[NodeId, LECNodeCurve] = entries.toMap
      val paletteMap: Map[NodeId, CurvePalette] = curves.keys.map(_ -> CurvePalette.Green).toMap
      val result = ColouredCurve.assignPaletteColours(curves, paletteMap)
      val lightestGreen = CurvePaletteRegistry.shades(CurvePalette.Green).last.value
      // Last two (indices 13, 14) should clamp to shade index 12
      val lastTwo = result.takeRight(2).map(_.hexColor.value)
      assertTrue(
        result.size == 15,
        lastTwo.forall(_ == lightestGreen)
      )
    },
    test("empty curves map returns empty result") {
      val result = ColouredCurve.assignPaletteColours(
        Map.empty[NodeId, LECNodeCurve],
        Map.empty[NodeId, CurvePalette]
      )
      assertTrue(result.isEmpty)
    },
    test("Purple palette assigns from Purple shade range") {
      val n = nid("node-p")
      val curve = makeCurve("p", "Purple Curve", 100.0)
      val result = ColouredCurve.assignPaletteColours(
        Map(n -> curve),
        Map(n -> CurvePalette.Purple)
      )
      val purpleShade0 = CurvePaletteRegistry.shades(CurvePalette.Purple).head.value
      assertTrue(
        result.head.hexColor.value == purpleShade0
      )
    }
  )

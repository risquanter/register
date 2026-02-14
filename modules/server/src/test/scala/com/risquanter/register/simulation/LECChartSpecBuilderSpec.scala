package com.risquanter.register.simulation

import zio.test.*

import com.risquanter.register.domain.data.{LECPoint, LECNodeCurve}

object LECChartSpecBuilderSpec extends ZIOSpecDefault:

  // ── Fixtures ──────────────────────────────────────────────────

  private val samplePoints = Vector(
    LECPoint(0L, 1.0),
    LECPoint(100L, 0.65),
    LECPoint(200L, 0.35),
    LECPoint(500L, 0.02)
  )

  private val rootCurve = LECNodeCurve(
    id = "root-id",
    name = "Portfolio",
    curve = samplePoints,
    quantiles = Map("p50" -> 150.0, "p95" -> 420.0)
  )

  private val childAlpha = LECNodeCurve(
    id = "alpha-id",
    name = "Alpha Risk",
    curve = Vector(LECPoint(0L, 1.0), LECPoint(80L, 0.50), LECPoint(200L, 0.05)),
    quantiles = Map("p50" -> 80.0, "p95" -> 180.0)
  )

  private val childZeta = LECNodeCurve(
    id = "zeta-id",
    name = "Zeta Risk",
    curve = Vector(LECPoint(0L, 1.0), LECPoint(60L, 0.70), LECPoint(150L, 0.10)),
    quantiles = Map.empty
  )

  private val curveNoQuantiles = LECNodeCurve(
    id = "nq-id",
    name = "No Quantiles",
    curve = samplePoints,
    quantiles = Map.empty
  )

  private val curveEmptyPoints = LECNodeCurve(
    id = "empty-id",
    name = "Empty",
    curve = Vector.empty,
    quantiles = Map("p50" -> 100.0)
  )

  private val curveWithSpecialChars = LECNodeCurve(
    id = "special-id",
    name = """Risk "A" \ B""",
    curve = samplePoints,
    quantiles = Map.empty
  )

  // ── Tests ─────────────────────────────────────────────────────

  def spec = suite("LECChartSpecBuilderSpec")(
    suite("empty / degenerate inputs")(
      test("empty curves vector produces empty spec") {
        val result = LECChartSpecBuilder.generateMultiCurveSpec(Vector.empty)
        assertTrue(
          result.contains("No data available"),
          result.contains("vega-lite/v6")
        )
      },
      test("single curve with empty points produces empty spec") {
        val result = LECChartSpecBuilder.generateMultiCurveSpec(Vector(curveEmptyPoints))
        assertTrue(result.contains("No data available"))
      }
    ),
    suite("single curve")(
      test("contains Vega-Lite v6 schema") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        assertTrue(result.contains("https://vega.github.io/schema/vega-lite/v6.json"))
      },
      test("contains data values matching input points") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        assertTrue(
          result.contains(""""loss": 0"""),
          result.contains(""""loss": 100"""),
          result.contains(""""loss": 500"""),
          result.contains(""""exceedance": 0.65""")
        )
      },
      test("contains risk name in data values") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        assertTrue(result.contains(""""risk": "Portfolio""""))
      }
    ),
    suite("multi-curve ordering")(
      test("root curve is first in color domain, children sorted alphabetically") {
        // Order: Portfolio (root), then Alpha Risk, Zeta Risk (alphabetical)
        val result = LECChartSpecBuilder.generateMultiCurveSpec(
          Vector(rootCurve, childZeta, childAlpha)
        )
        val domainIdx   = result.indexOf(""""domain": [""")
        val afterDomain = result.substring(domainIdx)
        val portfolioPos = afterDomain.indexOf("Portfolio")
        val alphaPos     = afterDomain.indexOf("Alpha Risk")
        val zetaPos      = afterDomain.indexOf("Zeta Risk")
        assertTrue(
          portfolioPos < alphaPos,
          alphaPos < zetaPos
        )
      }
    ),
    suite("quantile annotations")(
      test("P50 and P95 rules rendered when quantiles present on root") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        assertTrue(
          result.contains(""""label": "P50""""),
          result.contains(""""label": "P95""""),
          result.contains(""""x": 150.0"""),
          result.contains(""""x": 420.0""")
        )
      },
      test("no quantile rules when root has empty quantiles") {
        val result = LECChartSpecBuilder.generateSpec(curveNoQuantiles)
        assertTrue(
          !result.contains(""""label": "P50""""),
          !result.contains(""""label": "P95"""")
        )
      },
      test("multi-curve uses root quantiles only, not children") {
        // Root has quantiles, childZeta does not — only root's should appear
        val result = LECChartSpecBuilder.generateMultiCurveSpec(
          Vector(curveNoQuantiles, childAlpha)
        )
        // curveNoQuantiles is root with no quantiles — childAlpha's quantiles should NOT appear
        assertTrue(
          !result.contains(""""label": "P50""""),
          !result.contains(""""label": "P95"""")
        )
      }
    ),
    suite("color palette")(
      test("first curve gets first palette color, second gets second") {
        val colors = LECChartSpecBuilder.themeColorsRisk
        val result = LECChartSpecBuilder.generateMultiCurveSpec(
          Vector(rootCurve, childAlpha)
        )
        assertTrue(
          result.contains(colors(0)),
          result.contains(colors(1))
        )
      }
    ),
    suite("JSON escaping")(
      test("risk name with quotes and backslash does not break JSON") {
        val result = LECChartSpecBuilder.generateSpec(curveWithSpecialChars)
        // The escaped name should appear — verify no raw unescaped quote
        assertTrue(
          result.contains("""Risk \"A\" \\ B"""),
          !result.contains("""Risk "A" \ B""")
        )
      }
    ),
    suite("params interpolation toggle")(
      test("spec contains params bind for interpolation") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        assertTrue(
          result.contains(""""name": "interpolate""""),
          result.contains(""""value": "monotone""""),
          result.contains(""""options": ["monotone", "basis", "linear", "step-after"]""")
        )
      }
    ),
    suite("generateSpec delegation")(
      test("generateSpec(curve) produces same output as generateMultiCurveSpec(Vector(curve))") {
        val single = LECChartSpecBuilder.generateSpec(rootCurve)
        val multi  = LECChartSpecBuilder.generateMultiCurveSpec(Vector(rootCurve))
        assertTrue(single == multi)
      }
    )
  )

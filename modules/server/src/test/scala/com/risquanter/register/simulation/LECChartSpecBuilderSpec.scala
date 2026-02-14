package com.risquanter.register.simulation

import zio.test.*
import zio.json.*
import zio.json.ast.Json

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

  /** Realistic 100-tick curve simulating actual simulation output */
  private def make100TickCurve(id: String, name: String, maxLoss: Long): LECNodeCurve = {
    val ticks = LECGenerator.getTicks(1L, maxLoss, 100)
    val points = ticks.zipWithIndex.map { case (loss, i) =>
      // Simulate monotonically decreasing exceedance probability
      val prob = math.max(0.0, 1.0 - i.toDouble / ticks.size)
      LECPoint(loss, prob)
    }
    LECNodeCurve(id, name, points, Map("p50" -> (maxLoss * 0.3).toDouble, "p95" -> (maxLoss * 0.8).toDouble))
  }

  private val realisticRoot = make100TickCurve("root-id", "Portfolio", 5000L)
  private val realisticChild1 = make100TickCurve("child1-id", "Cyber Risk", 3000L)
  private val realisticChild2 = make100TickCurve("child2-id", "Ops Risk", 2000L)

  // ── JSON AST helpers for test assertions ──────────────────────

  /** Parse spec string to JSON AST — tests fail with diagnostic info if parsing fails */
  private def parseSpec(spec: String): Json =
    spec.fromJson[Json] match
      case Right(json) => json
      case Left(err)   => throw new AssertionError(s"Spec is not valid JSON: $err\nSpec: ${spec.take(500)}...")

  /** Navigate a JSON AST by field names (objects) and indices (arrays).
    * Returns `None` if any step fails to resolve.
    *
    * Example: `fieldAt(json, "layer", 0, "encoding", "color", "scale", "domain")`
    */
  private def fieldAt(json: Json, path: (String | Int)*): Option[Json] =
    path.foldLeft(Option(json)) {
      case (Some(Json.Obj(fields)), key: String) => fields.find(_._1 == key).map(_._2)
      case (Some(Json.Arr(elems)), idx: Int)     => elems.lift(idx)
      case _                                      => None
    }

  /** Extract all string values from a JSON array. */
  private def strValues(json: Json): Seq[String] = json match
    case Json.Arr(elems) => elems.collect { case Json.Str(s) => s }.toSeq
    case _               => Seq.empty

  /** Check if a JSON tree contains a specific string value anywhere */
  private def containsStr(json: Json, value: String): Boolean = json match
    case Json.Str(s) => s == value
    case Json.Obj(fields) => fields.exists((_, v) => containsStr(v, value))
    case Json.Arr(elements) => elements.exists(containsStr(_, value))
    case _ => false

  /** Check if a JSON tree contains a specific number value anywhere */
  private def containsNum(json: Json, value: Double): Boolean = json match
    case Json.Num(n) => n.doubleValue() == value
    case Json.Obj(fields) => fields.exists((_, v) => containsNum(v, value))
    case Json.Arr(elements) => elements.exists(containsNum(_, value))
    case _ => false

  // ── Tests ─────────────────────────────────────────────────────

  def spec = suite("LECChartSpecBuilderSpec")(
    suite("empty / degenerate inputs")(
      test("empty curves vector produces empty spec") {
        val result = LECChartSpecBuilder.generateMultiCurveSpec(Vector.empty)
        val json = parseSpec(result)
        assertTrue(
          containsStr(json, "No data available"),
          result.contains("vega-lite/v6")
        )
      },
      test("single curve with empty points produces empty spec") {
        val result = LECChartSpecBuilder.generateMultiCurveSpec(Vector(curveEmptyPoints))
        val json = parseSpec(result)
        assertTrue(containsStr(json, "No data available"))
      }
    ),
    suite("single curve")(
      test("contains Vega-Lite v6 schema") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        assertTrue(result.contains("https://vega.github.io/schema/vega-lite/v6.json"))
      },
      test("contains data values matching input points") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        val json = parseSpec(result)
        assertTrue(
          containsNum(json, 0.0),    // loss: 0
          containsNum(json, 100.0),  // loss: 100
          containsNum(json, 500.0),  // loss: 500
          containsNum(json, 0.65)    // exceedance: 0.65
        )
      },
      test("contains risk name in data values") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        val json = parseSpec(result)
        assertTrue(containsStr(json, "Portfolio"))
      }
    ),
    suite("multi-curve ordering")(
      test("root curve is first in color domain, children sorted alphabetically") {
        // Order: Portfolio (root), then Alpha Risk, Zeta Risk (alphabetical)
        val result = LECChartSpecBuilder.generateMultiCurveSpec(
          Vector(rootCurve, childZeta, childAlpha)
        )
        val json = parseSpec(result)
        // Navigate: last layer → encoding → color → scale → domain
        val layers = fieldAt(json, "layer")
        val lastLayerIdx = layers.collect { case Json.Arr(elems) => elems.size - 1 }.getOrElse(0)
        val domain = fieldAt(json, "layer", lastLayerIdx, "encoding", "color", "scale", "domain")
        val names  = domain.map(strValues).getOrElse(Seq.empty)
        assertTrue(
          names == Seq("Portfolio", "Alpha Risk", "Zeta Risk")
        )
      }
    ),
    suite("quantile annotations")(
      test("P50 and P95 rules rendered when quantiles present on root") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        val json = parseSpec(result)
        assertTrue(
          containsStr(json, "P50"),
          containsStr(json, "P95"),
          containsNum(json, 150.0),
          containsNum(json, 420.0)
        )
      },
      test("no quantile rules when root has empty quantiles") {
        val result = LECChartSpecBuilder.generateSpec(curveNoQuantiles)
        val json = parseSpec(result)
        assertTrue(
          !containsStr(json, "P50"),
          !containsStr(json, "P95")
        )
      },
      test("multi-curve uses root quantiles only, not children") {
        // Root has quantiles, childZeta does not — only root's should appear
        val result = LECChartSpecBuilder.generateMultiCurveSpec(
          Vector(curveNoQuantiles, childAlpha)
        )
        val json = parseSpec(result)
        // curveNoQuantiles is root with no quantiles — childAlpha's quantiles should NOT appear
        assertTrue(
          !containsStr(json, "P50"),
          !containsStr(json, "P95")
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
      test("risk name with quotes and backslash produces valid JSON with correct content") {
        val result = LECChartSpecBuilder.generateSpec(curveWithSpecialChars)
        val json = parseSpec(result)
        // The AST should contain the *unescaped* name as a Str value
        assertTrue(containsStr(json, """Risk "A" \ B"""))
      }
    ),
    suite("params interpolation toggle")(
      test("spec contains params bind for interpolation") {
        val result = LECChartSpecBuilder.generateSpec(rootCurve)
        val json = parseSpec(result)
        assertTrue(
          containsStr(json, "interpolate"),
          containsStr(json, "monotone"),
          containsStr(json, "basis"),
          containsStr(json, "linear"),
          containsStr(json, "step-after")
        )
      }
    ),
    suite("generateSpec delegation")(
      test("generateSpec(curve) produces same output as generateMultiCurveSpec(Vector(curve))") {
        val single = LECChartSpecBuilder.generateSpec(rootCurve)
        val multi  = LECChartSpecBuilder.generateMultiCurveSpec(Vector(rootCurve))
        assertTrue(single == multi)
      }
    ),
    suite("JSON validity")(
      test("single curve with few points produces valid JSON") {
        val spec = LECChartSpecBuilder.generateSpec(rootCurve)
        val parsed = spec.fromJson[Json]
        assertTrue(parsed.isRight)
      },
      test("multi-curve with few points produces valid JSON") {
        val spec = LECChartSpecBuilder.generateMultiCurveSpec(Vector(rootCurve, childAlpha, childZeta))
        val parsed = spec.fromJson[Json]
        assertTrue(parsed.isRight)
      },
      test("realistic 100-tick single curve produces valid JSON") {
        val spec = LECChartSpecBuilder.generateSpec(realisticRoot)
        val parsed = spec.fromJson[Json]
        assertTrue(parsed.isRight) ?? parsed.left.getOrElse("")
      },
      test("realistic 100-tick multi-curve (3 curves) produces valid JSON") {
        val spec = LECChartSpecBuilder.generateMultiCurveSpec(
          Vector(realisticRoot, realisticChild1, realisticChild2)
        )
        val parsed = spec.fromJson[Json]
        assertTrue(parsed.isRight) ?? parsed.left.getOrElse("")
      },
      test("empty spec produces valid JSON") {
        val spec = LECChartSpecBuilder.generateMultiCurveSpec(Vector.empty)
        val parsed = spec.fromJson[Json]
        assertTrue(parsed.isRight)
      },
      test("spec with special characters in name produces valid JSON") {
        val spec = LECChartSpecBuilder.generateSpec(curveWithSpecialChars)
        val parsed = spec.fromJson[Json]
        assertTrue(parsed.isRight)
      }
    ),
    suite("color mapping uses ID keys")(
      test("curves with same name but different IDs get distinct colors") {
        val curveA = LECNodeCurve("id-a", "Duplicate Name", samplePoints, Map.empty)
        val curveB = LECNodeCurve("id-b", "Duplicate Name", samplePoints, Map.empty)
        // This should not throw or produce incorrect colors even with same name
        val result = LECChartSpecBuilder.generateMultiCurveSpec(Vector(curveA, curveB))
        val json = parseSpec(result)
        val colors = LECChartSpecBuilder.themeColorsRisk
        assertTrue(
          result.contains(colors(0)),
          result.contains(colors(1))
        )
      }
    )
  )

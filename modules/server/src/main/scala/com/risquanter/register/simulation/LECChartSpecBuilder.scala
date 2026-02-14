package com.risquanter.register.simulation

import zio.json.EncoderOps
import zio.json.ast.Json
import zio.json.ast.Json.*

import com.risquanter.register.domain.data.{LECPoint, LECNodeCurve}

/** Vega-Lite v6 specification builder for Loss Exceedance Curves.
  *
  * Accepts `LECNodeCurve` — the core curve drawing type (id, name, curve,
  * quantiles). Does not depend on `LECCurveResponse` or its navigation/
  * tracing metadata (childIds, provenances) — those are endpoint envelope
  * concerns, not chart concerns.
  *
  * Generates a self-contained Vega-Lite JSON spec including:
  *   - Multi-curve overlay with BCG colour palette (themeColorsRisk)
  *   - Monotone interpolation (faithful to data points)
  *   - Interactive interpolation toggle via Vega-Lite params bind
  *   - Quantile annotation vertical rules (P50, P95)
  *   - B/M axis formatting (≥ 1 000 M → 1.0B, else 500M)
  *   - Sorting: root curve first, children alphabetically
  *
  * Implementation note: Uses `zio.json.ast.Json` AST for structural
  * JSON construction — eliminates manual escaping and quoting bugs.
  * This is an intermediate step toward a fully typed Vega-Lite DSL
  * (see Phase W.11 in IMPLEMENTATION-PLAN.md).
  */
object LECChartSpecBuilder {
  
  /** BCG color palette for unmitigated risks */
  val themeColorsRisk: Vector[String] = Vector(
    "#60b0f0",  // Light blue
    "#F2A64A",  // Orange
    "#75B56A",  // Green
    "#E1716A",  // Red
    "#6df9ce",  // Cyan
    "#515151",  // Dark gray
    "#838383",  // Light gray
    "#f2a64a",  // Orange (duplicate for more risks)
    "#ab5c0c",  // Brown
    "#350c28"   // Purple-black
  )
  
  // ── JSON AST helpers ──────────────────────────────────────────

  private def str(s: String): Json               = Str(s)
  private def num(n: Double): Json               = Num(java.math.BigDecimal.valueOf(n))
  private def bool(b: Boolean): Json             = Bool(b)
  private def arr(xs: Iterable[Json]): Json      = Arr(zio.Chunk.from(xs))
  private def obj(fields: (String, Json)*): Json = Obj(zio.Chunk.from(fields))

  // ── Vega-Lite annotation helpers ──────────────────────────────

  /** Quantile annotation: a dashed vertical rule + a text label.
    * Returns a pair of Vega-Lite layer objects for the given quantile value.
    */
  private def quantileAnnotation(value: Double, label: String): Seq[Json] =
    val data = obj("values" -> arr(Seq(obj("x" -> num(value)))))
    val xEnc = obj("field" -> str("x"), "type" -> str("quantitative"))
    Seq(
      obj(
        "mark"     -> obj("type" -> str("rule"), "strokeDash" -> arr(Seq(num(4.0), num(4.0))), "color" -> str("#6a8a8e")),
        "data"     -> data,
        "encoding" -> obj("x" -> xEnc)
      ),
      obj(
        "mark"     -> obj("type" -> str("text"), "align" -> str("left"), "dx" -> num(4.0), "dy" -> num(-6.0), "fontSize" -> num(11.0), "color" -> str("#a0b0b0")),
        "data"     -> obj("values" -> arr(Seq(obj("x" -> num(value), "label" -> str(label))))),
        "encoding" -> obj("x" -> xEnc, "text" -> obj("field" -> str("label")))
      )
    )

  // ── Public API ────────────────────────────────────────────────

  /** Generate Vega-Lite JSON specification for single LEC curve
    * 
    * @param curve Single LEC curve to display
    * @param width Diagram width in pixels
    * @param height Diagram height in pixels
    * @return Vega-Lite JSON as string
    */
  def generateSpec(
    curve: LECNodeCurve,
    width: Int = 950,
    height: Int = 400
  ): String = {
    generateMultiCurveSpec(Vector(curve), width, height)
  }
  
  /** Generate Vega-Lite JSON specification for multiple LEC curves
    * 
    * Creates multi-layer diagram with:
    * - One curve per risk node
    * - Shared X-axis (loss) and Y-axis (exceedance probability)
    * - Color-coded by risk name
    * - Smooth interpolation
    * 
    * Flat design (post ADR-004a/005 redesign):
    * - Accepts explicit list of curves (no embedded children)
    * - First curve treated as "root" for color ordering
    * - Remaining curves sorted alphabetically
    * 
    * @param curves LEC curves to display (first is root)
    * @param width Diagram width in pixels
    * @param height Diagram height in pixels
    * @return Vega-Lite JSON as string
    */
  def generateMultiCurveSpec(
    curves: Vector[LECNodeCurve],
    width: Int = 950,
    height: Int = 400
  ): String = {
    // Guard: empty curves or empty data points → empty spec
    val allPoints = curves.flatMap(_.curve)
    if (curves.isEmpty || allPoints.isEmpty) generateEmptySpec(width, height)
    else {
      // Sort: first curve stays first (root), rest alphabetically
      val rootCurve = curves.head
      val sortedChildren = curves.tail.sortBy(_.name)
      val sortedCurves = rootCurve +: sortedChildren
      // Map each curve ID to its theme colour (keyed by id, not full case class)
      val colorById: Map[String, String] = sortedCurves.map(_.id).zip(themeColorsRisk).toMap

      val minLoss = allPoints.map(_.loss).min.toDouble

      // ── Y-axis data-adaptive ceiling ──────────────────────────
      // Fit to the highest starting exceedance across all curves,
      // plus a 10% relative buffer for visual breathing room.
      // X-axis needs no clamping — tick domain is already trimmed
      // by LECGenerator.generateCurvePointsMulti (tail cutoff).
      val yBuffer = 1.1
      val yCeiling: Double = math.min(
        1.0,
        sortedCurves.flatMap(_.curve.headOption).map(_.exceedanceProbability).max * yBuffer
      )

      // Generate data points in Vega-Lite format
      val dataValues: Seq[Json] = sortedCurves.flatMap { curve =>
        curve.curve.map { point =>
          obj(
            "risk"        -> str(curve.name),
            "loss"        -> num(point.loss.toDouble),
            "exceedance"  -> num(point.exceedanceProbability)
          )
        }
      }

      // Sorted risk names for legend order and color domain (root first, then alphabetically)
      val sortedRiskNames: Seq[Json] = sortedCurves.map(c => str(c.name))

      // Color scale matching BCG approach (keyed by id — avoids hashing full case class)
      val colorRange: Seq[Json] = sortedCurves.map(c => str(colorById.getOrElse(c.id, "#000000")))

      // Quantile vertical-rule annotations (P50, P95) for the root curve
      val quantileRuleLayers: Seq[Json] =
        List("p50" -> "P50", "p95" -> "P95").flatMap { case (key, label) =>
          rootCurve.quantiles.get(key).toList.flatMap(quantileAnnotation(_, label))
        }

      // Main line layer
      val lineLayer = obj(
        "data" -> obj("values" -> arr(dataValues)),
        "mark" -> obj(
          "type"        -> str("line"),
          "interpolate" -> obj("expr" -> str("interpolate")),
          "point"       -> bool(false),
          "tooltip"     -> bool(true)
        ),
        "encoding" -> obj(
          "x" -> obj(
            "field" -> str("loss"),
            "type"  -> str("quantitative"),
            "title" -> str("Loss"),
            "axis"  -> obj(
              "labelAngle" -> num(0.0),
              "labelExpr"  -> str("if(datum.value >= 1e3, format(datum.value / 1e3, ',.1f') + 'B', format(datum.value, ',.0f') + 'M')")
            ),
            "scale" -> obj("domainMin" -> num(minLoss))
          ),
          "y" -> obj(
            "field" -> str("exceedance"),
            "type"  -> str("quantitative"),
            "title" -> str("Probability"),
            "axis"  -> obj("format" -> str(".1~%")),
            "scale" -> obj("domain" -> arr(Seq(num(0.0), num(yCeiling))))
          ),
          "color" -> obj(
            "field" -> str("risk"),
            "type"  -> str("nominal"),
            "title" -> str("Risk modelled"),
            "scale" -> obj(
              "domain" -> arr(sortedRiskNames),
              "range"  -> arr(colorRange)
            ),
            "sort" -> arr(sortedRiskNames)
          )
        )
      )

      val allLayers = quantileRuleLayers :+ lineLayer

      val spec = obj(
        "$schema"    -> str("https://vega.github.io/schema/vega-lite/v6.json"),
        "width"      -> num(width),
        "height"     -> num(height),
        "background" -> str("transparent"),
        "config"     -> obj(
          "legend" -> obj(
            "disable"    -> bool(false),
            "labelColor" -> str("#e6e8e8"),
            "titleColor" -> str("#e6e8e8")
          ),
          "axis" -> obj(
            "grid"        -> bool(true),
            "gridColor"   -> str("#2a3a3e"),
            "labelColor"  -> str("#b0b8b8"),
            "titleColor"  -> str("#e6e8e8"),
            "domainColor" -> str("#4a5a5e"),
            "tickColor"   -> str("#4a5a5e")
          ),
          "title" -> obj("color" -> str("#e6e8e8"))
        ),
        "params" -> arr(Seq(
          obj(
            "name"  -> str("interpolate"),
            "value" -> str("monotone"),
            "bind"  -> obj(
              "input"   -> str("select"),
              "options" -> arr(Seq(str("monotone"), str("basis"), str("linear"), str("step-after"))),
              "name"    -> str("Interpolation: ")
            )
          )
        )),
        "layer" -> arr(allLayers)
      )

      spec.toJson
    }
  }
  
  /** Generate empty spec when no data available */
  private def generateEmptySpec(width: Int, height: Int): String = {
    val spec = obj(
      "$schema"    -> str("https://vega.github.io/schema/vega-lite/v6.json"),
      "width"      -> num(width),
      "height"     -> num(height),
      "background" -> str("transparent"),
      "data"       -> obj("values" -> arr(Seq.empty)),
      "mark"       -> obj("type" -> str("text"), "color" -> str("#b0b8b8")),
      "encoding"   -> obj("text" -> obj("value" -> str("No data available")))
    )
    spec.toJson
  }
}

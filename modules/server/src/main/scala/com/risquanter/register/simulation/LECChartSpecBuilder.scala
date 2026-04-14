package com.risquanter.register.simulation

import zio.json.EncoderOps
import zio.json.ast.Json
import zio.json.ast.Json.*

import com.risquanter.register.domain.data.{LECPoint, LECNodeCurve}
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** Vega-Lite v6 specification builder for Loss Exceedance Curves.
  *
  * Accepts `ColouredCurve` — domain curve data paired with a resolved
  * hex colour. Does not depend on `LECCurveResponse` or its navigation/
  * tracing metadata (provenances) — those are endpoint envelope
  * concerns, not chart concerns.
  *
  * Generates a self-contained Vega-Lite JSON spec including:
  *   - Multi-curve overlay with palette-assigned colours
  *   - Monotone interpolation (faithful to data points)
  *   - Interactive interpolation toggle via Vega-Lite params bind
  *   - Quantile annotation vertical rules (P50, P95)
  *   - B/M axis formatting (≥ 1 000 M → 1.0B, else 500M)
  *   - Input order preserved (p95-sorted by `assignPaletteColours`)
  *
  * Implementation note: Uses `zio.json.ast.Json` AST for structural
  * JSON construction — eliminates manual escaping and quoting bugs.
  * This is an intermediate step toward a fully typed Vega-Lite DSL
  * (see Phase W.11 in IMPLEMENTATION-PLAN.md).
  */
object LECChartSpecBuilder {
  
  
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

  /** Generate Vega-Lite JSON specification for multiple LEC curves
    * 
    * Creates multi-layer diagram with:
    * - One curve per risk node
    * - Shared X-axis (loss) and Y-axis (exceedance probability)
    * - Colour-coded by resolved hex colour (from `ColouredCurve`)
    * - Smooth interpolation
    * 
    * Input order is preserved (caller is responsible for sorting —
    * typically p95-sorted by palette group via `assignPaletteColours`).
    * First curve's quantiles are used for P50/P95 vertical annotations.
    * 
    * @param coloured LEC curves with resolved hex colours (first is root)
    * @param width Diagram width in pixels
    * @param height Diagram height in pixels
    * @return Vega-Lite JSON as string
    */
  def generateMultiCurveSpec(
    coloured: Vector[ColouredCurve],
    width: Int = 950,
    height: Int = 400
  ): String = {
    // Guard: empty curves or empty data points → empty spec
    val allPoints = coloured.flatMap(_.curve.curve)
    if (coloured.isEmpty || allPoints.isEmpty) generateEmptySpec(width, height)
    else {
      // Preserve input order — caller (assignPaletteColours) handles sorting
      val sortedColoured = coloured

      val minLoss = allPoints.map(_.loss).min.toDouble

      // ── Y-axis data-adaptive ceiling ──────────────────────────
      val yBuffer = 1.1
      val yCeiling: Double = math.min(
        1.0,
        sortedColoured.flatMap(_.curve.curve.headOption).map(_.exceedanceProbability).max * yBuffer
      )

      // Generate data points — keyed by curve ID for stable colour binding
      val dataValues: Seq[Json] = sortedColoured.flatMap { cc =>
        cc.curve.curve.map { point =>
          obj(
            "curveId"     -> str(cc.curve.id),
            "risk"        -> str(cc.curve.name),
            "loss"        -> num(point.loss.toDouble),
            "exceedance"  -> num(point.exceedanceProbability)
          )
        }
      }

      // ID→name lookup expression for Vega-Lite legend labels.
      val idToNamePairs = sortedColoured.map(cc => s"datum.value == '${cc.curve.id}' ? '${cc.curve.name}'")
      val legendLabelExpr = (idToNamePairs :+ "datum.value").mkString(" : ")

      // Colour domain/range — hex colours from ColouredCurve, .value extracted here at the Vega-Lite JSON edge
      val colorDomain: Seq[Json] = sortedColoured.map(cc => str(cc.curve.id))
      val colorRange: Seq[Json]  = sortedColoured.map(cc => str(cc.hexColor.value))

      // Quantile vertical-rule annotations (P50, P95) for the first curve (root)
      val rootCurve = sortedColoured.head.curve
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
            "scale" -> obj(
              "domainMin" -> num(minLoss)
            )
          ),
          "y" -> obj(
            "field" -> str("exceedance"),
            "type"  -> str("quantitative"),
            "title" -> str("Probability"),
            "axis"  -> obj("format" -> str(".1~%")),
            "scale" -> obj("domain" -> arr(Seq(num(0.0), num(yCeiling))))
          ),
          "color" -> obj(
            "field" -> str("curveId"),
            "type"  -> str("nominal"),
            "title" -> str("Risk modelled"),
            "scale" -> obj(
              "domain" -> arr(colorDomain),
              "range"  -> arr(colorRange)
            ),
            "legend" -> obj(
              "labelExpr" -> str(legendLabelExpr)
            )
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

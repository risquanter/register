package app.chart

import scala.scalajs.js

import com.risquanter.register.domain.data.{LECNodeCurve, LECPoint}
import com.risquanter.register.domain.data.iron.NodeId

/** Client-side Vega-Lite v6 specification builder for LEC curves.
  *
  * Produces a `js.Dynamic` spec ready for `vegaEmbed`. Structure mirrors
  * the archived server-side `LECChartSpecBuilder` with these additions:
  *   - Hover selection param (`"hover"`) for pointer-based curve highlight
  *   - Invisible point layer for voronoi-based nearest-point detection
  *   - Opacity condition: hovered curve = 1.0, others = 0.3
  *
  * Shared structural elements (preserved from archived builder):
  *   - Data values shape: `{curveId, risk, loss, exceedance}`
  *   - Colour encoding: `scale.domain` (curveIds) + `scale.range` (hex colours)
  *   - Quantile annotations: dashed vertical rules + labels for P50/P95
  *   - Axes: B/M formatting on X, percentage on Y
  *   - Dark theme config (transparent background, light-on-dark text)
  *   - Legend with `labelExpr` mapping curveId → node name
  *   - Interpolation toggle param (monotone/basis/linear/step-after)
  *   - Y-axis adaptive ceiling (capped at 1.0)
  */
object LECSpecBuilder:

  /** Build a Vega-Lite spec from structured curve data and a colour map.
    *
    * @param curves       Map of nodeId → LECNodeCurve (from `lec-multi` endpoint)
    * @param colorMap     Map of nodeId → hex colour string (from ColorAssigner)
    * @param interpolation Initial interpolation mode (default: "monotone")
    * @param width        Chart width in pixels
    * @param height       Chart height in pixels
    * @return Vega-Lite spec as `js.Dynamic`, ready for `vegaEmbed`
    */
  def build(
    curves: Map[NodeId, LECNodeCurve],
    colorMap: Map[NodeId, String],
    interpolation: String = "monotone",
    width: Int = 950,
    height: Int = 400
  ): js.Dynamic =
    val allPoints = curves.values.flatMap(_.curve)
    if curves.isEmpty || allPoints.isEmpty then emptySpec(width, height)
    else buildSpec(curves, colorMap, interpolation, width, height)

  // ── Private builders ──────────────────────────────────────────

  private def buildSpec(
    curves: Map[NodeId, LECNodeCurve],
    colorMap: Map[NodeId, String],
    interpolation: String,
    width: Int,
    height: Int
  ): js.Dynamic =
    // Stable ordering: sort by curveId for deterministic domain/range
    val ordered = curves.toVector.sortBy(_._2.id)

    val allPoints = ordered.flatMap(_._2.curve)
    val minLoss = allPoints.map(_.loss).min.toDouble

    // Y-axis adaptive ceiling (capped at 1.0)
    val yBuffer = 1.1
    val yCeiling = math.min(
      1.0,
      ordered.flatMap(_._2.curve.headOption).map(_.exceedanceProbability).max * yBuffer
    )

    // Data values
    val dataValues = js.Array[js.Any]()
    ordered.foreach { case (_, nc) =>
      nc.curve.foreach { pt =>
        dataValues.push(js.Dynamic.literal(
          "curveId"    -> nc.id,
          "risk"       -> nc.name,
          "loss"       -> pt.loss.toDouble,
          "exceedance" -> pt.exceedanceProbability
        ))
      }
    }

    // Colour domain/range
    val colorDomain = js.Array[js.Any]()
    val colorRange = js.Array[js.Any]()
    ordered.foreach { case (nid, nc) =>
      colorDomain.push(nc.id)
      colorRange.push(colorMap.getOrElse(nid, "#888888"))
    }

    // Legend labelExpr: map curveId → display name
    val labelParts = ordered.map { case (_, nc) =>
      s"datum.value == '${nc.id}' ? '${nc.name.replace("'", "\\'")}'"
    }
    val legendLabelExpr = (labelParts :+ "datum.value").mkString(" : ")

    // Quantile annotations (from first curve)
    val rootCurve = ordered.head._2
    val quantileLayers = js.Array[js.Any]()
    List("p50" -> "P50", "p95" -> "P95").foreach { case (key, label) =>
      rootCurve.quantiles.get(key).foreach { value =>
        quantileAnnotation(value, label).foreach(quantileLayers.push(_))
      }
    }

    // Colour encoding (shared between line and point layers)
    val colorEncoding = js.Dynamic.literal(
      "field" -> "curveId",
      "type"  -> "nominal",
      "title" -> "Risk modelled",
      "scale" -> js.Dynamic.literal(
        "domain" -> colorDomain,
        "range"  -> colorRange
      ),
      "legend" -> js.Dynamic.literal(
        "labelExpr" -> legendLabelExpr
      )
    )

    // Main line layer with opacity condition for hover
    val lineLayer = js.Dynamic.literal(
      "data" -> js.Dynamic.literal("values" -> dataValues),
      "mark" -> js.Dynamic.literal(
        "type"        -> "line",
        "interpolate" -> js.Dynamic.literal("expr" -> "interpolate"),
        "point"       -> false,
        "tooltip"     -> true
      ),
      "encoding" -> js.Dynamic.literal(
        "x" -> js.Dynamic.literal(
          "field" -> "loss",
          "type"  -> "quantitative",
          "title" -> "Loss",
          "axis"  -> js.Dynamic.literal(
            "labelAngle" -> 0,
            "labelExpr"  -> "if(datum.value >= 1e3, format(datum.value / 1e3, ',.1f') + 'B', format(datum.value, ',.0f') + 'M')"
          ),
          "scale" -> js.Dynamic.literal(
            "domainMin" -> minLoss
          )
        ),
        "y" -> js.Dynamic.literal(
          "field" -> "exceedance",
          "type"  -> "quantitative",
          "title" -> "Probability",
          "axis"  -> js.Dynamic.literal("format" -> ".1~%"),
          "scale" -> js.Dynamic.literal("domain" -> js.Array(0.0, yCeiling))
        ),
        "color"   -> colorEncoding,
        "opacity" -> js.Dynamic.literal(
          "condition" -> js.Dynamic.literal(
            "param" -> "hover",
            "value" -> 1.0
          ),
          "value" -> 0.3
        )
      )
    )

    // Invisible point layer for voronoi-based nearest-point hover detection
    val pointLayer = js.Dynamic.literal(
      "data" -> js.Dynamic.literal("values" -> dataValues),
      "mark" -> js.Dynamic.literal(
        "type"    -> "point",
        "opacity" -> 0,
        "tooltip" -> true
      ),
      "encoding" -> js.Dynamic.literal(
        "x" -> js.Dynamic.literal(
          "field" -> "loss",
          "type"  -> "quantitative"
        ),
        "y" -> js.Dynamic.literal(
          "field" -> "exceedance",
          "type"  -> "quantitative"
        ),
        "color" -> colorEncoding
      ),
      "params" -> js.Array(
        js.Dynamic.literal(
          "name"   -> "hover",
          "select" -> js.Dynamic.literal(
            "type"    -> "point",
            "on"      -> "pointerover",
            "nearest" -> true,
            "fields"  -> js.Array("curveId")
          )
        )
      )
    )

    // Assemble layers: quantile annotations + line layer + point layer
    val allLayers = js.Array[js.Any]()
    for i <- 0 until quantileLayers.length do allLayers.push(quantileLayers(i))
    allLayers.push(lineLayer)
    allLayers.push(pointLayer)

    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> width,
      "height"     -> height,
      "background" -> "transparent",
      "config"     -> js.Dynamic.literal(
        "legend" -> js.Dynamic.literal(
          "disable"    -> false,
          "labelColor" -> "#e6e8e8",
          "titleColor" -> "#e6e8e8"
        ),
        "axis" -> js.Dynamic.literal(
          "grid"        -> true,
          "gridColor"   -> "#1c2225",
          "labelColor"  -> "#b0b8b8",
          "titleColor"  -> "#e6e8e8",
          "domainColor" -> "#4a5a5e",
          "tickColor"   -> "#4a5a5e"
        ),
        "title" -> js.Dynamic.literal("color" -> "#e6e8e8")
      ),
      "params" -> js.Array(
        js.Dynamic.literal(
          "name"  -> "interpolate",
          "value" -> interpolation,
          "bind"  -> js.Dynamic.literal(
            "input"   -> "select",
            "options" -> js.Array("monotone", "basis", "linear", "step-after"),
            "name"    -> "Interpolation: "
          )
        )
      ),
      "layer" -> allLayers
    )

  // ── Quantile annotation helpers ───────────────────────────────

  private def quantileAnnotation(value: Double, label: String): Seq[js.Dynamic] =
    val data = js.Dynamic.literal(
      "values" -> js.Array(js.Dynamic.literal("x" -> value))
    )
    val xEnc = js.Dynamic.literal("field" -> "x", "type" -> "quantitative")
    Seq(
      js.Dynamic.literal(
        "mark"     -> js.Dynamic.literal("type" -> "rule", "strokeDash" -> js.Array(4, 4), "color" -> "#6a8a8e"),
        "data"     -> data,
        "encoding" -> js.Dynamic.literal("x" -> xEnc)
      ),
      js.Dynamic.literal(
        "mark"     -> js.Dynamic.literal("type" -> "text", "align" -> "left", "dx" -> 4, "dy" -> -6, "fontSize" -> 11, "color" -> "#a0b0b0"),
        "data"     -> js.Dynamic.literal("values" -> js.Array(js.Dynamic.literal("x" -> value, "label" -> label))),
        "encoding" -> js.Dynamic.literal("x" -> xEnc, "text" -> js.Dynamic.literal("field" -> "label"))
      )
    )

  // ── Empty spec ────────────────────────────────────────────────

  private def emptySpec(width: Int, height: Int): js.Dynamic =
    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> width,
      "height"     -> height,
      "background" -> "transparent",
      "data"       -> js.Dynamic.literal("values" -> js.Array[js.Any]()),
      "mark"       -> js.Dynamic.literal("type" -> "text", "color" -> "#b0b8b8"),
      "encoding"   -> js.Dynamic.literal("text" -> js.Dynamic.literal("value" -> "No data available"))
    )

package com.risquanter.register.simulation

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
      val colorMapping = sortedCurves.zip(themeColorsRisk).toMap

      val minLoss = allPoints.map(_.loss).min

      // Generate data points in Vega-Lite format
      val dataValues = sortedCurves.flatMap { curve =>
        curve.curve.map { point =>
          s"""{
          "risk": "${escapeJson(curve.name)}",
          "loss": ${point.loss},
          "exceedance": ${point.exceedanceProbability}
        }"""
        }
      }.mkString(",\n        ")

      // Sorted risk names for legend order and color domain (root first, then alphabetically)
      val sortedRiskNames = sortedCurves.map(c => s""""${escapeJson(c.name)}""""").mkString(", ")

      // Color scale matching BCG approach
      val colorRange = sortedCurves.map(c => s""""${colorMapping.getOrElse(c, "#000000")}"""").mkString(", ")

      // Quantile vertical-rule annotations (P50, P95) for the root curve
      val quantileKeys = List("p50" -> "P50", "p95" -> "P95")
      val quantileRuleLayers = {
        val entries = quantileKeys.flatMap { case (key, label) =>
          rootCurve.quantiles.get(key).map { value =>
            val lbl = escapeJson(label)
            s"""    {
      "mark": {"type": "rule", "strokeDash": [4, 4], "color": "#888888"},
      "data": {"values": [{"x": $value}]},
      "encoding": {
        "x": {"field": "x", "type": "quantitative"}
      }
    },
    {
      "mark": {"type": "text", "align": "left", "dx": 4, "dy": -6, "fontSize": 11, "color": "#666666"},
      "data": {"values": [{"x": $value, "label": "$lbl"}]},
      "encoding": {
        "x": {"field": "x", "type": "quantitative"},
        "text": {"field": "label"}
      }
    }"""
          }
        }
        if entries.nonEmpty then entries.mkString(",\n") + "," else ""
      }

      s"""{
  "$$schema": "https://vega.github.io/schema/vega-lite/v6.json",
  "width": $width,
  "height": $height,
  "config": {
    "legend": { "disable": false },
    "axis": {
      "grid": true,
      "gridColor": "#dadada"
    }
  },
  "params": [
    {
      "name": "interpolate",
      "value": "monotone",
      "bind": {
        "input": "select",
        "options": ["monotone", "basis", "linear", "step-after"],
        "name": "Interpolation: "
      }
    }
  ],
  "layer": [
$quantileRuleLayers
    {
      "data": {
        "values": [
            $dataValues
        ]
      },
      "mark": {
        "type": "line",
        "interpolate": {"expr": "interpolate"},
        "point": false,
        "tooltip": true
      },
      "encoding": {
        "x": {
          "field": "loss",
          "type": "quantitative",
          "title": "Loss",
          "axis": {
            "labelAngle": 0,
            "labelExpr": "if(datum.value >= 1e3, format(datum.value / 1e3, ',.1f') + 'B', format(datum.value, ',.0f') + 'M')"
          },
          "scale": {
            "domainMin": $minLoss
          }
        },
        "y": {
          "field": "exceedance",
          "type": "quantitative",
          "title": "Probability",
          "axis": {
            "format": ".1~%"
          },
          "scale": {
            "domain": [0, 1]
          }
        },
        "color": {
          "field": "risk",
          "type": "nominal",
          "title": "Risk modelled",
          "scale": {
            "domain": [$sortedRiskNames],
            "range": [$colorRange]
          },
          "sort": [$sortedRiskNames]
        }
      }
    }
  ]
}"""
    }
  }
  
  /** Generate empty spec when no data available */
  private def generateEmptySpec(width: Int, height: Int): String = {
    s"""{
  "$$schema": "https://vega.github.io/schema/vega-lite/v6.json",
  "width": $width,
  "height": $height,
  "data": { "values": [] },
  "mark": "text",
  "encoding": {
    "text": { "value": "No data available" }
  }
}"""
  }
  
  /** Escape JSON special characters in strings */
  private def escapeJson(str: String): String = {
    str
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
  }
}

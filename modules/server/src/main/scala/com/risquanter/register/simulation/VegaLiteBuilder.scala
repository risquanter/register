package com.risquanter.register.simulation

import com.risquanter.register.domain.data.{LECPoint, LECCurveResponse}

/** Vega-Lite diagram generator for Loss Exceedance Curves
  * 
  * Preserves BCG implementation:
  * - Color palette: blues/oranges for risks (themeColorsRisk)
  * - Interpolation: "basis" for smooth B-spline curves
  * - Axis formatting: Custom expressions for billions/millions
  * - Sorting: Aggregate first, children alphabetically
  * 
  * Future client interaction (not implemented yet):
  * - Click-select curves to highlight
  * - Double-click portfolio nodes to expand/collapse
  * - Tooltip showing exact values on hover
  */
object VegaLiteBuilder {
  
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
    curve: LECCurveResponse,
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
    curves: Vector[LECCurveResponse],
    width: Int = 950,
    height: Int = 400
  ): String = {
    if (curves.isEmpty) {
      return generateEmptySpec(width, height)
    }
    
    // Sort: first curve stays first (root), rest alphabetically
    val rootCurve = curves.head
    val sortedChildren = curves.tail.sortBy(_.name)
    val sortedCurves = rootCurve +: sortedChildren
    val colorMapping = sortedCurves.zip(themeColorsRisk).toMap
    
    // Find global min/max loss for shared axis
    val allPoints = curves.flatMap(_.curve)
    if (allPoints.isEmpty) {
      return generateEmptySpec(width, height)
    }
    
    val minLoss = allPoints.map(_.loss).min
    val maxLoss = allPoints.map(_.loss).max
    
    // Generate data points in Vega-Lite format
    val dataValues = curves.flatMap { curve =>
      curve.curve.map { point =>
        s"""{
          "risk": "${escapeJson(curve.name)}",
          "loss": ${point.loss},
          "exceedance": ${point.exceedanceProbability}
        }"""
      }
    }.mkString(",\n        ")
    
    // Sorted risk names for legend order (root first, then alphabetically)
    val sortedRiskNames = sortedCurves.map(c => s""""${escapeJson(c.name)}"""").mkString(", ")
    
    // Color scale matching BCG approach
    val colorDomain = sortedCurves.map(c => s""""${escapeJson(c.name)}"""").mkString(", ")
    val colorRange = sortedCurves.map(c => s""""${colorMapping.getOrElse(c, "#000000")}"""").mkString(", ")
    
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
  "data": {
    "values": [
        $dataValues
    ]
  },
  "mark": {
    "type": "line",
    "interpolate": "basis",
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
        "labelExpr": "if (toNumber(datum.value) >= 1e9, format(toNumber(datum.value)/1e9, ',.0f') + 'B', format(datum.value, '.2s'))"
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
        "domain": [$colorDomain],
        "range": [$colorRange]
      },
      "sort": [$sortedRiskNames]
    }
  }
}"""
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

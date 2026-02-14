package app.views

// ============================================================================
// THROWAWAY — remove this file by Phase F completion at the latest.
//
// Provides a hardcoded Vega-Lite v6 spec that mimics the shape produced by
// LECChartSpecBuilder on the server.  Used solely to validate that
// LECChartView + VegaEmbed rendering works before the selection→fetch
// pipeline is wired in Phase F.
// ============================================================================

object LECChartTestSpec:

  /** A minimal multi-curve LEC spec with two risks and quantile rules. */
  val sampleSpec: String =
    """{
  "$schema": "https://vega.github.io/schema/vega-lite/v6.json",
  "width": 600,
  "height": 350,
  "config": {
    "legend": { "disable": false },
    "axis": { "grid": true, "gridColor": "#dadada" }
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
    {
      "mark": {"type": "rule", "strokeDash": [4, 4], "color": "#888888"},
      "data": {"values": [{"x": 150}]},
      "encoding": { "x": {"field": "x", "type": "quantitative"} }
    },
    {
      "mark": {"type": "text", "align": "left", "dx": 4, "dy": -6, "fontSize": 11, "color": "#666666"},
      "data": {"values": [{"x": 150, "label": "P50"}]},
      "encoding": {
        "x": {"field": "x", "type": "quantitative"},
        "text": {"field": "label"}
      }
    },
    {
      "mark": {"type": "rule", "strokeDash": [4, 4], "color": "#888888"},
      "data": {"values": [{"x": 420}]},
      "encoding": { "x": {"field": "x", "type": "quantitative"} }
    },
    {
      "mark": {"type": "text", "align": "left", "dx": 4, "dy": -6, "fontSize": 11, "color": "#666666"},
      "data": {"values": [{"x": 420, "label": "P95"}]},
      "encoding": {
        "x": {"field": "x", "type": "quantitative"},
        "text": {"field": "label"}
      }
    },
    {
      "data": {
        "values": [
          {"risk": "Portfolio", "loss": 0,   "exceedance": 1.0},
          {"risk": "Portfolio", "loss": 50,  "exceedance": 0.85},
          {"risk": "Portfolio", "loss": 100, "exceedance": 0.65},
          {"risk": "Portfolio", "loss": 150, "exceedance": 0.50},
          {"risk": "Portfolio", "loss": 200, "exceedance": 0.35},
          {"risk": "Portfolio", "loss": 300, "exceedance": 0.18},
          {"risk": "Portfolio", "loss": 420, "exceedance": 0.05},
          {"risk": "Portfolio", "loss": 500, "exceedance": 0.02},
          {"risk": "Cyber Risk", "loss": 0,   "exceedance": 1.0},
          {"risk": "Cyber Risk", "loss": 30,  "exceedance": 0.80},
          {"risk": "Cyber Risk", "loss": 80,  "exceedance": 0.55},
          {"risk": "Cyber Risk", "loss": 120, "exceedance": 0.35},
          {"risk": "Cyber Risk", "loss": 180, "exceedance": 0.20},
          {"risk": "Cyber Risk", "loss": 250, "exceedance": 0.08},
          {"risk": "Cyber Risk", "loss": 320, "exceedance": 0.03},
          {"risk": "Cyber Risk", "loss": 400, "exceedance": 0.01}
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
          "scale": { "domainMin": 0 }
        },
        "y": {
          "field": "exceedance",
          "type": "quantitative",
          "title": "Probability",
          "axis": { "format": ".1~%" },
          "scale": { "domain": [0, 1] }
        },
        "color": {
          "field": "risk",
          "type": "nominal",
          "title": "Risk modelled",
          "scale": {
            "domain": ["Portfolio", "Cyber Risk"],
            "range": ["#1b9e77", "#d95f02"]
          },
          "sort": ["Portfolio", "Cyber Risk"]
        }
      }
    }
  ]
}"""

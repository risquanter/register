package app.chart

import scala.scalajs.js

import app.state.{DistributionViewMode, LeafDistributionDraft}
import com.risquanter.register.http.requests.{DistributionPreviewPoint, DistributionPreviewResponse}

/** Client-side Vega-Lite v6 specification builder for distribution preview charts.
  *
  * Produces `js.Dynamic` specs ready for `vegaEmbed` for both PDF and CDF views.
  * Dark theme config mirrors [[LECSpecBuilder]] exactly.
  *
  * The `draft` parameter supplies the original anchor points (percentiles/quantiles
  * for expert mode, minLoss/maxLoss for lognormal) used to draw overlay annotations.
  */
object DistributionSpecBuilder:

  def build(
    response: DistributionPreviewResponse,
    viewMode: DistributionViewMode,
    draft:    Option[LeafDistributionDraft] = None,
    width:    Int = 950,
    height:   Int = 300
  ): js.Dynamic =
    if response.points.isEmpty then emptySpec(width, height)
    else viewMode match
      case DistributionViewMode.PDF => buildPdfSpec(response, draft, width, height)
      case DistributionViewMode.CDF => buildCdfSpec(response, draft, width, height)

  // ── Private builders ──────────────────────────────────────────

  private def buildPdfSpec(
    response: DistributionPreviewResponse,
    draft:    Option[LeafDistributionDraft],
    width:    Int,
    height:   Int
  ): js.Dynamic =
    val dataValues = makeDataValues(response.points)

    val mainLayer = js.Dynamic.literal(
      "mark" -> js.Dynamic.literal(
        "type"    -> "area",
        "color"   -> "#4a8a8e",
        "opacity" -> 0.7
      ),
      "encoding" -> js.Dynamic.literal(
        "x" -> xEncoding("Loss"),
        "y" -> js.Dynamic.literal(
          "field" -> "pdf",
          "type"  -> "quantitative",
          "title" -> "Density",
          "axis"  -> js.Dynamic.literal("labelColor" -> "#b0b8b8", "titleColor" -> "#e6e8e8")
        )
      )
    )

    val annotationLayers = anchorAnnotations(response, draft, DistributionViewMode.PDF)

    val allLayers = js.Array[js.Any]()
    allLayers.push(mainLayer)
    for i <- 0 until annotationLayers.length do allLayers.push(annotationLayers(i))

    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> width,
      "height"     -> height,
      "background" -> "transparent",
      "autosize"   -> "fit",
      "data"       -> js.Dynamic.literal("values" -> dataValues),
      "layer"      -> allLayers,
      "config"     -> darkConfig
    )

  private def buildCdfSpec(
    response: DistributionPreviewResponse,
    draft:    Option[LeafDistributionDraft],
    width:    Int,
    height:   Int
  ): js.Dynamic =
    val dataValues = makeDataValues(response.points)

    val mainLayer = js.Dynamic.literal(
      "mark" -> js.Dynamic.literal(
        "type"        -> "line",
        "interpolate" -> "monotone",
        "color"       -> "#4a8a8e"
      ),
      "encoding" -> js.Dynamic.literal(
        "x" -> xEncoding("Loss"),
        "y" -> js.Dynamic.literal(
          "field"  -> "cdf",
          "type"   -> "quantitative",
          "title"  -> "Probability",
          "axis"   -> js.Dynamic.literal(
            "format"     -> ".1~%",
            "labelColor" -> "#b0b8b8",
            "titleColor" -> "#e6e8e8"
          ),
          "scale"  -> js.Dynamic.literal("domain" -> js.Array(0.0, 1.0))
        )
      )
    )

    val annotationLayers = anchorAnnotations(response, draft, DistributionViewMode.CDF)

    val allLayers = js.Array[js.Any]()
    allLayers.push(mainLayer)
    for i <- 0 until annotationLayers.length do allLayers.push(annotationLayers(i))

    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> width,
      "height"     -> height,
      "background" -> "transparent",
      "autosize"   -> "fit",
      "data"       -> js.Dynamic.literal("values" -> dataValues),
      "layer"      -> allLayers,
      "config"     -> darkConfig
    )

  private def emptySpec(width: Int, height: Int): js.Dynamic =
    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> width,
      "height"     -> height,
      "background" -> "transparent",
      "data"       -> js.Dynamic.literal("values" -> js.Array[js.Any]()),
      "mark"       -> js.Dynamic.literal(
        "type"    -> "text",
        "color"   -> "#b0b8b8",
        "fontSize"-> 14
      ),
      "encoding"   -> js.Dynamic.literal(
        "text" -> js.Dynamic.literal("value" -> "Enter distribution parameters to see a preview")
      ),
      "config"     -> darkConfig
    )

  // ── Data helpers ──────────────────────────────────────────────

  private def makeDataValues(points: Array[DistributionPreviewPoint]): js.Array[js.Any] =
    val arr = js.Array[js.Any]()
    points.foreach { pt =>
      arr.push(js.Dynamic.literal("x" -> pt.x, "pdf" -> pt.pdf, "cdf" -> pt.cdf))
    }
    arr

  // ── Anchor overlay builders ───────────────────────────────────

  /** Build anchor overlay layers for both PDF and CDF views. */
  private def anchorAnnotations(
    response: DistributionPreviewResponse,
    draft:    Option[LeafDistributionDraft],
    viewMode: DistributionViewMode
  ): js.Array[js.Any] =
    val layers = js.Array[js.Any]()
    draft.foreach { d =>
      if d.distributionType == "expert" then
        // Expert mode: vertical rules at each input quantile x-position
        val pcts   = d.percentiles.getOrElse(Array.empty[Double])
        val quants = d.quantiles.getOrElse(Array.empty[Double])
        pcts.zip(quants).foreach { case (p, q) =>
          val label = f"P${(p * 100).round}%d"
          viewMode match
            case DistributionViewMode.PDF =>
              // Vertical dashed rule + text label
              ruleAnnotation(q, label).foreach(layers.push(_))
            case DistributionViewMode.CDF =>
              // Filled dot at (quantile_x, percentile) for exact-fit verification
              layers.push(cdfAnchorDot(q, p))
        }
      else
        // Lognormal: rules at minLoss (P05) and maxLoss (P95) — same in both views
        d.minLoss.foreach { min => ruleAnnotation(min.toDouble, "P05").foreach(layers.push(_)) }
        d.maxLoss.foreach { max => ruleAnnotation(max.toDouble, "P95").foreach(layers.push(_)) }
    }
    layers

  /** Dashed vertical rule + text label at x = `value`. */
  private def ruleAnnotation(value: Double, label: String): Seq[js.Dynamic] =
    val data = js.Dynamic.literal(
      "values" -> js.Array(js.Dynamic.literal("x" -> value))
    )
    val xEnc = js.Dynamic.literal("field" -> "x", "type" -> "quantitative")
    Seq(
      js.Dynamic.literal(
        "data"     -> data,
        "mark"     -> js.Dynamic.literal(
          "type"        -> "rule",
          "strokeDash"  -> js.Array(4, 4),
          "color"       -> "#6a8a8e"
        ),
        "encoding" -> js.Dynamic.literal("x" -> xEnc)
      ),
      js.Dynamic.literal(
        "data"     -> data,
        "mark"     -> js.Dynamic.literal(
          "type"     -> "text",
          "color"    -> "#a0b0b0",
          "fontSize" -> 11,
          "dx"       -> 4,
          "dy"       -> -6,
          "align"    -> "left"
        ),
        "encoding" -> js.Dynamic.literal(
          "x"    -> xEnc,
          "text" -> js.Dynamic.literal("value" -> label)
        )
      )
    )

  /** Filled dot at (quantile_x, cdf_probability) for CDF anchor verification. */
  private def cdfAnchorDot(quantileX: Double, probability: Double): js.Dynamic =
    js.Dynamic.literal(
      "data"     -> js.Dynamic.literal(
        "values" -> js.Array(js.Dynamic.literal("x" -> quantileX, "cdf" -> probability))
      ),
      "mark"     -> js.Dynamic.literal(
        "type"   -> "point",
        "filled" -> true,
        "color"  -> "#e6a35a",
        "size"   -> 60
      ),
      "encoding" -> js.Dynamic.literal(
        "x" -> js.Dynamic.literal("field" -> "x",   "type" -> "quantitative"),
        "y" -> js.Dynamic.literal("field" -> "cdf", "type" -> "quantitative")
      )
    )

  // ── Shared encoding ───────────────────────────────────────────

  private def xEncoding(title: String): js.Dynamic =
    js.Dynamic.literal(
      "field" -> "x",
      "type"  -> "quantitative",
      "title" -> title,
      "axis"  -> js.Dynamic.literal(
        "labelAngle" -> 0,
        "labelColor" -> "#b0b8b8",
        "titleColor" -> "#e6e8e8",
        "labelExpr"  -> "if(datum.value >= 1e3, format(datum.value / 1e3, ',.1f') + 'B', format(datum.value, ',.0f') + 'M')"
      )
    )

  // ── Dark theme config (verbatim from LECSpecBuilder) ─────────

  private def darkConfig: js.Dynamic =
    js.Dynamic.literal(
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
    )

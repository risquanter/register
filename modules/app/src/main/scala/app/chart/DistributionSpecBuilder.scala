package app.chart

import scala.scalajs.js

import app.state.DistributionViewMode
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.data.Distribution
import com.risquanter.register.domain.data.iron.IronConstants
import com.risquanter.register.http.requests.{DistributionPreviewPoint, DistributionPreviewResponse}

/** Client-side Vega-Lite v6 specification builder for distribution preview charts.
  *
  * Produces `js.Dynamic` specs ready for `vegaEmbed` for both PDF and CDF views.
  * Dark theme config comes from [[VegaSpecShared]], shared with the LEC chart.
  *
  * The `draft` parameter supplies the original anchor points (percentiles/quantiles
  * for expert mode, minLoss/maxLoss for lognormal) used to draw overlay annotations.
  */
object DistributionSpecBuilder:

  def build(
    response: DistributionPreviewResponse,
    viewMode: DistributionViewMode,
    draft:    Option[Distribution] = None,
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
    draft:    Option[Distribution],
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
          "title" -> "Density"
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
      "config"     -> VegaSpecShared.darkConfig
    )

  private def buildCdfSpec(
    response: DistributionPreviewResponse,
    draft:    Option[Distribution],
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
          "axis"   -> js.Dynamic.literal("format" -> ".1~%"),
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
      "config"     -> VegaSpecShared.darkConfig
    )

  private def emptySpec(width: Int, height: Int): js.Dynamic =
    VegaSpecShared.emptyMessageSpec(
      width, height, responsive = false,
      message = "Enter distribution parameters to see a preview", fontSize = 14
    )

  // ── Data helpers ──────────────────────────────────────────────

  private def makeDataValues(points: Array[DistributionPreviewPoint]): js.Array[js.Any] =
    val arr = js.Array[js.Any]()
    points.foreach { pt =>
      arr.push(js.Dynamic.literal("x" -> pt.x, "pdf" -> pt.pdf, "cdf" -> pt.cdf))
    }
    arr

  // ── Anchor overlay builders ───────────────────────────────────

  /** Build anchor overlay layers for both PDF and CDF views. Rules render as
    * TWO datum-driven layers regardless of anchor count
    * (`VegaSpecShared.verticalRuleLayers`); CDF anchor dots stay per-anchor. */
  private def anchorAnnotations(
    response: DistributionPreviewResponse,
    draft:    Option[Distribution],
    viewMode: DistributionViewMode
  ): js.Array[js.Any] =
    val layers = js.Array[js.Any]()
    val rules = Vector.newBuilder[VegaSpecShared.VerticalRuleDatum]
    draft.foreach { d =>
      if d.distributionType == IronConstants.Expert then
        // Expert mode: vertical rules at each input quantile x-position
        val pcts   = d.percentiles.getOrElse(Array.empty[Double])
        val quants = d.quantiles.getOrElse(Array.empty[Double])
        pcts.zip(quants).foreach { case (p, q) =>
          val label = f"P${(p * 100).round}%d"
          viewMode match
            case DistributionViewMode.PDF =>
              // Vertical dashed rule + text label
              rules += anchorRule(q, label)
            case DistributionViewMode.CDF =>
              // Filled dot at (quantile_x, percentile) for exact-fit verification
              layers.push(cdfAnchorDot(q, p))
        }
      else
        // Lognormal: rules at minLoss (P05) and maxLoss (P95) — same in both views
        d.minLoss.foreach { min => rules += anchorRule(min.toDouble, "P05") }
        d.maxLoss.foreach { max => rules += anchorRule(max.toDouble, "P95") }
    }
    VegaSpecShared
      .verticalRuleLayers(rules.result(), fontSize = 11, textDx = 4, textDy = -6, baseline = "alphabetic")
      .foreach(layers.push(_))
    layers

  private val AnchorRuleColor: HexColor = PaletteData.hex("#6a8a8e")
  private val AnchorTextColor: HexColor = PaletteData.hex("#a0b0b0")

  /** Always-visible dashed anchor rule in the preview's fixed colours — not
    * hover-linked (the preview has no hover selection). */
  private def anchorRule(x: Double, label: String): VegaSpecShared.VerticalRuleDatum =
    VegaSpecShared.VerticalRuleDatum(
      x, Seq(label),
      ruleColor = AnchorRuleColor, textColor = AnchorTextColor,
      dashed = true, visibilityKey = None, seriesId = None
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
        "labelExpr"  -> VegaSpecShared.lossAxisLabelExpr
      )
    )

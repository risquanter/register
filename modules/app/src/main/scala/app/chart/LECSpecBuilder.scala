package app.chart

import scala.scalajs.js

import app.core.NumberFormat
import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** Client-side Vega-Lite v6 specification builder for LEC curves.
  *
  * Produces a `js.Dynamic` spec ready for `vegaEmbed`.
  *
  * Spec features:
  *   - Hover selection param (`"hover"`) for pointer-based curve highlight
  *   - Invisible point layer for voronoi-based nearest-point detection
  *   - Opacity condition: hovered curve = 1.0, others = 0.2
  *   - Data values shape: `{curveId, risk, loss, exceedance}`
  *   - Colour encoding: `scale.domain` (curveIds) + `scale.range` (hex colours)
  *   - Per-curve annotations, each coloured to match that curve's own line,
  *     each with its own show/hide toggle and a value in its label, stacked
  *     as two lines ("P95" over "$131M") so adjacent rules' labels overlap
  *     less:
  *     - Tail quantile lines (dashed), each independently toggleable:
  *       P90 (`showP90`), P95 (`showP95`), P99 (`showP99`), P99.5 (`showP995`);
  *       only P95 starts checked, the others are opt-in
  *     - AAL — Average Annual Loss (solid, to read as "central value" rather
  *       than a threshold): (`showAAL`)
  *     - Probability of no loss: plain text, fixed at a pixel position (not
  *       a chart line — this number is the *size of the drop* from the
  *       curve's trivial 100% at x=0 to its occurrence-probability plateau
  *       for x>0, not a y-coordinate the curve ever occupies, so there's no
  *       line to anchor it to): (`showNoLossProbability`)
  *   - X-axis domain starts at 0 (not the smallest actual loss) — a
  *     risk's true likely outcome (often "no loss") must stay representable
  *   - Axes: B/M formatting on X, percentage on Y
  *   - Dark theme config ([[VegaSpecShared.darkConfig]] — transparent
  *     background, light-on-dark text, app's own font stack, ~20% larger
  *     label/title text than Vega's defaults for readability)
  *   - Legend with `labelExpr` mapping curveId → the series' legend label
  *     (node name by default; "node — branch · tree" in Overlay compare)
  *   - Interpolation toggle param (monotone/basis/linear/step-after)
  *   - Y-axis adaptive ceiling (capped at 1.0)
  */
/** Explicit axis extents for a spec, overriding the per-spec automatic ones.
  * Used by the side-by-side comparison layout: each branch's panel is pinned
  * to the extents of BOTH branches' visible curves, because per-panel
  * autoscaling would silently defeat the visual comparison.
  *
  * @param lossMax        Upper end of the x (loss) domain; lower end stays 0.
  * @param probabilityMax Upper end of the y (exceedance) domain, ≤ 1.0.
  */
final case class PinnedAxes(lossMax: Double, probabilityMax: Double)

/** One chart series: a node's curve, its assigned colour, the series
  * identity (`seriesId` — the data/colour-scale/legend key), and the legend
  * text — `label` (the node name) stacked over `origin` ("branch · tree",
  * when given) as two legend lines. A named type because the string fields
  * are same-typed — as positional tuple members a swap would compile
  * silently and render a legend of internal ids. */
final case class ChartSeries(curve: LECNodeCurve, colour: HexColor, seriesId: String, label: String, origin: Option[String])

object PinnedAxes:
  /** Shared extents over every given curve — the same headroom rule the
    * unpinned spec applies to its own curves (10% above the largest starting
    * exceedance, capped at 1.0). `None` when no curve has any points.
    *
    * The loss extent covers the annotation values (quantiles, AAL) as well
    * as the curve points: an unpinned spec gets that for free because
    * Vega-Lite unions the x domain across layers, but an explicit domain
    * overrides the union — without this, a P99.5 rule beyond the last curve
    * tick would draw outside the pinned plot area. */
  def fromCurves(curves: Iterable[LECNodeCurve]): Option[PinnedAxes] =
    val withData = curves.filter(_.curve.nonEmpty)
    val points = withData.flatMap(_.curve)
    if points.isEmpty then None
    else
      val annotationValues = withData.flatMap(nc => nc.quantiles.values ++ Seq(nc.averageAnnualLoss))
      val lossMax = (points.map(_.loss.toDouble) ++ annotationValues).max
      val probabilityMax = math.min(
        1.0,
        withData.flatMap(_.curve.headOption).map(_.exceedanceProbability).maxOption.getOrElse(1.0) * 1.1
      )
      Some(PinnedAxes(lossMax, probabilityMax))

object LECSpecBuilder:

  /** Build a Vega-Lite spec from paired curve data and colours.
    *
    * The interpolation mode and annotation-toggle visibility are driven live
    * off the Vega view's signals by `ChartParams.applyTo` — the spec only
    * declares those signals at their defaults (`Interpolation.default` /
    * `LecAnnotation.defaultOn`), so no initial-value parameter is taken here.
    *
    * @param curves        Ordered pairs of (curve data, assigned colour)
    * @param origin        Second legend line for every series ("branch ·
    *                      tree") — the legend format is the same in every
    *                      mode, so single-tree and compare read alike
    * @param width         Chart width in pixels
    * @param height        Chart height in pixels
    * @param pinned        Explicit axis extents; `None` (the default) keeps
    *                      the automatic per-spec extents
    * @param responsive    When true, `width`/`height` become `"container"` so
    *                      the chart fills its box (single/overlay Analyze
    *                      chart); false keeps the fixed pixel size (side-by-side
    *                      panels, Design preview)
    * @param zoomable      When true, adds a `bind:"scales"` interval param so
    *                      the two continuous axes pan/zoom in place
    * @return Vega-Lite spec as `js.Dynamic`, ready for `vegaEmbed`
    */
  def build(
    curves: Vector[(LECNodeCurve, HexColor)],
    origin: Option[String] = None,
    width: Int = 950,
    height: Int = 400,
    pinned: Option[PinnedAxes] = None,
    responsive: Boolean = false,
    zoomable: Boolean = false
  ): js.Dynamic =
    buildFromSeries(curves.map { case (nc, color) => ChartSeries(nc, color, nc.id.value, nc.name, origin) }, width, height, pinned, responsive, zoomable)

  /** Same as `build`, but each curve's series identity (`seriesId` — the
    * data points, colour-scale domain, and legend match) AND its legend
    * label are given explicitly instead of the defaults (`nc.id.value` /
    * `nc.name`). Needed when two curves share the same `NodeId` (e.g. the
    * same node's curve fetched from two different branches for an Overlay
    * comparison) — `NodeId` alone can't disambiguate them, and it can't be
    * faked with a synthetic string since it's Iron-refined to a real ULID;
    * the caller likewise says how the legend names each side
    * ("Root — main · Tree A"). `build` delegates here with today's defaults
    * so every existing call site is unchanged.
    */
  def buildFromSeries(
    series: Vector[ChartSeries],
    width: Int = 950,
    height: Int = 400,
    pinned: Option[PinnedAxes] = None,
    responsive: Boolean = false,
    zoomable: Boolean = false
  ): js.Dynamic =
    val allPoints = series.flatMap(_.curve.curve)
    if series.isEmpty || allPoints.isEmpty then
      VegaSpecShared.emptyMessageSpec(width, height, responsive, "No data available")
    else buildSpec(series, width, height, pinned, responsive, zoomable)

  // ── Private builders ──────────────────────────────────────────

  /** The chart's hover selection name — declared on the point layer, read by
    * the line and annotation layers' conditions. */
  private val HoverParamName = "hover"

  private def buildSpec(
    series: Vector[ChartSeries],
    width: Int,
    height: Int,
    pinned: Option[PinnedAxes],
    responsive: Boolean,
    zoomable: Boolean
  ): js.Dynamic =
    // Stable ordering: sort by seriesId for deterministic domain/range
    val ordered = series.sortBy(_.seriesId)

    // Y-axis ceiling: pinned extent if given, else adaptive (capped at 1.0)
    val yBuffer = 1.1
    val yCeiling = pinned.map(_.probabilityMax).getOrElse(math.min(
      1.0,
      ordered.flatMap(_.curve.curve.headOption).map(_.exceedanceProbability).max * yBuffer
    ))

    // Legend labelExpr: map seriesId → the caller-supplied legend lines.
    // The expr returns an ARRAY per entry — Vega stacks array text as one
    // line per element — so every entry reads as the node name over its
    // origin ("branch · tree"), the same format in every mode.
    val labelParts = ordered.map { s =>
      val lines = (s.label +: s.origin.toSeq)
        .map(l => s"'${l.replace("'", "\\'")}'")
        .mkString("[", ", ", "]")
      s"datum.value == '${s.seriesId}' ? $lines"
    }
    val legendLabelExpr = (labelParts :+ "[datum.value]").mkString(" : ")

    // Per-curve annotations — tail quantiles, AAL, no-loss probability — each
    // coloured to match that curve's own assigned line colour (not a single
    // shared colour taken from just one curve), so when several curves are
    // shown together (Compare/Overlay, or a multi-select on one tree), each
    // curve's markers are told apart by the same colour as its line.
    // Each tail quantile gets its own independent toggle (not one shared
    // "show all percentiles" switch). The `LecAnnotation` case is the single
    // source of truth for each toggle's signal name and label; the string key
    // here is the `LECNodeCurve.quantiles` map key it reads. All annotations
    // render as TWO datum-driven layers (rules + labels) regardless of curve
    // count — see `VegaSpecShared.verticalRuleLayers`.
    val quantileToggles = List("p90" -> LecAnnotation.P90, "p95" -> LecAnnotation.P95, "p99" -> LecAnnotation.P99, "p99.5" -> LecAnnotation.P995)
    // Only curves with data get annotations — a node with an empty curve
    // (no simulation outcomes) carries aal = 0.0 / noLoss = 1.0 fallbacks
    // that would otherwise draw a solid rule pinned to the y-axis and a
    // "no loss: 100%" row for a line that isn't on the chart at all.
    val withData = ordered.filter(_.curve.curve.nonEmpty)
    val annotationData: Vector[VegaSpecShared.VerticalRuleDatum] =
      withData.flatMap { s =>
        val nc = s.curve
        val quantileRules = quantileToggles.flatMap { case (key, ann) =>
          nc.quantiles.get(key).map { value =>
            VegaSpecShared.VerticalRuleDatum(
              value, Seq(ann.label, formatLossValue(value)),
              ruleColor = s.colour, textColor = s.colour,
              dashed = true, visibilityKey = Some(ann.signalName),
              seriesId = Some(s.seriesId))
          }
        }
        quantileRules :+ VegaSpecShared.VerticalRuleDatum(
          nc.averageAnnualLoss, Seq(LecAnnotation.AAL.label, formatLossValue(nc.averageAnnualLoss)),
          ruleColor = s.colour, textColor = s.colour,
          dashed = false, visibilityKey = Some(LecAnnotation.AAL.signalName),
          seriesId = Some(s.seriesId))
      }

    // "Probability of no loss" rows — see `noLossLayer` for why this is text
    // at a fixed pixel position rather than a line.
    val noLossRows: Vector[(String, HexColor, String)] =
      withData.map { s =>
        (s"${s.curve.name} — no loss: ${formatProbability(s.curve.probabilityOfNoLoss)}", s.colour, s.seriesId)
      }

    // Fresh data values array — called per layer to avoid shared mutable state (F2)
    def makeDataValues(): js.Array[js.Any] =
      val arr = js.Array[js.Any]()
      ordered.foreach { s =>
        s.curve.curve.foreach { pt =>
          arr.push(js.Dynamic.literal(
            "curveId"    -> s.seriesId,
            "risk"       -> s.curve.name,
            "loss"       -> pt.loss.toDouble,
            "exceedance" -> pt.exceedanceProbability
          ))
        }
      }
      arr

    // Fresh colour encoding — called per layer to avoid shared mutable state (F2)
    def makeColorEncoding(): js.Dynamic =
      val domain = js.Array[js.Any]()
      val range = js.Array[js.Any]()
      ordered.foreach { s =>
        domain.push(s.seriesId)
        range.push(s.colour.value: String) // .value at the Vega edge
      }
      js.Dynamic.literal(
        "field" -> "curveId",
        "type"  -> "nominal",
        "title" -> "Risk modelled",
        "scale" -> js.Dynamic.literal(
          "domain" -> domain,
          "range"  -> range
        ),
        "legend" -> js.Dynamic.literal(
          "labelExpr"  -> legendLabelExpr,
          // "…" cap for extreme names — the untruncated names stay readable
          // on the slot cards.
          "labelLimit" -> 220
        )
      )

    // Main line layer with opacity condition for hover
    val lineLayer = js.Dynamic.literal(
      "data" -> js.Dynamic.literal("values" -> makeDataValues()),
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
            "labelExpr"  -> VegaSpecShared.lossAxisLabelExpr
          ),
          // Lower end is deliberately 0, not the curve's own smallest loss
          // tick: a risk's true likely outcome is often "no loss", which must
          // stay representable on the axis, not be clamped away — see
          // `probabilityOfNoLoss`'s doc comment (LECGenerator) for why.
          // A pinned upper end fixes the domain outright (shared axes across
          // side-by-side panels); otherwise it stays automatic.
          "scale" -> pinned.fold(
            js.Dynamic.literal("domainMin" -> 0.0)
          )(p => js.Dynamic.literal("domain" -> js.Array(0.0, p.lossMax)))
        ),
        "y" -> js.Dynamic.literal(
          "field" -> "exceedance",
          "type"  -> "quantitative",
          "title" -> "Probability",
          "axis"  -> js.Dynamic.literal("format" -> ".1~%"),
          "scale" -> js.Dynamic.literal("domain" -> js.Array(0.0, yCeiling))
        ),
        "color"   -> makeColorEncoding(),
        "opacity" -> js.Dynamic.literal(
          "condition" -> js.Array(
            js.Dynamic.literal("param" -> HoverParamName, "empty" -> false, "value" -> 1.0),
            js.Dynamic.literal("test" -> VegaSpecShared.hoverStoreEmptyTest(HoverParamName), "value" -> 1.0)
          ),
          "value" -> 0.2
        ),
        "strokeWidth" -> js.Dynamic.literal(
          "condition" -> js.Array(
            js.Dynamic.literal("param" -> HoverParamName, "empty" -> false, "value" -> 3.0),
            js.Dynamic.literal("test" -> VegaSpecShared.hoverStoreEmptyTest(HoverParamName), "value" -> 1.5)
          ),
          "value" -> 1.5
        )
      )
    )

    // Point-layer params: the hover selection, plus (when zoomable) the
    // scale-bound interval selection for pan/zoom. Both live on this one layer
    // rather than at the top level: a `bind:"scales"` interval selection placed
    // at the top of a layered spec is pushed into every layer sharing the axes,
    // declaring its `grid_x`/`grid_y` signals more than once ("Duplicate signal
    // name"). Defined on a single layer it is generated once; the scales are
    // shared, so pan/zoom still applies to the whole chart.
    val pointParams = js.Array[js.Any](
      js.Dynamic.literal(
        "name"   -> HoverParamName,
        "select" -> js.Dynamic.literal(
          "type"    -> "point",
          "on"      -> "pointerover",
          "clear"   -> "pointerout",
          "nearest" -> true,
          "fields"  -> js.Array("curveId")
        )
      )
    )
    if zoomable then
      pointParams.push(js.Dynamic.literal(
        "name"   -> "grid",
        "select" -> js.Dynamic.literal("type" -> "interval", "encodings" -> js.Array("x", "y")),
        "bind"   -> "scales"
      ))

    // Invisible point layer for voronoi-based nearest-point hover detection
    val pointLayer = js.Dynamic.literal(
      "data" -> js.Dynamic.literal("values" -> makeDataValues()),
      "mark" -> js.Dynamic.literal(
        "type"    -> "point",
        "opacity" -> 0,
        "tooltip" -> false
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
        "color" -> makeColorEncoding()
      ),
      "params" -> pointParams
    )

    // Assemble layers: annotation rule/label layers + no-loss rows + line
    // layer + point layer (annotations under the lines, as before the fold)
    val allLayers = js.Array[js.Any]()
    VegaSpecShared.verticalRuleLayers(annotationData, hoverParam = Some(HoverParamName)).foreach(allLayers.push(_))
    noLossLayer(noLossRows).foreach(allLayers.push(_))
    allLayers.push(lineLayer)
    allLayers.push(pointLayer)

    // Responsive sizing: "container" lets vega-embed's ResizeObserver size the
    // chart to its box (the single/overlay Analyze chart); the fixed pixel size
    // otherwise (side-by-side panels, Design preview).
    val widthField: js.Any  = if responsive then "container" else width
    val heightField: js.Any = if responsive then "container" else height

    // Named signals the chart's `expr`s read (the line's `interpolate`, each
    // annotation's opacity). No Vega `bind` inputs — these are driven live by
    // `ChartParams.applyTo` from the native `LecChartControls` panel; the spec
    // only declares them at their defaults. `LecAnnotation` is the single source
    // of truth for the toggle signal names and defaults. The pan/zoom selection
    // is NOT here — it lives on the point layer (see `pointParams`) to avoid
    // duplicate signals on this layered spec.
    val paramsArr = js.Array[js.Any](
      js.Dynamic.literal("name" -> "interpolate", "value" -> Interpolation.default.signalValue)
    )
    LecAnnotation.values.foreach { a =>
      paramsArr.push(js.Dynamic.literal("name" -> a.signalName, "value" -> a.defaultOn))
    }

    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> widthField,
      "height"     -> heightField,
      "background" -> "transparent",
      "config"     -> VegaSpecShared.darkConfig,
      "params" -> paramsArr,
      "layer" -> allLayers
    )

  // ── Annotation helpers ────────────────────────────────────────

  /** Format a loss value (already in millions, per `LossDistribution`'s own
    * convention) for a static annotation label — mirrors the x-axis's own
    * `labelExpr` B/M formatting so the label and axis never disagree.
    */
  private def formatLossValue(value: Double): String =
    if value >= 1000 then f"$$${value / 1000}%,.1fB"
    else if value >= 10 then f"$$${math.round(value)}%,dM"
    else if value >= 1 then f"$$$value%.1fM"
    // Below $1M whole-M rounding would print "$0M" for genuinely nonzero
    // values (an AAL of 0.25 is $250k, not zero) — switch to thousands.
    else f"$$${math.round(value * 1000)}%,dK"

  /** Format a probability (0.0-1.0) as a whole-number percentage, for the
    * no-loss annotation label. */
  private def formatProbability(p: Double): String =
    s"${NumberFormat.percentValue(p, 0)}%"

  /** "Probability of no loss" as plain text, fixed at a pixel position
    * within the plot area — ONE layer stacking one row per curve (rows carry
    * the label, colour, and pixel `y` as data; mark-property exprs read
    * them). Pixel-positioned, not a line: this number doesn't correspond to
    * any y-coordinate the curve's own P(Loss >= x) scale actually reaches —
    * the curve is trivially 100% at x=0 exactly, then jumps straight down to
    * the occurrence probability for any x>0, so the number is the *size of
    * the drop*, not a *level* a reference line could sit at. Neither channel
    * uses a data scale, so the rows can't collide with either domain.
    */
  private def noLossLayer(rows: Vector[(String, HexColor, String)], fontSize: Int = 13): Option[js.Dynamic] =
    Option.when(rows.nonEmpty) {
      val values = js.Array[js.Any]()
      rows.zipWithIndex.foreach { case ((label, color, seriesId), idx) =>
        values.push(js.Dynamic.literal("label" -> label, "color" -> (color.value: String), "curveId" -> seriesId, "row" -> idx))
      }
      js.Dynamic.literal(
        "data" -> js.Dynamic.literal("values" -> values),
        "mark" -> js.Dynamic.literal(
          "type"     -> "text",
          "align"    -> "left",
          "baseline" -> "top",
          "fontSize" -> fontSize,
          "x"        -> 6,
          "y"        -> js.Dynamic.literal("expr" -> s"6 + datum.row * ${fontSize + 3}"),
          "color"    -> js.Dynamic.literal("expr" -> "datum.color")
        ),
        "encoding" -> js.Dynamic.literal(
          "text" -> js.Dynamic.literal("field" -> "label"),
          // Shared hover-follow conditions; hidden when the NoLoss toggle is off.
          "opacity" -> VegaSpecShared.hoverOpacityConditions(
            s"!${LecAnnotation.NoLoss.signalName}", HoverParamName)
        )
      )
    }

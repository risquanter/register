package app.chart

import scala.scalajs.js

import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** Client-side Vega-Lite v6 specification builder for LEC curves.
  *
  * Produces a `js.Dynamic` spec ready for `vegaEmbed`.
  *
  * Spec features:
  *   - Hover selection param (`"hover"`) for pointer-based curve highlight
  *   - Invisible point layer for voronoi-based nearest-point detection
  *   - Opacity condition: hovered curve = 1.0, others = 0.3
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
  *   - Dark theme config (transparent background, light-on-dark text,
  *     app's own font stack, ~20% larger label/title text than Vega's
  *     defaults for readability)
  *   - Legend with `labelExpr` mapping curveId → node name
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

  /** The user-facing input params declared in `buildSpec`'s `paramsArr`
    * (interpolation select + the annotation checkboxes).
    * `LECChartView` reads their live values off the old Vega view before a
    * re-embed and re-applies them to the new one, so changing the chart's
    * data (new selection, compare toggled) doesn't silently reset the
    * user's toggle choices to the spec defaults. Keep in sync with `paramsArr`.
    * The optional `grid` zoom param (added when `zoomable`) is deliberately
    * absent — a re-embed resets the pan/zoom because the data changed.
    */
  val preservedParams: List[String] =
    List("interpolate", "showP90", "showP95", "showP99", "showP995", "showAAL", "showNoLossProbability")

  /** Build a Vega-Lite spec from paired curve data and colours.
    *
    * @param curves        Ordered pairs of (curve data, assigned colour)
    * @param interpolation Initial interpolation mode
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
    interpolation: String = "monotone",
    width: Int = 950,
    height: Int = 400,
    pinned: Option[PinnedAxes] = None,
    responsive: Boolean = false,
    zoomable: Boolean = false
  ): js.Dynamic =
    buildFromSeries(curves.map { case (nc, color) => (nc, color, nc.id.value) }, interpolation, width, height, pinned, responsive, zoomable)

  /** Same as `build`, but the chart's series identity (`curveId` — the data
    * points, colour-scale domain, and legend match) is given explicitly per
    * curve instead of always being `nc.id.value`. Needed when two curves
    * share the same `NodeId` (e.g. the same node's curve fetched from two
    * different branches for an Overlay comparison) — `NodeId` alone can't
    * disambiguate them, and it can't be faked with a synthetic string since
    * it's Iron-refined to a real ULID. `build` delegates here with today's
    * default so every existing call site is unchanged.
    */
  def buildFromSeries(
    curves: Vector[(LECNodeCurve, HexColor, String)],
    interpolation: String = "monotone",
    width: Int = 950,
    height: Int = 400,
    pinned: Option[PinnedAxes] = None,
    responsive: Boolean = false,
    zoomable: Boolean = false
  ): js.Dynamic =
    val allPoints = curves.flatMap(_._1.curve)
    if curves.isEmpty || allPoints.isEmpty then emptySpec(width, height, responsive)
    else buildSpec(curves, interpolation, width, height, pinned, responsive, zoomable)

  // ── Private builders ──────────────────────────────────────────

  private def buildSpec(
    curves: Vector[(LECNodeCurve, HexColor, String)],
    interpolation: String,
    width: Int,
    height: Int,
    pinned: Option[PinnedAxes],
    responsive: Boolean,
    zoomable: Boolean
  ): js.Dynamic =
    // Stable ordering: sort by curveId for deterministic domain/range
    val ordered = curves.sortBy(_._3)

    // Y-axis ceiling: pinned extent if given, else adaptive (capped at 1.0)
    val yBuffer = 1.1
    val yCeiling = pinned.map(_.probabilityMax).getOrElse(math.min(
      1.0,
      ordered.flatMap(_._1.curve.headOption).map(_.exceedanceProbability).max * yBuffer
    ))

    // Legend labelExpr: map curveId → display name (immutable String, safe to share).
    // `buildFromSeries` callers that disambiguate two branches' curves for the
    // same node (Analyze Overlay compare) encode the branch as a "@branch"
    // suffix on curveId (see CompareColorAssigner) — surfaced here as "(branch)"
    // so the legend can tell the two apart instead of showing the same node
    // name twice. `build`'s default curveId (`nc.id.value`) never contains
    // '@', so every non-Compare call site's legend is unchanged.
    val labelParts = ordered.map { case (nc, _, curveId) =>
      val branchSuffix = curveId.lastIndexOf('@') match
        case -1 => ""
        case i  => s" (${curveId.substring(i + 1)})"
      s"datum.value == '${curveId}' ? '${(nc.name + branchSuffix).replace("'", "\\'")}'"
    }
    val legendLabelExpr = (labelParts :+ "datum.value").mkString(" : ")

    // Per-curve annotations — tail quantiles, AAL, no-loss probability — each
    // coloured to match that curve's own assigned line colour (not a single
    // shared colour taken from just one curve), so when several curves are
    // shown together (Compare/Overlay, or a multi-select on one tree), each
    // curve's markers are told apart by the same colour as its line.
    // Each tail quantile gets its own independent toggle (not one shared
    // "show all percentiles" switch) — showP90/showP95/showP99/showP995.
    val quantileToggles = List("p90" -> ("P90", "showP90"), "p95" -> ("P95", "showP95"), "p99" -> ("P99", "showP99"), "p99.5" -> ("P99.5", "showP995"))
    val annotationLayers = js.Array[js.Any]()
    // Only curves with data get annotations — a node with an empty curve
    // (no simulation outcomes) carries aal = 0.0 / noLoss = 1.0 fallbacks
    // that would otherwise draw a solid rule pinned to the y-axis and a
    // "no loss: 100%" row for a line that isn't on the chart at all.
    val withData = ordered.filter { case (nc, _, _) => nc.curve.nonEmpty }
    withData.foreach { case (nc, hexColor, _) =>
      quantileToggles.foreach { case (key, (rawLabel, toggleParam)) =>
        nc.quantiles.get(key).foreach { value =>
          verticalAnnotation(value, Seq(rawLabel, formatLossValue(value)), hexColor.value, dashed = true, toggleParam = toggleParam)
            .foreach(annotationLayers.push(_))
        }
      }
      verticalAnnotation(nc.averageAnnualLoss, Seq("AAL", formatLossValue(nc.averageAnnualLoss)), hexColor.value, dashed = false, toggleParam = "showAAL")
        .foreach(annotationLayers.push(_))
    }

    // "Probability of no loss" as fixed, view-relative text — not a line.
    // This number doesn't correspond to any y-coordinate the curve's own
    // P(Loss >= x) scale actually reaches: the curve is trivially 100% at
    // x=0 exactly, then jumps straight down to the occurrence probability
    // for any x>0 — there's no "70%" height anywhere on it to anchor a
    // reference line to (that number is the *size of the drop*, not a
    // *level*). Positioned via literal pixel values (no "field", so it's
    // never subject to either data scale), one row per curve.
    withData.zipWithIndex.foreach { case ((nc, hexColor, _), idx) =>
      val label = s"${nc.name} — no loss: ${formatProbability(nc.probabilityOfNoLoss)}"
      noLossStat(label, hexColor.value, idx).foreach(annotationLayers.push(_))
    }

    // Fresh data values array — called per layer to avoid shared mutable state (F2)
    def makeDataValues(): js.Array[js.Any] =
      val arr = js.Array[js.Any]()
      ordered.foreach { case (nc, _, curveId) =>
        nc.curve.foreach { pt =>
          arr.push(js.Dynamic.literal(
            "curveId"    -> curveId,
            "risk"       -> nc.name,
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
      ordered.foreach { case (_, hexColor, curveId) =>
        domain.push(curveId)
        range.push(hexColor.value: String) // .value at the Vega edge
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
          "labelExpr" -> legendLabelExpr
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
            "labelExpr"  -> "if(datum.value >= 1e3, format(datum.value / 1e3, ',.1f') + 'B', format(datum.value, ',.0f') + 'M')"
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
            js.Dynamic.literal("param" -> "hover", "empty" -> false, "value" -> 1.0),
            js.Dynamic.literal("test" -> "length(data('hover_store')) == 0", "value" -> 1.0)
          ),
          "value" -> 0.2
        ),
        "strokeWidth" -> js.Dynamic.literal(
          "condition" -> js.Array(
            js.Dynamic.literal("param" -> "hover", "empty" -> false, "value" -> 3.0),
            js.Dynamic.literal("test" -> "length(data('hover_store')) == 0", "value" -> 1.5)
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
        "name"   -> "hover",
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

    // Assemble layers: per-curve annotations + line layer + point layer
    val allLayers = js.Array[js.Any]()
    for i <- 0 until annotationLayers.length do allLayers.push(annotationLayers(i))
    allLayers.push(lineLayer)
    allLayers.push(pointLayer)

    // Responsive sizing: "container" lets vega-embed's ResizeObserver size the
    // chart to its box (the single/overlay Analyze chart); the fixed pixel size
    // otherwise (side-by-side panels, Design preview).
    val widthField: js.Any  = if responsive then "container" else width
    val heightField: js.Any = if responsive then "container" else height

    // The user-facing bound inputs (interpolation select + annotation
    // checkboxes). The pan/zoom selection is NOT here — it lives on the point
    // layer (see `pointParams`) to avoid duplicate signals on this layered spec.
    val paramsArr = js.Array[js.Any](
      js.Dynamic.literal(
        "name"  -> "interpolate",
        "value" -> interpolation,
        "bind"  -> js.Dynamic.literal(
          "input"   -> "select",
          "options" -> js.Array("monotone", "basis", "linear", "step-after"),
          "name"    -> "Interpolation: "
        )
      ),
      // Only P95 starts checked among the percentile toggles — one
      // uncluttered default line; the rest are opt-in.
      js.Dynamic.literal(
        "name"  -> "showP90",
        "value" -> false,
        "bind"  -> js.Dynamic.literal(
          "input" -> "checkbox",
          "name"  -> "Show P90: "
        )
      ),
      js.Dynamic.literal(
        "name"  -> "showP95",
        "value" -> true,
        "bind"  -> js.Dynamic.literal(
          "input" -> "checkbox",
          "name"  -> "Show P95: "
        )
      ),
      js.Dynamic.literal(
        "name"  -> "showP99",
        "value" -> false,
        "bind"  -> js.Dynamic.literal(
          "input" -> "checkbox",
          "name"  -> "Show P99: "
        )
      ),
      js.Dynamic.literal(
        "name"  -> "showP995",
        "value" -> false,
        "bind"  -> js.Dynamic.literal(
          "input" -> "checkbox",
          "name"  -> "Show P99.5: "
        )
      ),
      js.Dynamic.literal(
        "name"  -> "showAAL",
        "value" -> true,
        "bind"  -> js.Dynamic.literal(
          "input" -> "checkbox",
          "name"  -> "Show AAL: "
        )
      ),
      js.Dynamic.literal(
        "name"  -> "showNoLossProbability",
        "value" -> true,
        "bind"  -> js.Dynamic.literal(
          "input" -> "checkbox",
          "name"  -> "Show no-loss probability: "
        )
      )
    )

    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> widthField,
      "height"     -> heightField,
      "background" -> "transparent",
      "config"     -> js.Dynamic.literal(
        // Matches the app's own font stack — Vega/canvas-free (svg renderer,
        // see LECChartView) text otherwise falls back to a generic default
        // that doesn't match the rest of the UI.
        "font"   -> "'Geist', ui-sans-serif, system-ui, -apple-system, sans-serif",
        "legend" -> js.Dynamic.literal(
          "disable"       -> false,
          "labelColor"    -> "#e6e8e8",
          "titleColor"    -> "#e6e8e8",
          // ~20% larger than Vega-Lite's own defaults (~10/11), for readability
          "labelFontSize" -> 12,
          "titleFontSize" -> 13
        ),
        "axis" -> js.Dynamic.literal(
          "grid"          -> true,
          "gridColor"     -> "#1c2225",
          // Brighter than before (#b0b8b8) — was too low-contrast against the
          // dark background for tick labels specifically.
          "labelColor"    -> "#c8ced0",
          "titleColor"    -> "#e6e8e8",
          "domainColor"   -> "#4a5a5e",
          "tickColor"     -> "#4a5a5e",
          "labelFontSize" -> 12,
          "titleFontSize" -> 13
        ),
        "title" -> js.Dynamic.literal("color" -> "#e6e8e8", "fontSize" -> 13)
      ),
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
    s"${math.round(p * 100)}%"

  /** A vertical rule + text label at x = `value` — used for tail quantiles
    * (dashed) and AAL (solid, so it reads as "a central value" rather than
    * "a threshold", the same solid/dashed distinction cat-modeling exhibits
    * commonly use to tell a mean apart from a percentile at a glance).
    *
    * @param labelLines Label rendered as stacked lines (Vega text marks
    *   treat an array datum as one line per element) — the name over its
    *   value ("P95" / "$131M") so a narrow column of text hugs the rule,
    *   overlapping far less than the previous single-line "P95: $131M"
    *   when several quantile rules sit close together.
    * @param toggleParam Name of the top-level checkbox param (declared in
    *   `buildSpec`) gating this layer's visibility — referenced via `expr`
    *   the same way the interpolation dropdown drives the line layer's
    *   `interpolate` mark property, so no extra Scala-side state is needed.
    */
  private def verticalAnnotation(
    value: Double,
    labelLines: Seq[String],
    color: String,
    dashed: Boolean,
    toggleParam: String,
    fontSize: Int = 13
  ): Seq[js.Dynamic] =
    val data = js.Dynamic.literal(
      "values" -> js.Array(js.Dynamic.literal("x" -> value))
    )
    val xEnc = js.Dynamic.literal("field" -> "x", "type" -> "quantitative")
    val toggleOpacity = js.Dynamic.literal("expr" -> s"$toggleParam ? 1 : 0")
    val ruleMark =
      if dashed then js.Dynamic.literal("type" -> "rule", "strokeDash" -> js.Array(4, 4), "color" -> color, "opacity" -> toggleOpacity)
      else js.Dynamic.literal("type" -> "rule", "color" -> color, "opacity" -> toggleOpacity)
    Seq(
      js.Dynamic.literal(
        "mark"     -> ruleMark,
        "data"     -> data,
        "encoding" -> js.Dynamic.literal("x" -> xEnc)
      ),
      js.Dynamic.literal(
        "mark"     -> js.Dynamic.literal(
          "type"       -> "text",
          "align"      -> "left",
          "baseline"   -> "top",
          "dx"         -> 4,
          "dy"         -> 4,
          "fontSize"   -> fontSize,
          "lineHeight" -> (fontSize + 3),
          "color"      -> color,
          "opacity"    -> toggleOpacity
        ),
        "data"     -> js.Dynamic.literal("values" -> js.Array(js.Dynamic.literal("x" -> value, "label" -> js.Array(labelLines*)))),
        "encoding" -> js.Dynamic.literal("x" -> xEnc, "text" -> js.Dynamic.literal("field" -> "label"))
      )
    )

  /** "Probability of no loss" as plain text, fixed at a pixel position
    * within the plot area — no `"field"` on either channel, so it is never
    * subject to the x or y data scale and can't collide with either domain
    * the way a data-encoded mark could. `index` stacks one row per curve.
    */
  private def noLossStat(label: String, color: String, index: Int, fontSize: Int = 13): Seq[js.Dynamic] =
    val toggleOpacity = js.Dynamic.literal("expr" -> "showNoLossProbability ? 1 : 0")
    Seq(
      js.Dynamic.literal(
        "data"     -> js.Dynamic.literal("values" -> js.Array(js.Dynamic.literal("label" -> label))),
        "mark"     -> js.Dynamic.literal("type" -> "text", "align" -> "left", "baseline" -> "top", "color" -> color, "fontSize" -> fontSize, "opacity" -> toggleOpacity),
        "encoding" -> js.Dynamic.literal(
          "x"    -> js.Dynamic.literal("value" -> 6),
          "y"    -> js.Dynamic.literal("value" -> (6 + index * (fontSize + 3))),
          "text" -> js.Dynamic.literal("field" -> "label")
        )
      )
    )

  // ── Empty spec ────────────────────────────────────────────────

  private def emptySpec(width: Int, height: Int, responsive: Boolean): js.Dynamic =
    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> (if responsive then ("container": js.Any) else (width: js.Any)),
      "height"     -> (if responsive then ("container": js.Any) else (height: js.Any)),
      "background" -> "transparent",
      "data"       -> js.Dynamic.literal("values" -> js.Array[js.Any]()),
      "mark"       -> js.Dynamic.literal("type" -> "text", "color" -> "#b0b8b8"),
      "encoding"   -> js.Dynamic.literal("text" -> js.Dynamic.literal("value" -> "No data available"))
    )

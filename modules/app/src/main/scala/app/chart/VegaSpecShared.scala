package app.chart

import scala.scalajs.js

import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** Shared Vega-Lite building blocks for every chart surface (LEC chart +
  * distribution preview): the dark theme, the loss-axis label formatting,
  * datum-driven vertical rule/label annotation layers, and the no-data spec.
  */
object VegaSpecShared:

  /** App dark-theme Vega config — font stack, label/title colours and sizes.
    * Single source of truth for all chart surfaces. */
  def darkConfig: js.Dynamic =
    js.Dynamic.literal(
      // Matches the app's own font stack — Vega text otherwise falls back to
      // a generic default that doesn't match the rest of the UI.
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
        // Bright enough for tick labels against the dark background.
        "labelColor"    -> "#c8ced0",
        "titleColor"    -> "#e6e8e8",
        "domainColor"   -> "#4a5a5e",
        "tickColor"     -> "#4a5a5e",
        "labelFontSize" -> 12,
        "titleFontSize" -> 13
      ),
      "title" -> js.Dynamic.literal("color" -> "#e6e8e8", "fontSize" -> 13)
    )

  /** X-axis B/M `labelExpr` shared by all loss axes, so annotation labels and
    * axes never disagree across chart surfaces. */
  val lossAxisLabelExpr: String =
    "if(datum.value >= 1e3, format(datum.value / 1e3, ',.1f') + 'B', format(datum.value, ',.0f') + 'M')"

  /** One vertical rule + its stacked text label, as data.
    *
    * @param labelLines Rendered as stacked lines (Vega text marks treat an
    *   array datum as one line per element) — a narrow column of text hugging
    *   the rule overlaps far less than a single long line when several rules
    *   sit close together.
    * @param dashed Dashed reads as "a threshold" (percentile), solid as "a
    *   central value" (AAL) — the distinction cat-modeling exhibits commonly
    *   use to tell a mean apart from a percentile at a glance.
    * @param visibilityKey The annotation-toggle signal name gating this datum
    *   — the host spec must declare a param of that name (the LEC chart's
    *   `LecAnnotation.signalName`s); `None` = always visible and no signal
    *   requirement.
    * @param seriesId The chart series this annotation belongs to (its hover
    *   identity, matched against the hover selection's `curveId` field);
    *   `None` = not hover-linked (distribution anchors).
    */
  final case class VerticalRuleDatum(
      x: Double,
      labelLines: Seq[String],
      ruleColor: HexColor,
      textColor: HexColor,
      dashed: Boolean,
      visibilityKey: Option[String],
      seriesId: Option[String]
  )

  /** Opacity expr mapping a datum's `toggle` field to its signal — chained
    * over exactly the toggle keys present in the data, so the emitted spec
    * references only the signals the data demands (Vega resolves every
    * signal name in an expression at spec-parse time, reachable or not; a
    * name the host spec doesn't declare fails the whole parse). A datum
    * without a toggle falls through to visible; no toggled datum at all
    * means no expression — `None`. */
  private def toggleOpacityExpr(toggleKeys: Vector[String]): Option[String] =
    Option.when(toggleKeys.nonEmpty)(
      toggleKeys
        .map(k => s"datum.toggle == '$k' ? ($k ? 1 : 0)")
        .mkString("", " : ", " : 1")
    )

  /** Boolean form of the same chain — true when the datum's toggle is OFF
    * (used as the "hidden" test in the hover-aware condition list); the
    * constant `false` (never hidden) when no datum carries a toggle. */
  private def toggleHiddenExpr(toggleKeys: Vector[String]): String =
    toggleKeys match
      case Vector() => "false"
      case keys =>
        keys
          .map(k => s"datum.toggle == '$k' ? !$k")
          .mkString("", " : ", " : false")

  /** Expr: the hover selection's store is empty (nothing hovered). Single
    * source for the `<param>_store` dataset-name convention. */
  def hoverStoreEmptyTest(hoverParam: String): String =
    s"length(data('${hoverParam}_store')) == 0"

  /** The hover-follow opacity object shared by every hover-linked annotation
    * layer — hidden by its own toggle, full when its series is hovered or
    * nothing is hovered, dimmed otherwise (first matching condition wins,
    * mirroring the line layer's own hover conditions). */
  def hoverOpacityConditions(hiddenTest: String, hoverParam: String): js.Dynamic =
    js.Dynamic.literal(
      "condition" -> js.Array[js.Any](
        js.Dynamic.literal("test" -> hiddenTest, "value" -> 0),
        js.Dynamic.literal("param" -> hoverParam, "empty" -> false, "value" -> 1),
        js.Dynamic.literal("test" -> hoverStoreEmptyTest(hoverParam), "value" -> 1)
      ),
      "value" -> 0.2
    )

  /** TWO layers — one rule layer + one text layer — covering ALL given data.
    * Colour, dash, label, and toggle visibility come from datum fields (via
    * mark-property exprs), so any number of annotations costs two layers, not
    * two per annotation. Text styling params default to the LEC chart's
    * values; the distribution preview passes its own.
    *
    * @param hoverParam `Some(p)`: annotations follow the hover selection `p`
    *   the same way the curves do — the hovered series' annotations stay at
    *   full opacity, other series' dim to 0.2, no hover = all full. Requires
    *   the spec to declare selection `p` over a `curveId` field (the LEC
    *   chart's `"hover"`). `None`: toggle-only visibility — mandatory for a
    *   spec without that selection (the distribution preview), whose missing
    *   `<p>_store` dataset would otherwise fail expression evaluation. */
  def verticalRuleLayers(
      data: Vector[VerticalRuleDatum],
      fontSize: Int = 13,
      textDx: Int = 4,
      textDy: Int = 4,
      baseline: String = "top",
      hoverParam: Option[String] = None
  ): Seq[js.Dynamic] =
    if data.isEmpty then Seq.empty
    else
      val ruleValues = js.Array[js.Any]()
      val textValues = js.Array[js.Any]()
      data.foreach { d =>
        // "" matches no signal name, so the opacity chain falls through to
        // visible; likewise no real series id ever equals "". `.value` here
        // is the Vega edge — colours stay `HexColor` until this push.
        val toggle: String = d.visibilityKey.getOrElse("")
        val curveId: String = d.seriesId.getOrElse("")
        ruleValues.push(js.Dynamic.literal(
          "x"       -> d.x,
          "color"   -> (d.ruleColor.value: String),
          "dashed"  -> d.dashed,
          "toggle"  -> toggle,
          "curveId" -> curveId
        ))
        textValues.push(js.Dynamic.literal(
          "x"       -> d.x,
          "label"   -> js.Array(d.labelLines*),
          "color"   -> (d.textColor.value: String),
          "toggle"  -> toggle,
          "curveId" -> curveId
        ))
      }
      val xEnc = js.Dynamic.literal("field" -> "x", "type" -> "quantitative")

      // The toggle signals this data actually gates on — the visibility
      // expressions are built over exactly this set, so the host spec only
      // needs to declare the toggle params its own annotations use (and a
      // spec with untoggled annotations, like the distribution preview,
      // needs none).
      val toggleKeys: Vector[String] = data.flatMap(_.visibilityKey).distinct

      // The two visibility modes, decided once, decorations produced
      // together: toggle-only puts one opacity expr on the MARKS; hover-aware
      // puts the shared condition list plus the thickness boost (1px → 3px,
      // the curve line's own hover width — on dark shades the opacity delta
      // alone is hard to see) on the ENCODINGS.
      val (markOpacity, encodingOpacity, hoveredRuleWidth)
          : (Option[js.Dynamic], Option[js.Dynamic], Option[js.Dynamic]) =
        hoverParam match
          case None =>
            (toggleOpacityExpr(toggleKeys).map(e => js.Dynamic.literal("expr" -> e)), None, None)
          case Some(p) =>
            (None,
             Some(hoverOpacityConditions(toggleHiddenExpr(toggleKeys), p)),
             Some(js.Dynamic.literal(
               "condition" -> js.Array[js.Any](
                 js.Dynamic.literal("param" -> p, "empty" -> false, "value" -> 3.0)
               ),
               "value" -> 1.0
             )))

      val ruleMark = js.Dynamic.literal(
        "type"       -> "rule",
        "color"      -> js.Dynamic.literal("expr" -> "datum.color"),
        "strokeDash" -> js.Dynamic.literal("expr" -> "datum.dashed ? [4,4] : []")
      )
      val textMark = js.Dynamic.literal(
        "type"       -> "text",
        "align"      -> "left",
        "baseline"   -> baseline,
        "dx"         -> textDx,
        "dy"         -> textDy,
        "fontSize"   -> fontSize,
        "lineHeight" -> (fontSize + 3),
        "color"      -> js.Dynamic.literal("expr" -> "datum.color")
      )
      markOpacity.foreach { o =>
        ruleMark.updateDynamic("opacity")(o)
        textMark.updateDynamic("opacity")(o)
      }

      val ruleEncoding = js.Dynamic.literal("x" -> xEnc)
      val textEncoding = js.Dynamic.literal(
        "x"    -> xEnc,
        "text" -> js.Dynamic.literal("field" -> "label")
      )
      encodingOpacity.foreach { o =>
        ruleEncoding.updateDynamic("opacity")(o)
        textEncoding.updateDynamic("opacity")(o)
      }
      hoveredRuleWidth.foreach(ruleEncoding.updateDynamic("strokeWidth")(_))

      Seq(
        js.Dynamic.literal(
          "data"     -> js.Dynamic.literal("values" -> ruleValues),
          "mark"     -> ruleMark,
          "encoding" -> ruleEncoding
        ),
        js.Dynamic.literal(
          "data"     -> js.Dynamic.literal("values" -> textValues),
          "mark"     -> textMark,
          "encoding" -> textEncoding
        )
      )

  /** Text-only spec for the no-data state. */
  def emptyMessageSpec(
      width: Int,
      height: Int,
      responsive: Boolean,
      message: String,
      fontSize: Int = 11
  ): js.Dynamic =
    js.Dynamic.literal(
      "$schema"    -> "https://vega.github.io/schema/vega-lite/v6.json",
      "width"      -> (if responsive then ("container": js.Any) else (width: js.Any)),
      "height"     -> (if responsive then ("container": js.Any) else (height: js.Any)),
      "background" -> "transparent",
      "data"       -> js.Dynamic.literal("values" -> js.Array[js.Any]()),
      "mark"       -> js.Dynamic.literal("type" -> "text", "color" -> "#b0b8b8", "fontSize" -> fontSize),
      "encoding"   -> js.Dynamic.literal("text" -> js.Dynamic.literal("value" -> message)),
      "config"     -> darkConfig
    )

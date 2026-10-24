package app.components

import com.raquo.laminar.api.L.*
import org.scalajs.dom.MouseEvent
import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.data.iron.{SafeName, NodeId, OccurrenceProbability, IronConstants}

/** Unified tree-node row used by both TreePreview (draft state) and
  * TreeDetailView (persisted tree).
  *
  * Produces a `div.tree-node-row.tree-node-row--depth-{depth}` with layout:
  *   [indent] [prefixContent…] [icon] [label] [suffixContent…] [remove?]
  *
  * Pure view component — owns no state (ADR-019 Pattern 4).
  *
  * @param label         Display text for the node.
  * @param kind          Portfolio or Leaf — drives the icon.
  * @param depth         Tree depth (0 = root children). Controls indent width.
  * @param onRemove      If Some, a remove button is rendered at the end.
  * @param onNodeClick   If Some, wired to the icon and label spans.
  * @param isSelected    Signal driving `.node-selected` on the row.
  * @param tooltip       Native `title` attribute on the label span.
  * @param prefixContent Modifiers injected before the icon (e.g. toggle button).
  * @param suffixContent Modifiers injected after the label (e.g. swatch trigger).
  */
object TreeNodeRow:

  enum NodeKind:
    case Portfolio, Leaf

  /** Structured leaf tooltip for the native `title` attribute. The lines below
    * the separator are decided by the shape's case: a metalog fit renders its
    * quantile anchors as two parallel lists, a lognormal its two bounds. A draft
    * node has no identifier yet, and its identifier line is omitted.
    *
    * {{{
    * leafTooltip(name = "Outage", probability = 0.3,
    *             shape = Lognormal(LossRange(1000, 50000)), id = None)
    * // Outage
    * // ─────────────────────
    * // Type:         lognormal
    * // Probability:  0.3
    * // Min Loss:     1000
    * // Max Loss:     50000
    * }}}
    */
  def leafTooltip(
    name:        SafeName.SafeName,
    probability: OccurrenceProbability,
    shape:       DistributionShape,
    id:          Option[NodeId]
  ): String =
    val idLine = id.fold("")(n => s"\nID:           ${n.toSafeId.value}")
    val base   = s"${name.value}\n─────────────────────$idLine\nProbability:  $probability"
    // The mode and the lines describing it come from one match on the case, so
    // the label and the fields below it cannot name different modes.
    val shapeLines = shape match
      case DistributionShape.Metalog(anchors, _) =>
        val percentiles = anchors.values.map(a => (a.percentile: Double)).mkString(", ")
        val quantiles   = anchors.values.map(a => (a.loss: Double)).mkString(", ")
        s"\nType:         ${IronConstants.Metalog}" +
          s"\nPercentiles:  [$percentiles]\nQuantiles:    [$quantiles]"
      case DistributionShape.Lognormal(bounds) =>
        s"\nType:         ${IronConstants.Lognormal}" +
          s"\nMin Loss:     ${bounds.min: Long}\nMax Loss:     ${bounds.max: Long}"
    s"$base$shapeLines"

  /** Structured portfolio tooltip for the native `title` attribute. A draft node
    * has no identifier and no counted children, and those lines are omitted.
    */
  def portfolioTooltip(
    name:       SafeName.SafeName,
    id:         Option[NodeId],
    childCount: Option[Int]
  ): String =
    val sep       = "\n─────────────────────"
    val idLine    = id.fold("")(n => s"\nID:           ${n.toSafeId.value}")
    val childLine = childCount.fold("")(n => s"\nChildren:     $n")
    s"${name.value}$sep$idLine$childLine"

  def apply(
    label:         String,
    kind:          NodeKind,
    depth:         Int,
    onRemove:      Option[() => Unit],
    onNodeClick:   Option[MouseEvent => Unit],
    isSelected:    Signal[Boolean]            = Signal.fromValue(false),
    tooltip:       Option[String],
    prefixContent: Seq[Modifier[HtmlElement]] = Nil,
    suffixContent: Seq[Modifier[HtmlElement]] = Nil
  ): HtmlElement =

    val icon: SvgElement = kind match
      case NodeKind.Portfolio => Icons.portfolio("node-icon")
      case NodeKind.Leaf      => Icons.leaf("node-icon")

    val rowCls: Signal[String] =
      isSelected.map(sel =>
        if sel then s"tree-node-row tree-node-row--depth-$depth node-selected"
        else s"tree-node-row tree-node-row--depth-$depth"
      )

    div(
      cls <-- rowCls,
      // Indent spacer — width driven by CSS var(--tree-indent-unit) × depth
      span(
        cls := "tree-node-indent",
        dataAttr("depth") := depth.toString,
        styleAttr := s"--_depth: $depth"
      ),
      // Prefix slot (e.g. toggle chevron in TreeDetailView)
      prefixContent,
      // Icon — click target when onNodeClick is wired
      span(
        cls := "node-icon-click-target",
        onNodeClick.map(fn => onClick --> fn).toList,
        onNodeClick.map(_ => cursor.pointer).toList,
        icon
      ),
      // Label
      span(
        cls := "node-label tree-node-label",
        tooltip.map(t => title := t).toList,
        onNodeClick.map(fn => onClick --> fn).toList,
        onNodeClick.map(_ => cursor.pointer).toList,
        label
      ),
      // Suffix slot (e.g. swatch trigger + picker in TreeDetailView)
      suffixContent,
      // Remove button
      onRemove.map(fn =>
        button(
          cls := "remove-btn",
          "✕",
          onClick --> (_ => fn())
        )
      ).toList
    )

package app.components

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom.MouseEvent

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

  def apply(
    label:         String,
    kind:          NodeKind,
    depth:         Int,
    onRemove:      Option[() => Unit]         = None,
    onNodeClick:   Option[MouseEvent => Unit] = None,
    isSelected:    Signal[Boolean]            = Signal.fromValue(false),
    tooltip:       Option[String]             = None,
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

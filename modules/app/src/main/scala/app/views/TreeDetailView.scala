package app.views

import com.raquo.laminar.api.L.{*, given}

import app.state.{TreeViewState, LoadState, ChartHoverBridge}
import app.components.Icons
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.iron.HexColor.HexColor

/** Expandable hierarchical view of a server-persisted risk tree.
  *
  * Renders the full node structure (portfolios + leaves) with
  * expand/collapse controls on portfolio nodes and click-to-select
  * on any node.
  *
  * Highlighting is inline-style-driven from `nodeColorMap` (P3 §4.1):
  * - Charted nodes: solid 3px left border in the node's curve hex colour.
  * - Query-matched-but-not-charted: dotted 3px left border in neutral.
  * - Neither: no border.
  *
  * Pure derived view — owns no state (ADR-019 Pattern 4).
  * Receives `TreeViewState` as constructor arg (Pattern 2).
  *
  * @param state             Tree navigation and chart state.
  * @param queryMatchedNodes Signal of node IDs matching the active query.
  */
object TreeDetailView:

  // ── Node display helpers (mirrors TreePreview.TreeNode pattern) ──

  private def nodeIcon(node: RiskNode): SvgElement = node match
    case _: RiskPortfolio => Icons.portfolio("node-icon")
    case _: RiskLeaf      => Icons.leaf("node-icon")

  private def nodeLabel(node: RiskNode): String = node match
    case leaf: RiskLeaf =>
      s"${leaf.name} (id: ${leaf.id.value})"
    case portfolio: RiskPortfolio =>
      s"${portfolio.name} (id: ${portfolio.id.value})"

  /** Build a structured tooltip showing all node params (D2(a): native title). */
  private def nodeTooltip(node: RiskNode): String = node match
    case leaf: RiskLeaf =>
      val base = s"${leaf.name}\n" +
        s"─────────────────────\n" +
        s"ID:           ${leaf.id.value}\n" +
        s"Type:         ${leaf.distributionType}\n" +
        s"Probability:  ${leaf.probability}"
      val pcts = leaf.percentiles.fold("")(arr => s"\nPercentiles:  [${arr.mkString(", ")}]")
      val qtls = leaf.quantiles.fold("")(arr => s"\nQuantiles:    [${arr.mkString(", ")}]")
      val minL = leaf.minLoss.fold("")(v => s"\nMin Loss:     $v")
      val maxL = leaf.maxLoss.fold("")(v => s"\nMax Loss:     $v")
      s"$base$pcts$qtls$minL$maxL"
    case portfolio: RiskPortfolio =>
      s"${portfolio.name}\n" +
        s"─────────────────────\n" +
        s"ID:           ${portfolio.id.value}\n" +
        s"Children:     ${portfolio.childIds.length}"

  private def isPortfolio(node: RiskNode): Boolean = node match
    case _: RiskPortfolio => true
    case _: RiskLeaf      => false

  /** Inline border style for a tree node based on chart/query state (§4.1).
    *
    * Charted nodes get their curve hex colour as a solid border.
    * Query-matched-but-not-charted nodes get a neutral dotted border.
    * All other nodes get no border.
    */
  private def borderStyleFor(
    nodeId: NodeId,
    colorMap: Map[NodeId, HexColor],
    queryMatched: Set[NodeId],
    isHovered: Boolean
  ): String =
    colorMap.get(nodeId) match
      case Some(hex) =>
        val width = if isHovered then "5px" else "3px"
        s"border-left: $width solid ${hex.value}; padding-left: calc(var(--sp-1) - $width);"
      case None =>
        if queryMatched.contains(nodeId) then
          "border-left: 3px dotted var(--foreground-variant); padding-left: calc(var(--sp-1) - 3px);"
        else ""

  // ── Public API ────────────────────────────────────────────────

  def apply(
    state: TreeViewState,
    queryMatchedNodes: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
    hoverBridge: ChartHoverBridge = new ChartHoverBridge()
  ): HtmlElement =
    div(
      cls := "tree-detail-view",
      child <-- state.selectedTree.signal.map {
        case LoadState.Idle        => renderPlaceholder("Select a tree to view its structure.")
        case LoadState.Loading     => renderPlaceholder("Loading tree structure…")
        case LoadState.Failed(msg) => renderError(msg)
        case LoadState.Loaded(tree) => renderTree(tree, state, queryMatchedNodes, hoverBridge)
      }
    )

  private def renderPlaceholder(message: String): HtmlElement =
    div(cls := "tree-detail-placeholder", p(message))

  private def renderError(message: String): HtmlElement =
    div(cls := "tree-detail-error", p(cls := "error-message", s"Failed: $message"))

  /** Render the full tree from a RiskTree domain object. */
  private def renderTree(tree: RiskTree, state: TreeViewState, queryMatchedNodes: Signal[Set[NodeId]], hoverBridge: ChartHoverBridge): HtmlElement =
    // Build children lookup: parentId → child nodes
    val childrenOf: Map[Option[NodeId], Seq[RiskNode]] =
      tree.nodes.groupBy(_.parentId)

    val rootNode = tree.nodes.find(_.id == tree.rootId)

    div(
      cls := "tree-detail-container",
      div(
        cls := "tree-detail-header",
        Icons.treeRoot("tree-root-icon"),
        span(cls := "tree-name", tree.name.value.toString)
      ),
      rootNode match
        case Some(root) =>
          div(
            cls := "tree-detail-nodes",
            renderNode(root, childrenOf, state, queryMatchedNodes, hoverBridge, prefix = "", isLast = true, isRoot = true)
          )
        case None =>
          div(cls := "tree-detail-error", p("Root node not found"))
    )

  /** Recursively render a node and its children. */
  private def renderNode(
    node: RiskNode,
    childrenOf: Map[Option[NodeId], Seq[RiskNode]],
    state: TreeViewState,
    queryMatchedNodes: Signal[Set[NodeId]],
    hoverBridge: ChartHoverBridge,
    prefix: String,
    isLast: Boolean,
    isRoot: Boolean
  ): HtmlElement =
    val nodeId   = node.id
    val children = childrenOf.getOrElse(Some(nodeId), Seq.empty)
    val hasChildren = isPortfolio(node) && children.nonEmpty

    val isExpanded: Signal[Boolean] = state.expandedNodes.signal.map(_.contains(nodeId))
    val isSelected: Signal[Boolean] = state.selectedNodeId.signal.map(_.contains(nodeId))

    val lineCls: Signal[String] =
      isSelected.map(sel => if sel then "tree-detail-inline node-selected" else "tree-detail-inline")

    val borderStyle: Signal[String] =
      state.nodeColorMap
        .combineWith(queryMatchedNodes, hoverBridge.hoveredCurveId.signal)
        .map { (colorMap, queryMatched, hoveredId) =>
          borderStyleFor(nodeId, colorMap, queryMatched, hoveredId.contains(nodeId))
        }

    val connector = if isRoot then "" else if isLast then "└── " else "├── "
    val branchText = s"$prefix$connector"
    val childPrefix =
      if isRoot then ""
      else prefix + (if isLast then "    " else "│   ")

    def handleNodeClick(ev: org.scalajs.dom.MouseEvent): Unit =
      if ev.ctrlKey || ev.metaKey then
        ev.preventDefault()
        state.userSelectionToggle.onNext(nodeId)
      else
        state.selectNode(nodeId)

    div(
      cls := "tree-detail-node",
      div(
        cls := "tree-node",
        span(cls := "tree-branch", branchText),
        div(
          cls <-- lineCls,
          styleAttr <-- borderStyle,
          onMouseEnter --> { _ => hoverBridge.hoveredCurveId.set(Some(nodeId)) },
          onMouseLeave --> { _ => hoverBridge.hoveredCurveId.set(None) },
          if hasChildren then
            span(
              cls := "node-toggle",
              cursor.pointer,
              child <-- isExpanded.map {
                case true  => Icons.chevronDown("toggle-icon")
                case false => Icons.chevronRight("toggle-icon")
              },
              onClick --> (_ => state.toggleExpanded(nodeId))
            )
          else
            span(cls := "node-toggle node-toggle-spacer", Icons.chevronRight("toggle-icon")),
          span(
            cls := "node-icon-click-target",
            cursor.pointer,
            nodeIcon(node),
            onClick --> handleNodeClick
          ),
          span(
            cls := "node-label",
            cursor.pointer,
            title := nodeTooltip(node),
            nodeLabel(node),
            onClick --> handleNodeClick
          )
        )
      ),
      // Children (only rendered when expanded)
      if hasChildren then
        div(
          cls := "node-children",
          display <-- isExpanded.map(if _ then "block" else "none"),
          children.toList.zipWithIndex.map { case (c, idx) =>
            val childIsLast = idx == children.size - 1
            renderNode(c, childrenOf, state, queryMatchedNodes, hoverBridge, childPrefix, childIsLast, isRoot = false)
          }
        )
      else
        emptyNode
    )

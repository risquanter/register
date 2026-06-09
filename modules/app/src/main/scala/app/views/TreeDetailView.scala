package app.views

import com.raquo.laminar.api.L.{*, given}

import app.state.{TreeViewState, LoadState, ChartHoverBridge}
import app.components.{Icons, ColorSwatchPicker, TreeNodeRow}
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
  * Receives shared `TreeViewState` (ADR-019 Pattern 2 consumer).
  * Owns local picker open/close UI state.
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
      TreeNodeRow.leafTooltip(
        name        = leaf.name,
        distType    = leaf.distributionType,
        probability = leaf.probability,
        id          = Some(leaf.id),
        percentiles = leaf.percentiles,
        quantiles   = leaf.quantiles,
        minLoss     = leaf.minLoss,
        maxLoss     = leaf.maxLoss
      )
    case portfolio: RiskPortfolio =>
      TreeNodeRow.portfolioTooltip(
        name       = portfolio.name,
        id         = Some(portfolio.id),
        childCount = Some(portfolio.childIds.length)
      )

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
    // Local state: which node's picker popover is open (if any)
    val pickerOpenFor: Var[Option[NodeId]] = Var(None)

    div(
      cls := "tree-detail-view",
      // Close picker when clicking outside (attached to the container)
      onClick --> { _ => pickerOpenFor.set(None) },
      // F-GP1: Escape key closes picker
      onKeyDown --> { ev => if ev.key == "Escape" then pickerOpenFor.set(None) },
      // F-GP2(B): reactively clear preview whenever picker closes
      pickerOpenFor.signal.changes.filter(_.isEmpty) --> { _ => state.clearPreview() },
      child <-- state.selectedTree.signal.map {
        case LoadState.Idle        => renderPlaceholder("Select a tree to view its structure.")
        case LoadState.Loading     => renderPlaceholder("Loading tree structure…")
        case LoadState.Failed(msg) => renderError(msg)
        case LoadState.Loaded(tree) => renderTree(tree, state, queryMatchedNodes, hoverBridge, pickerOpenFor)
      }
    )

  private def renderPlaceholder(message: String): HtmlElement =
    div(cls := "tree-detail-placeholder", p(message))

  private def renderError(message: String): HtmlElement =
    div(cls := "tree-detail-error", p(cls := "error-message", s"Failed: $message"))

  /** Render the full tree from a RiskTree domain object. */
  private def renderTree(tree: RiskTree, state: TreeViewState, queryMatchedNodes: Signal[Set[NodeId]], hoverBridge: ChartHoverBridge, pickerOpenFor: Var[Option[NodeId]]): HtmlElement =
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
            renderNode(root, childrenOf, state, queryMatchedNodes, hoverBridge, pickerOpenFor, depth = 0)
          )
        case None =>
          div(cls := "tree-detail-error", p("Root node not found"))
    )

  /** Recursively render a node and its children. */
  private def renderNode(
    node:              RiskNode,
    childrenOf:        Map[Option[NodeId], Seq[RiskNode]],
    state:             TreeViewState,
    queryMatchedNodes: Signal[Set[NodeId]],
    hoverBridge:       ChartHoverBridge,
    pickerOpenFor:     Var[Option[NodeId]],
    depth:             Int
  ): HtmlElement =
    val nodeId      = node.id
    val children    = childrenOf.getOrElse(Some(nodeId), Seq.empty)
    val hasChildren = isPortfolio(node) && children.nonEmpty

    val isExpanded: Signal[Boolean] = state.expandedNodes.signal.map(_.contains(nodeId))
    val isSelected: Signal[Boolean] = state.selectedNodeId.signal.map(_.contains(nodeId))

    val borderStyle: Signal[String] =
      state.nodeColorMap
        .combineWith(queryMatchedNodes, hoverBridge.hoveredCurveId.signal)
        .map { (colorMap, queryMatched, hoveredId) =>
          borderStyleFor(nodeId, colorMap, queryMatched, hoveredId.contains(nodeId))
        }

    def handleNodeClick(ev: org.scalajs.dom.MouseEvent): Unit =
      if ev.ctrlKey || ev.metaKey then
        ev.preventDefault()
        state.userSelectionToggle.onNext(nodeId)
      else
        state.selectNode(nodeId)

    val isCharted: Signal[Boolean]             = state.nodeColorMap.map(_.contains(nodeId))
    val currentColor: Signal[Option[HexColor]] = state.nodeColorMap.map(_.get(nodeId))
    val pickerOpen: Signal[Boolean]            = pickerOpenFor.signal.map(_.contains(nodeId))

    val nodeKind = node match
      case _: RiskPortfolio => TreeNodeRow.NodeKind.Portfolio
      case _: RiskLeaf      => TreeNodeRow.NodeKind.Leaf

    val toggleSpan: HtmlElement =
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
        span(cls := "node-toggle node-toggle-spacer", Icons.chevronRight("toggle-icon"))

    val row = TreeNodeRow(
      label         = nodeLabel(node),
      kind          = nodeKind,
      depth         = depth,
      tooltip       = Some(nodeTooltip(node)),
      onNodeClick   = Some(handleNodeClick),
      isSelected    = isSelected,
      prefixContent = List(toggleSpan),
      suffixContent = List(
        child.maybe <-- isCharted.combineWith(currentColor).map(renderSwatchTrigger(nodeId, pickerOpenFor, _, _)),
        child.maybe <-- pickerOpen.combineWith(currentColor).map(renderPickerPopover(nodeId, state, pickerOpenFor, _, _))
      )
    ).amend(
      cls := "tree-detail-inline",
      styleAttr <-- borderStyle,
      onMouseEnter --> { _ => hoverBridge.hoveredCurveId.set(Some(nodeId)) },
      onMouseLeave --> { _ => hoverBridge.hoveredCurveId.set(None) },
      position.relative
    )

    div(
      cls := "tree-detail-node",
      row,
      if hasChildren then
        div(
          cls := "node-children",
          display <-- isExpanded.map(if _ then "block" else "none"),
          children.toList.map { c =>
            renderNode(c, childrenOf, state, queryMatchedNodes, hoverBridge, pickerOpenFor, depth + 1)
          }
        )
      else
        emptyNode
    )

  // ── Extracted view helpers (F3a: named functions for inline lambdas) ──

  /** Render the colour-swatch trigger icon for charted nodes (PD1(b)). */
  private def renderSwatchTrigger(
    nodeId: NodeId,
    pickerOpenFor: Var[Option[NodeId]],
    charted: Boolean,
    colorOpt: Option[HexColor]
  ): Option[HtmlElement] =
    if charted then
      Some(div(
        cls := "node-swatch-trigger",
        styleAttr := s"background-color: ${colorOpt.map(_.value).getOrElse("transparent")};",
        onClick.stopPropagation --> { _ =>
          if pickerOpenFor.now().contains(nodeId) then pickerOpenFor.set(None)
          else pickerOpenFor.set(Some(nodeId))
        }
      ))
    else None

  /** Render the colour picker popover when open for this node. */
  private def renderPickerPopover(
    nodeId: NodeId,
    state: TreeViewState,
    pickerOpenFor: Var[Option[NodeId]],
    open: Boolean,
    colorOpt: Option[HexColor]
  ): Option[HtmlElement] =
    if open then
      colorOpt.map { col =>
        ColorSwatchPicker(
          currentColor = col,
          onSelect = hex => {
            state.setColorOverride(nodeId, hex)
            pickerOpenFor.set(None)
          },
          onReset = () => {
            state.clearColorOverride(nodeId)
            pickerOpenFor.set(None)
          },
          onPreview = hex => state.setPreview(nodeId, hex),
          onPreviewClear = () => state.clearPreview()
        )
      }
    else None

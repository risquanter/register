package app.views

import com.raquo.laminar.api.L.{*, given}

import app.state.{TreeViewState, LoadState}
import app.components.Icons
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.NodeId

/** Expandable hierarchical view of a server-persisted risk tree.
  *
  * Renders the full node structure (portfolios + leaves) with
  * expand/collapse controls on portfolio nodes and click-to-select
  * on any node. Nodes matching the last query are highlighted with
  * the `node-query-matched` CSS class (ADR-028 / T3.4).
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

  // ── Public API ────────────────────────────────────────────────

  def apply(
    state: TreeViewState,
    queryMatchedNodes: Signal[Set[NodeId]] = Signal.fromValue(Set.empty)
  ): HtmlElement =
    div(
      cls := "tree-detail-view",
      child <-- state.selectedTree.signal.map {
        case LoadState.Idle        => renderPlaceholder("Select a tree to view its structure.")
        case LoadState.Loading     => renderPlaceholder("Loading tree structure…")
        case LoadState.Failed(msg) => renderError(msg)
        case LoadState.Loaded(tree) => renderTree(tree, state, queryMatchedNodes)
      }
    )

  private def renderPlaceholder(message: String): HtmlElement =
    div(cls := "tree-detail-placeholder", p(message))

  private def renderError(message: String): HtmlElement =
    div(cls := "tree-detail-error", p(cls := "error-message", s"Failed: $message"))

  /** Render the full tree from a RiskTree domain object. */
  private def renderTree(tree: RiskTree, state: TreeViewState, queryMatchedNodes: Signal[Set[NodeId]]): HtmlElement =
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
            renderNode(root, childrenOf, state, queryMatchedNodes, prefix = "", isLast = true, isRoot = true)
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
    prefix: String,
    isLast: Boolean,
    isRoot: Boolean
  ): HtmlElement =
    val nodeId   = node.id
    val children = childrenOf.getOrElse(Some(nodeId), Seq.empty)
    val hasChildren = isPortfolio(node) && children.nonEmpty

    val isExpanded: Signal[Boolean] = state.expandedNodes.signal.map(_.contains(nodeId))
    val isSelected: Signal[Boolean] = state.selectedNodeId.signal.map(_.contains(nodeId))
    val isChartSelected: Signal[Boolean] = state.userSelectedNodeIds.map(_.contains(nodeId))
    val isQueryMatched: Signal[Boolean] = queryMatchedNodes.map(_.contains(nodeId))

    val lineCls: Signal[String] =
      isSelected.combineWith(isChartSelected, isQueryMatched).map { (sel, chart, qMatch) =>
        val base = "tree-detail-inline"
        val s = if sel then " node-selected" else ""
        val c = if chart then " node-chart-selected" else ""
        val q = if qMatch then " node-query-matched" else ""
        s"$base$s$c$q"
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
            renderNode(c, childrenOf, state, queryMatchedNodes, childPrefix, childIsLast, isRoot = false)
          }
        )
      else
        emptyNode
    )

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
  * on any node (preparing for Phase F LEC wiring).
  *
  * Pure derived view — owns no state (ADR-019 Pattern 4).
  * Receives `TreeViewState` as constructor arg (Pattern 2).
  */
object TreeDetailView:

  // ── Node display helpers (mirrors TreePreview.TreeNode pattern) ──

  private def nodeIcon(node: RiskNode): SvgElement = node match
    case _: RiskPortfolio => Icons.portfolio("node-icon")
    case _: RiskLeaf      => Icons.leaf("node-icon")

  private def nodeLabel(node: RiskNode): String = node match
    case leaf: RiskLeaf =>
      s"${leaf.name} (${leaf.distributionType}, p=${f"${leaf.probability}%.2f"})"
    case portfolio: RiskPortfolio =>
      portfolio.name

  private def isPortfolio(node: RiskNode): Boolean = node match
    case _: RiskPortfolio => true
    case _: RiskLeaf      => false

  // ── Public API ────────────────────────────────────────────────

  def apply(state: TreeViewState): HtmlElement =
    div(
      cls := "tree-detail-view",
      child <-- state.selectedTree.signal.map {
        case LoadState.Idle        => renderPlaceholder("Select a tree to view its structure.")
        case LoadState.Loading     => renderPlaceholder("Loading tree structure…")
        case LoadState.Failed(msg) => renderError(msg)
        case LoadState.Loaded(tree) => renderTree(tree, state)
      }
    )

  private def renderPlaceholder(message: String): HtmlElement =
    div(cls := "tree-detail-placeholder", p(message))

  private def renderError(message: String): HtmlElement =
    div(cls := "tree-detail-error", p(cls := "error-message", s"Failed: $message"))

  /** Render the full tree from a RiskTree domain object. */
  private def renderTree(tree: RiskTree, state: TreeViewState): HtmlElement =
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
            renderNode(root, childrenOf, state, prefix = "", isLast = true, isRoot = true)
          )
        case None =>
          div(cls := "tree-detail-error", p("Root node not found"))
    )

  /** Recursively render a node and its children. */
  private def renderNode(
    node: RiskNode,
    childrenOf: Map[Option[NodeId], Seq[RiskNode]],
    state: TreeViewState,
    prefix: String,
    isLast: Boolean,
    isRoot: Boolean
  ): HtmlElement =
    val nodeId   = node.id
    val children = childrenOf.getOrElse(Some(nodeId), Seq.empty)
    val hasChildren = isPortfolio(node) && children.nonEmpty

    val isExpanded: Signal[Boolean] = state.expandedNodes.signal.map(_.contains(nodeId))
    val isSelected: Signal[Boolean] = state.selectedNodeId.signal.map(_.contains(nodeId))
    val isChartSelected: Signal[Boolean] = state.chartNodeIds.map(_.contains(nodeId))

    val lineCls: Signal[String] = isSelected.combineWith(isChartSelected).map { (sel, chart) =>
      (sel, chart) match
        case (true, true)   => "tree-detail-inline node-selected node-chart-selected"
        case (true, false)  => "tree-detail-inline node-selected"
        case (false, true)  => "tree-detail-inline node-chart-selected"
        case (false, false) => "tree-detail-inline"
    }

    val connector = if isRoot then "" else if isLast then "└── " else "├── "
    val branchText = s"$prefix$connector"
    val childPrefix =
      if isRoot then ""
      else prefix + (if isLast then "    " else "│   ")

    def handleNodeClick(ev: org.scalajs.dom.MouseEvent): Unit =
      if ev.ctrlKey || ev.metaKey then
        ev.preventDefault()
        state.toggleChartSelection(nodeId)
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
            renderNode(c, childrenOf, state, childPrefix, childIsLast, isRoot = false)
          }
        )
      else
        emptyNode
    )

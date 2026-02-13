package app.views

import com.raquo.laminar.api.L.{*, given}

import app.state.{TreeViewState, LoadState}
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

  def apply(state: TreeViewState): HtmlElement =
    div(
      cls := "tree-detail-view",
      child <-- state.selectedTree.signal.map {
        case LoadState.Idle       => renderPlaceholder("Select a tree to view its structure.")
        case LoadState.Loading    => renderPlaceholder("Loading tree structure…")
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
    // Build lookup: NodeId → RiskNode for O(1) access
    val nodeMap: Map[NodeId, RiskNode] = tree.nodes.map(n => n.id -> n).toMap

    // Build children lookup: parentId → child nodes
    val childrenOf: Map[Option[NodeId], Seq[RiskNode]] =
      tree.nodes.groupBy(_.parentId)

    val rootNode = nodeMap.get(tree.rootId)

    div(
      cls := "tree-detail-container",
      div(
        cls := "tree-detail-header",
        span(cls := "tree-icon", "🌳"),
        span(cls := "tree-name", tree.name.value.toString)
      ),
      rootNode match
        case Some(root) =>
          div(
            cls := "tree-detail-nodes",
            renderNode(root, childrenOf, state, depth = 0)
          )
        case None =>
          div(cls := "tree-detail-error", p("Root node not found"))
    )

  /** Recursively render a node and its children. */
  private def renderNode(
    node: RiskNode,
    childrenOf: Map[Option[NodeId], Seq[RiskNode]],
    state: TreeViewState,
    depth: Int
  ): HtmlElement =
    val nodeIdStr = node.id.value
    val children = childrenOf.getOrElse(Some(node.id), Seq.empty)
    val isPortfolio = node.isInstanceOf[RiskPortfolio]

    val isExpanded: Signal[Boolean] = state.expandedNodes.signal.map(_.contains(nodeIdStr))
    val isSelected: Signal[Boolean] = state.selectedNodeId.signal.map(_.contains(nodeIdStr))

    div(
      cls := "tree-detail-node",
      // Node row
      div(
        cls <-- isSelected.map(sel => if sel then "node-row node-selected" else "node-row"),
        paddingLeft := s"${depth * 20}px",
        // Expand/collapse toggle (only for portfolios with children)
        if isPortfolio && children.nonEmpty then
          span(
            cls := "node-toggle",
            cursor.pointer,
            child.text <-- isExpanded.map(if _ then "▼ " else "▶ "),
            onClick --> (_ => state.toggleExpanded(nodeIdStr))
          )
        else
          span(cls := "node-toggle node-toggle-spacer", "  "),
        // Icon
        span(cls := "node-icon", if isPortfolio then "📁" else "🍃"),
        // Label
        span(
          cls := "node-label",
          cursor.pointer,
          nodeLabel(node),
          onClick --> (_ => state.selectNode(nodeIdStr))
        )
      ),
      // Children (only rendered when expanded)
      if isPortfolio && children.nonEmpty then
        div(
          cls := "node-children",
          display <-- isExpanded.map(if _ then "block" else "none"),
          children.toList.map(child => renderNode(child, childrenOf, state, depth + 1))
        )
      else
        emptyNode
    )

  /** Format the display label for a node. */
  private def nodeLabel(node: RiskNode): String = node match
    case leaf: RiskLeaf =>
      val dt = leaf.distributionType.toString
      val p  = leaf.probability
      s"${leaf.name} ($dt, p=${f"$p%.2f"})"
    case portfolio: RiskPortfolio =>
      portfolio.name

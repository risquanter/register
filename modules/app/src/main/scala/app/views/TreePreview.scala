package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.{TreeBuilderState, PortfolioDraft, LeafDraft}
import app.components.{Icons, TreeNodeRow}

/**
 * Hierarchical tree preview rendered from TreeBuilderState signals.
 *
 * Displays a structured tree with one DOM row per node. Each row carries
 * depth-aware CSS classes (`.tree-node-row--depth-{n}`) and a `data-depth`
 * attribute on the indent span so Phase D can draw connector lines in CSS
 * without touching Scala code again.
 *
 * Pure derived view — owns no state (ADR-019 Pattern 4).
 */
object TreePreview:

  /** A unified node for rendering: either a portfolio or a leaf. */
  private enum TreeNode(val name: String):
    case Portfolio(n: String) extends TreeNode(n)
    case Leaf(n: String, distType: String, probability: Double) extends TreeNode(n)

    def iconSvg: SvgElement = this match
      case _: Portfolio => Icons.portfolio("tree-icon")
      case _: Leaf      => Icons.leaf("tree-icon")

    def label: String = this match
      case Portfolio(n) => n
      case Leaf(n, dt, p) => s"$n ($dt, p=${f"$p%.2f"})"

    /** Tooltip showing all draft params (D2(a): native title). */
    def tooltip: String = this match
      case Portfolio(n) => n
      case Leaf(n, dt, p) =>
        s"$n\n─────────────────────\nType:         $dt\nProbability:  $p"

  def apply(builderState: TreeBuilderState): HtmlElement =
    val treeSignal: Signal[List[HtmlElement]] =
      builderState.treeNameVar.signal
        .combineWith(builderState.portfoliosVar.signal, builderState.leavesVar.signal)
        .map { case (treeName, portfolios, leaves) =>
          if portfolios.isEmpty && leaves.isEmpty then
            List(div(cls := "tree-empty", em("Add portfolios and leaves to see the tree structure.")))
          else
            renderTree(treeName, portfolios, leaves, builderState)
        }

    div(
      cls := "tree-preview",
      h3("Tree Preview"),
      div(
        cls := "tree-container",
        children <-- treeSignal
      )
    )

  /** Build the full hierarchical tree as a list of DOM elements. */
  private def renderTree(
    treeName: String,
    portfolios: List[PortfolioDraft],
    leaves: List[LeafDraft],
    builderState: TreeBuilderState
  ): List[HtmlElement] =
    val displayName = if treeName.isBlank then "(unnamed tree)" else treeName

    // Build child lookup: parent name → children (portfolios + leaves unified)
    val allNodes: List[(TreeNode, Option[String])] =
      portfolios.map(p => (TreeNode.Portfolio(p.name.value.toString), p.parent.map(_.value.toString))) ++
      leaves.map(l => (TreeNode.Leaf(l.name.value.toString, l.distribution.distributionType, l.distribution.probability), l.parent.map(_.value.toString)))

    val childrenOf: Map[Option[String], List[TreeNode]] =
      allNodes.groupMap(_._2)(_._1)

    // Root row
    val rootRow = div(
      cls := "tree-node-row tree-node-row--depth-0 tree-root",
      dataAttr("depth") := "0",
      Icons.treeRoot("tree-root-icon"),
      span(cls := "tree-node-label", displayName)
    )

    // Render children of root (parent = None)
    val rootChildren = childrenOf.getOrElse(None, Nil)
    val childRows = renderChildren(rootChildren, childrenOf, depth = 1, builderState)

    rootRow :: childRows

  /** Recursively render children as individual DOM rows. */
  private def renderChildren(
    nodes: List[TreeNode],
    childrenOf: Map[Option[String], List[TreeNode]],
    depth: Int,
    builderState: TreeBuilderState
  ): List[HtmlElement] =
    nodes.flatMap { node =>
      val nodeKind = node match
        case _: TreeNode.Portfolio => TreeNodeRow.NodeKind.Portfolio
        case _: TreeNode.Leaf      => TreeNodeRow.NodeKind.Leaf

      val nodeRow = TreeNodeRow(
        label   = node.label,
        kind    = nodeKind,
        depth   = depth,
        tooltip = Some(node.tooltip),
        onRemove = Some(() => builderState.removeNode(node.name))
      )

      val grandchildren = childrenOf.getOrElse(Some(node.name), Nil)
      nodeRow :: renderChildren(grandchildren, childrenOf, depth + 1, builderState)
    }

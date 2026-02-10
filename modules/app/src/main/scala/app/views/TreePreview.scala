package app.views

import com.raquo.laminar.api.L.{*, given}
import app.state.{TreeBuilderState, PortfolioDraft, LeafDraft}

/**
 * Hierarchical tree preview rendered from TreeBuilderState signals.
 *
 * Displays an ASCII-style tree structure with box-drawing characters (├── └──)
 * showing portfolios (📁) and leaves (🍃) in their parent-child hierarchy.
 * Each node has a remove button that triggers cascade deletion.
 *
 * Pure derived view — owns no state (ADR-019 Pattern 4).
 */
object TreePreview:

  /** A unified node for rendering: either a portfolio or a leaf. */
  private enum TreeNode(val name: String):
    case Portfolio(n: String) extends TreeNode(n)
    case Leaf(n: String, distType: String, probability: Double) extends TreeNode(n)

    def icon: String = this match
      case _: Portfolio => "📁"
      case _: Leaf => "🍃"

    def label: String = this match
      case Portfolio(n) => n
      case Leaf(n, dt, p) => s"$n ($dt, p=${f"$p%.2f"})"

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
        fontFamily := "monospace",
        whiteSpace.pre,
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
      portfolios.map(p => (TreeNode.Portfolio(p.name), p.parent)) ++
      leaves.map(l => (TreeNode.Leaf(l.name, l.distribution.distributionType, l.distribution.probability), l.parent))

    val childrenOf: Map[Option[String], List[TreeNode]] =
      allNodes.groupMap(_._2)(_._1)

    // Root line
    val rootLine = div(cls := "tree-node tree-root", span(s"🌳 $displayName"))

    // Render children of root (parent = None)
    val rootChildren = childrenOf.getOrElse(None, Nil)
    val childElements = renderChildren(rootChildren, childrenOf, prefix = "", builderState)

    rootLine :: childElements

  /** Recursively render children with box-drawing prefixes. */
  private def renderChildren(
    nodes: List[TreeNode],
    childrenOf: Map[Option[String], List[TreeNode]],
    prefix: String,
    builderState: TreeBuilderState
  ): List[HtmlElement] =
    nodes.zipWithIndex.flatMap { case (node, idx) =>
      val isLast = idx == nodes.size - 1
      val connector = if isLast then "└── " else "├── "
      val childPrefix = prefix + (if isLast then "    " else "│   ")

      val nodeLine = div(
        cls := "tree-node",
        span(cls := "tree-branch", s"$prefix$connector"),
        span(cls := s"tree-icon", node.icon),
        span(cls := "tree-label", s" ${node.label} "),
        button(
          cls := "remove-btn",
          "✕",
          onClick --> (_ => builderState.removeNode(node.name))
        )
      )

      val grandchildren = childrenOf.getOrElse(Some(node.name), Nil)
      nodeLine :: renderChildren(grandchildren, childrenOf, childPrefix, builderState)
    }

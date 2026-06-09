package app.views

import com.raquo.laminar.api.L.{*, given}
import io.github.iltotore.iron.*
import app.state.{TreeBuilderState, PortfolioDraft, LeafDraft, DistributionMode}
import app.components.{Icons, TreeNodeRow}
import com.risquanter.register.domain.data.iron.{SafeName, Probability, NonNegativeLong}

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
  private enum TreeNode(val name: SafeName.SafeName):
    case Portfolio(n: SafeName.SafeName) extends TreeNode(n)
    case Leaf(
      n:           SafeName.SafeName,
      distType:    DistributionMode,
      probability: Probability,
      percentiles: Option[Array[Double]],
      quantiles:   Option[Array[Double]],
      minLoss:     Option[NonNegativeLong],
      maxLoss:     Option[NonNegativeLong]
    ) extends TreeNode(n)

    def iconSvg: SvgElement = this match
      case _: Portfolio => Icons.portfolio("tree-icon")
      case _: Leaf      => Icons.leaf("tree-icon")

    def label: String = this match
      case Portfolio(n)  => n.value
      case l: Leaf       => s"${l.n.value} (${l.distType.toApiString}, p=${f"${l.probability}%.2f"})"

    /** Tooltip — delegates to shared TreeNodeRow utilities (D2(a): native title). */
    def tooltip: String = this match
      case Portfolio(n) =>
        TreeNodeRow.portfolioTooltip(name = n)
      case l: Leaf =>
        TreeNodeRow.leafTooltip(
          name        = l.n,
          distType    = l.distType,
          probability = l.probability,
          percentiles = l.percentiles,
          quantiles   = l.quantiles,
          minLoss     = l.minLoss,
          maxLoss     = l.maxLoss
        )

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
      portfolios.map(p => (TreeNode.Portfolio(p.name), p.parent.map(_.value))) ++
      leaves.map { l =>
        val s = l.distribution.shape
        val node = TreeNode.Leaf(
          n           = l.name,
          distType    = s.distributionType,
          // safe: leavesVar invariant — only nodes that passed validateDistribution are appended
          probability = l.distribution.probability.assume,
          percentiles = s.percentiles,
          quantiles   = s.quantiles,
          minLoss     = s.minLoss.map(_.assume), // safe: lossFilter guarantees >= 0
          maxLoss     = s.maxLoss.map(_.assume)  // safe: lossFilter guarantees >= 0
        )
        (node, l.parent.map(_.value))
      }

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
        onRemove = Some(() => builderState.removeNode(node.name.value))
      )

      val grandchildren = childrenOf.getOrElse(Some(node.name.value), Nil)
      nodeRow :: renderChildren(grandchildren, childrenOf, depth + 1, builderState)
    }

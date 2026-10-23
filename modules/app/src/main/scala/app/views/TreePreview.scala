package app.views

import com.raquo.laminar.api.L.*
import org.scalajs.dom.MouseEvent
import app.state.{TreeBuilderState, PortfolioDraft, LeafDraft, FormMode, FormTarget, currentTarget}
import app.components.{Icons, TreeNodeRow}
import app.components.ConfirmGuard.proceedOrConfirm
import com.risquanter.register.domain.data.DistributionShape
import com.risquanter.register.domain.data.iron.{SafeName, OccurrenceProbability}
import com.risquanter.register.http.requests.RiskTreeRequests.ParentRef

/**
 * Hierarchical tree preview rendered from TreeBuilderState signals.
 *
 * Displays a structured tree with one DOM row per node. Each row carries
 * depth-aware CSS classes (`.tree-node-row--depth-{n}`) and a `data-depth`
 * attribute on the indent span, so connector lines are drawn in CSS without
 * touching Scala code.
 *
 * Pure derived view — owns no state (ADR-019 Pattern 4).
 */
object TreePreview:

  /** A unified node for rendering: either a portfolio or a leaf. */
  private enum TreeNode(val name: SafeName.SafeName):
    case Portfolio(n: SafeName.SafeName) extends TreeNode(n)
    case Leaf(
      n:           SafeName.SafeName,
      probability: OccurrenceProbability,
      shape:       DistributionShape
    ) extends TreeNode(n)

    def iconSvg: SvgElement = this match
      case _: Portfolio => Icons.portfolio("tree-icon")
      case _: Leaf      => Icons.leaf("tree-icon")

    def label: String = this match
      case Portfolio(n)  => n.value
      case l: Leaf       => s"${l.n.value} (${l.shape.distributionType}, p=${f"${l.probability}%.2f"})"

    /** Native `title` text, built by the shared [[TreeNodeRow]] helpers. A draft
      * node has no identifier and no counted children, so both are `None`. */
    def tooltip: String = this match
      case Portfolio(n) =>
        TreeNodeRow.portfolioTooltip(name = n, id = None, childCount = None)
      case l: Leaf =>
        TreeNodeRow.leafTooltip(
          name        = l.n,
          probability = l.probability,
          shape       = l.shape,
          id          = None
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
      portfolios.map(p => (TreeNode.Portfolio(p.name), ParentRef.nameOf(p.parent).map(_.value))) ++
      leaves.map { l =>
        val node = TreeNode.Leaf(
          n           = l.name,
          probability = l.probability,
          shape       = l.shape
        )
        (node, ParentRef.nameOf(l.parent).map(_.value))
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

  /** Select `target`, unless the active form has unsaved content, in which case
    * confirm before discarding it. Already viewing `target` is a no-op (today's
    * toggle-off-to-Blank behavior is dropped). Locked has nothing unsaved, so a
    * `Locked` → different-node click always proceeds without a prompt.
    */
  private def selectOrConfirm(builderState: TreeBuilderState, target: FormTarget): Unit =
    if !builderState.activeForm.now().currentTarget.contains(target) then
      proceedOrConfirm(builderState.isEditDirtyVar.now(), "Discard unsaved changes?") { () =>
        builderState.activeForm.set(FormMode.Locked(target))
      }

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

      // Selection is derived directly from `activeForm`'s current target — one
      // source for both this form and the node-editing views, so "highlight
      // follows the just-submitted node" falls out for free (no separate sync).
      val (isSelected, onNodeClick) = node match
        case _: TreeNode.Portfolio =>
          val target = FormTarget.Portfolio(node.name)
          val sel     = builderState.activeForm.signal.map(_.currentTarget.contains(target))
          val click   = Some((_: MouseEvent) => selectOrConfirm(builderState, target))
          (sel, click)
        case _: TreeNode.Leaf =>
          val target = FormTarget.Leaf(node.name)
          val sel     = builderState.activeForm.signal.map(_.currentTarget.contains(target))
          val click   = Some((_: MouseEvent) => selectOrConfirm(builderState, target))
          (sel, click)

      val nodeRow = TreeNodeRow(
        label       = node.label,
        kind        = nodeKind,
        depth       = depth,
        tooltip     = Some(node.tooltip),
        onRemove    = Some(() => builderState.removeNode(node.name)),
        onNodeClick = onNodeClick,
        isSelected  = isSelected
      )

      val grandchildren = childrenOf.getOrElse(Some(node.name.value), Nil)
      nodeRow :: renderChildren(grandchildren, childrenOf, depth + 1, builderState)
    }

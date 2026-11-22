package app.views

import com.raquo.laminar.api.L.*

import zio.prelude.Validation

import app.state.{TreeViewState, LoadState, ChartHoverBridge, CoverageBadge, LECChartState, MitigationReadout, SelectionEdit}
import app.components.{Icons, ColorSwatchPicker, FailureNotice, MitigationPanel, TreeNodeRow}
import com.risquanter.register.domain.data.{Mitigation, MitigationSelection, MitigationSpec, RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.domain.errors.ValidationError
import com.risquanter.register.http.responses.{TreeHistoryEntry, TreeStructureResponse}

/** Expandable hierarchical view of a server-persisted risk tree.
  *
  * Renders the full node structure (portfolios + leaves) with
  * expand/collapse controls on portfolio nodes and click-to-select
  * on any node.
  *
  * Highlighting is inline-style-driven from `nodeColorMap`:
  * - Charted nodes: solid 3px left border in the node's curve hex colour.
  * - Query-matched-but-not-charted: dotted 3px left border in neutral.
  * - Neither: no border.
  *
  * Receives shared `TreeViewState` (ADR-019 Pattern 2 consumer).
  * Owns local picker open/close UI state.
  *
  * @param state             Tree navigation and chart state.
  * @param queryMatchedNodes Signal of node IDs matching the active query.
  * @param changedNodeIds    Signal of node IDs the Compare diff reports changed
  *                          (renders the ✎ marker).
  * @param selectionLocked   When true (the row mirrors the baseline), Ctrl+click
  *                          selection is disabled and shows a transient notice;
  *                          a persistent header hint marks the mirrored state.
  *                          The mitigation panel and rows are locked with it.
  * @param structure         The structure read the tree is rendered from, with
  *                          each mitigation's resolved scope and the stale ids.
  * @param selection         The mitigations this card's valuation applies.
  * @param onSelectionEdit   Applies an edit to the owner's current selection.
  *                          It takes the edit rather than its result, so two
  *                          edits in one tick cannot overwrite each other.
  * @param staleAction       What a stale statement's badge offers on this card.
  */
object TreeDetailView:

  /** One selection edit, as `SelectionEdit` builds them. */
  type SelectionUpdate = MitigationSelection.Enumerated => Validation[ValidationError, MitigationSelection.Enumerated]

  /** How long a transient notice beside the tree stays up, in milliseconds.
    * Chosen by this project: long enough to read one sentence. */
  private val TransientNoticeMillis: Int = 2200

  // ── Node display helpers (mirrors TreePreview.TreeNode pattern) ──

  private def nodeIcon(node: RiskNode): SvgElement = node match
    case _: RiskPortfolio => Icons.portfolio("node-icon")
    case _: RiskLeaf      => Icons.leaf("node-icon")

  private def nodeLabel(node: RiskNode): String = node match
    case leaf: RiskLeaf =>
      s"${leaf.name} (id: ${leaf.id.value})"
    case portfolio: RiskPortfolio =>
      s"${portfolio.name} (id: ${portfolio.id.value})"

  /** Build a structured tooltip showing all node params, shown as the native title. */
  private def nodeTooltip(node: RiskNode): String = node match
    case leaf: RiskLeaf =>
      TreeNodeRow.leafTooltip(
        name        = leaf.name,
        probability = leaf.probability,
        shape       = leaf.shape,
        id          = Some(leaf.id)
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

  /** The left border of a row: solid in the curve colour, wider while hovered;
    * dotted for a query match with no curve; none otherwise. */
  private def borderStyleFor(
    colour:       Option[HexColor],
    queryMatched: Boolean,
    isHovered:    Boolean
  ): String =
    (colour, queryMatched) match
      case (Some(hex), _) =>
        val width = if isHovered then "5px" else "3px"
        s"border-left: $width solid ${hex.value}; padding-left: calc(var(--sp-1) - $width);"
      case (None, true) =>
        "border-left: 3px dotted var(--foreground-variant); padding-left: calc(var(--sp-1) - 3px);"
      case (None, false) => ""

  /** What a click on a tree card row asks for. */
  private enum RowGesture:
    /** Ctrl+click or Cmd+click. */
    case Add
    /** Ctrl+Shift+click or Cmd+Shift+click. */
    case Remove
    /** A click with neither key held. */
    case Plain

  private object RowGesture:
    def of(ev: org.scalajs.dom.MouseEvent): RowGesture =
      (ev.ctrlKey || ev.metaKey, ev.shiftKey) match
        case (true, true)  => Remove
        case (true, false) => Add
        case (false, _)    => Plain

  /** The tree card's click rule for a node row and a mitigation row alike. A
    * Ctrl gesture adds or removes, unless the card mirrors the baseline, when it
    * shows the locked notice instead. A plain click runs `plain`. */
  private def onRowGesture(
    ev:     org.scalajs.dom.MouseEvent,
    view:   NodeView,
    add:    () => Unit,
    remove: () => Unit,
    plain:  () => Unit
  ): Unit =
    (RowGesture.of(ev), view.selectionLocked.now()) match
      case (RowGesture.Plain, _) => plain()
      case (_, true) =>
        ev.preventDefault()
        view.lockedNoticeVisible.set(true)
      case (RowGesture.Add, false) =>
        ev.preventDefault()
        add()
      case (RowGesture.Remove, false) =>
        ev.preventDefault()
        remove()

  /** A tree card row's border and hover: the border from `border`, and hovering
    * highlights the curves of `curveNode`. */
  private def decorateRow(
    row:       HtmlElement,
    border:    Signal[String],
    curveNode: NodeId,
    view:      NodeView
  ): HtmlElement =
    row.amend(
      cls := "tree-detail-inline",
      styleAttr <-- border,
      onMouseEnter --> { _ => view.hoverBridge.hoveredCurveId.set(Some(curveNode)) },
      onMouseLeave --> { _ => view.hoverBridge.hoveredCurveId.set(None) },
      position.relative
    )

  /** The empty expand-arrow slot of a row with no children. */
  private def toggleSpacer(): HtmlElement =
    span(cls := "node-toggle node-toggle-spacer", Icons.chevronRight("toggle-icon"))

  // ── Public API ────────────────────────────────────────────────

  def apply(
    state: TreeViewState,
    queryMatchedNodes: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
    hoverBridge: ChartHoverBridge = new ChartHoverBridge(),
    changedNodeIds: Signal[Set[NodeId]] = Signal.fromValue(Set.empty),
    selectionLocked: Signal[Boolean] = Val(false),
    pinnedAt: Signal[Option[TreeHistoryEntry]] = Val(None),
    structure: Signal[LoadState[TreeStructureResponse]],
    selection: Signal[MitigationSelection.Enumerated],
    onSelectionEdit: SelectionUpdate => Unit,
    staleAction: MitigationPanel.StaleBadgeAction
  ): HtmlElement =
    // Local state: which node's picker popover is open (if any)
    val pickerOpenFor: Var[Option[NodeId]] = Var(None)
    // Pulsed true when a Ctrl-gesture is blocked because the row mirrors the
    // baseline selection; auto-hides shortly after so the notice is transient.
    val lockedNoticeVisible: Var[Boolean] = Var(false)
    // Pulsed true when a Ctrl+click is refused because the curve selection is
    // full; auto-hides like the locked notice.
    val capNoticeVisible: Var[Boolean] = Var(false)
    // Strict mirror of the (possibly derived — mirror OR rewound) incoming lock,
    // so the click handler can read it synchronously via `.now()`.
    val lockedNow: Var[Boolean] = Var(false)

    div(
      cls := "tree-detail-view",
      position.relative,
      selectionLocked --> lockedNow.writer,
      // Historical-view banner while a history stop other than the head is
      // pinned. Informational only — the pin is a read dimension, not a lock:
      // Ctrl+click still charts curves at the pinned commit.
      child.maybe <-- pinnedAt.map {
        case Some(entry) =>
          Some(div(cls := "pinned-banner", s"Viewing ${entry.at} · ${entry.commitHash.value.take(8)}"))
        case None => None
      },
      // Close picker when clicking outside (attached to the container).
      // Guarded: this fires on EVERY click in the tree, and Var.set emits
      // even when the value is unchanged — an unguarded None-over-None write
      // here rippled through clearPreview into the chart-spec chain and
      // re-embedded the Vega chart (resetting its toggles) on every click.
      onClick --> { _ => if pickerOpenFor.now().isDefined then pickerOpenFor.set(None) },
      // The Escape key closes the picker.
      onKeyDown --> { ev => if ev.key == "Escape" && pickerOpenFor.now().isDefined then pickerOpenFor.set(None) },
      // Closing the picker clears its colour preview.
      pickerOpenFor.signal.changes.filter(_.isEmpty) --> { _ => state.clearPreview() },
      // Auto-hide the transient selection-locked notice; each fresh block
      // restarts the timer (flatMapSwitch drops the previous delay).
      lockedNoticeVisible.signal.changes.filter(identity)
        .flatMapSwitch(_ => EventStream.fromValue(false).delay(TransientNoticeMillis)) --> lockedNoticeVisible.writer,
      // A refused selection shows its notice here, beside the tree the click
      // was on, and restarts the same auto-hide.
      state.chartState.selectionCapReached --> { _ => capNoticeVisible.set(true) },
      capNoticeVisible.signal.changes.filter(identity)
        .flatMapSwitch(_ => EventStream.fromValue(false).delay(TransientNoticeMillis)) --> capNoticeVisible.writer,
      child.maybe <-- capNoticeVisible.signal.map { visible =>
        Option.when(visible)(div(cls := "selection-cap-notice", BlockedMessages.selectionCapReached(LECChartState.MaxUserSelections)))
      },
      // Turning Mirror off clears any pending notice at once, so it never keeps
      // telling the user to disable an already-disabled toggle.
      lockedNow.signal.changes.filter(!_) --> { _ => if lockedNoticeVisible.now() then lockedNoticeVisible.set(false) },
      child.maybe <-- lockedNoticeVisible.signal.map { visible =>
        Option.when(visible)(div(cls := "selection-locked-notice", NoticeMessages.SelectionMirrored))
      },
      MitigationPanel(
        structure,
        selection,
        id => onSelectionEdit(SelectionEdit.toggleWhole(_, id)),
        lockedNow.signal,
        staleAction
      ),
      child <-- structure.map {
        case LoadState.Idle        => renderPlaceholder("Select a tree to view its structure.")
        case LoadState.Loading     => renderPlaceholder("Loading tree structure…")
        // The selected tree is held but absent at the branch or commit read.
        // There is nothing to show, and the sentence says where it was looked for,
        // composed from the kind of absence the server's answer names.
        case LoadState.TreeAbsent(failure) => renderPlaceholder(ErrorMessages.viewFailed(failure))
        case LoadState.Failed(failure) => FailureNotice(failure, None)
        case LoadState.Loaded(response) =>
          val view = NodeView(state, queryMatchedNodes, hoverBridge, pickerOpenFor, changedNodeIds,
            lockedNow.signal, lockedNoticeVisible, response, selection, onSelectionEdit, staleAction)
          renderTree(response.tree, view)
      }
    )

  /** What every node row of one rendered tree reads: the tree view's state, the
    * card's signals and callbacks, and the structure read the tree came from. */
  private final case class NodeView(
    state:               TreeViewState,
    queryMatchedNodes:   Signal[Set[NodeId]],
    hoverBridge:         ChartHoverBridge,
    pickerOpenFor:       Var[Option[NodeId]],
    changedNodeIds:      Signal[Set[NodeId]],
    selectionLocked:     StrictSignal[Boolean],
    lockedNoticeVisible: Var[Boolean],
    response:            TreeStructureResponse,
    selection:           Signal[MitigationSelection.Enumerated],
    onSelectionEdit:     SelectionUpdate => Unit,
    staleAction:         MitigationPanel.StaleBadgeAction
  )

  private def renderPlaceholder(message: String): HtmlElement =
    div(cls := "tree-detail-placeholder", p(message))

  /** Render the full tree from a RiskTree domain object. */
  private def renderTree(tree: RiskTree, view: NodeView): HtmlElement =
    // Build children lookup: parentId → child nodes
    val childrenOf: Map[Option[NodeId], Seq[RiskNode]] =
      tree.nodes.groupBy(_.parentId)

    val rootNode = tree.nodes.find(_.id == tree.rootId)

    div(
      cls := "tree-detail-container",
      div(
        cls := "tree-detail-header",
        Icons.treeRoot("tree-root-icon"),
        span(cls := "tree-name", tree.name.value.toString),
        child.maybe <-- view.selectionLocked.map { locked =>
          Option.when(locked)(span(cls := "selection-lock-hint", "mirroring baseline"))
        }
      ),
      rootNode match
        case Some(root) =>
          div(
            cls := "tree-detail-nodes",
            renderNode(root, childrenOf, view, depth = 0)
          )
        case None =>
          FailureNotice.lines(List(ErrorMessages.RootNodeMissing), None)
    )

  /** Recursively render a node and its children. */
  private def renderNode(
    node:       RiskNode,
    childrenOf: Map[Option[NodeId], Seq[RiskNode]],
    view:       NodeView,
    depth:      Int
  ): HtmlElement =
    val NodeView(state, queryMatchedNodes, hoverBridge, pickerOpenFor, changedNodeIds,
      _, _, response, _, _, _) = view
    val nodeId      = node.id
    val children    = childrenOf.getOrElse(Some(nodeId), Seq.empty)
    val hasChildren = isPortfolio(node) && children.nonEmpty

    val isExpanded: Signal[Boolean] = state.expandedNodes.signal.map(_.contains(nodeId))
    val isSelected: Signal[Boolean] = state.selectedNodeId.signal.map(_.contains(nodeId))

    val borderStyle: Signal[String] =
      state.nodeColorMap
        .combineWith(queryMatchedNodes, hoverBridge.hoveredCurveId.signal)
        .map { (colorMap, queryMatched, hoveredId) =>
          borderStyleFor(colorMap.get(nodeId), queryMatched.contains(nodeId), hoveredId.contains(nodeId))
        }

    // Ctrl+click puts the node on the chart and Ctrl+Shift+click takes it off;
    // each does nothing when the node is already where it asks. A plain click
    // selects the node for its detail.
    def handleNodeClick(ev: org.scalajs.dom.MouseEvent): Unit =
      onRowGesture(ev, view,
        add    = () => chartNode(state, nodeId),
        remove = () => unchartNode(state, nodeId),
        plain  = () => state.selectNode(nodeId)
      )

    val isCharted: Signal[Boolean]             = state.nodeColorMap.map(_.contains(nodeId))
    val currentColor: Signal[Option[HexColor]] = state.nodeColorMap.map(_.get(nodeId))
    val pickerOpen: Signal[Boolean]            = pickerOpenFor.signal.map(_.contains(nodeId))
    val isChanged: Signal[Boolean]             = changedNodeIds.map(_.contains(nodeId))

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
      else toggleSpacer()

    val row = TreeNodeRow(
      label         = nodeLabel(node),
      kind          = nodeKind,
      depth         = depth,
      tooltip       = Some(nodeTooltip(node)),
      onRemove      = None,
      onNodeClick   = Some(handleNodeClick),
      isSelected    = isSelected,
      prefixContent = List(toggleSpan),
      suffixContent = List(
        MitigationReadout.badgeAt(response, nodeId).map(renderCoverageBadge),
        child.maybe <-- isChanged.map(renderChangedMarker),
        child.maybe <-- isCharted.combineWith(currentColor).map(renderSwatchTrigger(nodeId, pickerOpenFor, _, _)),
        child.maybe <-- pickerOpen.combineWith(currentColor).map(renderPickerPopover(nodeId, state, pickerOpenFor, _, _))
      )
    )

    div(
      cls := "tree-detail-node",
      decorateRow(row, borderStyle, nodeId, view),
      MitigationReadout.scopingAt(response, nodeId).map(renderMitigationRow(nodeId, _, view, depth + 1)),
      if hasChildren then
        div(
          cls := "node-children",
          display <-- isExpanded.map(if _ then "block" else "none"),
          children.toList.map { c =>
            renderNode(c, childrenOf, view, depth + 1)
          }
        )
      else
        emptyNode
    )

  /** One mitigation scoped at `node`, as a row under it drawn like a node row.
    * Ctrl+click applies the mitigation at this node and puts the node on the
    * chart, so its mitigated curve is drawn; Ctrl+Shift+click stops applying
    * it here. An applied row carries the node's curve colour as its border.
    * The stale badge marks an absolute statement whose leaf has changed. */
  private def renderMitigationRow(node: NodeId, mitigation: Mitigation, view: NodeView, depth: Int): HtmlElement =
    val id    = mitigation.id
    val scope = MitigationReadout.scopeOf(view.response, id)
    val stale = mitigation.spec match
      case _: MitigationSpec.LeafAbsolute                                 => view.response.staleMitigationIds.contains(id)
      case _: MitigationSpec.LeafRelative | _: MitigationSpec.ResultStage => false

    val borderStyle: Signal[String] =
      view.selection
        .combineWith(view.state.nodeColorMap, view.hoverBridge.hoveredCurveId.signal)
        .map { (selection, colorMap, hoveredId) =>
          val colour = colorMap.get(node).filter(_ => SelectionEdit.appliesAt(selection, id, node))
          borderStyleFor(colour, queryMatched = false, hoveredId.contains(node))
        }

    def handleClick(ev: org.scalajs.dom.MouseEvent): Unit =
      onRowGesture(ev, view,
        add = () =>
          view.onSelectionEdit(SelectionEdit.applyAt(_, id, node, scope))
          chartNode(view.state, node),
        remove = () => view.onSelectionEdit(SelectionEdit.removeAt(_, id, node, scope)),
        plain  = () => ()
      )

    val row = TreeNodeRow(
      label         = mitigation.name.value,
      kind          = TreeNodeRow.NodeKind.Mitigation,
      depth         = depth,
      tooltip       = None,
      onRemove      = None,
      onNodeClick   = Some(handleClick),
      prefixContent = List(toggleSpacer()),
      suffixContent = List(Option.when(stale)(MitigationPanel.staleBadge(id, view.staleAction)))
    )
    decorateRow(row, borderStyle, node, view)

  /** Put `node` on the chart, unless it is already there. */
  private def chartNode(state: TreeViewState, node: NodeId): Unit =
    if !state.userSelectedNodeIds.now().contains(node) then state.userSelectionToggle.onNext(node)

  /** Take `node` off the chart, unless it is not there. */
  private def unchartNode(state: TreeViewState, node: NodeId): Unit =
    if state.userSelectedNodeIds.now().contains(node) then state.userSelectionToggle.onNext(node)

  /** The node's coverage badge: solid where a mitigation is scoped at the node,
    * faint where one is scoped only below it. Its tooltip lists them. */
  private def renderCoverageBadge(badge: CoverageBadge): HtmlElement =
    val (modifier, mitigations, heading) = badge match
      case CoverageBadge.Scoped(ms)    => ("coverage-badge--scoped", ms, "Mitigations scoped here")
      case CoverageBadge.BelowOnly(ms) => ("coverage-badge--below", ms, "Mitigations scoped below")
    span(
      cls := s"coverage-badge $modifier",
      title := (heading :: mitigations.toList.map(_.name.value)).mkString("\n"),
      "◆"
    )

  // ── View helpers ──

  /** ✎ marker for a node the active Compare diff reports as changed.
    * Identical and unchanged nodes render nothing. */
  private def renderChangedMarker(changed: Boolean): Option[HtmlElement] =
    Option.when(changed)(span(cls := "node-changed-marker", title := "Changed vs. compared branch", "✎"))

  /** Render the colour-swatch trigger icon for charted nodes. A click opens
    * this node's picker, or closes it when it is already open. */
  private def renderSwatchTrigger(
    nodeId: NodeId,
    pickerOpenFor: Var[Option[NodeId]],
    charted: Boolean,
    colorOpt: Option[HexColor]
  ): Option[HtmlElement] =
    Option.when(charted)(div(
      cls := "node-swatch-trigger",
      styleAttr := s"background-color: ${colorOpt.map(_.value).getOrElse("transparent")};",
      onClick.stopPropagation --> { _ =>
        pickerOpenFor.update(openFor => Option.unless(openFor.contains(nodeId))(nodeId))
      }
    ))

  /** Render the colour picker popover when open for this node. */
  private def renderPickerPopover(
    nodeId: NodeId,
    state: TreeViewState,
    pickerOpenFor: Var[Option[NodeId]],
    open: Boolean,
    colorOpt: Option[HexColor]
  ): Option[HtmlElement] =
    colorOpt.filter(_ => open).map { col =>
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

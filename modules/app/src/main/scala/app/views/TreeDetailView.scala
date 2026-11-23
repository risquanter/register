package app.views

import com.raquo.laminar.api.L.*

import app.chart.{GridColumn, PaletteData}
import app.state.{TreeViewState, LoadState, ChartHoverBridge, CoverageBadge, LECChartState, MitigationReadout, MitigationToggle, SelectionEdit}
import app.components.{Icons, ColorSwatchPicker, FailureNotice, FormInputs, MitigationPanel, TreeNodeRow}
import com.risquanter.register.domain.data.{Mitigation, MitigationSpec, RiskTree, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}
import com.risquanter.register.domain.data.iron.HexColor.HexColor
import com.risquanter.register.http.responses.{TreeHistoryEntry, TreeStructureResponse}

/** One tree card's body: a grid whose rows are the tree's mitigations and
  * nodes and whose columns are selections.
  *
  * The columns are the card's `CurveGrid` columns: Inherent, Residual, and the
  * ones the user added. A box in a node's row draws that node's curve under
  * the column's selection. A box in a mitigation's line, or in a mitigation's
  * row under a node, changes what a user column's selection applies, and
  * draws nothing.
  *
  * {{{
  *                      Inherent  Residual    A    [+]
  * v Mitigations
  *     MFA                          [x]      [x]
  * v Tree structure
  *     Cyber              [x]       [ ]      [x]        Cyber is drawn under Inherent and under A
  *       - MFA                      [x]      [x]        A applies MFA at Cyber
  * }}}
  *
  * A node row's left border is inline-style-driven from `nodeColorMap`:
  * - A node with a curve: solid 3px left border in the node's curve hex colour.
  * - Query-matched with no curve: dotted 3px left border in neutral.
  * - Neither: no border.
  *
  * Receives shared `TreeViewState` (ADR-019 Pattern 2 consumer), and changes
  * the grid only through its `chartState`. Owns local UI state: the open
  * picker, the two sections' open flags and the hovered column.
  *
  * @param state             Tree navigation and chart state.
  * @param queryMatchedNodes Signal of node IDs matching the active query. Their
  *                          Inherent boxes are ticked and fixed.
  * @param changedNodeIds    Signal of node IDs the Compare diff reports changed
  *                          (renders the ✎ marker).
  * @param selectionLocked   When true (the card mirrors the baseline), every
  *                          box and column control is disabled, and a header
  *                          hint marks the mirrored state.
  * @param structure         The structure read the tree is rendered from, with
  *                          each mitigation's resolved scope and the stale ids.
  * @param staleAction       What a stale statement's badge offers on this card.
  */
object TreeDetailView:

  /** How long a transient notice beside the tree stays up, in milliseconds.
    * Chosen by this project: long enough to read one sentence. */
  private val TransientNoticeMillis: Int = 2200

  /** The titles of a loaded card's two sections. */
  private val MitigationsTitle: String = "Mitigations"
  private val StructureTitle: String   = "Tree structure"

  private val NoTreeSelected: String   = "Select a tree to view its structure."
  private val LoadingStructure: String = "Loading tree structure…"
  private val MirroringHint: String    = "mirroring baseline"

  private val AddColumnGlyph: String      = "+"
  private val AddColumnTooltip: String    = "Add a column"
  private val RemoveColumnGlyph: String   = "×"
  private val RemoveColumnTooltip: String = "Remove this column"
  private val HideColumnTooltip: String   = "Hide this column's curves"
  private val ShowColumnTooltip: String   = "Show this column's curves"
  private val ResidualTooltip: String     = "Residual applies every mitigation."
  private val NoTooltip: String           = ""

  /** The grid's columns that are not selections: the name column and the
    * column holding the add button. */
  private val FixedColumns: Int = 2

  /** The CSS `display` of a shown table row and of a hidden one. */
  private val ShownRow: String  = "table-row"
  private val HiddenRow: String = "none"

  /** How many leading characters of a commit hash the pinned banner shows.
    * Chosen by this project; the history slider's tooltip shows as many. */
  private val ShortHashChars: Int = 8

  /** The banner over a card pinned to an earlier commit. */
  private def pinnedBanner(entry: TreeHistoryEntry): String =
    s"Viewing ${entry.at} · ${entry.commitHash.value.take(ShortHashChars)}"

  /** `KeyboardEvent.key` of the Escape key, fixed by the UI Events specification. */
  private val EscapeKey: String = "Escape"

  /** A row's left-border width where its node has a curve, and its width
    * while that curve is hovered. Chosen by this project. */
  private val CurveBorder: String        = "3px"
  private val HoveredCurveBorder: String = "5px"

  /** The inline style of a row with no border. */
  private val NoBorder: String = ""

  /** The CSS colour of a swatch trigger whose node has no colour yet. */
  private val NoSwatchColour: String = "transparent"

  private val ChangedGlyph: String   = "✎"
  private val ChangedTooltip: String = "Changed vs. compared branch"
  private val CoverageGlyph: String  = "◆"
  private val ScopedHereHeading: String  = "Mitigations scoped here"
  private val ScopedBelowHeading: String = "Mitigations scoped below"

  // ── Node display helpers (mirrors TreePreview.TreeNode pattern) ──

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

  /** The left border of a node row: solid in the curve colour, wider while
    * hovered; dotted for a query match with no curve; none otherwise. */
  private def borderStyleFor(
    colour:       Option[HexColor],
    queryMatched: Boolean,
    isHovered:    Boolean
  ): String =
    (colour, queryMatched) match
      case (Some(hex), _) =>
        val width = if isHovered then HoveredCurveBorder else CurveBorder
        s"border-left: $width solid ${hex.value}; padding-left: calc(var(--sp-1) - $width);"
      case (None, true) =>
        s"border-left: $CurveBorder dotted var(--foreground-variant); padding-left: calc(var(--sp-1) - $CurveBorder);"
      case (None, false) => NoBorder

  /** A grid row's name content: the border from `border`, and hovering
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
    staleAction: MitigationPanel.StaleBadgeAction
  ): HtmlElement =
    // Local state: which node's picker popover is open (if any)
    val pickerOpenFor: Var[Option[NodeId]] = Var(None)
    // Pulsed true when a tick is refused because the limit of ticked nodes
    // is reached; auto-hides shortly after so the notice is transient.
    val capNoticeVisible: Var[Boolean] = Var(false)
    // Whether each section of the card is open. Held here rather than in the
    // loaded render, so a reload, a branch switch or a pin keeps them.
    val mitigationsOpen: Var[Boolean] = Var(true)
    val structureOpen: Var[Boolean]   = Var(true)
    // The column the pointer is over, for the column tint.
    val hoveredColumn: Var[Option[GridColumn]] = Var(None)
    val columns: Signal[List[GridColumn]] = state.chartState.grid.map(_.columns).distinct

    div(
      cls := "tree-detail-view",
      position.relative,
      // Historical-view banner while a history stop other than the head is
      // pinned. Informational only — the pin is a read dimension, not a lock:
      // a tick still draws the curve at the pinned commit.
      child.maybe <-- pinnedAt.map {
        case Some(entry) =>
          Some(div(cls := "pinned-banner", pinnedBanner(entry)))
        case None => None
      },
      // Close picker when clicking outside (attached to the container).
      // Guarded: this fires on EVERY click in the tree, and Var.set emits
      // even when the value is unchanged — an unguarded None-over-None write
      // here rippled through clearPreview into the chart-spec chain and
      // re-embedded the Vega chart (resetting its toggles) on every click.
      onClick --> { _ => if pickerOpenFor.now().isDefined then pickerOpenFor.set(None) },
      // The Escape key closes the picker.
      onKeyDown --> { ev => if ev.key == EscapeKey && pickerOpenFor.now().isDefined then pickerOpenFor.set(None) },
      // Closing the picker clears its colour preview.
      pickerOpenFor.signal.changes.filter(_.isEmpty) --> { _ => state.clearPreview() },
      // A refused tick shows its notice here, beside the tree the click was
      // on. Each fresh refusal restarts the auto-hide timer (flatMapSwitch
      // drops the previous delay).
      state.chartState.selectionCapReached --> { _ => capNoticeVisible.set(true) },
      capNoticeVisible.signal.changes.filter(identity)
        .flatMapSwitch(_ => EventStream.fromValue(false).delay(TransientNoticeMillis)) --> capNoticeVisible.writer,
      child.maybe <-- capNoticeVisible.signal.map { visible =>
        Option.when(visible)(div(cls := "selection-cap-notice", BlockedMessages.selectionCapReached(LECChartState.MaxTickedNodes)))
      },
      child <-- structure.map {
        case LoadState.Idle        => renderPlaceholder(NoTreeSelected)
        case LoadState.Loading     => renderPlaceholder(LoadingStructure)
        // The selected tree is held but absent at the branch or commit read.
        // There is nothing to show, and the sentence says where it was looked for,
        // composed from the kind of absence the server's answer names.
        case LoadState.TreeAbsent(failure) => renderPlaceholder(ErrorMessages.viewFailed(failure))
        case LoadState.Failed(failure) => FailureNotice(failure, None)
        case LoadState.Loaded(response) =>
          val view = NodeView(state, queryMatchedNodes, hoverBridge, pickerOpenFor, changedNodeIds,
            selectionLocked, response, staleAction, columns, hoveredColumn)
          renderGrid(response, view, mitigationsOpen, structureOpen)
      }
    )

  /** What every row of one rendered grid reads: the tree view's state, the
    * card's signals, and the structure read the tree came from. */
  private final case class NodeView(
    state:             TreeViewState,
    queryMatchedNodes: Signal[Set[NodeId]],
    hoverBridge:       ChartHoverBridge,
    pickerOpenFor:     Var[Option[NodeId]],
    changedNodeIds:    Signal[Set[NodeId]],
    selectionLocked:   Signal[Boolean],
    response:          TreeStructureResponse,
    staleAction:       MitigationPanel.StaleBadgeAction,
    columns:           Signal[List[GridColumn]],
    hoveredColumn:     Var[Option[GridColumn]]
  )

  private def renderPlaceholder(message: String): HtmlElement =
    div(cls := "tree-detail-placeholder", p(message))

  /** How many columns a cell spanning the whole grid covers. */
  private def fullSpan(view: NodeView): Signal[Int] =
    view.columns.map(_.size + FixedColumns)

  /** Shows a table row while `shown` holds. */
  private def rowDisplay(shown: Signal[Boolean]): Modifier[HtmlElement] =
    display <-- shown.map(if _ then ShownRow else HiddenRow)

  /** The loaded card: one table whose columns are the grid's. */
  private def renderGrid(
    response:        TreeStructureResponse,
    view:            NodeView,
    mitigationsOpen: Var[Boolean],
    structureOpen:   Var[Boolean]
  ): HtmlElement =
    val tree: RiskTree = response.tree
    val childrenOf: Map[Option[NodeId], Seq[RiskNode]] =
      tree.nodes.groupBy(_.parentId)

    val structureRows: List[HtmlElement] = tree.nodes.find(_.id == tree.rootId) match
      case Some(root) => nodeRows(root, childrenOf, view, depth = 0, ancestors = Nil, structureOpen.signal)
      case None =>
        List(tr(
          rowDisplay(structureOpen.signal),
          td(colSpan <-- fullSpan(view), FailureNotice.lines(List(ErrorMessages.RootNodeMissing), None))
        ))

    table(
      cls := "tree-grid",
      // The boxes take the card's palette colour.
      styleAttr <-- view.state.userPalette.map(palette => s"--grid-accent: ${PaletteData.familySwatch(palette).value};"),
      thead(headerRow(tree.name.value, view)),
      tbody(
        sectionRow(MitigationsTitle, mitigationsOpen, view),
        MitigationReadout.mitigations(response).map(mitigationLineRow(_, view, mitigationsOpen.signal)),
        sectionRow(StructureTitle, structureOpen, view),
        structureRows
      )
    )

  /** The header row: the tree's name, one title per column, and the add button. */
  private def headerRow(treeName: String, view: NodeView): HtmlElement =
    val chart = view.state.chartState
    tr(
      th(
        cls := "tree-grid-name tree-grid-corner",
        span(cls := "tree-name", treeName),
        child.maybe <-- view.selectionLocked.map { locked =>
          Option.when(locked)(span(cls := "selection-lock-hint", MirroringHint))
        }
      ),
      children <-- view.columns.split(identity)((column, _, _) => columnHeader(column, view)),
      th(
        cls := "tree-grid-add",
        FormInputs.blockable(
          button(
            cls := "tree-grid-add-btn",
            tpe := "button",
            title := AddColumnTooltip,
            AddColumnGlyph,
            onClick --> (_ => chart.addColumn())
          ),
          chart.grid.combineWith(view.selectionLocked).map { (grid, locked) =>
            (locked, grid.addColumn) match
              case (true, _)        => List(NoticeMessages.SelectionMirrored)
              case (false, None)    => List(BlockedMessages.columnsFull(GridColumn.all.size))
              case (false, Some(_)) => Nil
          }
        )
      )
    )

  /** What tints a cell of the column the pointer is over, and tracks the
    * pointer entering and leaving the cell. */
  private def columnHover(column: GridColumn, view: NodeView): List[Modifier[HtmlElement]] =
    List(
      cls("tree-grid-cell--col-hover") <-- view.hoveredColumn.signal.map(_.contains(column)),
      onMouseEnter --> { _ => view.hoveredColumn.set(Some(column)) },
      onMouseLeave --> { _ => view.hoveredColumn.set(None) }
    )

  /** One column's title: its marker and label, a click on which hides or shows
    * its curves, and a remove button on a user column. */
  private def columnHeader(column: GridColumn, view: NodeView): HtmlElement =
    val chart    = view.state.chartState
    val isHidden = chart.grid.map(_.isHidden(column)).distinct
    val removeButton: Option[HtmlElement] = column match
      case GridColumn.User(user) =>
        Some(button(
          cls := "tree-grid-col-remove",
          tpe := "button",
          title := RemoveColumnTooltip,
          disabled <-- view.selectionLocked,
          RemoveColumnGlyph,
          onClick --> (_ => chart.removeColumn(user))
        ))
      case GridColumn.Inherent | GridColumn.Residual => None
    th(
      cls := "tree-grid-col",
      columnHover(column, view),
      button(
        cls := "tree-grid-col-title",
        cls("tree-grid-col-title--hidden") <-- isHidden,
        tpe := "button",
        title <-- isHidden.map(if _ then ShowColumnTooltip else HideColumnTooltip),
        disabled <-- view.selectionLocked,
        span(cls := "tree-grid-col-glyph", GridColumn.glyph(column)),
        span(GridColumn.label(column)),
        onClick --> (_ => chart.toggleHidden(column))
      ),
      removeButton
    )

  /** A section's title row. A click shows or hides the section's rows. */
  private def sectionRow(heading: String, open: Var[Boolean], view: NodeView): HtmlElement =
    tr(
      cls := "tree-grid-section",
      td(
        colSpan <-- fullSpan(view),
        button(
          cls := "tree-section-title",
          tpe := "button",
          onClick --> { _ => open.update(v => !v) },
          child <-- open.signal.map {
            case true  => Icons.chevronDown("tree-section-chevron")
            case false => Icons.chevronRight("tree-section-chevron")
          },
          span(heading)
        )
      )
    )

  /** The cell under the add button, which holds nothing. */
  private def fillerCell(): HtmlElement = td(cls := "tree-grid-filler")

  /** One mitigation's line in the Mitigations section. */
  private def mitigationLineRow(mitigation: Mitigation, view: NodeView, shown: Signal[Boolean]): HtmlElement =
    tr(
      cls := "tree-grid-row",
      rowDisplay(shown),
      td(cls := "tree-grid-name", MitigationPanel.nameCell(view.response, mitigation, view.staleAction)),
      children <-- view.columns.split(identity)((column, _, _) => wholeScopeCell(column, mitigation.id, view)),
      fillerCell()
    )

  /** A node's row, the rows of the mitigations scoped at it, and its children's. */
  private def nodeRows(
    node:        RiskNode,
    childrenOf:  Map[Option[NodeId], Seq[RiskNode]],
    view:        NodeView,
    depth:       Int,
    ancestors:   List[NodeId],
    sectionOpen: Signal[Boolean]
  ): List[HtmlElement] =
    val NodeView(state, queryMatchedNodes, hoverBridge, pickerOpenFor, changedNodeIds, _, response, _, _, _) = view
    val nodeId      = node.id
    val childNodes  = childrenOf.getOrElse(Some(nodeId), Seq.empty)
    val hasChildren = isPortfolio(node) && childNodes.nonEmpty

    val isExpanded: Signal[Boolean] = state.expandedNodes.signal.map(_.contains(nodeId))
    val isSelected: Signal[Boolean] = state.selectedNodeId.signal.map(_.contains(nodeId))

    // The row is shown while its section is open and every ancestor of its
    // node is expanded.
    val shown: Signal[Boolean] =
      sectionOpen.combineWith(state.expandedNodes.signal)
        .map((open, expanded) => open && ancestors.forall(expanded.contains))
        .distinct

    val borderStyle: Signal[String] =
      state.nodeColorMap
        .combineWith(queryMatchedNodes, hoverBridge.hoveredCurveId.signal)
        .map { (colorMap, queryMatched, hoveredId) =>
          borderStyleFor(colorMap.get(nodeId), queryMatched.contains(nodeId), hoveredId.contains(nodeId))
        }

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
      // A click selects the node for its detail.
      onNodeClick   = Some(_ => state.selectNode(nodeId)),
      isSelected    = isSelected,
      prefixContent = List(toggleSpan),
      suffixContent = List(
        MitigationReadout.badgeAt(response, nodeId).map(renderCoverageBadge),
        child.maybe <-- isChanged.map(renderChangedMarker),
        child.maybe <-- isCharted.combineWith(currentColor).map(renderSwatchTrigger(nodeId, pickerOpenFor, _, _)),
        child.maybe <-- pickerOpen.combineWith(currentColor).map(renderPickerPopover(nodeId, state, pickerOpenFor, _, _))
      )
    )

    val nodeRow = tr(
      cls := "tree-grid-row",
      rowDisplay(shown),
      td(cls := "tree-grid-name", decorateRow(row, borderStyle, nodeId, view)),
      children <-- view.columns.split(identity)((column, _, _) => tickCell(column, nodeId, view)),
      fillerCell()
    )

    nodeRow
      :: MitigationReadout.scopingAt(response, nodeId).map(mitigationRow(nodeId, _, view, depth + 1, shown))
      ::: childNodes.toList.flatMap(nodeRows(_, childrenOf, view, depth + 1, nodeId :: ancestors, sectionOpen))

  /** One mitigation scoped at `node`, as a row under it. Its boxes apply the
    * mitigation at this node in a user column. The stale badge marks an
    * absolute statement whose leaf has changed. */
  private def mitigationRow(node: NodeId, mitigation: Mitigation, view: NodeView, depth: Int, shown: Signal[Boolean]): HtmlElement =
    val id    = mitigation.id
    val scope = MitigationReadout.scopeOf(view.response, id)
    val stale = mitigation.spec match
      case _: MitigationSpec.LeafAbsolute                                 => view.response.staleMitigationIds.contains(id)
      case _: MitigationSpec.LeafRelative | _: MitigationSpec.ResultStage => false

    val row = TreeNodeRow(
      label         = mitigation.name.value,
      kind          = TreeNodeRow.NodeKind.Mitigation,
      depth         = depth,
      tooltip       = None,
      onRemove      = None,
      onNodeClick   = None,
      prefixContent = List(toggleSpacer()),
      suffixContent = List(Option.when(stale)(MitigationPanel.staleBadge(id, view.staleAction)))
    )
    tr(
      cls := "tree-grid-row",
      rowDisplay(shown),
      td(cls := "tree-grid-name", decorateRow(row, Val(NoBorder), node, view)),
      children <-- view.columns.split(identity)((column, _, _) => atNodeCell(column, id, node, scope, view)),
      fillerCell()
    )

  /** A cell of `column`, tinted while the pointer is over the column. */
  private def gridCell(column: GridColumn, view: NodeView, content: Modifier[HtmlElement]*): HtmlElement =
    td(cls := "tree-grid-cell", columnHover(column, view), content)

  /** A box's tooltip: why it cannot be changed, or nothing. */
  private def boxTooltip(matchedByQuery: Boolean, locked: Boolean): String =
    (locked, matchedByQuery) match
      case (true, _)      => NoticeMessages.SelectionMirrored
      case (false, true)  => NoticeMessages.MatchedByQuery
      case (false, false) => NoTooltip

  private def isOn(toggle: MitigationToggle): Boolean = toggle match
    case MitigationToggle.On                             => true
    case MitigationToggle.Off | MitigationToggle.Partial => false

  private def isPartial(toggle: MitigationToggle): Boolean = toggle match
    case MitigationToggle.Partial                   => true
    case MitigationToggle.Off | MitigationToggle.On => false

  /** A ticked box that cannot be changed: the Residual column applies every
    * mitigation. */
  private def fixedBox(): HtmlElement =
    input(typ := "checkbox", cls := "tree-grid-box", checked := true, disabled := true, title := ResidualTooltip)

  /** A box that edits a user column's selection. It is drawn from the
    * selection, so a click the edit refuses leaves it as it was. */
  private def editBox(on: Signal[Boolean], partial: Signal[Boolean], onToggle: () => Unit, view: NodeView): HtmlElement =
    input(
      typ := "checkbox",
      cls := "tree-grid-box",
      controlled(
        checked <-- on,
        onInput.mapToChecked --> (_ => onToggle())
      ),
      indeterminate <-- partial,
      disabled <-- view.selectionLocked,
      title <-- view.selectionLocked.map(boxTooltip(matchedByQuery = false, _))
    )

  /** A node row's cell: the box that draws `node`'s curve under `column`. A
    * node the query matches is ticked and fixed under Inherent. The box is
    * drawn from the grid, so a tick the limit refuses leaves it unticked. */
  private def tickCell(column: GridColumn, node: NodeId, view: NodeView): HtmlElement =
    val chart  = view.state.chartState
    val ticked = chart.grid.map(_.isTicked(column, node))
    val matched: Signal[Boolean] = column match
      case GridColumn.Inherent                      => view.queryMatchedNodes.map(_.contains(node))
      case GridColumn.Residual | GridColumn.User(_) => Val(false)
    gridCell(column, view,
      input(
        typ := "checkbox",
        cls := "tree-grid-box",
        controlled(
          checked <-- ticked.combineWith(matched).map(_ || _),
          onInput.mapToChecked --> (on => chart.setTick(column, node, on))
        ),
        disabled <-- matched.combineWith(view.selectionLocked).map(_ || _),
        title <-- matched.combineWith(view.selectionLocked).map(boxTooltip)
      )
    )

  /** A Mitigations line's cell: empty under Inherent, ticked and fixed under
    * Residual, and a three-state box under a user column. */
  private def wholeScopeCell(column: GridColumn, id: MitigationId, view: NodeView): HtmlElement =
    column match
      case GridColumn.Inherent => gridCell(column, view)
      case GridColumn.Residual => gridCell(column, view, fixedBox())
      case GridColumn.User(user) =>
        val chart  = view.state.chartState
        val toggle = chart.grid.map(grid => SelectionEdit.toggleOf(grid.userSelection(user), id)).distinct
        gridCell(column, view,
          editBox(toggle.map(isOn), toggle.map(isPartial), () => chart.editSelection(user, SelectionEdit.toggleWhole(_, id)), view))

  /** A mitigation row's cell: empty under Inherent, ticked and fixed under
    * Residual, and a box applying the mitigation at `node` under a user column. */
  private def atNodeCell(column: GridColumn, id: MitigationId, node: NodeId, scope: Set[NodeId], view: NodeView): HtmlElement =
    column match
      case GridColumn.Inherent => gridCell(column, view)
      case GridColumn.Residual => gridCell(column, view, fixedBox())
      case GridColumn.User(user) =>
        val chart   = view.state.chartState
        val applies = chart.grid.map(grid => SelectionEdit.appliesAt(grid.userSelection(user), id, node)).distinct
        gridCell(column, view,
          editBox(applies, Val(false), () => chart.editSelection(user, SelectionEdit.toggleAt(_, id, node, scope)), view))

  /** The node's coverage badge: solid where a mitigation is scoped at the node,
    * faint where one is scoped only below it. Its tooltip lists them. */
  private def renderCoverageBadge(badge: CoverageBadge): HtmlElement =
    val (modifier, mitigations, heading) = badge match
      case CoverageBadge.Scoped(ms)    => ("coverage-badge--scoped", ms, ScopedHereHeading)
      case CoverageBadge.BelowOnly(ms) => ("coverage-badge--below", ms, ScopedBelowHeading)
    span(
      cls := s"coverage-badge $modifier",
      title := (heading :: mitigations.toList.map(_.name.value)).mkString("\n"),
      CoverageGlyph
    )

  // ── View helpers ──

  /** ✎ marker for a node the active Compare diff reports as changed.
    * Identical and unchanged nodes render nothing. */
  private def renderChangedMarker(changed: Boolean): Option[HtmlElement] =
    Option.when(changed)(span(cls := "node-changed-marker", title := ChangedTooltip, ChangedGlyph))

  /** Render the colour-swatch trigger icon for nodes with a curve. A click
    * opens this node's picker, or closes it when it is already open. */
  private def renderSwatchTrigger(
    nodeId: NodeId,
    pickerOpenFor: Var[Option[NodeId]],
    charted: Boolean,
    colorOpt: Option[HexColor]
  ): Option[HtmlElement] =
    Option.when(charted)(div(
      cls := "node-swatch-trigger",
      styleAttr := s"background-color: ${colorOpt.map(_.value).getOrElse(NoSwatchColour)};",
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

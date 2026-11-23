package app.state

import zio.prelude.Validation

import app.chart.{GridColumn, UserColumn}
import com.risquanter.register.domain.data.MitigationSelection
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.domain.errors.ValidationError

/** One card's grid: the columns the user added with the selection each
  * states, the nodes ticked in each column, and the columns hidden from the
  * chart. Built from `empty` by the edits below, so a tick or a hidden mark
  * never names a user column the grid does not hold, and no column holds an
  * empty set of ticks.
  *
  * {{{
  * val withA = CurveGrid.empty.addColumn.get            // columns Inherent, Residual, A
  * withA.tick(GridColumn.Inherent, cyber, cap = 13)     // Inherent holds cyber
  *   .map(_.requested(query = Set.empty))               // Inherent -> (Inherent, Set(cyber))
  * }}}
  */
final case class CurveGrid private (
  userColumns: Map[UserColumn, MitigationSelection.Enumerated],
  ticks:       Map[GridColumn, Set[NodeId]],
  hidden:      Set[GridColumn]
):
  /** Whether the grid holds `column`. Inherent and Residual are always held. */
  private def holds(column: GridColumn): Boolean = column match
    case GridColumn.Inherent | GridColumn.Residual => true
    case GridColumn.User(user)                     => userColumns.contains(user)

  /** The selection `column` states. */
  private def selectionOf(column: GridColumn): MitigationSelection = column match
    case GridColumn.Inherent   => MitigationSelection.Inherent
    case GridColumn.Residual   => MitigationSelection.Residual
    case GridColumn.User(user) => userSelection(user)

  /** The ticks with `column` holding `nodes`. A column left with none is
    * dropped, so two grids with the same ticks are equal. */
  private def ticksWith(column: GridColumn, nodes: Set[NodeId]): Map[GridColumn, Set[NodeId]] =
    if nodes.isEmpty then ticks - column else ticks.updated(column, nodes)

  private def ticksIn(column: GridColumn): Set[NodeId] = ticks.getOrElse(column, Set.empty)

  /** Inherent, Residual, then the user columns held, in letter order. */
  def columns: List[GridColumn] = GridColumn.all.filter(holds)

  /** The selection a user column states. A column not held applies nothing. */
  def userSelection(column: UserColumn): MitigationSelection.Enumerated =
    userColumns.getOrElse(column, MitigationSelection.Inherent)

  def isTicked(column: GridColumn, node: NodeId): Boolean = ticksIn(column).contains(node)

  def isHidden(column: GridColumn): Boolean = hidden.contains(column)

  /** Every node ticked in at least one column. The limit counts these. */
  def tickedNodes: Set[NodeId] = ticks.values.flatten.toSet

  /** The grid with the first unused letter added, applying nothing. `None`
    * when all six are held. */
  def addColumn: Option[CurveGrid] =
    UserColumn.values.find(user => !userColumns.contains(user))
      .map(user => copy(userColumns = userColumns.updated(user, MitigationSelection.Inherent)))

  /** The grid without `column`, its ticks and its hidden mark. */
  def removeColumn(column: UserColumn): CurveGrid =
    val gridColumn = GridColumn.User(column)
    copy(userColumns = userColumns - column, ticks = ticks - gridColumn, hidden = hidden - gridColumn)

  /** The grid with `edit` applied to a held column's selection. A column not
    * held is left as it is. */
  def editSelection(
    column: UserColumn,
    edit:   MitigationSelection.Enumerated => Validation[ValidationError, MitigationSelection.Enumerated]
  ): Validation[ValidationError, CurveGrid] =
    userColumns.get(column) match
      case Some(selection) => edit(selection).map(edited => copy(userColumns = userColumns.updated(column, edited)))
      case None            => Validation.succeed(this)

  /** The grid with `node` ticked in `column`. `None` when `node` holds no
    * tick yet and `cap` nodes already do. A column not held takes no tick. */
  def tick(column: GridColumn, node: NodeId, cap: Int): Option[CurveGrid] =
    (holds(column), tickedNodes) match
      case (false, _)                                                  => Some(this)
      case (true, nodes) if !nodes.contains(node) && nodes.size >= cap => None
      case (true, _)                                                   => Some(copy(ticks = ticksWith(column, ticksIn(column) + node)))

  def untick(column: GridColumn, node: NodeId): CurveGrid =
    copy(ticks = ticksWith(column, ticksIn(column) - node))

  /** Hide a shown column of the grid, or show a hidden one. A column not held
    * takes no mark. */
  def toggleHidden(column: GridColumn): CurveGrid =
    (holds(column), hidden.contains(column)) match
      case (false, _)    => this
      case (true, true)  => copy(hidden = hidden - column)
      case (true, false) => copy(hidden = hidden + column)

  /** The columns and their selections, with no node ticked. */
  def withoutTicks: CurveGrid = copy(ticks = Map.empty)

  /** The ticks kept to `nodes`. */
  def restrictedTo(nodes: Set[NodeId]): CurveGrid =
    copy(ticks = ticks.view.mapValues(_.intersect(nodes)).filter((_, kept) => kept.nonEmpty).toMap)

  /** `nodes` added to the Inherent column's ticks, uncapped. */
  def withInherentTicks(nodes: Set[NodeId]): CurveGrid =
    copy(ticks = ticksWith(GridColumn.Inherent, ticksIn(GridColumn.Inherent) ++ nodes))

  /** What the card asks for: each column holding a tick, with its selection
    * and its nodes. `query` counts as ticks in the Inherent column. */
  def requested(query: Set[NodeId]): Map[GridColumn, (MitigationSelection, Set[NodeId])] =
    withInherentTicks(query).ticks.map((column, nodes) => column -> (selectionOf(column), nodes))

object CurveGrid:
  /** Inherent and Residual, nothing ticked, nothing hidden. */
  val empty: CurveGrid = new CurveGrid(Map.empty, Map.empty, Set.empty)

package app.state

import zio.prelude.Validation
import zio.test.*

import app.chart.{GridColumn, UserColumn}
import com.risquanter.register.domain.data.MitigationSelection
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId}

/** Pure tests for a card's grid: its columns, its ticks and their limit, its
  * hidden marks, and what it asks for. */
object CurveGridSpec extends ZIOSpecDefault:

  private def node(i: Int): NodeId = NodeId.fromString(f"01HX9ABCDE00000000000000$i%02d").toOption.get

  private val n1 = node(1)
  private val n2 = node(2)
  private val n3 = node(3)

  private val cap = 13

  private val columnA = GridColumn.User(UserColumn.A)
  private val columnB = GridColumn.User(UserColumn.B)

  private val selectingM: MitigationSelection.Enumerated = MitigationSelection.single(MitigationId(node(50).toSafeId))

  /** The grid after `count` columns were added to `grid`. */
  private def withColumns(grid: CurveGrid, count: Int): Option[CurveGrid] =
    (1 to count).foldLeft(Option(grid))((acc, _) => acc.flatMap(_.addColumn))

  /** `grid` with `nodes` ticked in `column`, all within the limit. */
  private def ticked(grid: CurveGrid, column: GridColumn, nodes: NodeId*): CurveGrid =
    nodes.foldLeft(grid)((acc, n) => acc.tick(column, n, cap).get)

  private val withA: CurveGrid = CurveGrid.empty.addColumn.get

  def spec = suite("CurveGrid")(

    test("empty holds Inherent and Residual, with nothing ticked and nothing hidden") {
      assertTrue(
        CurveGrid.empty.columns == List(GridColumn.Inherent, GridColumn.Residual),
        CurveGrid.empty.tickedNodes.isEmpty,
        !CurveGrid.empty.isHidden(GridColumn.Inherent),
        CurveGrid.empty.requested(Set.empty).isEmpty
      )
    },

    test("addColumn takes the first unused letter, applying nothing") {
      val twoColumns = withColumns(CurveGrid.empty, 2).get
      val withoutA   = twoColumns.removeColumn(UserColumn.A)
      assertTrue(
        withA.columns == List(GridColumn.Inherent, GridColumn.Residual, columnA),
        withA.userSelection(UserColumn.A) == MitigationSelection.Inherent,
        twoColumns.columns == List(GridColumn.Inherent, GridColumn.Residual, columnA, columnB),
        withoutA.addColumn.map(_.columns) == Some(List(GridColumn.Inherent, GridColumn.Residual, columnA, columnB))
      )
    },

    test("addColumn gives none once all six user columns are held") {
      val full = withColumns(CurveGrid.empty, UserColumn.values.length)
      assertTrue(
        full.map(_.columns) == Some(GridColumn.all),
        full.flatMap(_.addColumn).isEmpty
      )
    },

    test("removeColumn drops the column's selection, its ticks and its hidden mark") {
      val edited  = withA.editSelection(UserColumn.A, _ => Validation.succeed(selectingM)).toOption.get
      val used    = ticked(edited, columnA, n1).toggleHidden(columnA)
      val removed = used.removeColumn(UserColumn.A)
      assertTrue(
        used.isTicked(columnA, n1),
        used.isHidden(columnA),
        removed == CurveGrid.empty,
        removed.userSelection(UserColumn.A) == MitigationSelection.Inherent
      )
    },

    test("editSelection changes a held column's selection and leaves a column not held as it is") {
      val edited    = withA.editSelection(UserColumn.A, _ => Validation.succeed(selectingM)).toOption
      val notHeld   = withA.editSelection(UserColumn.B, _ => Validation.succeed(selectingM)).toOption
      assertTrue(
        edited.map(_.userSelection(UserColumn.A)) == Some(selectingM),
        notHeld == Some(withA)
      )
    },

    test("a tick is refused for a node past the limit, and accepted for a second column of a ticked node") {
      val full = ticked(CurveGrid.empty, GridColumn.Inherent, (1 to cap).map(node)*)
      assertTrue(
        full.tickedNodes.size == cap,
        full.tick(GridColumn.Inherent, node(cap + 1), cap).isEmpty,
        full.tick(GridColumn.Residual, node(cap + 1), cap).isEmpty,
        full.tick(GridColumn.Residual, n1, cap).exists(_.isTicked(GridColumn.Residual, n1))
      )
    },

    test("a tick in a column the grid does not hold changes nothing") {
      assertTrue(CurveGrid.empty.tick(columnA, n1, cap) == Some(CurveGrid.empty))
    },

    test("untick removes the tick, and a grid left with none equals one never ticked") {
      val one = ticked(withA, columnA, n1)
      assertTrue(
        one.isTicked(columnA, n1),
        !one.isTicked(GridColumn.Inherent, n1),
        one.untick(columnA, n1) == withA
      )
    },

    test("toggleHidden hides a shown column and shows a hidden one, and ignores a column not held") {
      val hidden = CurveGrid.empty.toggleHidden(GridColumn.Residual)
      assertTrue(
        hidden.isHidden(GridColumn.Residual),
        hidden.toggleHidden(GridColumn.Residual) == CurveGrid.empty,
        CurveGrid.empty.toggleHidden(columnA) == CurveGrid.empty
      )
    },

    test("withoutTicks keeps the columns, their selections and the hidden marks") {
      val edited = withA.editSelection(UserColumn.A, _ => Validation.succeed(selectingM)).toOption.get.toggleHidden(columnA)
      val used   = ticked(ticked(edited, GridColumn.Inherent, n1), columnA, n2)
      assertTrue(
        used.withoutTicks == edited,
        used.withoutTicks.tickedNodes.isEmpty
      )
    },

    test("restrictedTo keeps only the ticks of the given nodes") {
      val used = ticked(ticked(withA, GridColumn.Inherent, n1, n2), columnA, n2, n3)
      val kept = used.restrictedTo(Set(n1, n3))
      assertTrue(
        kept.tickedNodes == Set(n1, n3),
        kept.isTicked(GridColumn.Inherent, n1),
        kept.isTicked(columnA, n3),
        used.restrictedTo(Set.empty) == withA
      )
    },

    test("withInherentTicks adds nodes to the Inherent column past the limit") {
      val many = (1 to cap + 2).map(node).toSet
      assertTrue(CurveGrid.empty.withInherentTicks(many).tickedNodes == many)
    },

    test("requested gives each ticked column its selection and nodes, adds the query's matches to Inherent, and leaves out a column with no tick") {
      val edited = withColumns(CurveGrid.empty, 2).get
        .editSelection(UserColumn.A, _ => Validation.succeed(selectingM)).toOption.get
      val used   = ticked(ticked(edited, GridColumn.Residual, n1), columnA, n2)
      assertTrue(
        used.requested(Set.empty) == Map(
          GridColumn.Residual -> (MitigationSelection.Residual, Set(n1)),
          columnA             -> (selectingM, Set(n2))
        ),
        used.requested(Set(n3)).get(GridColumn.Inherent) == Some((MitigationSelection.Inherent, Set(n3))),
        !used.requested(Set(n3)).contains(columnB)
      )
    }
  )

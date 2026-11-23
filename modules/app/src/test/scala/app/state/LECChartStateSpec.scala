package app.state

import zio.prelude.Validation
import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import io.github.iltotore.iron.*

import app.chart.{GridColumn, PaletteData, UserColumn}
import app.core.LocalProblem
import com.risquanter.register.domain.data.{LECNodeCurve, MitigationSelection, RiskTree, ScopeRestriction}
import com.risquanter.register.domain.data.iron.{BranchChoice, CommitHash, MitigationId, NodeId, Probability, SafeName, TreeId, WorkspaceKeySecret}
import com.risquanter.register.http.requests.LECCurvesMultiRequest
import com.risquanter.register.http.responses.{CurveValuationResponse, LECCurvesResponse, LECNodeSeries}

/** Tests for `LECChartState`: its edits of the card's grid, the curve request
  * a grid builds, the form an answer is held in, and the curves drawn from
  * it. */
object LECChartStateSpec extends ZIOSpecDefault:

  private def node(i: Int): NodeId = NodeId.fromString(f"01HX9ABCDE00000000000000$i%02d").toOption.get

  private val n1 = node(1)
  private val n2 = node(2)

  private val noLoss: Probability = 0.5

  /** A curve told apart from another by its average annual loss alone. */
  private def curve(id: NodeId, averageAnnualLoss: Double): LECNodeCurve =
    LECNodeCurve(id, SafeName.fromString("Node").toOption.get, Vector.empty, Map.empty, averageAnnualLoss, noLoss)

  private def newState: LECChartState =
    new LECChartState(
      keySignal      = Val(Option.empty[WorkspaceKeySecret]),
      selectedTreeId = Val(Option.empty[TreeId]),
      selectedTree   = Val[LoadState[RiskTree]](LoadState.Idle),
      userIdAccessor = () => None,
      branchAccessor = () => BranchChoice.Main,
      atAccessor     = () => Option.empty[CommitHash],
      userPalette    = Val(PaletteData.Aqua),
      origin         = Val(None)
    )

  private val mitigation = MitigationId(n2.toSafeId)
  private val other      = MitigationId(n1.toSafeId)
  private val selectingM: MitigationSelection.Enumerated = MitigationSelection.single(mitigation)

  private val columnA = GridColumn.User(UserColumn.A)
  private val columnB = GridColumn.User(UserColumn.B)

  private val inherentAtN1  = curve(n1, 100.0)
  private val mitigatedAtN1 = curve(n1, 50.0)
  private val inherentAtN2  = curve(n2, 70.0)

  /** The answer held: n1 and n2 under Inherent, n1 under the selection. */
  private val held: Map[MitigationSelection, Map[NodeId, LECNodeSeries]] = Map(
    MitigationSelection.Inherent -> Map(n1 -> LECNodeSeries(inherentAtN1, Nil), n2 -> LECNodeSeries(inherentAtN2, Nil)),
    selectingM                   -> Map(n1 -> LECNodeSeries(mitigatedAtN1, List(mitigation)))
  )

  /** Inherent holds n1 and n2, and column A holds n1 under the selection. */
  private val asked: Map[GridColumn, (MitigationSelection, Set[NodeId])] = Map(
    GridColumn.Inherent -> (MitigationSelection.Inherent, Set(n1, n2)),
    columnA             -> (selectingM, Set(n1))
  )

  /** What a request states, as (selection, nodes) per valuation in order. */
  private def stated(
    result: Option[Either[LocalProblem, LECCurvesMultiRequest]]
  ): Option[List[(MitigationSelection, List[NodeId])]] =
    result.flatMap(_.toOption).map(_.valuations.map(v => v.selection -> v.nodeIds))

  def spec = suite("LECChartState")(

    test("a tick past the limit is refused, leaves the grid unchanged and emits the notice") {
      val owner = new ManualOwner
      val state = newState
      var refusals = 0
      state.selectionCapReached.foreach(_ => refusals += 1)(using owner)
      (1 to LECChartState.MaxTickedNodes).foreach(i => state.setTick(GridColumn.Inherent, node(i), on = true))
      val full = state.grid.now()
      state.setTick(GridColumn.Inherent, node(LECChartState.MaxTickedNodes + 1), on = true)
      val afterRefusal = state.grid.now()
      owner.killSubscriptions()
      assertTrue(
        full.tickedNodes.size == LECChartState.MaxTickedNodes,
        afterRefusal == full,
        refusals == 1
      )
    },

    test("setTick ticks and unticks one cell") {
      val state = newState
      state.setTick(GridColumn.Residual, n1, on = true)
      val ticked = state.grid.now().isTicked(GridColumn.Residual, n1)
      state.setTick(GridColumn.Residual, n1, on = false)
      assertTrue(ticked, state.grid.now() == CurveGrid.empty)
    },

    test("setGrid replaces the whole grid and skips a no-op write") {
      val owner = new ManualOwner
      val state = newState
      var emissions = 0
      state.grid.foreach(_ => emissions += 1)(using owner)
      val afterObserve = emissions               // the initial (empty) emission
      val withTick = CurveGrid.empty.tick(GridColumn.Inherent, n1, LECChartState.MaxTickedNodes).get

      state.setGrid(withTick)
      val afterSet = state.grid.now()
      val emissionsAfterSet = emissions

      state.setGrid(withTick)                    // identical → no write, no emission
      val emissionsAfterNoop = emissions

      state.clearGrid()                          // different → wholesale replace
      val afterClear = state.grid.now()

      owner.killSubscriptions()
      assertTrue(
        afterObserve == 1,
        afterSet == withTick,
        emissionsAfterSet == afterObserve + 1,
        emissionsAfterNoop == emissionsAfterSet,
        afterClear == CurveGrid.empty
      )
    },

    test("the column edits add, edit, hide and remove a user column") {
      val state = newState
      state.addColumn()
      state.editSelection(UserColumn.A, _ => Validation.succeed(selectingM))
      state.toggleHidden(columnA)
      val edited = state.grid.now()
      state.removeColumn(UserColumn.A)
      assertTrue(
        edited.columns == List(GridColumn.Inherent, GridColumn.Residual, columnA),
        edited.userSelection(UserColumn.A) == selectingM,
        edited.isHidden(columnA),
        state.grid.now() == CurveGrid.empty
      )
    },

    test("reset clears the ticks and keeps the columns") {
      val state = newState
      state.addColumn()
      state.setTick(columnA, n1, on = true)
      state.reset()
      assertTrue(
        state.grid.now().tickedNodes.isEmpty,
        state.grid.now().columns == List(GridColumn.Inherent, GridColumn.Residual, columnA)
      )
    },

    test("a request states one valuation per distinct sent selection, in column order, with nodes in identifier order") {
      val result = LECChartState.requestFor(asked, held = Some(Set(mitigation)))
      assertTrue(stated(result) == Some(List(
        MitigationSelection.Inherent -> List(n1, n2),
        selectingM                   -> List(n1)
      )))
    },

    test("two columns with equal selections share one valuation naming the union of their nodes") {
      val twice = Map[GridColumn, (MitigationSelection, Set[NodeId])](
        columnA -> (selectingM, Set(n1)),
        columnB -> (selectingM, Set(n2))
      )
      val result = LECChartState.requestFor(twice, held = Some(Set(mitigation)))
      assertTrue(stated(result) == Some(List(selectingM -> List(n1, n2))))
    },

    test("a column that applies nothing shares the Inherent valuation") {
      val nothing = Map[GridColumn, (MitigationSelection, Set[NodeId])](
        GridColumn.Inherent -> (MitigationSelection.Inherent, Set(n1)),
        columnA             -> (MitigationSelection.Inherent, Set(n2))
      )
      val result = LECChartState.requestFor(nothing, held = Some(Set.empty))
      assertTrue(stated(result) == Some(List(MitigationSelection.Inherent -> List(n1, n2))))
    },

    test("a request names only the mitigations the tree holds") {
      val both = MitigationSelection.create(Map(
        mitigation -> ScopeRestriction.FullScope,
        other      -> ScopeRestriction.FullScope
      )).toOption.get
      val result = LECChartState.requestFor(Map(columnA -> (both, Set(n1))), held = Some(Set(mitigation)))
      assertTrue(stated(result) == Some(List(selectingM -> List(n1))))
    },

    test("the Residual column is sent as Residual whatever the tree holds") {
      val residual = Map[GridColumn, (MitigationSelection, Set[NodeId])](
        GridColumn.Residual -> (MitigationSelection.Residual, Set(n1)))
      assertTrue(
        stated(LECChartState.requestFor(residual, held = Some(Set.empty))) ==
          Some(List(MitigationSelection.Residual -> List(n1))),
        stated(LECChartState.requestFor(residual, held = None)) ==
          Some(List(MitigationSelection.Residual -> List(n1)))
      )
    },

    test("a request waits while the tree's mitigations are unknown and a column names a mitigation") {
      val inherentOnly = Map[GridColumn, (MitigationSelection, Set[NodeId])](
        GridColumn.Inherent -> (MitigationSelection.Inherent, Set(n1)))
      assertTrue(
        LECChartState.requestFor(asked, held = None).isEmpty,
        LECChartState.requestFor(inherentOnly, held = None).exists(_.isRight)
      )
    },

    test("a request its constructor refuses becomes a failed form") {
      val tooMany = (0 to RiskTree.MaxNodes).map(i => NodeId.fromString(f"01HX9ABCDE0000000000$i%06d").toOption.get).toSet
      val refused = Map[GridColumn, (MitigationSelection, Set[NodeId])](
        GridColumn.Inherent -> (MitigationSelection.Inherent, tooMany))
      assertTrue(LECChartState.requestFor(refused, held = None) match
        case Some(Left(LocalProblem.FormInvalid(errors))) => errors.nonEmpty
        case _                                            => false)
    },

    test("an answer is held by the selection each valuation repeats") {
      val response = LECCurvesResponse(List(
        CurveValuationResponse(selectingM, Map(n1 -> LECNodeSeries(mitigatedAtN1, List(mitigation)))),
        CurveValuationResponse(MitigationSelection.Inherent, Map(
          n1 -> LECNodeSeries(inherentAtN1, Nil),
          n2 -> LECNodeSeries(inherentAtN2, Nil)
        ))
      ))
      assertTrue(LECChartState.curvesBySelection(response) == held)
    },

    test("each node is drawn once per shown column holding it, in column order") {
      assertTrue(LECChartState.drawn(asked, hidden = Set.empty, Set(mitigation), held) == Map(
        n1 -> List(GridColumn.Inherent -> inherentAtN1, columnA -> mitigatedAtN1),
        n2 -> List(GridColumn.Inherent -> inherentAtN2)
      ))
    },

    test("a hidden column's curves are left out") {
      assertTrue(
        LECChartState.drawn(asked, hidden = Set(columnA), Set(mitigation), held) == Map(
          n1 -> List(GridColumn.Inherent -> inherentAtN1),
          n2 -> List(GridColumn.Inherent -> inherentAtN2)
        ),
        LECChartState.drawn(asked, hidden = Set(GridColumn.Inherent), Set(mitigation), held) == Map(
          n1 -> List(columnA -> mitigatedAtN1)
        )
      )
    },

    test("a column whose mitigation the tree does not hold is drawn from the valuation it was sent as") {
      // Column A's selection is sent without the absent mitigation, which is Inherent.
      assertTrue(LECChartState.drawn(asked, hidden = Set.empty, Set.empty, held).get(n1) ==
        Some(List(GridColumn.Inherent -> inherentAtN1, columnA -> inherentAtN1)))
    }
  )

package app.state

import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import io.github.iltotore.iron.*

import app.chart.{CurveVariant, PaletteData}
import app.core.LocalProblem
import com.risquanter.register.domain.data.{LECNodeCurve, MitigationSelection, RiskTree}
import com.risquanter.register.domain.data.iron.{BranchChoice, CommitHash, MitigationId, NodeId, Probability, SafeName, TreeId, WorkspaceKeySecret}
import com.risquanter.register.http.responses.LECNodeSeries

/** Tests for `LECChartState`'s user selection — the wholesale-replace write used
  * by the mirror-baseline sync, and the toggle's selection cap — for the
  * readings each display mode draws from an analysis answer, and for the
  * curve request a selection builds. */
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
      keySignal         = Val(Option.empty[WorkspaceKeySecret]),
      selectedTreeId    = Val(Option.empty[TreeId]),
      selectedTree      = Val[LoadState[RiskTree]](LoadState.Idle),
      userIdAccessor    = () => None,
      branchAccessor    = () => BranchChoice.Main,
      atAccessor        = () => Option.empty[CommitHash],
      selectionAccessor = () => MitigationSelection.Inherent,
      display           = Val(CurveDisplay.Inherent),
      userPalette       = Val(PaletteData.Aqua),
      origin            = Val(None)
    )

  private val mitigation = MitigationId(n2.toSafeId)
  private val inherentAtN1  = curve(n1, 100.0)
  private val mitigatedAtN1 = curve(n1, 50.0)
  private val inherentAtN2  = curve(n2, 70.0)

  /** n1 has both readings; the selection does not reach n2. */
  private val answer = Map(
    n1 -> List(LECNodeSeries(inherentAtN1, Nil), LECNodeSeries(mitigatedAtN1, List(mitigation))),
    n2 -> List(LECNodeSeries(inherentAtN2, Nil))
  )

  def spec = suite("LECChartState")(

    test("a selection past the cap is refused, leaves the set unchanged and emits the notice") {
      val owner = new ManualOwner
      val state = newState
      var refusals = 0
      state.selectionCapReached.foreach(_ => refusals += 1)(owner)
      (1 to LECChartState.MaxUserSelections).foreach(i => state.userSelectionToggle.onNext(node(i)))
      val full = state.userSelectedNodeIds.now()
      state.userSelectionToggle.onNext(node(LECChartState.MaxUserSelections + 1))
      val afterRefusal = state.userSelectedNodeIds.now()
      owner.killSubscriptions()
      assertTrue(
        full.size == LECChartState.MaxUserSelections,
        afterRefusal == full,
        refusals == 1
      )
    },

    test("replaces the whole selection and skips a no-op write") {
      val owner = new ManualOwner
      val state = newState
      var emissions = 0
      state.userSelectedNodeIds.signal.foreach(_ => emissions += 1)(owner)
      val afterObserve = emissions               // the initial (empty) emission

      state.setUserSelection(Set(n1, n2))
      val afterSet = state.userSelectedNodeIds.now()
      val emissionsAfterSet = emissions

      state.setUserSelection(Set(n1, n2))        // identical → no write, no emission
      val emissionsAfterNoop = emissions

      state.setUserSelection(Set(n1))            // different → wholesale replace
      val afterReplace = state.userSelectedNodeIds.now()

      owner.killSubscriptions()
      assertTrue(
        afterObserve == 1,
        afterSet == Set(n1, n2),
        emissionsAfterSet == afterObserve + 1,
        emissionsAfterNoop == emissionsAfterSet,
        afterReplace == Set(n1)
      )
    },

    test("Inherent draws each node's reading that names no mitigation") {
      assertTrue(LECChartState.readings(answer, CurveDisplay.Inherent) == Map(
        n1 -> List(CurveVariant.Inherent -> inherentAtN1),
        n2 -> List(CurveVariant.Inherent -> inherentAtN2)
      ))
    },

    test("Mitigated draws the mitigated reading, and the mitigation-free one where the selection does not reach") {
      assertTrue(LECChartState.readings(answer, CurveDisplay.Mitigated) == Map(
        n1 -> List(CurveVariant.Mitigated -> mitigatedAtN1),
        n2 -> List(CurveVariant.Inherent -> inherentAtN2)
      ))
    },

    test("Both draws every reading, each marked by the valuation it is") {
      assertTrue(LECChartState.readings(answer, CurveDisplay.Both) == Map(
        n1 -> List(CurveVariant.Inherent -> inherentAtN1, CurveVariant.Mitigated -> mitigatedAtN1),
        n2 -> List(CurveVariant.Inherent -> inherentAtN2)
      ))
    },

    test("a request under a selection names only the mitigations the tree holds") {
      val other  = MitigationId(n1.toSafeId)
      val both   = MitigationSelection.create(List(mitigation, other), Nil).toOption.get
      val result = LECChartState.requestFor(List(n1), both, held = Some(Set(mitigation)))
      assertTrue(result.flatMap(_.toOption).map(_.selection) == Some(MitigationSelection.single(mitigation)))
    },

    test("a request under a selection waits while the tree's mitigations are unknown") {
      assertTrue(
        LECChartState.requestFor(List(n1), MitigationSelection.single(mitigation), held = None).isEmpty,
        LECChartState.requestFor(List(n1), MitigationSelection.Inherent, held = None).exists(_.isRight)
      )
    },

    test("a request its constructor refuses becomes a failed form") {
      val tooMany = (0 to RiskTree.MaxNodes).toList.map(_ => n1)
      assertTrue(LECChartState.requestFor(tooMany, MitigationSelection.Inherent, held = None) match
        case Some(Left(LocalProblem.FormInvalid(errors))) => errors.nonEmpty
        case _                                            => false)
    }
  )

package app.state

import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import io.github.iltotore.iron.*

import com.risquanter.register.domain.data.{LECNodeCurve, RiskTree}
import com.risquanter.register.domain.data.iron.{MitigationId, NodeId, Probability, SafeName, TreeId, WorkspaceKeySecret}
import com.risquanter.register.http.responses.LECNodeSeries

/** Tests for `LECChartState`'s user selection — the wholesale-replace write used
  * by the mirror-baseline sync, and the toggle's selection cap — and for picking
  * each node's mitigation-free curve out of an analysis answer. */
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
      selectedTree   = Val[LoadState[RiskTree]](LoadState.Idle)
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

    test("each node's curve is the reading that names no mitigation") {
      val mitigation = MitigationId(n2.toSafeId)
      val inherentAtN1 = curve(n1, 100.0)
      val answer = Map(
        n1 -> List(LECNodeSeries(inherentAtN1, Nil), LECNodeSeries(curve(n1, 50.0), List(mitigation))),
        n2 -> List(LECNodeSeries(curve(n2, 70.0), Nil))
      )
      assertTrue(LECChartState.inherentCurves(answer) == Map(n1 -> inherentAtN1, n2 -> curve(n2, 70.0)))
    }
  )

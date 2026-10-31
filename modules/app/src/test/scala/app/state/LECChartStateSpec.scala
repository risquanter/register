package app.state

import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, WorkspaceKeySecret}

/** Tests for `LECChartState`'s user selection: the wholesale-replace write used
  * by the mirror-baseline sync, and the toggle's selection cap. */
object LECChartStateSpec extends ZIOSpecDefault:

  private def node(i: Int): NodeId = NodeId.fromString(f"01HX9ABCDE00000000000000$i%02d").toOption.get

  private val n1 = node(1)
  private val n2 = node(2)

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
    }
  )

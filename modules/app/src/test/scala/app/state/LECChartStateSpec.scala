package app.state

import zio.test.*
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.ManualOwner

import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, WorkspaceKeySecret}

/** Pure tests for `LECChartState.setUserSelection` — the wholesale-replace
  * write used by the mirror-baseline sync. */
object LECChartStateSpec extends ZIOSpecDefault:

  private val n1 = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val n2 = NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  private def newState: LECChartState =
    new LECChartState(
      keySignal      = Val(Option.empty[WorkspaceKeySecret]),
      selectedTreeId = Val(Option.empty[TreeId]),
      selectedTree   = Val[LoadState[RiskTree]](LoadState.Idle),
      globalError    = Var(Option.empty[GlobalError])
    )

  def spec = suite("LECChartState.setUserSelection")(

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

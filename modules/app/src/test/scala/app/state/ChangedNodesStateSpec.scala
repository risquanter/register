package app.state

import zio.test.*

import app.core.LocalProblem
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.responses.{ChangedNodesResponse, ChangedNodesStatus, NodeChangeEntry, NodeChangeStatus}

/** Pure tests for `ChangedNodesState.deriveChangedNodeIds` (the Analyze
  * overlay ✎ change markers) — no Laminar Var/Signal harness needed, since
  * the derivation is a plain function of `LoadState`.
  */
object ChangedNodesStateSpec extends ZIOSpecDefault:

  private val leaf1 = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val leaf2 = NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  def spec = suite("ChangedNodesState.deriveChangedNodeIds")(

    test("Idle/Loading/Failed → empty (nothing changed, nothing to mark)") {
      assertTrue(
        ChangedNodesState.deriveChangedNodeIds(LoadState.Idle) == Set.empty,
        ChangedNodesState.deriveChangedNodeIds(LoadState.Loading) == Set.empty,
        ChangedNodesState.deriveChangedNodeIds(LoadState.Failed(LocalProblem.NoActiveWorkspace)) == Set.empty
      )
    },

    test("Ok: only entries whose status differs are reported as changed") {
      val resp = ChangedNodesResponse(ChangedNodesStatus.Ok(List(
        NodeChangeEntry(leaf1, NodeChangeStatus.Identical),
        NodeChangeEntry(leaf2, NodeChangeStatus.Changed)
      )))
      assertTrue(ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set(leaf2))
    },

    test("Ok with all identical → empty") {
      val resp = ChangedNodesResponse(ChangedNodesStatus.Ok(List(NodeChangeEntry(leaf1, NodeChangeStatus.Identical))))
      assertTrue(ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set.empty)
    },

    test("a tree missing on a side → empty") {
      assertTrue(
        ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(ChangedNodesResponse(ChangedNodesStatus.MissingOnA()))) == Set.empty,
        ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(ChangedNodesResponse(ChangedNodesStatus.MissingOnB()))) == Set.empty,
        ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(ChangedNodesResponse(ChangedNodesStatus.MissingOnBoth()))) == Set.empty
      )
    }
  )

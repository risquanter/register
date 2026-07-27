package app.state

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.responses.{ChangedNodesResponse, NodeChangeEntry}

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
        ChangedNodesState.deriveChangedNodeIds(LoadState.Failed("boom")) == Set.empty
      )
    },

    test("status \"ok\": only non-identical entries are reported as changed") {
      val resp = ChangedNodesResponse(
        status = "ok",
        entries = List(
          NodeChangeEntry(leaf1.value, "identical"),
          NodeChangeEntry(leaf2.value, "changed")
        )
      )
      assertTrue(ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set(leaf2))
    },

    test("status \"ok\" with all identical → empty") {
      val resp = ChangedNodesResponse("ok", List(NodeChangeEntry(leaf1.value, "identical")))
      assertTrue(ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set.empty)
    },

    test("non-\"ok\" status (tree missing on a side) → empty regardless of entries") {
      val resp = ChangedNodesResponse("missing-on-a", List(NodeChangeEntry(leaf1.value, "changed")))
      assertTrue(ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set.empty)
    },

    test("an entry with an unparseable nodeId is silently dropped, not a crash") {
      val resp = ChangedNodesResponse("ok", List(NodeChangeEntry("not-a-valid-ulid", "changed"), NodeChangeEntry(leaf2.value, "changed")))
      assertTrue(ChangedNodesState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set(leaf2))
    }
  )

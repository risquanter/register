package app.state

import zio.test.*

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.responses.{ScenarioDiffResponse, NodeDiffEntry}

/** Pure tests for `ScenarioDiffState.deriveChangedNodeIds` (milestone-2b
  * Phase C, Analyze Overlay compare mode) — no Laminar Var/Signal harness
  * needed, since the derivation is a plain function of `LoadState`.
  */
object ScenarioDiffStateSpec extends ZIOSpecDefault:

  private val leaf1 = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val leaf2 = NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  def spec = suite("ScenarioDiffState.deriveChangedNodeIds")(

    test("Idle/Loading/Failed → empty (nothing changed, nothing to mark)") {
      assertTrue(
        ScenarioDiffState.deriveChangedNodeIds(LoadState.Idle) == Set.empty,
        ScenarioDiffState.deriveChangedNodeIds(LoadState.Loading) == Set.empty,
        ScenarioDiffState.deriveChangedNodeIds(LoadState.Failed("boom")) == Set.empty
      )
    },

    test("status \"ok\": only non-identical entries are reported as changed") {
      val resp = ScenarioDiffResponse(
        status = "ok",
        entries = List(
          NodeDiffEntry(leaf1.value, "identical"),
          NodeDiffEntry(leaf2.value, "changed")
        )
      )
      assertTrue(ScenarioDiffState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set(leaf2))
    },

    test("status \"ok\" with all identical → empty") {
      val resp = ScenarioDiffResponse("ok", List(NodeDiffEntry(leaf1.value, "identical")))
      assertTrue(ScenarioDiffState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set.empty)
    },

    test("non-\"ok\" status (tree missing on a branch) → empty regardless of entries") {
      val resp = ScenarioDiffResponse("missing-on-a", List(NodeDiffEntry(leaf1.value, "changed")))
      assertTrue(ScenarioDiffState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set.empty)
    },

    test("an entry with an unparseable nodeId is silently dropped, not a crash") {
      val resp = ScenarioDiffResponse("ok", List(NodeDiffEntry("not-a-valid-ulid", "changed"), NodeDiffEntry(leaf2.value, "changed")))
      assertTrue(ScenarioDiffState.deriveChangedNodeIds(LoadState.Loaded(resp)) == Set(leaf2))
    }
  )

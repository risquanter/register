package app.state

import zio.test.*

import com.risquanter.register.domain.data.iron.{NodeId, CommitHash}
import com.risquanter.register.domain.data.LECNodeCurve
import com.risquanter.register.http.responses.{TreeHistoryResponse, TreeHistoryEntry, HistoryOperation}

/** Pure tests for the Slice E-A derivations — no Laminar Var/Signal harness,
  * since both are plain functions of `LoadState`.
  */
object TreeHistoryStateSpec extends ZIOSpecDefault:

  private val c1 = CommitHash.fromString("a" * 40).toOption.get
  private val c2 = CommitHash.fromString("b" * 40).toOption.get
  private val n1 = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val n2 = NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  private def curve(id: NodeId): LECNodeCurve =
    LECNodeCurve(id, "n", Vector.empty, Map.empty, 0.0, 0.0)

  def spec = suite("Slice E-A pure derivations")(

    test("deriveCommits: Loaded → its entries, unmodified (oldest-first as fetched)") {
      val entries = List(
        TreeHistoryEntry(c1, HistoryOperation.Create, "2026-01-01T00:00:00Z"),
        TreeHistoryEntry(c2, HistoryOperation.Update, "2026-01-02T00:00:00Z")
      )
      assertTrue(TreeHistoryState.deriveCommits(LoadState.Loaded(TreeHistoryResponse(entries))) == entries)
    },

    test("deriveCommits: Idle/Loading/Failed → Nil") {
      assertTrue(
        TreeHistoryState.deriveCommits(LoadState.Idle) == Nil,
        TreeHistoryState.deriveCommits(LoadState.Loading) == Nil,
        TreeHistoryState.deriveCommits(LoadState.Failed("boom")) == Nil
      )
    },

    test("deriveDropped: selected nodes absent from the loaded curve map are dropped") {
      val cache = LoadState.Loaded(Map(n1 -> curve(n1)))
      assertTrue(LECChartState.deriveDropped(Set(n1, n2), cache) == Set(n2))
    },

    test("deriveDropped: every selection present → empty") {
      val cache = LoadState.Loaded(Map(n1 -> curve(n1), n2 -> curve(n2)))
      assertTrue(LECChartState.deriveDropped(Set(n1, n2), cache) == Set.empty)
    },

    test("deriveDropped: non-loaded cache → empty (nothing charted yet, nothing dropped)") {
      assertTrue(
        LECChartState.deriveDropped(Set(n1), LoadState.Idle) == Set.empty,
        LECChartState.deriveDropped(Set(n1), LoadState.Loading) == Set.empty,
        LECChartState.deriveDropped(Set(n1), LoadState.Failed("x")) == Set.empty
      )
    }
  )

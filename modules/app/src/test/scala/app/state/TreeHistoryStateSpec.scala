package app.state

import zio.test.*
import io.github.iltotore.iron.autoRefine

import app.core.LocalProblem
import com.risquanter.register.domain.data.iron.{NodeId, CommitHash, SafeName}
import com.risquanter.register.domain.data.{LECNodeCurve, MitigationSelection}
import com.risquanter.register.http.responses.{LECNodeSeries, TreeHistoryResponse, TreeHistoryEntry, HistoryOperation, TreeWriteKind}

/** Pure tests for the history derivations — no Laminar Var/Signal harness,
  * since both are plain functions of `LoadState`.
  */
object TreeHistoryStateSpec extends ZIOSpecDefault:

  private val c1 = CommitHash.fromString("a" * 40).toOption.get
  private val c2 = CommitHash.fromString("b" * 40).toOption.get
  private val n1 = NodeId.fromString("01HX9ABCDE0000000000000001").toOption.get
  private val n2 = NodeId.fromString("01HX9ABCDE0000000000000002").toOption.get

  /** An answer holding one valuation, the Inherent one, with a curve for each
    * given node. */
  private def inherentCurves(ids: NodeId*): Map[MitigationSelection, Map[NodeId, LECNodeSeries]] =
    Map(MitigationSelection.Inherent -> ids.map(id =>
      id -> LECNodeSeries(LECNodeCurve(id, SafeName.fromString("n").toOption.get, Vector.empty, Map.empty, 0.0, 0.0), Nil)
    ).toMap)

  def spec = suite("History pure derivations")(

    test("deriveCommits: Loaded → its entries, unmodified (oldest-first as fetched)") {
      val entries = List(
        TreeHistoryEntry(c1, HistoryOperation.TreeWrite(TreeWriteKind.Create), "2026-01-01T00:00:00Z"),
        TreeHistoryEntry(c2, HistoryOperation.TreeWrite(TreeWriteKind.Update), "2026-01-02T00:00:00Z")
      )
      assertTrue(TreeHistoryState.deriveCommits(LoadState.Loaded(TreeHistoryResponse(entries))) == entries)
    },

    test("deriveCommits: Idle/Loading/Failed → Nil") {
      assertTrue(
        TreeHistoryState.deriveCommits(LoadState.Idle) == Nil,
        TreeHistoryState.deriveCommits(LoadState.Loading) == Nil,
        TreeHistoryState.deriveCommits(LoadState.Failed(LocalProblem.NoActiveWorkspace)) == Nil
      )
    },

    test("deriveDropped: selected nodes absent from the loaded curve map are dropped") {
      val cache = LoadState.Loaded(inherentCurves(n1))
      assertTrue(LECChartState.deriveDropped(Set(n1, n2), cache) == Set(n2))
    },

    test("deriveDropped: every selection present → empty") {
      val cache = LoadState.Loaded(inherentCurves(n1, n2))
      assertTrue(LECChartState.deriveDropped(Set(n1, n2), cache) == Set.empty)
    },

    test("deriveDropped: non-loaded cache → empty (nothing charted yet, nothing dropped)") {
      assertTrue(
        LECChartState.deriveDropped(Set(n1), LoadState.Idle) == Set.empty,
        LECChartState.deriveDropped(Set(n1), LoadState.Loading) == Set.empty,
        LECChartState.deriveDropped(Set(n1), LoadState.Failed(LocalProblem.NoActiveWorkspace)) == Set.empty
      )
    }
  )

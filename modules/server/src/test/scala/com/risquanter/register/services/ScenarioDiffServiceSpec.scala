package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.*

import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, WorkspaceId, TreeId, NodeId, BranchRef}
import com.risquanter.register.testutil.TestHelpers.*

/** Pure service-level tests for `ScenarioDiffService` (UC5, milestone-2b
  * Phase C) — the content-hash diff logic, exercised against a stub
  * `RiskTreeService` keyed by branch, without HTTP/Tapir.
  */
object ScenarioDiffServiceSpec extends ZIOSpecDefault:

  private given Checked[Permission] = TestChecked.value

  private val wsId: WorkspaceId = WorkspaceId(safeId("diff-workspace"))
  private val treeIdF: TreeId = treeId("diff-tree")
  private val rootId = nodeId("root")
  private val leaf1Id = nodeId("leaf1")
  private val leaf2Id = nodeId("leaf2")

  private val branchA = BranchRef.fromString("scenarios.diffws.branch-a").toOption.get
  private val branchB = BranchRef.fromString("scenarios.diffws.branch-b").toOption.get

  private val seedVarIdOf: Map[NodeId, Long] = Map(leaf1Id -> 1L, leaf2Id -> 2L)

  private def leaf(id: NodeId, probability: Double): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = id.value.toString,
      name = s"Leaf ${id.value}",
      distributionType = "lognormal",
      probability = probability,
      minLoss = Some(1000L),
      maxLoss = Some(50000L),
      parentId = Some(rootId),
      seedVarId = seedVarIdOf(id)
    )

  private def tree(children: Seq[RiskNode]): RiskTree =
    unsafeGet(
      RiskTree.fromNodes(
        id = treeIdF,
        name = SafeName.SafeName("Diff Tree".refineUnsafe),
        nodes = RiskPortfolio.unsafeFromStrings(
          id = rootId.value.toString,
          name = "Root",
          childIds = children.map(_.id.value.toString).toArray,
          parentId = None
        ) +: children,
        rootId = rootId
      ),
      "Test fixture has invalid RiskTree"
    )

  /** Stub keyed purely by branch — the only dimension the diff service varies
    * on. Built on the shared `CascadeTestStubs.riskTreeService` (`onGetById`
    * hook) rather than a fresh hand-written `RiskTreeService` double, so the
    * dying-boilerplate for the other five methods lives in one place.
    */
  private def stubRiskTreeService(byBranch: PartialFunction[Option[BranchRef], Option[RiskTree]]): RiskTreeService =
    CascadeTestStubs.riskTreeService(
      onDelete = (_, _) => ZIO.die(new UnsupportedOperationException),
      onGetById = (_, _, branch) => ZIO.succeed(byBranch.applyOrElse(branch, (_: Option[BranchRef]) => None))
    )

  /** Unwraps the happy-path `Diff` case; fails loudly (not silently) if a
    * test that expects entries got a missing-tree outcome instead. */
  private def entriesOf(result: ScenarioDiffResult): List[NodeDiff] = result match
    case ScenarioDiffResult.Diff(entries) => entries
    case other => throw new AssertionError(s"Expected ScenarioDiffResult.Diff, got $other")

  def spec = suite("ScenarioDiffService.diff")(

    test("identical leaf → Identical; changed leaf → Changed; ancestor portfolio changes too (Merkle propagation)") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val treeB = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.3))) // leaf2 changed
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case Some(`branchA`) => Some(treeA)
        case Some(`branchB`) => Some(treeB)
      })
      for
        result <- service.diff(wsId, treeIdF, Some(branchA), Some(branchB))
      yield
        val entries = entriesOf(result).map(d => d.nodeId -> d.status).toMap
        assertTrue(
          entries(leaf1Id) == NodeDiffStatus.Identical,
          entries(leaf2Id) == NodeDiffStatus.Changed,
          entries(rootId) == NodeDiffStatus.Changed
        )
    },

    test("node present only on branchB → Added; node present only on branchA → Removed") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1)))
      val treeB = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2))) // leaf2 added on B
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case Some(`branchA`) => Some(treeA)
        case Some(`branchB`) => Some(treeB)
      })
      for
        result <- service.diff(wsId, treeIdF, Some(branchA), Some(branchB))
      yield
        val entries = entriesOf(result).map(d => d.nodeId -> d.status).toMap
        assertTrue(entries(leaf2Id) == NodeDiffStatus.Added)
    },

    test("node present only on branchA (missing on branchB) → Removed") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val treeB = tree(Seq(leaf(leaf1Id, 0.1))) // leaf2 missing on B relative to A
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case Some(`branchA`) => Some(treeA)
        case Some(`branchB`) => Some(treeB)
      })
      for
        result <- service.diff(wsId, treeIdF, Some(branchA), Some(branchB))
      yield
        val entries = entriesOf(result).map(d => d.nodeId -> d.status).toMap
        assertTrue(entries(leaf2Id) == NodeDiffStatus.Removed)
    },

    test("calling diff with the branch arguments swapped flips Added/Removed for the same underlying difference") {
      val treeWithExtra = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val treeWithout   = tree(Seq(leaf(leaf1Id, 0.1)))
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case Some(`branchA`) => Some(treeWithExtra)
        case Some(`branchB`) => Some(treeWithout)
      })
      for
        forward  <- service.diff(wsId, treeIdF, Some(branchA), Some(branchB))
        backward <- service.diff(wsId, treeIdF, Some(branchB), Some(branchA))
      yield
        val forwardStatus  = entriesOf(forward).map(d => d.nodeId -> d.status).toMap
        val backwardStatus = entriesOf(backward).map(d => d.nodeId -> d.status).toMap
        assertTrue(
          forwardStatus(leaf2Id) == NodeDiffStatus.Removed,
          backwardStatus(leaf2Id) == NodeDiffStatus.Added
        )
    },

    test("same branch on both sides → every node Identical") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case Some(`branchA`) => Some(treeA)
      })
      for
        result <- service.diff(wsId, treeIdF, Some(branchA), Some(branchA))
      yield
        assertTrue(entriesOf(result).forall(_.status == NodeDiffStatus.Identical))
    },

    test("tree missing on branchB only → MissingOnB (mirrors RiskTreeService.getById's own Option, not an error)") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1)))
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case Some(`branchA`) => Some(treeA)
        case Some(`branchB`) => None
      })
      for
        result <- service.diff(wsId, treeIdF, Some(branchA), Some(branchB))
      yield assertTrue(result == ScenarioDiffResult.MissingOnB)
    },

    test("tree missing on branchA only → MissingOnA") {
      val treeB = tree(Seq(leaf(leaf1Id, 0.1)))
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case Some(`branchA`) => None
        case Some(`branchB`) => Some(treeB)
      })
      for
        result <- service.diff(wsId, treeIdF, Some(branchA), Some(branchB))
      yield assertTrue(result == ScenarioDiffResult.MissingOnA)
    },

    test("tree missing on both branches → MissingOnBoth") {
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case _ => None
      })
      for
        result <- service.diff(wsId, treeIdF, Some(branchA), Some(branchB))
      yield assertTrue(result == ScenarioDiffResult.MissingOnBoth)
    },

    test("entries are returned in a stable order (sorted by NodeId), not raw Set iteration order") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val service = ScenarioDiffServiceLive(stubRiskTreeService {
        case Some(`branchA`) => Some(treeA)
      })
      for
        result <- service.diff(wsId, treeIdF, Some(branchA), Some(branchA))
      yield
        val ids = entriesOf(result).map(_.nodeId.value)
        assertTrue(ids == ids.sorted)
    }
  )

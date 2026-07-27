package com.risquanter.register.services

import zio.*
import zio.test.*
import io.github.iltotore.iron.*

import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.{RiskTree, RiskLeaf, RiskPortfolio, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, WorkspaceId, TreeId, NodeId, BranchRef, Revision}
import com.risquanter.register.testutil.TestHelpers.*

/** Pure service-level tests for `ChangedNodesService` (UC5) — the content-hash
  * changed-nodes logic, exercised against a stub `RiskTreeService` keyed by
  * revision, without HTTP/Tapir.
  */
object ChangedNodesServiceSpec extends ZIOSpecDefault:

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

  /** Stub keyed by branch — the revisions under test are all branch heads. The
    * service reads each side via `getById(_, _, rev)`; this adapter unwraps
    * `Revision.Head` back to the branch the test cases key on. Built on the
    * shared `CascadeTestStubs.riskTreeService` (`onGetById` hook) so the
    * dying-boilerplate for the other methods lives in one place.
    */
  private def stubRiskTreeService(byBranch: PartialFunction[BranchRef, Option[RiskTree]]): RiskTreeService =
    CascadeTestStubs.riskTreeService(
      onDelete = (_, _) => ZIO.die(new UnsupportedOperationException),
      onGetById = (_, _, rev) => ZIO.succeed(rev match
        case Revision.Head(b) => byBranch.applyOrElse(b, (_: BranchRef) => None)
        case Revision.At(_)   => None)
    )

  /** Unwraps the happy-path `Changes` case; fails loudly (not silently) if a
    * test that expects entries got a missing-tree outcome instead. */
  private def entriesOf(result: ChangedNodesResult): List[NodeChange] = result match
    case ChangedNodesResult.Changes(entries) => entries
    case other => throw new AssertionError(s"Expected ChangedNodesResult.Changes, got $other")

  private def headA = Revision.Head(branchA)
  private def headB = Revision.Head(branchB)

  def spec = suite("ChangedNodesService.changedNodes")(

    test("identical leaf → Identical; changed leaf → Changed; ancestor portfolio changes too (Merkle propagation)") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val treeB = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.3))) // leaf2 changed
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case `branchA` => Some(treeA)
        case `branchB` => Some(treeB)
      })
      for
        result <- service.changedNodes(wsId, treeIdF, headA, headB)
      yield
        val entries = entriesOf(result).map(d => d.nodeId -> d.status).toMap
        assertTrue(
          entries(leaf1Id) == NodeChangeStatus.Identical,
          entries(leaf2Id) == NodeChangeStatus.Changed,
          entries(rootId) == NodeChangeStatus.Changed
        )
    },

    test("node present only on branchB → Added; node present only on branchA → Removed") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1)))
      val treeB = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2))) // leaf2 added on B
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case `branchA` => Some(treeA)
        case `branchB` => Some(treeB)
      })
      for
        result <- service.changedNodes(wsId, treeIdF, headA, headB)
      yield
        val entries = entriesOf(result).map(d => d.nodeId -> d.status).toMap
        assertTrue(entries(leaf2Id) == NodeChangeStatus.Added)
    },

    test("node present only on branchA (missing on branchB) → Removed") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val treeB = tree(Seq(leaf(leaf1Id, 0.1))) // leaf2 missing on B relative to A
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case `branchA` => Some(treeA)
        case `branchB` => Some(treeB)
      })
      for
        result <- service.changedNodes(wsId, treeIdF, headA, headB)
      yield
        val entries = entriesOf(result).map(d => d.nodeId -> d.status).toMap
        assertTrue(entries(leaf2Id) == NodeChangeStatus.Removed)
    },

    test("calling changedNodes with the sides swapped flips Added/Removed for the same underlying difference") {
      val treeWithExtra = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val treeWithout   = tree(Seq(leaf(leaf1Id, 0.1)))
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case `branchA` => Some(treeWithExtra)
        case `branchB` => Some(treeWithout)
      })
      for
        forward  <- service.changedNodes(wsId, treeIdF, headA, headB)
        backward <- service.changedNodes(wsId, treeIdF, headB, headA)
      yield
        val forwardStatus  = entriesOf(forward).map(d => d.nodeId -> d.status).toMap
        val backwardStatus = entriesOf(backward).map(d => d.nodeId -> d.status).toMap
        assertTrue(
          forwardStatus(leaf2Id) == NodeChangeStatus.Removed,
          backwardStatus(leaf2Id) == NodeChangeStatus.Added
        )
    },

    test("same revision on both sides → every node Identical") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case `branchA` => Some(treeA)
      })
      for
        result <- service.changedNodes(wsId, treeIdF, headA, headA)
      yield
        assertTrue(entriesOf(result).forall(_.status == NodeChangeStatus.Identical))
    },

    test("tree missing on branchB only → MissingOnB (mirrors RiskTreeService.getById's own Option, not an error)") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1)))
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case `branchA` => Some(treeA)
        case `branchB` => None
      })
      for
        result <- service.changedNodes(wsId, treeIdF, headA, headB)
      yield assertTrue(result == ChangedNodesResult.MissingOnB)
    },

    test("tree missing on branchA only → MissingOnA") {
      val treeB = tree(Seq(leaf(leaf1Id, 0.1)))
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case `branchA` => None
        case `branchB` => Some(treeB)
      })
      for
        result <- service.changedNodes(wsId, treeIdF, headA, headB)
      yield assertTrue(result == ChangedNodesResult.MissingOnA)
    },

    test("tree missing on both sides → MissingOnBoth") {
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case _ => None
      })
      for
        result <- service.changedNodes(wsId, treeIdF, headA, headB)
      yield assertTrue(result == ChangedNodesResult.MissingOnBoth)
    },

    test("entries are returned in a stable order (sorted by NodeId), not raw Set iteration order") {
      val treeA = tree(Seq(leaf(leaf1Id, 0.1), leaf(leaf2Id, 0.2)))
      val service = ChangedNodesServiceLive(stubRiskTreeService {
        case `branchA` => Some(treeA)
      })
      for
        result <- service.changedNodes(wsId, treeIdF, headA, headA)
      yield
        val ids = entriesOf(result).map(_.nodeId.value)
        assertTrue(ids == ids.sorted)
    }
  )

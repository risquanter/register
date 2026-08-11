package com.risquanter.register.services

import zio.*
import zio.test.*
import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio, RiskLeaf}
import com.risquanter.register.domain.data.iron.{SafeName, TreeId, WorkspaceId, SeedVarId, BranchRef, CommitHash, Revision}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId}

/** Commit-pinned reads are scoped to the authenticated workspace's Irmin paths,
  * NOT to the commit's provenance (fixed constraint in DONE-PLAN-PHASE-E-HISTORY §
  * "path scoping, not commit provenance"). All workspaces share one Irmin store,
  * so any commit physically contains every workspace's subtree; a pinned read
  * must still only surface the reading workspace's own data. */
object PinnedReadAuthorizationItSpec extends ZIOSpecDefault:

  private val wsA = WorkspaceId(safeId("pinned-auth-ws-a"))
  private val wsB = WorkspaceId(safeId("pinned-auth-ws-b"))

  private def oneLeafTree(tid: TreeId): RiskTree =
    val rootId = nodeId("root"); val leafId = nodeId("leaf-1")
    val root = RiskPortfolio.create(rootId.value, "Root", Array(leafId), None).toEither.toOption.get
    val leaf = RiskLeaf.create(id = leafId.value, name = "Leaf 1", distributionType = "lognormal",
      probability = 0.1, minLoss = Some(1000L), maxLoss = Some(2000L), parentId = Some(rootId), seedVarId = 1L).toEither.toOption.get
    RiskTree(tid, SafeName.fromString("Pinned Tree").toOption.get, Seq(root, leaf), rootId,
      TreeIndex.fromNodesUnsafe(Map(rootId -> root, leafId -> leaf)), SeedVarId.fromLong(1L).toOption.get)

  private val irminLayer: ZLayer[Any, Throwable, RiskTreeRepository & IrminClient] =
    ZLayer.make[RiskTreeRepository & IrminClient](
      IrminCompose.irminConfigLayer, IrminClientLive.layer, RiskTreeRepositoryIrmin.layer
    )

  override def spec =
    suite("PinnedReadAuthorizationItSpec")(
      test("a commit-pinned read surfaces only the reading workspace's own subtree, never another workspace's") {
        val tB = treeId("ws-b-only-tree")
        val treeB = oneLeafTree(tB)
        for
          repo   <- ZIO.service[RiskTreeRepository]
          irmin  <- ZIO.service[IrminClient]
          // workspace B writes a tree on main → a commit that (in the shared store)
          // physically contains B's subtree.
          _      <- repo.create(wsB, treeB, BranchRef.Main)
          cbOpt  <- irmin.getBranch(BranchRef.Main).map(_.flatMap(_.head).map(_.hash))
          cb     <- ZIO.fromOption(cbOpt).orElseFail(new RuntimeException("main has no head after B's write"))
          cbHash <- ZIO.fromEither(CommitHash.fromString(cb)).mapError(e => new RuntimeException(e.mkString(", ")))
          // B, reading its own tree pinned to that commit, sees it.
          bOwn   <- repo.getById(wsB, tB, Revision.At(cbHash))
          // A, reading the SAME commit and the SAME treeId but scoped to A's paths,
          // sees nothing of B's data — path scoping, not commit provenance.
          bViaA  <- repo.getById(wsA, tB, Revision.At(cbHash))
        yield assertTrue(bOwn.isDefined, bViaA.isEmpty)
      }
    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

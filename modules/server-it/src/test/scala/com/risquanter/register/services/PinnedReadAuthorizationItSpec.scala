package com.risquanter.register.services

import zio.*
import zio.test.*
import com.risquanter.register.domain.data.{RiskTree, RiskPortfolio, RiskLeaf}
import com.risquanter.register.domain.data.iron.{SafeName, TreeId, WorkspaceId, SeedVarId, BranchRef, CommitHash, Revision, ScenarioName}
import com.risquanter.register.domain.errors.{ErrorLocation, ErrorSubject, Failure, FieldName}
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId, lognormalShape}

/** Commit-pinned reads are scoped to the authenticated workspace's Irmin paths,
  * NOT to the commit's provenance. All workspaces share one Irmin store,
  * so any commit physically contains every workspace's subtree; a pinned read
  * must still only surface the reading workspace's own data. */
object PinnedReadAuthorizationItSpec extends ZIOSpecDefault:

  private val wsA = WorkspaceId(safeId("pinned-auth-ws-a"))
  private val wsB = WorkspaceId(safeId("pinned-auth-ws-b"))
  private val wsC = WorkspaceId(safeId("pinned-auth-ws-c"))

  private def oneLeafTree(tid: TreeId): RiskTree =
    val rootId = nodeId("root"); val leafId = nodeId("leaf-1")
    val root = RiskPortfolio.create(rootId.value, "Root", Vector(leafId), None, ErrorSubject.Request).toEither.toOption.get
    val leaf = RiskLeaf.create(subject = ErrorSubject.Request,id = leafId.value, name = "Leaf 1", probability = 0.1,
      shape = lognormalShape(1000L, 2000L), parentId = Some(rootId), seedVarId = 1L).toEither.toOption.get
    RiskTree.fromNodesUnsafe(tid, SafeName.fromString("Pinned Tree").toOption.get, Seq(root, leaf), rootId,
      Some(SeedVarId.fromLong(1L).toOption.get), mitigations = Nil)

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
          cbHash <- ZIO.fromOption(cbOpt).orElseFail(new RuntimeException("main has no head after B's write"))
          // B, reading its own tree pinned to that commit, sees it.
          bOwn   <- repo.getById(wsB, tB, Revision.At(cbHash)).map(_.map(_._1))
          // A, reading the SAME commit and the SAME treeId but scoped to A's paths,
          // sees nothing of B's data — path scoping, not commit provenance.
          bViaA  <- repo.getById(wsA, tB, Revision.At(cbHash)).map(_.map(_._1))
        yield assertTrue(bOwn.isDefined, bViaA.isEmpty)
      },
      test("a client's commit is read, and reverted to, only when it lies in the named branch's history") {
        val tC = treeId("ws-c-fork-tree")
        def headOf(irmin: IrminClient, branch: BranchRef): IO[Failure | RuntimeException, CommitHash] =
          irmin.getBranch(branch).map(_.flatMap(_.head).map(_.hash))
            .flatMap(h => ZIO.fromOption(h).orElseFail(new RuntimeException(s"${branch.toBranchRef} has no head")))
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          // g: main holds tC. B forks from main at g.
          _       <- repo.create(wsC, oneLeafTree(tC), BranchRef.Main)
          g       <- headOf(irmin, BranchRef.Main)
          branchB  = BranchRef.scenario(wsC, ScenarioName.fromString("fork-b").toOption.get).toOption.get
          _       <- irmin.createBranchAt(branchB, g)
          // h: a later commit on main only. It still holds tC.
          _       <- repo.create(wsC, oneLeafTree(treeId("ws-c-second-tree")), BranchRef.Main)
          h       <- headOf(irmin, BranchRef.Main)
          never    = CommitHash.fromString("f" * 40).toOption.get
          onB     <- repo.getById(wsC, tC, Revision.Pinned(branchB, g)).map(_.isDefined)
          offB    <- repo.getById(wsC, tC, Revision.Pinned(branchB, h)).map(_.isDefined)
          onMain  <- repo.getById(wsC, tC, Revision.Pinned(BranchRef.Main, h)).map(_.isDefined)
          unknown <- repo.getById(wsC, tC, Revision.Pinned(branchB, never)).map(_.isDefined)
          refused <- repo.revert(wsC, tC, h, branchB).flip.map {
                       case Failure.NotFound(core) => core.errors.map((location, _) => location).toList
                       case _                      => Nil
                     }
          allowed <- repo.revert(wsC, tC, g, branchB).exit
        yield assertTrue(
          onB, !offB, onMain, !unknown,
          // The refusal points at the body field the commit came in.
          refused == List(ErrorLocation.requestField(FieldName.ToCommit)),
          allowed.isSuccess
        )
      }
    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock

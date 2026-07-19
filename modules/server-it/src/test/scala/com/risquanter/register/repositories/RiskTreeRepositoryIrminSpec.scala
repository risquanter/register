package com.risquanter.register.repositories

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, NodeId, TreeId, WorkspaceId, SeedVarId, PositiveInt}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.infra.irmin.{IrminClient, IrminClientLive}
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.domain.data.RiskPortfolio
import com.risquanter.register.domain.data.RiskLeaf
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.{safeId, nodeId, treeId}
import io.github.iltotore.iron.*

/**
  * Integration tests for RiskTreeRepositoryIrmin against a real Irmin container.
  *
  * PREREQUISITES:
  *   docker compose --profile persistence up -d
  *
  * Runs only when IRMIN_URL is set (e.g., http://localhost:9080).
  */
object RiskTreeRepositoryIrminSpec extends ZIOSpecDefault:

  private val wsId: WorkspaceId = WorkspaceId(safeId("test-irmin-ws"))

  private def sampleTree(tid: TreeId, treeName: String): RiskTree =
    val rootId  = nodeId("root")
    val leaf1Id = nodeId("leaf-1")
    val leaf2Id = nodeId("leaf-2")

    val portfolio = RiskPortfolio.create(
      id = rootId.value,
      name = "Root",
      childIds = Array(leaf1Id, leaf2Id),
      parentId = None
    ).toEither.toOption.get

    val leaf1 = RiskLeaf.create(
      id = leaf1Id.value,
      name = "Leaf 1",
      distributionType = "lognormal",
      probability = 0.1,
      minLoss = Some(1000L),
      maxLoss = Some(2000L),
      parentId = Some(rootId),
      seedVarId = 1L
    ).toEither.toOption.get

    val leaf2 = RiskLeaf.create(
      id = leaf2Id.value,
      name = "Leaf 2",
      distributionType = "lognormal",
      probability = 0.2,
      minLoss = Some(1500L),
      maxLoss = Some(3000L),
      parentId = Some(rootId),
      seedVarId = 2L
    ).toEither.toOption.get

    val index = TreeIndex.fromNodesUnsafe(
      Map(rootId -> portfolio, leaf1Id -> leaf1, leaf2Id -> leaf2)
    )

    RiskTree(
      id = tid,
      name = SafeName.fromString(treeName).toOption.get,
      nodes = Seq(portfolio, leaf1, leaf2),
      rootId = rootId,
      index = index,
      seedVarHighWater = SeedVarId.fromLong(2L).toOption.get
    )

  private def updatedTree(original: RiskTree): RiskTree =
    val rootId  = original.rootId
    val leaf1Id = nodeId("leaf-1")
    val root    = original.index.nodes(rootId).asInstanceOf[RiskPortfolio]
    val leaf1   = original.index.nodes(leaf1Id)
    val newRoot = RiskPortfolio.create(
      id = root.id.value,
      name = root.name.value,
      childIds = Array(leaf1Id),
      parentId = None
    ).toEither.toOption.get
    val newIndex = TreeIndex.fromNodesUnsafe(Map(rootId -> newRoot, leaf1Id -> leaf1))
    RiskTree(
      id = original.id,
      name = original.name,
      nodes = Seq(newRoot, leaf1),
      rootId = rootId,
      index = newIndex,
      seedVarHighWater = original.seedVarHighWater
    )

  /** Identity-preserving edit: change leaf-1's `minLoss` while keeping every NodeId
    * (and therefore every Irmin node path) unchanged. Models the frontend
    * identity-preserving update path at the repository boundary. */
  private def editLeaf1MinLoss(original: RiskTree, newMin: Long): RiskTree =
    val rootId  = original.rootId
    val leaf1Id = nodeId("leaf-1")
    val leaf2Id = nodeId("leaf-2")
    val root    = original.index.nodes(rootId).asInstanceOf[RiskPortfolio]
    val leaf2   = original.index.nodes(leaf2Id)
    val newLeaf1 = RiskLeaf.create(
      id = leaf1Id.value,          // SAME id → same path nodes/{leaf-1}
      name = "Leaf 1",
      distributionType = "lognormal",
      probability = 0.1,
      minLoss = Some(newMin),
      maxLoss = Some(2000L),
      parentId = Some(rootId),
      seedVarId = 1L               // identity-preserving: same leaf keeps its stream
    ).toEither.toOption.get
    val newIndex = TreeIndex.fromNodesUnsafe(Map(rootId -> root, leaf1Id -> newLeaf1, leaf2Id -> leaf2))
    RiskTree(
      id = original.id,
      name = original.name,
      nodes = Seq(root, newLeaf1, leaf2),
      rootId = rootId,
      index = newIndex,
      seedVarHighWater = original.seedVarHighWater
    )

  private def positiveInt(n: Int): PositiveInt = n.refineUnsafe

  private val irminLayer: ZLayer[Any, Throwable, RiskTreeRepository & IrminClient] =
    ZLayer.make[RiskTreeRepository & IrminClient](
      IrminCompose.irminConfigLayer,
      IrminClientLive.layer,
      RiskTreeRepositoryIrmin.layer
    )

  override def spec =
    suite("RiskTreeRepositoryIrminSpec")(
      test("create and get roundtrip with metadata") {
        for
          repo   <- ZIO.service[RiskTreeRepository]
          tree    = sampleTree(treeId("tree-1"), "Tree One")
          _      <- repo.create(wsId, tree)
          loaded <- repo.getById(wsId, tree.id)
        yield assertTrue(loaded.exists(_.index.nodes.size == tree.index.nodes.size))
      },

      test("update prunes removed nodes") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(treeId("tree-2"), "Tree Two")
          _    <- repo.create(wsId, tree)
          _    <- repo.update(wsId, tree.id, _ => updatedTree(tree))
          got  <- repo.getById(wsId, tree.id)
          leaf2Id = nodeId("leaf-2")
        yield assertTrue(got.exists(!_.index.nodes.contains(leaf2Id)))
      },

      test("list returns created trees") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(treeId("tree-3"), "Tree Three")
          _    <- repo.create(wsId, tree)
          all  <- repo.getAllForWorkspace(wsId)
        yield assertTrue(all.exists(_.exists(_.id == tree.id)))
      },

      test("delete removes tree") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(treeId("tree-4"), "Tree Four")
          _    <- repo.create(wsId, tree)
          _    <- repo.delete(wsId, tree.id)
          res  <- repo.getById(wsId, tree.id)
        yield assertTrue(res.isEmpty)
      },

      // DD-7: every repository write action is exactly ONE Irmin commit whose
      // message names the user action — the commit log IS the user history.
      test("create is one commit covering meta and all nodes, with the action message") {
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          tree     = sampleTree(treeId("tree-dd7-create"), "DD7 Create")
          base     = s"workspaces/${wsId.value}/risk-trees/${tree.id.value}"
          _       <- repo.create(wsId, tree)
          hMeta   <- irmin.getHistory(IrminPath.unsafeFrom(s"$base/meta"), positiveInt(10))
          hNode   <- irmin.getHistory(IrminPath.unsafeFrom(s"$base/nodes/${nodeId("leaf-1").value}"), positiveInt(10))
        yield assertTrue(
          hMeta.map(_.hash) == hNode.map(_.hash),   // same single commit wrote meta and node
          hMeta.size == 1,
          hMeta.headOption.exists(_.info.message == s"workspace:${wsId.value}:risk-tree:${tree.id.value}:create")
        )
      },

      test("update is one commit; node removal happens in that same commit") {
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          tree     = sampleTree(treeId("tree-dd7-update"), "DD7 Update")
          base     = s"workspaces/${wsId.value}/risk-trees/${tree.id.value}"
          _       <- repo.create(wsId, tree)
          _       <- repo.update(wsId, tree.id, _ => updatedTree(tree))
          hMeta   <- irmin.getHistory(IrminPath.unsafeFrom(s"$base/meta"), positiveInt(10))
          leaf2   <- irmin.get(IrminPath.unsafeFrom(s"$base/nodes/${nodeId("leaf-2").value}"))
        yield assertTrue(
          hMeta.size == 2,                          // create + update, nothing else
          hMeta.map(_.info.message).toSet == Set(
            s"workspace:${wsId.value}:risk-tree:${tree.id.value}:create",
            s"workspace:${wsId.value}:risk-tree:${tree.id.value}:update"
          ),
          leaf2.isEmpty                             // pruned by subtree replace, no extra commit
        )
      },

      test("delete is one commit that leaves no residue under the tree path") {
        for
          repo    <- ZIO.service[RiskTreeRepository]
          irmin   <- ZIO.service[IrminClient]
          tree     = sampleTree(treeId("tree-dd7-delete"), "DD7 Delete")
          base     = s"workspaces/${wsId.value}/risk-trees/${tree.id.value}"
          _       <- repo.create(wsId, tree)
          headC   <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          _       <- repo.delete(wsId, tree.id)
          headD   <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          res     <- repo.getById(wsId, tree.id)
          residue <- irmin.list(IrminPath.unsafeFrom(base))
        yield assertTrue(
          res.isEmpty,
          residue.isEmpty,                          // no meta, no nodes dir, nothing
          headC != headD,
          headD.isDefined
        )
      },

      // Git-semantics guarantee that later enables path-scoped time travel:
      // an identity-preserving edit rewrites the node at the SAME path and records a
      // NEW commit on top of the old one — it does not delete the node and recreate it
      // under a fresh id (which would sever the path's commit lineage).
      test("identity-preserving update rewrites a node in place at the same path and advances commit history") {
        for
          repo       <- ZIO.service[RiskTreeRepository]
          irmin      <- ZIO.service[IrminClient]
          tree        = sampleTree(treeId("tree-git"), "Git Tree")
          leaf1Id     = nodeId("leaf-1")
          leafPath    = IrminPath.unsafeFrom(
                          s"workspaces/${wsId.value}/risk-trees/${tree.id.value}/nodes/${leaf1Id.value}"
                        )
          _          <- repo.create(wsId, tree)
          before     <- irmin.get(leafPath)
          headBefore <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          _          <- repo.update(wsId, tree.id, t => editLeaf1MinLoss(t, 1234L))
          after      <- irmin.get(leafPath)
          headAfter  <- irmin.mainBranch.map(_.flatMap(_.head).map(_.hash))
          reloaded   <- repo.getById(wsId, tree.id)
        yield assertTrue(
          before.isDefined,                                 // node was stored at nodes/{id}
          after.isDefined,                                  // STILL at the same path after edit
          before != after,                                  // content changed (minLoss 1000 → 1234)
          headBefore.isDefined,
          headAfter.isDefined,
          headBefore != headAfter,                          // a new commit → the path has lineage
          reloaded.exists(_.index.nodes.contains(leaf1Id))  // NodeId preserved across the update
        )
      }
    ).provideLayerShared(irminLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
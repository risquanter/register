package com.risquanter.register.repositories

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.risquanter.register.configs.IrminConfig
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, SafeUrl}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.infra.irmin.IrminClientLive
import com.risquanter.register.domain.data.RiskPortfolio
import com.risquanter.register.domain.data.RiskLeaf
import com.risquanter.register.testcontainers.IrminCompose
import com.risquanter.register.testutil.TestHelpers.safeId
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual

/**
  * Integration tests for RiskTreeRepositoryIrmin against a real Irmin container.
  *
  * PREREQUISITES:
  *   docker compose --profile persistence up -d
  *
  * Runs only when IRMIN_URL is set (e.g., http://localhost:9080).
  */
object RiskTreeRepositoryIrminSpec extends ZIOSpecDefault:

  private def sampleTree(treeId: Long, treeName: String): RiskTree =
    val rootId  = safeId("root")
    val leaf1Id = safeId("leaf-1")
    val leaf2Id = safeId("leaf-2")

    val portfolio = RiskPortfolio.create(
      id = rootId.value.toString,
      name = "Root",
      childIds = Array(leaf1Id, leaf2Id),
      parentId = None
    ).toEither.toOption.get

    val leaf1 = RiskLeaf.create(
      id = leaf1Id.value.toString,
      name = "Leaf 1",
      distributionType = "lognormal",
      probability = 0.1,
      minLoss = Some(1000L),
      maxLoss = Some(2000L),
      parentId = Some(rootId)
    ).toEither.toOption.get

    val leaf2 = RiskLeaf.create(
      id = leaf2Id.value.toString,
      name = "Leaf 2",
      distributionType = "lognormal",
      probability = 0.2,
      minLoss = Some(1500L),
      maxLoss = Some(3000L),
      parentId = Some(rootId)
    ).toEither.toOption.get

    val index = TreeIndex.fromNodesUnsafe(
      Map(rootId -> portfolio, leaf1Id -> leaf1, leaf2Id -> leaf2)
    )

    RiskTree(
      id = treeId.refineEither[GreaterEqual[0L]].toOption.get,
      name = SafeName.fromString(treeName).toOption.get,
      nodes = Seq(portfolio, leaf1, leaf2),
      rootId = rootId,
      index = index
    )

  private def updatedTree(original: RiskTree): RiskTree =
    val rootId  = original.rootId
    val leaf1Id = safeId("leaf-1")
    val root    = original.index.nodes(rootId).asInstanceOf[RiskPortfolio]
    val leaf1   = original.index.nodes(leaf1Id)
    val newRoot = RiskPortfolio.create(
      id = root.id.value.toString,
      name = root.name,
      childIds = Array(leaf1Id),
      parentId = None
    ).toEither.toOption.get
    val newIndex = TreeIndex.fromNodesUnsafe(Map(rootId -> newRoot, leaf1Id -> leaf1))
    RiskTree(
      id = original.id,
      name = original.name,
      nodes = Seq(newRoot, leaf1),
      rootId = rootId,
      index = newIndex
    )

  private val irminLayer: ZLayer[Any, Throwable, RiskTreeRepository] =
    ZLayer.make[RiskTreeRepository](
      IrminCompose.irminConfigLayer,
      IrminClientLive.layer,
      RiskTreeRepositoryIrmin.layer
    )

  override def spec =
    suite("RiskTreeRepositoryIrminSpec")(
      test("create and get roundtrip with metadata") {
        for
          repo   <- ZIO.service[RiskTreeRepository]
          tree    = sampleTree(1L, "Tree One")
          _      <- repo.create(tree)
          loaded <- repo.getById(tree.id)
        yield assertTrue(loaded.exists(_.index.nodes.size == tree.index.nodes.size))
      },

      test("update prunes removed nodes") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(2L, "Tree Two")
          _    <- repo.create(tree)
          _    <- repo.update(tree.id, _ => updatedTree(tree))
          got  <- repo.getById(tree.id)
          leaf2Id = safeId("leaf-2")
        yield assertTrue(got.exists(!_.index.nodes.contains(leaf2Id)))
      },

      test("list returns created trees") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(3L, "Tree Three")
          _    <- repo.create(tree)
          all  <- repo.getAll
        yield assertTrue(all.exists(_.exists(_.id == tree.id)))
      },

      test("delete removes tree") {
        for
          repo <- ZIO.service[RiskTreeRepository]
          tree  = sampleTree(4L, "Tree Four")
          _    <- repo.create(tree)
          _    <- repo.delete(tree.id)
          res  <- repo.getById(tree.id)
        yield assertTrue(res.isEmpty)
      }
    ).provideLayerShared(irminLayer) @@ TestAspect.sequential
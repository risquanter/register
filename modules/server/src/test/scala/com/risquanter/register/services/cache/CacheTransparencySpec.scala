package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import io.github.iltotore.iron.*
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, TrialId, Loss}
import com.risquanter.register.domain.data.iron.{SafeName, TreeId, NodeId, SeedEntityId, ContentHash}
import com.risquanter.register.testutil.TestHelpers.*

/**
  * Cache-transparency equivalence (milestone 2b Phase A): with fixed seeds,
  * any edit sequence must yield BYTE-IDENTICAL figures with the real
  * ContentCache vs a pass-through (never-hit) cache.
  *
  * This converts "staleness is structurally impossible" from a design claim
  * into an executable assertion: if the cache could ever serve a stale or
  * wrong entry, the cached run would diverge from the uncached run.
  *
  * The edit sequence deliberately includes the item-17 shape (reparent +
  * param change in one step) and repeated reads (cache hits on the real run).
  */
object CacheTransparencySpec extends ZIOSpecDefault {

  private val testEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

  // ----- fixtures: v1 → v2 (param edit) → v3 (reparent + param edit) -----

  private def leaf(id: String, parent: String, prob: Double, seedVar: Long): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = idStr(id),
      name = s"Leaf $id",
      distributionType = "lognormal",
      probability = prob,
      minLoss = Some(1000L),
      maxLoss = Some(50000L),
      parentId = Some(nodeId(parent)),
      seedVarId = seedVar
    )

  private def portfolio(id: String, children: Seq[String], parent: Option[String]): RiskPortfolio =
    RiskPortfolio.unsafeFromStrings(
      id = idStr(id),
      name = s"Portfolio $id",
      childIds = children.map(idStr).toArray,
      parentId = parent.map(nodeId)
    )

  private val testTreeId: TreeId = treeId("transparency-tree")

  private def tree(nodes: Seq[com.risquanter.register.domain.data.RiskNode]): RiskTree =
    unsafeGet(
      RiskTree.fromNodes(
        id = testTreeId,
        name = SafeName.SafeName("Transparency Tree".refineUnsafe),
        nodes = nodes,
        rootId = nodeId("root")
      ),
      "Transparency fixture has invalid RiskTree"
    )

  private val v1 = tree(Seq(
    portfolio("root", Seq("sub", "fraud"), None),
    portfolio("sub", Seq("cyber", "hw"), Some("root")),
    leaf("cyber", "sub", 0.25, 1L),
    leaf("hw", "sub", 0.25, 2L),
    leaf("fraud", "root", 0.25, 3L)
  ))

  // v2: cyber probability edited
  private val v2 = tree(Seq(
    portfolio("root", Seq("sub", "fraud"), None),
    portfolio("sub", Seq("cyber", "hw"), Some("root")),
    leaf("cyber", "sub", 0.4, 1L),
    leaf("hw", "sub", 0.25, 2L),
    leaf("fraud", "root", 0.25, 3L)
  ))

  // v3: cyber reparented sub → root AND probability changed again (item-17 shape)
  private val v3 = tree(Seq(
    portfolio("root", Seq("sub", "fraud", "cyber"), None),
    portfolio("sub", Seq("hw"), Some("root")),
    leaf("cyber", "root", 0.6, 1L),
    leaf("hw", "sub", 0.25, 2L),
    leaf("fraud", "root", 0.25, 3L)
  ))

  private val editSequence = List(v1, v2, v2, v3, v3, v1)  // repeats force hits on the real run

  /** A CacheScope whose caches never store and never hit — every read
    * simulates fresh. The truth baseline.
    */
  private val passThroughScope: ULayer[CacheScope] =
    ZLayer.succeed(new CacheScope {
      override def cacheFor(seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache] =
        ZIO.succeed(new ContentCache {
          override def get(key: ContentHash): UIO[Option[LeafSimResult]] = ZIO.none
          override def put(key: ContentHash, value: LeafSimResult): UIO[Unit] = ZIO.unit
          override def stats: UIO[CacheStats] = ZIO.succeed(CacheStats(0, 0L, 0L, 0L))
        })
    })

  private def resolverWith(scope: ULayer[CacheScope]): ZLayer[Any, Throwable, RiskResultResolver & CacheScope] =
    ZLayer.make[RiskResultResolver & CacheScope](
      scope,
      ZLayer.succeed(TestConfigs.simulation),
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console,
      RiskResultResolverLive.layer
    )

  /** Run the full edit sequence, collecting every node's figures at every step. */
  private val runSequence: ZIO[RiskResultResolver & CacheScope, Throwable, List[Map[NodeId, Map[TrialId, Loss]]]] =
    ZIO.serviceWithZIO[RiskResultResolver] { resolver =>
      ZIO.foreach(editSequence) { t =>
        ZIO.foreach(t.index.nodes.keys.toList.sortBy(_.value)) { nid =>
          resolver.ensureCached(t, nid, testEntity).map(r => nid -> r.outcomes)
        }.map(_.toMap)
      }
    }

  def spec = suite("CacheTransparencySpec")(

    test("real ContentCache and pass-through cache yield byte-identical figures over the full edit sequence") {
      for {
        cachedRun   <- runSequence.provideLayer(resolverWith(CacheScope.layer))
        uncachedRun <- runSequence.provideLayer(resolverWith(passThroughScope))
      } yield assertTrue(
        cachedRun == uncachedRun,
        // guard against vacuous equality: figures exist at every step
        cachedRun.forall(_.values.exists(_.nonEmpty))
      )
    }
  ) @@ TestAspect.withLiveClock
}

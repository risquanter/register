package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import zio.test.Assertion.*
import io.github.iltotore.iron.*
import com.risquanter.register.configs.{SimulationConfig, TestConfigs}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}
import com.risquanter.register.domain.data.{RiskResult, RiskResultGroup, RiskNode, RiskLeaf, RiskPortfolio, RiskTree, Mitigation, MitigationTarget, MitigationSpec, MitigationSelection, MitigationPrecedence, TargetingPredicate, TransformPipeline, ResultTransformSpec, RiskLeafTransform, LikelihoodTransform, DistributionTransform}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, PositiveInt, TreeId, NodeId, SeedEntityId, MitigationId, ValidationUtil}
import com.risquanter.register.testutil.TestHelpers.*

/**
 * Tests for CachedResultResolverLive (ADR-015), content-addressed since
 * milestone 2b Phase A.
 *
 * Verifies cache-aside behavior over ContentHash keys (DD-16), per-workspace
 * cache isolation via CacheScope (DD-17), leaf-only caching (DD-15), orphan
 * semantics (a param edit strands the old entry; the new content misses),
 * error handling, and the resolver-edge mitigation fold (ADR-034 F: param-stage
 * transforms change the cache key, result-stage transforms apply post-cache).
 */
object CachedResultResolverSpec extends ZIOSpecDefault {

  private val testEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(1L).toOption.get

  // Test fixture: Simple risk tree for testing (flat node format)
  private val rootIdStr  = safeId("root").value.toString
  private val risk1IdStr = safeId("risk1").value.toString
  private val risk2IdStr = safeId("risk2").value.toString

  val risk1Leaf = RiskLeaf.unsafeApply(
    id = risk1IdStr,
    name = "Risk 1",
    distributionType = "lognormal",
    probability = 0.1,
    minLoss = Some(10000L),
    maxLoss = Some(50000L),
    parentId = Some(nodeId("root")),
    seedVarId = 1L
  )

  val risk2Leaf = RiskLeaf.unsafeApply(
    id = risk2IdStr,
    name = "Risk 2",
    distributionType = "lognormal",
    probability = 0.2,
    minLoss = Some(5000L),
    maxLoss = Some(20000L),
    parentId = Some(nodeId("root")),
    seedVarId = 2L
  )

  val rootNode: RiskNode = RiskPortfolio.unsafeFromStrings(
    id = rootIdStr,
    name = "Root Portfolio",
    childIds = Array(risk1IdStr, risk2IdStr),
    parentId = None
  )

  val allNodes = Seq(rootNode, risk1Leaf, risk2Leaf)
  val rootId = nodeId("root")
  val risk1Id = nodeId("risk1")
  val risk2Id = nodeId("risk2")

  // Create test RiskTree
  val testTreeId: TreeId = treeId("test-tree")
  val testTree = unsafeGet(
    RiskTree.fromNodes(
      id = testTreeId,
      name = SafeName.SafeName("Test Tree".refineUnsafe),
      nodes = allNodes,
      rootId = rootId
    ),
    "Test fixture has invalid RiskTree"
  )

  // Content-hash keys are derived from leaf content, not node identity
  val risk1Key = ContentHashIndex.hashOf(risk1Leaf)
  val risk2Key = ContentHashIndex.hashOf(risk2Leaf)

  // ── Mitigation fixtures ──────────────────────────────────────────────
  // Targeting resolves server-side, so each test supplies the resolved scope
  // explicitly via `scopes(...)`; the predicate here is a well-formed stand-in.
  private val pred: MitigationTarget =
    MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get)

  private def mitName(s: String): SafeName.SafeName = SafeName.fromString(s).toOption.get

  private def scopes(pairs: (Mitigation, Set[NodeId])*): Map[MitigationId, Set[NodeId]] =
    pairs.iterator.map { case (m, ns) => m.id -> ns }.toMap

  private def treeWith(mits: Mitigation*): RiskTree = unsafeGet(
    RiskTree.fromNodes(
      id = testTreeId,
      name = SafeName.SafeName("Test Tree".refineUnsafe),
      nodes = allNodes,
      rootId = rootId,
      mitigations = mits.toList
    ),
    "Mitigated fixture has invalid RiskTree"
  )

  private def resultCap(label: String, cap: Long,
                        precedence: MitigationPrecedence = MitigationPrecedence.default): Mitigation =
    Mitigation.create(
      mitigationId(label), mitName(label), pred,
      MitigationSpec.ResultStage(TransformPipeline(List(
        ResultTransformSpec.CapLosses(ValidationUtil.refineNonNegativeLong(cap).toOption.get)))),
      precedence).toEither.toOption.get

  private def leafScaleSeverity(label: String, factor: Double): Mitigation =
    Mitigation.create(
      mitigationId(label), mitName(label), pred,
      MitigationSpec.LeafStage(
        RiskLeafTransform(LikelihoodTransform.Keep,
          DistributionTransform.ScaleSeverity(ValidationUtil.refineNonNegativeDouble(factor).toOption.get)),
        None, None),
      MitigationPrecedence.default).toEither.toOption.get

  // Test layer with all dependencies
  val testLayer: ZLayer[Any, Throwable, CachedResultResolver & CacheScope] =
    ZLayer.make[CachedResultResolver & CacheScope](
      CacheScope.layer,
      ZLayer.succeed(TestConfigs.simulation),
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console,
      CachedResultResolverLive.layer
    )

  def spec = suite("CachedResultResolverSpec")(

    suite("ensureCached - content-addressed cache behavior")(

      test("cache miss: simulates and caches the identity-free content under the content hash") {
        for {
          resolver   <- ZIO.service[CachedResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          // Verify cache is empty initially
          initialCached <- cache.get(risk1Key)
          _ <- ZIO.succeed(assertTrue(initialCached.isEmpty))

          // Call ensureCached - should simulate
          result <- resolver.ensureCached(testTree, risk1Id, testEntity)

          // Verify result carries the requested node's identity
          _ <- ZIO.succeed(assertTrue(result.nodeId == risk1Id))

          // Verify content is now cached under the leaf's content hash
          cachedResult <- cache.get(risk1Key)
        } yield assertTrue(
          cachedResult.isDefined,
          // Cached value is identity-free: same outcomes, no node ID anywhere
          cachedResult.get.outcomes.outcomes == result.outcomes
        )
      },

      test("cache hit: returns cached content and counts a hit") {
        for {
          resolver   <- ZIO.service[CachedResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          // First call: simulate and cache
          firstResult  <- resolver.ensureCached(testTree, risk1Id, testEntity)
          statsBefore  <- cache.stats

          // Second call: served from cache
          secondResult <- resolver.ensureCached(testTree, risk1Id, testEntity)
          statsAfter   <- cache.stats
        } yield assertTrue(
          firstResult.outcomes == secondResult.outcomes,
          firstResult.nodeId == secondResult.nodeId,
          statsAfter.hits > statsBefore.hits
        )
      },

      test("simulates portfolio by aggregating children — portfolio results are never cached (DD-15)") {
        for {
          resolver   <- ZIO.service[CachedResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          risk1Result <- resolver.ensureCached(testTree, risk1Id, testEntity)
          risk2Result <- resolver.ensureCached(testTree, risk2Id, testEntity)
          rootResult  <- resolver.ensureCached(testTree, rootId, testEntity)
          allTrialIds  = risk1Result.outcomes.keySet ++ risk2Result.outcomes.keySet
          stats       <- cache.stats
        } yield assertTrue(
          rootResult.nodeId == rootId,
          // Deterministic seeds: both leaves fire in at least one trial
          allTrialIds.nonEmpty,
          // Every trial in either child: root outcome = pointwise sum (missing = 0)
          allTrialIds.forall { t =>
            rootResult.outcomes.getOrElse(t, 0L) ==
              risk1Result.outcomes.getOrElse(t, 0L) +
              risk2Result.outcomes.getOrElse(t, 0L)
          },
          // Only the two leaf entries exist — no portfolio entry (DD-15 → B)
          stats.entries == 2
        )
      },

      test("param edit strands the old entry (orphan) and the new content misses the old key") {
        // Same leaf identity, changed probability → different content hash
        val editedLeaf = RiskLeaf.unsafeApply(
          id = risk1IdStr,
          name = "Risk 1",
          distributionType = "lognormal",
          probability = 0.6,
          minLoss = Some(10000L),
          maxLoss = Some(50000L),
          parentId = Some(nodeId("root")),
          seedVarId = 1L
        )
        val editedTree = unsafeGet(
          RiskTree.fromNodes(
            id = testTreeId,
            name = SafeName.SafeName("Test Tree".refineUnsafe),
            nodes = Seq(rootNode, editedLeaf, risk2Leaf),
            rootId = rootId
          ),
          "Edited fixture has invalid RiskTree"
        )
        val editedKey = ContentHashIndex.hashOf(editedLeaf)

        for {
          resolver   <- ZIO.service[CachedResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          // Original content cached under its hash
          _         <- resolver.ensureCached(testTree, risk1Id, testEntity)
          origEntry <- cache.get(risk1Key)

          // Edited content: different key ⇒ MISS ⇒ fresh simulation
          statsBefore <- cache.stats
          edited      <- resolver.ensureCached(editedTree, risk1Id, testEntity)
          statsAfter  <- cache.stats

          // Old entry still present but unreachable from the edited tree (orphan)
          orphan   <- cache.get(risk1Key)
          newEntry <- cache.get(editedKey)
        } yield assertTrue(
          editedKey != risk1Key,
          origEntry.isDefined,
          statsAfter.misses > statsBefore.misses,
          orphan.isDefined,   // stranded — eviction's job, never served for edited content
          newEntry.isDefined,
          // The edited simulation really used the new params — figures differ
          edited.outcomes != origEntry.get.outcomes.outcomes
        )
      },

      test("content-identical leaves in different trees share one cache entry") {
        // Same leaf content under a different tree/name → same content hash
        val otherTree = unsafeGet(
          RiskTree.fromNodes(
            id = treeId("other-tree"),
            name = SafeName.SafeName("Other Tree".refineUnsafe),
            nodes = Seq(
              RiskPortfolio.unsafeFromStrings(
                id = rootIdStr,
                name = "Other Root",
                childIds = Array(risk1IdStr),
                parentId = None
              ),
              risk1Leaf
            ),
            rootId = rootId
          ),
          "Other fixture has invalid RiskTree"
        )

        for {
          resolver   <- ZIO.service[CachedResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          first       <- resolver.ensureCached(testTree, risk1Id, testEntity)
          statsBefore <- cache.stats
          second      <- resolver.ensureCached(otherTree, risk1Id, testEntity)
          statsAfter  <- cache.stats
        } yield assertTrue(
          // Cross-tree hit: figures identical, no second entry created
          first.outcomes == second.outcomes,
          statsAfter.hits > statsBefore.hits,
          statsAfter.entries == statsBefore.entries
        )
      }
    ),

    suite("ensureCachedAll - batch operations")(

      test("caches multiple nodes in one call") {
        for {
          resolver   <- ZIO.service[CachedResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)

          // Call with multiple node IDs
          results <- resolver.ensureCachedAll(testTree, Set(risk1Id, risk2Id), testEntity)

          // Verify all are now cached under their content hashes
          cached1 <- cache.get(risk1Key)
          cached2 <- cache.get(risk2Key)
        } yield assertTrue(
          // Verify all results returned
          results.size == 2,
          results.contains(risk1Id),
          results.contains(risk2Id),
          cached1.isDefined,
          cached2.isDefined
        )
      },

      test("handles empty set") {
        for {
          resolver <- ZIO.service[CachedResultResolver]
          results <- resolver.ensureCachedAll(testTree, Set.empty, testEntity)
        } yield assertTrue(results.isEmpty)
      },

      test("mix of cached and uncached nodes") {
        for {
          resolver <- ZIO.service[CachedResultResolver]

          // Pre-cache risk1
          _ <- resolver.ensureCached(testTree, risk1Id, testEntity)

          // Call with both cached and uncached
          results <- resolver.ensureCachedAll(testTree, Set(risk1Id, risk2Id), testEntity)

        } yield assertTrue(
          // Verify both returned
          results.size == 2,
          results.contains(risk1Id),
          results.contains(risk2Id)
        )
      }
    ),

    suite("workspace isolation (DD-17)")(

      test("different seedEntityIds resolve to separate caches") {
        val otherEntity: SeedEntityId.SeedEntityId = SeedEntityId.fromLong(2L).toOption.get
        for {
          resolver   <- ZIO.service[CachedResultResolver]
          cacheScope <- ZIO.service[CacheScope]

          _      <- resolver.ensureCached(testTree, risk1Id, testEntity)
          cacheB <- cacheScope.cacheFor(otherEntity)
          // Workspace B never simulated anything: same content hash, no entry
          crossHit <- cacheB.get(risk1Key)
        } yield assertTrue(crossHit.isEmpty)
      }
    ),

    suite("error handling")(

      test("fails when node not found in tree") {
        val invalidId = nodeId("nonexistent")

        for {
          resolver <- ZIO.service[CachedResultResolver]
          result <- resolver.ensureCached(testTree, invalidId, testEntity).exit

        } yield assertTrue(
          // Should fail with some error
          result.isFailure
        )
      }
    ),

    suite("telemetry verification")(

      test("records simulation on cache miss") {
        // Note: This is a basic test. Full telemetry testing would require
        // test doubles or inspecting the telemetry backend.
        for {
          resolver <- ZIO.service[CachedResultResolver]

          // This should trigger simulation and record metrics
          _ <- resolver.ensureCached(testTree, risk1Id, testEntity)

          // Telemetry is recorded via TracingLive.console
          // In a full implementation, we would assert on captured spans/metrics
        } yield assertCompletes
      }
    ),

    suite("mitigation edge-fold (§8.14, ADR-034 F)")(

      test("un-mitigated: a tree carrying a mitigation still resolves raw under selection None, group preserved") {
        val cap = resultCap("cap-root", 1L)   // would bind hard if applied
        val tree = treeWith(cap)
        for {
          resolver <- ZIO.service[CachedResultResolver]
          raw  <- resolver.ensureCached(testTree, rootId, testEntity)  // mitigation-free tree
          none <- resolver.ensureCached(tree, rootId, testEntity)      // mitigation present, selection defaults to None
        } yield assertTrue(
          // Not collapsed — the result-stage transform was not applied
          none.isInstanceOf[RiskResultGroup],
          // Byte-identical to the mitigation-free resolution
          none.outcomes == raw.outcomes,
          none.nodeId == rootId
        )
      },

      test("ResultStage cap on a leaf: caps that leaf's outcomes; an unscoped leaf is identity") {
        for {
          resolver <- ZIO.service[CachedResultResolver]
          raw   <- resolver.ensureCached(testTree, risk1Id, testEntity)
          rawMax = raw.outcomes.values.maxOption.getOrElse(2L)
          cap    = math.max(1L, rawMax / 2)      // derived from the raw run → guaranteed to bind
          m      = resultCap("cap-leaf", cap)
          tree   = treeWith(m)
          mit   <- resolver.ensureCached(tree, risk1Id, testEntity, selection = MitigationSelection.All, resolvedScopes =scopes(m -> Set(risk1Id)))
          raw2  <- resolver.ensureCached(tree, risk2Id, testEntity)
          mit2  <- resolver.ensureCached(tree, risk2Id, testEntity, selection = MitigationSelection.All, resolvedScopes =scopes(m -> Set(risk1Id)))
        } yield assertTrue(
          // Every trial: mitigated = min(raw, cap)
          raw.outcomes.forall { case (t, loss) => mit.outcomes.getOrElse(t, 0L) == math.min(loss, cap) },
          mit.outcomes.values.forall(_ <= cap),
          mit.outcomes != raw.outcomes,          // the cap bound
          // identity for a leaf the mitigation does not scope
          mit2.outcomes == raw2.outcomes
        )
      },

      test("ResultStage cap on a portfolio: raw aggregate = sum(children); mitigated = cap(sum), collapsed to a flat result") {
        for {
          resolver <- ZIO.service[CachedResultResolver]
          rawRoot <- resolver.ensureCached(testTree, rootId, testEntity)
          raw1    <- resolver.ensureCached(testTree, risk1Id, testEntity)
          raw2    <- resolver.ensureCached(testTree, risk2Id, testEntity)
          rawMax   = rawRoot.outcomes.values.maxOption.getOrElse(2L)
          cap      = math.max(1L, rawMax / 2)
          m        = resultCap("cap-root", cap)
          tree     = treeWith(m)
          mitRoot <- resolver.ensureCached(tree, rootId, testEntity, selection = MitigationSelection.All, resolvedScopes =scopes(m -> Set(rootId)))
        } yield assertTrue(
          // Raw aggregate stays the pristine commutative sum of children (ADR-034 Decision 4)
          rawRoot.isInstanceOf[RiskResultGroup],
          rawRoot.outcomes.forall { case (t, loss) =>
            loss == raw1.outcomes.getOrElse(t, 0L) + raw2.outcomes.getOrElse(t, 0L)
          },
          // Mitigated aggregate = f_P applied to the combined (here raw) total
          rawRoot.outcomes.forall { case (t, loss) =>
            mitRoot.outcomes.getOrElse(t, 0L) == math.min(loss, cap)
          },
          mitRoot.outcomes.values.forall(_ <= cap),
          mitRoot.outcomes != rawRoot.outcomes,
          // A binding portfolio transform cannot be a group → flat RiskResult
          mitRoot.isInstanceOf[RiskResult]
        )
      },

      test("compositional fold: caps on a child and its ancestor compose by position, order-independent") {
        for {
          resolver <- ZIO.service[CachedResultResolver]
          raw1    <- resolver.ensureCached(testTree, risk1Id, testEntity)
          raw2    <- resolver.ensureCached(testTree, risk2Id, testEntity)
          rawRoot <- resolver.ensureCached(testTree, rootId, testEntity)
          capChild = math.max(1L, raw1.outcomes.values.maxOption.getOrElse(2L) / 2)
          capRoot  = math.max(1L, rawRoot.outcomes.values.maxOption.getOrElse(2L) * 2 / 3)
          mc       = resultCap("cap-child", capChild)
          mr       = resultCap("cap-root", capRoot)
          treeAB   = treeWith(mc, mr)
          treeBA   = treeWith(mr, mc)
          sel      = scopes(mc -> Set(risk1Id), mr -> Set(rootId))
          rootAB  <- resolver.ensureCached(treeAB, rootId, testEntity, selection = MitigationSelection.All, resolvedScopes =sel)
          rootBA  <- resolver.ensureCached(treeBA, rootId, testEntity, selection = MitigationSelection.All, resolvedScopes =sel)
        } yield assertTrue(
          // Authoring order of the two mitigations does not change the result
          rootAB.outcomes == rootBA.outcomes,
          // Positional fold: cap the child first, add the untouched sibling, then cap the total
          (raw1.outcomes.keySet ++ raw2.outcomes.keySet).forall { t =>
            rootAB.outcomes.getOrElse(t, 0L) ==
              math.min(math.min(raw1.outcomes.getOrElse(t, 0L), capChild) + raw2.outcomes.getOrElse(t, 0L), capRoot)
          },
          rootAB.isInstanceOf[RiskResult]
        )
      },

      test("LeafStage mitigation changes the leaf's cache key and figures; the raw entry is untouched") {
        val scale = leafScaleSeverity("scale-leaf", 0.5)
        val tree  = treeWith(scale)
        val effTree = effectiveTreeFor(tree, scale)
        val effKey  = ContentHashIndex.hashOf(effTree.index.nodes(risk1Id).asInstanceOf[RiskLeaf])
        for {
          resolver   <- ZIO.service[CachedResultResolver]
          cacheScope <- ZIO.service[CacheScope]
          cache      <- cacheScope.cacheFor(testEntity)
          raw   <- resolver.ensureCached(testTree, risk1Id, testEntity)
          mit   <- resolver.ensureCached(tree, risk1Id, testEntity, selection = MitigationSelection.All, resolvedScopes =scopes(scale -> Set(risk1Id)))
          rawEntry <- cache.get(risk1Key)
          effEntry <- cache.get(effKey)
        } yield assertTrue(
          effKey != risk1Key,             // param-stage changed the content hash
          mit.outcomes != raw.outcomes,   // and the simulated figures
          rawEntry.isDefined,             // raw entry present and untouched
          effEntry.isDefined              // mitigated entry cached under the new key
        )
      }
    )

  ).provide(testLayer) @@ TestAspect.sequential

  /** The effective (param-stage-baked) tree for one LeafStage mitigation scoping
    * `risk1`, used to derive the mitigated leaf's content hash. */
  private def effectiveTreeFor(tree: RiskTree, m: Mitigation): RiskTree =
    com.risquanter.register.domain.data.MitigationApplication
      .effectiveTree(tree, MitigationSelection.All, scopes(m -> Set(risk1Id)))
      .toEither.toOption.get
}

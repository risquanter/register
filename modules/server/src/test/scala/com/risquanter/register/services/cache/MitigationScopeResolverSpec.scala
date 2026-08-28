package com.risquanter.register.services.cache

import zio.*
import zio.test.*
import io.github.iltotore.iron.autoRefine

import com.risquanter.register.domain.data.{
  RiskTree, RiskLeaf, RiskPortfolio, RiskNode,
  Mitigation, MitigationTarget, MitigationSpec, MitigationPrecedence, TargetingPredicate,
  RiskLeafTransform, LikelihoodTransform, DistributionTransform,
  TransformPipeline, ResultTransformSpec
}
import com.risquanter.register.domain.data.iron.{NodeId, TreeId, BranchRef, CommitHash, WorkspaceId, SafeName, SeedVarId}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.testutil.TestHelpers

/** Tests for [[MitigationScopeResolver]] — turning each mitigation's stored
  * targeting predicate into its applied node-id scope against a tree version,
  * with per-mitigation failure isolation, stage-domain restriction, granular
  * failure classification, and per-tree-version / per-workspace memoization.
  */
object MitigationScopeResolverSpec extends ZIOSpecDefault with TestHelpers:

  // ── Fixture tree ─────────────────────────────────────────────────────
  //
  //   Servers (portfolio, root)
  //   ├── AppTier (portfolio)
  //   │   ├── Web (leaf)
  //   │   └── DB  (leaf)
  //   └── Edge (leaf)
  //
  // leaves: Web, DB, Edge   portfolios: Servers, AppTier

  private val serversId = nodeId("servers")
  private val appTierId = nodeId("apptier")
  private val webId     = nodeId("web")
  private val dbId      = nodeId("db")
  private val edgeId    = nodeId("edge")

  private val servers = RiskPortfolio.unsafeApply(
    id = serversId.value, name = "Servers",
    childIds = Array(appTierId, edgeId), parentId = None)
  private val appTier = RiskPortfolio.unsafeApply(
    id = appTierId.value, name = "AppTier",
    childIds = Array(webId, dbId), parentId = Some(serversId))
  private def leaf(id: NodeId, nm: String, seed: Long, parent: NodeId): RiskLeaf =
    RiskLeaf.unsafeApply(
      id = id.value, name = nm, distributionType = "lognormal", probability = 0.2,
      minLoss = Some(1000L), maxLoss = Some(50000L), parentId = Some(parent), seedVarId = seed)
  private val web  = leaf(webId, "Web", 1L, appTierId)
  private val db   = leaf(dbId, "DB", 2L, appTierId)
  private val edge = leaf(edgeId, "Edge", 3L, serversId)

  private val allNodes: Map[NodeId, RiskNode] =
    Map(serversId -> servers, appTierId -> appTier, webId -> web, dbId -> db, edgeId -> edge)

  private def mkTree(mitigations: Mitigation*): RiskTree =
    RiskTree(
      id     = treeId("scope-tree"),
      name   = SafeName.fromString("Scope Tree").toOption.get,
      nodes  = allNodes.values.toSeq,
      rootId = serversId,
      index  = TreeIndex.fromNodesUnsafe(allNodes),
      seedVarHighWater = SeedVarId.fromLong(1000L).toOption.get,
      mitigations = mitigations.toList
    )

  // ── Mitigation builders ──────────────────────────────────────────────

  private val leafSpec: MitigationSpec =
    MitigationSpec.LeafStage(
      RiskLeafTransform(LikelihoodTransform.Scale(0.5), DistributionTransform.Keep), None, None)
  private val resultSpec: MitigationSpec =
    MitigationSpec.ResultStage(TransformPipeline(List(ResultTransformSpec.CapLosses(1000000L))))

  private def mit(label: String, predSrc: String, spec: MitigationSpec): Mitigation =
    Mitigation.create(
      mitigationId(label),
      SafeName.fromString(label).toOption.get,
      MitigationTarget.Predicate(TargetingPredicate.create(predSrc).toEither.toOption.get),
      spec,
      MitigationPrecedence.default
    ).toEither.toOption.get

  // ── Resolution context ───────────────────────────────────────────────

  private val rev1 = CommitHash.fromString("a" * 40).toOption.get
  private val rev2 = CommitHash.fromString("b" * 40).toOption.get
  private def ctx(rev: CommitHash) =
    ScopeResolutionContext(treeId("scope-tree"), BranchRef.Main, rev)

  private def newResolver: UIO[MitigationScopeResolverLive] =
    Ref.make(Map.empty[(TreeId, BranchRef), (CommitHash, ResolvedScopes)])
      .map(MitigationScopeResolverLive(_))

  // ── Spec ─────────────────────────────────────────────────────────────

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("MitigationScopeResolver")(

      test("happy path — predicate resolves to the matching leaf ids") {
        // leaves under AppTier = {Web, DB}; Edge is under Servers, excluded.
        val m = mit("m-happy", """leaf(x) /\ descendant_of(x, "AppTier")""", resultSpec)
        for
          r   <- newResolver
          out <- r.resolve(ctx(rev1), mkTree(m))
        yield assertTrue(out.outcomes(m.id) == ScopeOutcome.Resolved(Set(webId, dbId)))
      },

      test("stage-domain restriction — ResultStage keeps portfolios, LeafStage drops them") {
        // portfolio(x) satisfies {Servers, AppTier}. ResultStage domain = all
        // nodes → kept; LeafStage domain = leaves → intersection empty.
        val mResult = mit("m-pf-result", "portfolio(x)", resultSpec)
        val mLeaf   = mit("m-pf-leaf", "portfolio(x)", leafSpec)
        for
          r   <- newResolver
          out <- r.resolve(ctx(rev1), mkTree(mResult, mLeaf))
        yield assertTrue(
          out.appliedScopes(mResult.id) == Set(serversId, appTierId),
          out.appliedScopes(mLeaf.id)   == Set.empty[NodeId]
        )
      },

      test("per-mitigation isolation + granularity — a stale predicate fails in place, the valid one resolves") {
        val mStale = mit("m-stale", """child_of(x, "Gone")""", resultSpec)  // "Gone" not a node
        val mOk    = mit("m-ok", "leaf(x)", resultSpec)
        for
          r   <- newResolver
          out <- r.resolve(ctx(rev1), mkTree(mStale, mOk))
        yield assertTrue(
          out.outcomes(mStale.id) == ScopeOutcome.Failed(NonEmptyChunk(ScopeResolutionFailure.UnknownNode("Gone"))),
          out.appliedScopes(mStale.id) == Set.empty[NodeId],
          out.outcomes(mOk.id) == ScopeOutcome.Resolved(Set(webId, dbId, edgeId))
        )
      },

      test("malformed id vs unknown name — has_id(bad) is MalformedNodeId, named(gone) is UnknownNode") {
        val mBadId   = mit("m-badid", """has_id(x, "not-an-id")""", resultSpec)
        val mBadName = mit("m-badname", """named(x, "Gone")""", resultSpec)
        for
          r   <- newResolver
          out <- r.resolve(ctx(rev1), mkTree(mBadId, mBadName))
        yield assertTrue(
          out.outcomes(mBadId.id)   == ScopeOutcome.Failed(NonEmptyChunk(ScopeResolutionFailure.MalformedNodeId("not-an-id"))),
          out.outcomes(mBadName.id) == ScopeOutcome.Failed(NonEmptyChunk(ScopeResolutionFailure.UnknownNode("Gone")))
        )
      },

      test("projection — appliedScopes maps Resolved to its set and Failed to the empty set") {
        val mOk    = mit("m-ok", "leaf(x)", resultSpec)
        val mStale = mit("m-stale", """named(x, "Gone")""", resultSpec)
        for
          r   <- newResolver
          out <- r.resolve(ctx(rev1), mkTree(mOk, mStale))
        yield assertTrue(
          out.appliedScopes == Map(
            mOk.id    -> Set(webId, dbId, edgeId),
            mStale.id -> Set.empty[NodeId]
          ),
          out.failures.keySet == Set(mStale.id)
        )
      },

      test("memoization is per tree version and head-only") {
        // Same (treeId, branch, revision) reuses the cached ResolvedScopes object
        // (reference identity); a new revision recomputes; head-only eviction
        // means the slot now holds the new revision, so the original revision
        // recomputes on its next request too.
        val t = mkTree(mit("m-ok", "leaf(x)", resultSpec))
        for
          r <- newResolver
          a <- r.resolve(ctx(rev1), t)
          b <- r.resolve(ctx(rev1), t)   // memo hit
          c <- r.resolve(ctx(rev2), t)   // new revision → recompute + replace slot
          d <- r.resolve(ctx(rev1), t)   // slot now holds rev2 → recompute
        yield assertTrue(b eq a, !(c eq a), !(d eq a))
      },

      test("per-workspace isolation — one resolver instance per WorkspaceId") {
        val ws1 = WorkspaceId(safeId("ws-1"))
        val ws2 = WorkspaceId(safeId("ws-2"))
        for
          ref   <- Ref.make(Map.empty[WorkspaceId, MitigationScopeResolver])
          scope  = ScopeResolverScopeLive(ref)
          r1    <- scope.resolverFor(ws1)
          r1b   <- scope.resolverFor(ws1)
          r2    <- scope.resolverFor(ws2)
        yield assertTrue(r1 eq r1b, !(r1 eq r2))
      }
    )

end MitigationScopeResolverSpec

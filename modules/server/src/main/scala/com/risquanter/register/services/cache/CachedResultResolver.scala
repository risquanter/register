package com.risquanter.register.services.cache

import zio.*
import com.risquanter.register.domain.data.{LossDistribution, RiskTree, MitigationSelection}
import com.risquanter.register.domain.data.iron.{NodeId, SeedEntityId, MitigationId}

/**
  * Service for resolving RiskResult with cache-aside pattern (ADR-015).
  *
  * Core primitive: `ensureCached(tree, nodeId)` checks cache first, simulates on miss.
  * All query APIs compose on top of this primitive.
  *
  * Methods take a `RiskTree` parameter to access tree-scoped TreeIndex.
  *
  * == Separation of Concerns ==
  * - ContentCache: Pure content-addressed storage (get/put; no invalidation —
  *   an edited leaf hashes to a new key and misses naturally)
  * - CachedResultResolver: Orchestration (content hashing + cache + simulation)
  *
  * == Mitigation ==
  * `selection` chooses which mitigations a resolution applies and `resolvedScopes`
  * carries the server-resolved per-mitigation node sets. Param-stage transforms
  * are baked into the effective tree so they change the cache-key content;
  * result-stage transforms are applied at the read edge and never cached (ADR-034,
  * PLAN-RISKTRANSFORM §8.14). The defaults (`None` / empty) make the whole
  * mitigation path identity, so every existing caller resolves the raw tree.
  *
  * == Usage Pattern ==
  * {{{
  * // Query APIs become simple compositions:
  * def getLECCurve(tree: RiskTree, nodeId: NodeId) =
  *   resolver.ensureCached(tree, nodeId).map(LECGenerator.generateCurvePoints(_))
  *
  * def probOfExceedance(tree: RiskTree, nodeId: NodeId, threshold: Loss) =
  *   resolver.ensureCached(tree, nodeId).map(_.probOfExceedance(threshold))
  * }}}
  */
trait CachedResultResolver {

  /**
    * Ensure result is cached for a node.
    *
    * Cache-aside pattern (content-addressed since milestone 2b Phase A):
    * 1. Compute the node's content hash from the effective tree (ContentHashIndex)
    * 2. Leaf hit: return cached content with this node's ID attached
    * 3. Leaf miss: simulate, cache under the content hash, return
    * 4. Portfolio: aggregate child results on every read (never cached)
    *
    * @param tree Risk tree containing the node (provides TreeIndex)
    * @param nodeId Node identifier
    * @param seedEntityId Owning workspace's stochastic identity (HDR Entity axis) —
    *                     threaded explicitly from the controller's resolved workspace
    * @param includeProvenance Whether to capture provenance metadata (default: false)
    * @param selection Which mitigations to apply (default: None — un-mitigated)
    * @param resolvedScopes Server-resolved per-mitigation node sets (default: empty)
    * @return LossDistribution (from cache or freshly simulated)
    */
  def ensureCached(
    tree: RiskTree,
    nodeId: NodeId,
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.None,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): Task[LossDistribution]

  /**
    * Batch version of ensureCached for multiple nodes.
    *
    * Optimized for multi-node display (e.g., split pane LEC comparison).
    * Nodes with cached results are returned immediately; only missing
    * nodes trigger simulation.
    *
    * @param tree Risk tree containing the nodes
    * @param nodeIds Set of node identifiers
    * @param seedEntityId Owning workspace's stochastic identity (HDR Entity axis)
    * @param includeProvenance Whether to capture provenance metadata (default: false)
    * @param selection Which mitigations to apply (default: None — un-mitigated)
    * @param resolvedScopes Server-resolved per-mitigation node sets (default: empty)
    * @return Map from nodeId to LossDistribution
    */
  def ensureCachedAll(
    tree: RiskTree,
    nodeIds: Set[NodeId],
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.None,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): Task[Map[NodeId, LossDistribution]]
}

object CachedResultResolver {

  // Accessor methods for ZIO service pattern
  def ensureCached(
    tree: RiskTree,
    nodeId: NodeId,
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.None,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): ZIO[CachedResultResolver, Throwable, LossDistribution] =
    ZIO.serviceWithZIO[CachedResultResolver](_.ensureCached(tree, nodeId, seedEntityId, includeProvenance, selection, resolvedScopes))

  def ensureCachedAll(
    tree: RiskTree,
    nodeIds: Set[NodeId],
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.None,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): ZIO[CachedResultResolver, Throwable, Map[NodeId, LossDistribution]] =
    ZIO.serviceWithZIO[CachedResultResolver](_.ensureCachedAll(tree, nodeIds, seedEntityId, includeProvenance, selection, resolvedScopes))
}

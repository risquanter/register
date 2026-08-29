package com.risquanter.register.services.cache

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Histogram, Counter}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.opentelemetry.api.trace.SpanKind
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.{LossDistribution, RiskResult, RiskResultGroup, RiskNode, RiskLeaf, RiskPortfolio, RiskTree, TrialOutcomes, Mitigation, MitigationApplication, MitigationSelection, NodeProvenance}
import com.risquanter.register.domain.data.iron.{PositiveInt, NodeId, ContentHash, SeedEntityId, MitigationId}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.services.helper.Simulator
import io.github.iltotore.iron.refineUnsafe

/**
  * Live implementation of CachedResultResolver (ADR-015), content-addressed.
  *
  * Resolution pipeline per request:
  * 1. `effectiveTree` bakes every param-stage (LeafStage) mitigation into the
  *    tree so it changes the cache-key content; `selection = None` returns the
  *    input tree revalidated (identical content, identical hashes).
  * 2. `ContentHashIndex.build(effective)` — pure, O(n): leaf keys hash the
  *    DD-16 projection; portfolio Merkle hashes ride along for diffing.
  * 3. Leaf: look up `ContentCache` by content hash — hit returns the cached
  *    identity-free content with the requested node's ID attached at this
  *    edge (DD-16/DD-18); miss simulates and stores. The result-stage transform
  *    (`resultTransformFor`) is applied to the finished leaf outcomes at the
  *    edge and never cached (ADR-034 Decision 1 / D3).
  * 4. Portfolio: never cached (DD-15 → B) — the mitigated children are combined
  *    with `RiskResultGroup.create` on every read, then this node's result-stage
  *    transform is applied to the combined total (ADR-034 F:
  *    `mitigated(P) = f_P(⊕ mitigated(children))`).
  *
  * There is no invalidation path: an edited leaf hashes to a new key and
  * simply misses; the old entry becomes an unreachable orphan for the
  * `EvictionStrategy`.
  *
  * Cache instances are per-workspace via `CacheScope` (DD-17), keyed by the
  * workspace's `seedEntityId`.
  *
  * Telemetry (ADR-002):
  * - Spans: ensureCached; simulateLeaf on miss (with duration + trials metrics)
  * - Cache stats (entries/hits/misses) logged at debug after each resolution
  */
final case class CachedResultResolverLive(
    cacheScope: CacheScope,
    config: SimulationConfig,
    tracing: Tracing,
    simulationDuration: Histogram[Double],
    trialsCounter: Counter[Long]
) extends CachedResultResolver {

  private given SimulationConfig = config

  // Read from config at construction time
  private val nTrials: PositiveInt = config.defaultNTrials.refineUnsafe
  private val parallelism: PositiveInt = config.defaultTrialParallelism.refineUnsafe
  // HDR seeds for reproducible simulations (ADR-003)
  private val seed3: Long = config.defaultSeed3
  private val seed4: Long = config.defaultSeed4

  override def ensureCached(
    tree: RiskTree,
    nodeId: NodeId,
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.None,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): Task[LossDistribution] =
    tracing.span("ensureCached", SpanKind.INTERNAL) {
      for {
        _         <- tracing.setAttribute("tree_id", tree.id.value)
        _         <- tracing.setAttribute("node_id", nodeId.value)
        _         <- tracing.setAttribute("include_provenance", includeProvenance)
        effective <- effectiveTreeOf(tree, selection, resolvedScopes)
        scoped     = MitigationApplication.scoped(tree, selection, resolvedScopes)
        cache     <- cacheScope.cacheFor(seedEntityId)
        result    <- distributionForId(effective, ContentHashIndex.build(effective), cache, nodeId, seedEntityId, scoped)
        stats     <- cache.stats
        _         <- ZIO.logDebug(s"ContentCache stats: entries=${stats.entries}, hits=${stats.hits}, misses=${stats.misses}, evicted=${stats.evictedTotal}")
      } yield result
    }

  override def ensureCachedAll(
    tree: RiskTree,
    nodeIds: Set[NodeId],
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.None,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): Task[Map[NodeId, LossDistribution]] =
    for {
      effective <- effectiveTreeOf(tree, selection, resolvedScopes)
      scoped     = MitigationApplication.scoped(tree, selection, resolvedScopes)
      cache     <- cacheScope.cacheFor(seedEntityId)
      // One tree fingerprint serves the whole batch
      hashes     = ContentHashIndex.build(effective)
      results   <- ZIO.foreach(nodeIds.toList)(id =>
        distributionForId(effective, hashes, cache, id, seedEntityId, scoped).map(id -> _)
      )
    } yield results.toMap

  /** Param-stage half of the mitigation action: LeafStage transforms baked into
    * the tree so they drive the cache keys. `selection = None` yields the input
    * tree revalidated through `RiskTree.fromNodes` — identical content, identical
    * hashes — so raw leaf simulations are shared with the un-mitigated path.
    * ADR-010: a validation failure becomes typed `ValidationFailed`. */
  private def effectiveTreeOf(
    tree: RiskTree,
    selection: MitigationSelection,
    resolvedScopes: Map[MitigationId, Set[NodeId]]
  ): Task[RiskTree] =
    ZIO.fromEither(MitigationApplication.effectiveTree(tree, selection, resolvedScopes).toEither)
      .mapError(errs => ValidationFailed(errs.toList))

  private def distributionForId(
    tree: RiskTree,
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    nodeId: NodeId,
    seedEntityId: SeedEntityId.SeedEntityId,
    scoped: Map[NodeId, List[Mitigation]]
  ): Task[LossDistribution] =
    ZIO.fromOption(tree.index.nodes.get(nodeId))
      .orElseFail(ValidationFailed(List(ValidationError(
        field = "nodeId",
        code = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = s"Node not found in tree index: $nodeId"
      ))))
      .flatMap(node => distributionOf(tree, hashes, cache, node, seedEntityId, scoped))

  private def distributionOf(
    tree: RiskTree,
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    node: RiskNode,
    seedEntityId: SeedEntityId.SeedEntityId,
    scoped: Map[NodeId, List[Mitigation]]
  ): Task[LossDistribution] =
    node match {
      case leaf: RiskLeaf =>
        rawLeafResult(hashes, cache, leaf, seedEntityId).map { raw =>
          // Result-stage transform on the finished leaf operand (ADR-009), applied
          // post-cache and never stored (D3). None when nothing result-stage
          // scopes the leaf, so the raw cached value passes through unchanged.
          MitigationApplication.resultTransformFor(leaf.id, scoped) match {
            case None    => raw
            case Some(t) => RiskResult.fromTrialOutcomes(leaf.id, t.run(raw.trialOutcomes), raw.provenances)
          }
        }

      case portfolio: RiskPortfolio =>
        for {
          // Parallel child resolution: children root disjoint subtrees (single-parent
          // tree), and TrialOutcomes aggregation is associative and commutative, so
          // evaluation order cannot change the aggregated figures. foreachPar
          // preserves list order, keeping provenance order identical to sequential.
          childResults <- ZIO.foreachPar(portfolio.childIds.toList) { childId =>
            ZIO.fromOption(tree.index.nodes.get(childId))
              .orElseFail(ValidationFailed(List(ValidationError(
                field = s"riskPortfolio.${portfolio.id}.childIds",
                code = ValidationErrorCode.CONSTRAINT_VIOLATION,
                message = s"Child node not found in tree index: $childId"
              ))))
              .flatMap(childNode => distributionOf(tree, hashes, cache, childNode, seedEntityId, scoped))
          }
          _ <- ZIO.when(childResults.isEmpty) {
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = s"riskPortfolio.${portfolio.id}.childIds",
              code = ValidationErrorCode.EMPTY_COLLECTION,
              message = s"RiskPortfolio '${portfolio.id}' has no children"
            ))))
          }
          // ⊕ mitigated(children): the commutative fold over already-mitigated
          // children. Portfolios are never cached (DD-15 → B); the aggregate is
          // an undecorated combine of its children (mitigated or not), kept that
          // way by ADR-034 Decision 4's non-mutation invariant.
          combined <- ZIO.fromEither(RiskResultGroup.create(portfolio.id, childResults*).toEither)
            .mapError(errors => ValidationFailed(errors.toList))
        } yield {
          // F (ADR-034): mitigated(P) = f_P(⊕ mitigated(children)). No result-stage
          // mitigation scoping P returns the group unchanged — child structure and
          // drill-down preserved, byte-identical to the un-mitigated path. A binding
          // transform cannot be a RiskResultGroup (its aggregate is pinned to
          // combine(children)), so it collapses to a flat RiskResult carrying the
          // transformed outcomes and the descendants' provenances.
          MitigationApplication.resultTransformFor(portfolio.id, scoped) match {
            case None    => combined
            case Some(t) => RiskResult.fromTrialOutcomes(portfolio.id, t.run(combined.trialOutcomes), descendantProvenances(combined))
          }
        }
    }

  /** Every descendant leaf's provenance, in traversal order. A collapsed
    * transformed portfolio already carries its own descendants' provenances, so
    * matching `RiskResult` returns them directly without recursing further. */
  private def descendantProvenances(dist: LossDistribution): List[NodeProvenance] =
    dist match {
      case r: RiskResult      => r.provenances
      case g: RiskResultGroup => g.children.flatMap(descendantProvenances)
    }

  private def rawLeafResult(
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    leaf: RiskLeaf,
    seedEntityId: SeedEntityId.SeedEntityId
  ): Task[RiskResult] =
    for {
      key <- ZIO.fromOption(hashes.get(leaf.id))
        .orElseFail(ValidationFailed(List(ValidationError(
          field = s"riskLeaf.${leaf.id}",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = s"Leaf not reachable from tree root (no content hash): ${leaf.id}"
        ))))
      cached <- cache.get(key)
      result <- cached match {
        case Some(content) =>
          // Hit: identity attached at the edge — the entry may have been
          // written for any content-identical leaf (DD-16/DD-18)
          ZIO.succeed(RiskResult.fromTrialOutcomes(leaf.id, content.outcomes, List(content.provenance)))
        case None =>
          simulateLeaf(cache, key, leaf, seedEntityId)
      }
    } yield result

  private def simulateLeaf(
    cache: ContentCache,
    key: ContentHash,
    leaf: RiskLeaf,
    seedEntityId: SeedEntityId.SeedEntityId
  ): Task[RiskResult] =
    tracing.span("simulateLeaf", SpanKind.INTERNAL) {
      for {
        _         <- tracing.setAttribute("node_id", leaf.id.value)
        _         <- tracing.setAttribute("n_trials", nTrials.toLong)
        startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

        // Always capture provenance (filter at service layer)
        // Rationale: Maintain chain of truth - provenance always in cache
        (sampler, provenance) <- Simulator.createSamplerFromLeaf(leaf, seedEntityId, seed3, seed4)
        trials                <- Simulator.performTrials(sampler, nTrials, parallelism)
        outcomes               = TrialOutcomes(nTrials, trials)
        _                     <- cache.put(key, LeafSimResult(outcomes, provenance))

        endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        _       <- recordSimulationMetrics(leaf.id.value, nTrials, endTime - startTime)
      } yield RiskResult.fromTrialOutcomes(leaf.id, outcomes, List(provenance))
    }

  /** Record simulation performance metrics (ADR-002) */
  private def recordSimulationMetrics(nodeName: String, nTrials: PositiveInt, durationMs: Long): UIO[Unit] = {
    val attrs = Attributes(Attribute.string("node_name", nodeName))
    simulationDuration.record(durationMs.toDouble, attrs) *>
      trialsCounter.add(nTrials.toLong, attrs)
  }
}

object CachedResultResolverLive {

  /** Metric names */
  private object MetricNames {
    val simulationDuration = "risk_result.simulation.duration_ms"
    val simulationDurationUnit = "ms"
    val simulationDurationDesc = "Duration of node simulation in milliseconds"

    val trialsCounter = "risk_result.simulation.trials"
    val trialsUnit = "1"
    val trialsDesc = "Total number of simulation trials executed"
  }

  /**
    * Create ZLayer for CachedResultResolver with telemetry.
    * Uses CacheScope for per-workspace content-addressed cache access (DD-17).
    */
  val layer: ZLayer[CacheScope & SimulationConfig & Tracing & Meter, Throwable, CachedResultResolver] =
    ZLayer.fromZIO {
      for {
        cacheScope <- ZIO.service[CacheScope]
        config     <- ZIO.service[SimulationConfig]
        tracing    <- ZIO.service[Tracing]
        meter      <- ZIO.service[Meter]

        // Create metric instruments
        simDuration <- meter.histogram(
          MetricNames.simulationDuration,
          Some(MetricNames.simulationDurationUnit),
          Some(MetricNames.simulationDurationDesc)
        )
        trials <- meter.counter(
          MetricNames.trialsCounter,
          Some(MetricNames.trialsUnit),
          Some(MetricNames.trialsDesc)
        )
      } yield CachedResultResolverLive(cacheScope, config, tracing, simDuration, trials)
    }
}

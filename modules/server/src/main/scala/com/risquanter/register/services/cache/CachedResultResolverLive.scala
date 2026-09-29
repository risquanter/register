package com.risquanter.register.services.cache

import zio.*
import zio.prelude.Validation
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Histogram, Counter}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.opentelemetry.api.trace.SpanKind
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio, RiskTree, MitigationApplicationRecord}
import com.risquanter.register.mitigation.{MitigationApplication, MitigationSelection}
import com.risquanter.register.simulation.{LossDistribution, TrialOutcomes}
import com.risquanter.register.domain.data.iron.{PositiveInt, NodeId, ContentHash, SeedEntityId, MitigationId, ValidationMessages}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.services.helper.Simulator
import io.github.iltotore.iron.refineUnsafe

/**
  * Live implementation of CachedResultResolver (ADR-015), content-addressed.
  *
  * Resolution pipeline per request:
  * 1. `effectiveTree` bakes every param-stage mitigation into the tree so it
  *    changes the cache-key content.
  * 2. `ContentHashIndex.build` computes the leaf content hashes.
  * 3. `recordsByNode` computes each node's result-stage layer once for the tree.
  * 4. Leaf: look up `ContentCache` by content hash, simulating and storing on a
  *    miss. `NodeLosses.leaf` attaches the requested node's ID at this edge.
  * 5. Portfolio: never cached. `PortfolioLosses.create` combines the
  *    already-mitigated children on every read.
  * 6. `LossDistribution.decorate` applies the node's layer to those figures,
  *    giving `m(P) = f_P(⊕ m(children))` (ADR-034).
  *
  * There is no invalidation path: an edited leaf hashes to a new key and
  * misses; the old entry becomes an orphan for the `EvictionStrategy`.
  *
  * Cache instances are per-workspace via `ContentCacheRegistry`, keyed by the
  * workspace's `seedEntityId`.
  */
final case class CachedResultResolverLive(
    caches: ContentCacheRegistry,
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
    selection: MitigationSelection = MitigationSelection.Inherent,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): Task[LossDistribution] =
    tracing.span("ensureCached", SpanKind.INTERNAL) {
      for {
        _         <- tracing.setAttribute("tree_id", tree.id.value)
        _         <- tracing.setAttribute("node_id", nodeId.value)
        _         <- tracing.setAttribute("include_provenance", includeProvenance)
        effective <- effectiveTreeOf(tree, selection, resolvedScopes)
        scoped     = MitigationApplication.scoped(tree, selection, resolvedScopes)
        records    = MitigationApplication.recordsByNode(scoped)
        cache     <- caches.forWorkspace(seedEntityId)
        result    <- distributionForId(effective, ContentHashIndex.build(effective), cache, nodeId, seedEntityId, records)
        stats     <- cache.stats
        _         <- ZIO.logDebug(s"ContentCache stats: entries=${stats.entries}, hits=${stats.hits}, misses=${stats.misses}, evicted=${stats.evictedTotal}")
      } yield result
    }

  override def ensureCachedAll(
    tree: RiskTree,
    nodeIds: Set[NodeId],
    seedEntityId: SeedEntityId.SeedEntityId,
    includeProvenance: Boolean = false,
    selection: MitigationSelection = MitigationSelection.Inherent,
    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty
  ): Task[Map[NodeId, LossDistribution]] =
    for {
      effective <- effectiveTreeOf(tree, selection, resolvedScopes)
      scoped     = MitigationApplication.scoped(tree, selection, resolvedScopes)
      records    = MitigationApplication.recordsByNode(scoped)
      cache     <- caches.forWorkspace(seedEntityId)
      // One tree fingerprint serves the whole batch
      hashes     = ContentHashIndex.build(effective)
      results   <- ZIO.foreach(nodeIds.toList)(id =>
        distributionForId(effective, hashes, cache, id, seedEntityId, records).map(id -> _)
      )
    } yield results.toMap

  /** Param-stage half of the mitigation action: LeafStage transforms baked into
    * the tree so they drive the cache keys. `selection = Inherent` yields the input
    * tree revalidated through `RiskTree.fromNodes` — identical content, identical
    * hashes — so raw leaf simulations are shared with the un-mitigated path.
    * ADR-010: a validation failure becomes typed `ValidationFailed`. */
  private def effectiveTreeOf(
    tree: RiskTree,
    selection: MitigationSelection,
    resolvedScopes: Map[MitigationId, Set[NodeId]]
  ): Task[RiskTree] =
    fromValidation(MitigationApplication.effectiveTree(tree, selection, resolvedScopes))

  private def distributionForId(
    tree: RiskTree,
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    nodeId: NodeId,
    seedEntityId: SeedEntityId.SeedEntityId,
    records: Map[NodeId, List[MitigationApplicationRecord]]
  ): Task[LossDistribution] =
    ZIO.fromOption(tree.index.nodes.get(nodeId))
      .orElseFail(ValidationFailed(List(ValidationError(
        field = "nodeId",
        code = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = s"Node not found in tree index: $nodeId"
      ))))
      .flatMap(node => distributionOf(tree, hashes, cache, node, seedEntityId, records))

  private def distributionOf(
    tree: RiskTree,
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    node: RiskNode,
    seedEntityId: SeedEntityId.SeedEntityId,
    records: Map[NodeId, List[MitigationApplicationRecord]]
  ): Task[LossDistribution] =
    node match {
      case leaf: RiskLeaf =>
        for {
          content <- rawLeafResult(hashes, cache, leaf, seedEntityId)
          losses   = NodeLosses.leaf(leaf.id, content.outcomes, content.provenance)
          applied  = records.getOrElse(leaf.id, Nil)
          value   <- fromValidation(LossDistribution.decorate(
                       losses.nodeId, losses.trials, Some(losses.provenance), applied,
                       MitigationApplication.run(applied, _)))
        } yield value

      case portfolio: RiskPortfolio =>
        for {
          // Children root disjoint subtrees and the aggregation is associative
          // and commutative, so evaluation order cannot change the figures.
          // foreachPar preserves list order (ADR-009 §4).
          childResults <- ZIO.foreachPar(portfolio.childIds.toList) { childId =>
            ZIO.fromOption(tree.index.nodes.get(childId))
              .orElseFail(ValidationFailed(List(ValidationError(
                field = s"riskPortfolio.${portfolio.id}.childIds",
                code = ValidationErrorCode.CONSTRAINT_VIOLATION,
                message = s"Child node not found in tree index: $childId"
              ))))
              .flatMap(childNode => distributionOf(tree, hashes, cache, childNode, seedEntityId, records))
          }
          _ <- ZIO.when(childResults.isEmpty) {
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = s"riskPortfolio.${portfolio.id}.childIds",
              code = ValidationErrorCode.EMPTY_COLLECTION,
              message = ValidationMessages.portfolioHasNoChildren
            ))))
          }
          // ⊕ m(children), then f_P on top. The layer is applied outside the
          // aggregate claim, so a transformed portfolio keeps its children
          // (ADR-034 Decision 3). A portfolio carries no provenance of its own
          // (ADR-003 §2).
          losses  <- fromValidation(NodeLosses.portfolio(portfolio.id, childResults))
          applied  = records.getOrElse(portfolio.id, Nil)
          value   <- fromValidation(LossDistribution.decorate(
                       losses.nodeId, losses.trials, None, applied,
                       MitigationApplication.run(applied, _)))
        } yield value
    }

  /** A `Validation` failure becomes a typed `ValidationFailed` (ADR-010). */
  private def fromValidation[A](v: Validation[ValidationError, A]): Task[A] =
    ZIO.fromEither(v.toEither).mapError(errs => ValidationFailed(errs.toList))

  /** Identity-free cached content for one effective leaf. Node identity is
    * attached one level above, since the same entry may serve any
    * content-identical leaf. */
  private def rawLeafResult(
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    leaf: RiskLeaf,
    seedEntityId: SeedEntityId.SeedEntityId
  ): Task[LeafSimResult] =
    for {
      key <- ZIO.fromOption(hashes.get(leaf.id))
        .orElseFail(ValidationFailed(List(ValidationError(
          field = s"riskLeaf.${leaf.id}",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = s"Leaf not reachable from tree root (no content hash): ${leaf.id}"
        ))))
      cached <- cache.get(key)
      result <- cached match {
        case Some(content) => ZIO.succeed(content)
        case None          => simulateLeaf(cache, key, leaf, seedEntityId)
      }
    } yield result

  private def simulateLeaf(
    cache: ContentCache,
    key: ContentHash,
    leaf: RiskLeaf,
    seedEntityId: SeedEntityId.SeedEntityId
  ): Task[LeafSimResult] =
    tracing.span("simulateLeaf", SpanKind.INTERNAL) {
      for {
        _         <- tracing.setAttribute("node_id", leaf.id.value)
        _         <- tracing.setAttribute("n_trials", nTrials.toLong)
        startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

        // Always capture provenance (filter at service layer)
        // Rationale: Maintain chain of truth - provenance always in cache
        (sampler, provenance) <- Simulator.createSamplerFromLeaf(leaf, seedEntityId, seed3, seed4)
        losses                <- Simulator.performTrials(sampler, nTrials, parallelism)
        content                = LeafSimResult(TrialOutcomes(nTrials, losses), provenance)
        _                     <- cache.put(key, content)

        endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        _       <- recordSimulationMetrics(leaf.id.value, nTrials, endTime - startTime)
      } yield content
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
    * Uses ContentCacheRegistry for per-workspace content-addressed cache access.
    */
  val layer: ZLayer[ContentCacheRegistry & SimulationConfig & Tracing & Meter, Throwable, CachedResultResolver] =
    ZLayer.fromZIO {
      for {
        caches     <- ZIO.service[ContentCacheRegistry]
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
      } yield CachedResultResolverLive(caches, config, tracing, simDuration, trials)
    }
}

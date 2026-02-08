package com.risquanter.register.services.cache

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Histogram, Counter}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.opentelemetry.api.trace.SpanKind
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.{RiskResult, RiskNode, RiskLeaf, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.data.iron.{PositiveInt, NodeId}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.services.helper.Simulator
import io.github.iltotore.iron.refineUnsafe

/**
  * Live implementation of RiskResultResolver (ADR-015).
  *
  * Dependencies:
  * - TreeCacheManager: Per-tree cache management
  * - SimulationConfig: Simulation parameters (nTrials, parallelism)
  * - Tracing: OpenTelemetry tracing for observability
  * - Meter: Metrics instrumentation
  *
  * TreeIndex is obtained from RiskTree parameter per operation (tree-scoped design).
  * Cache is obtained from TreeCacheManager using tree.id.
  * Simulation parameters are read from config at construction time.
  * Seeds are hardwired for now (TODO: add to config when reproducibility API is defined).
  *
  * Telemetry (ADR-002):
  * - Spans: ensureCached with cache_hit attribute, simulateSubtree on miss
  * - Metrics: simulation duration histogram, trials counter
  */
final case class RiskResultResolverLive(
    cacheManager: TreeCacheManager,
    config: SimulationConfig,
    tracing: Tracing,
    simulationDuration: Histogram[Double],
    trialsCounter: Counter[Long]
) extends RiskResultResolver {

  private given SimulationConfig = config

  // Read from config at construction time
  private val nTrials: PositiveInt = config.defaultNTrials.refineUnsafe
  private val parallelism: PositiveInt = config.defaultTrialParallelism.refineUnsafe
  // HDR seeds for reproducible simulations (ADR-003)
  private val seed3: Long = config.defaultSeed3
  private val seed4: Long = config.defaultSeed4

  override def ensureCached(tree: RiskTree, nodeId: NodeId, includeProvenance: Boolean = false): Task[RiskResult] =
    tracing.span("ensureCached", SpanKind.INTERNAL) {
      for {
        _         <- tracing.setAttribute("tree_id", tree.id.toString)
        _         <- tracing.setAttribute("node_id", nodeId.value.toString)
        _         <- tracing.setAttribute("include_provenance", includeProvenance)
        cache     <- cacheManager.cacheFor(tree.id)
        resultOpt <- cache.get(nodeId)
        result <- resultOpt match {
          case Some(cached) =>
            tracing.setAttribute("cache_hit", true) *> ZIO.succeed(cached)
          case None =>
            tracing.setAttribute("cache_hit", false) *> simulateSubtree(tree, nodeId, includeProvenance)
        }
      } yield result
    }

  override def ensureCachedAll(tree: RiskTree, nodeIds: Set[NodeId], includeProvenance: Boolean = false): Task[Map[NodeId, RiskResult]] =
    ZIO.foreach(nodeIds.toList)(id => ensureCached(tree, id, includeProvenance).map(id -> _)).map(_.toMap)

  /**
    * Simulate subtree rooted at nodeId, caching all results.
    * Wraps simulation in span with timing metrics.
    */
  private def simulateSubtree(tree: RiskTree, nodeId: NodeId, includeProvenance: Boolean): Task[RiskResult] =
    tracing.span("simulateSubtree", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_id", nodeId.value.toString)
        _ <- tracing.setAttribute("n_trials", nTrials.toLong)
        _ <- tracing.setAttribute("parallelism", parallelism.toLong)
        _ <- tracing.setAttribute("include_provenance", includeProvenance)
        _ <- tracing.addEvent("simulation_started")
        
        startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        
        node <- ZIO.fromOption(tree.index.nodes.get(nodeId))
          .orElseFail(ValidationFailed(List(ValidationError(
            field = "nodeId",
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = s"Node not found in tree index: $nodeId"
          ))))
        
        cache  <- cacheManager.cacheFor(tree.id)
        result <- simulateNode(tree, cache, node, includeProvenance)
        
        endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        durationMs = endTime - startTime
        
        _ <- tracing.addEvent("simulation_completed")
        _ <- recordSimulationMetrics(nodeId.value.toString, nTrials, durationMs)
      } yield result
    }
  
  /** Record simulation performance metrics (ADR-002) */
  private def recordSimulationMetrics(nodeName: String, nTrials: PositiveInt, durationMs: Long): UIO[Unit] = {
    val attrs = Attributes(Attribute.string("node_name", nodeName))
    simulationDuration.record(durationMs.toDouble, attrs) *>
      trialsCounter.add(nTrials.toLong, attrs)
  }

  private def simulateNode(tree: RiskTree, cache: RiskResultCache, node: RiskNode, includeProvenance: Boolean): Task[RiskResult] =
    node match {
      case leaf: RiskLeaf =>
        simulateLeaf(cache, leaf, includeProvenance)

      case portfolio: RiskPortfolio =>
        for
          childResults <- ZIO.foreach(portfolio.childIds.toList) { childId =>
            // Look up child node from index, then check cache or simulate
            cache.get(childId).flatMap {
              case Some(cached) => ZIO.succeed(cached)
              case None         => 
                // Look up child node from tree index
                ZIO.fromOption(tree.index.nodes.get(childId))
                  .orElseFail(ValidationFailed(List(ValidationError(
                    field = s"riskPortfolio.${portfolio.id}.childIds",
                    code = ValidationErrorCode.CONSTRAINT_VIOLATION,
                    message = s"Child node not found in tree index: $childId"
                  ))))
                  .flatMap(childNode => simulateNode(tree, cache, childNode, includeProvenance))
            }
          }
          combined <- ZIO.attempt {
            if childResults.isEmpty then
              throw ValidationFailed(List(ValidationError(
                field = s"riskPortfolio.${portfolio.id}.childIds",
                code = ValidationErrorCode.EMPTY_COLLECTION,
                message = s"RiskPortfolio '${portfolio.id}' has no children"
              )))
            childResults.reduce[RiskResult]((a, b) => RiskResult.combine(a, b))
              .withId(updatedName = portfolio.id)
          }
          _ <- cache.put(NodeId(portfolio.id), combined)
        yield combined
    }

  private def simulateLeaf(cache: RiskResultCache, leaf: RiskLeaf, includeProvenance: Boolean): Task[RiskResult] =
    for
      // Always capture provenance (filter at service layer)
      // Rationale: Maintain chain of truth - provenance always in cache
      (sampler, provenance)  <- Simulator.createSamplerFromLeaf(leaf, seed3, seed4)
      trials <- Simulator.performTrials(sampler, nTrials, parallelism)
      result = RiskResult(leaf.id, trials, List(provenance))
      _ <- cache.put(NodeId(leaf.id), result)
    yield result
}

object RiskResultResolverLive {

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
    * Create ZLayer for RiskResultResolver with telemetry.
    * Uses TreeCacheManager for per-tree cache access.
    */
  val layer: ZLayer[TreeCacheManager & SimulationConfig & Tracing & Meter, Throwable, RiskResultResolver] =
    ZLayer.fromZIO {
      for
        cacheManager <- ZIO.service[TreeCacheManager]
        config       <- ZIO.service[SimulationConfig]
        tracing      <- ZIO.service[Tracing]
        meter        <- ZIO.service[Meter]
        
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
      yield RiskResultResolverLive(cacheManager, config, tracing, simDuration, trials)
    }
}

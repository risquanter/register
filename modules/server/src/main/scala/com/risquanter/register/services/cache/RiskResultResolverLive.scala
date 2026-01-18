package com.risquanter.register.services.cache

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Histogram, Counter}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.opentelemetry.api.trace.SpanKind
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.domain.data.{RiskResult, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}
import com.risquanter.register.domain.data.iron.{PositiveInt, SafeId}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.services.helper.Simulator
import io.github.iltotore.iron.refineUnsafe
import zio.prelude.Identity

/**
  * Live implementation of RiskResultResolver (ADR-015).
  *
  * Dependencies:
  * - RiskResultCache: Pure storage for cached results
  * - TreeIndex: Node lookup and parent structure
  * - SimulationConfig: Simulation parameters (nTrials, parallelism)
  * - Tracing: OpenTelemetry tracing for observability
  * - Meter: Metrics instrumentation
  *
  * Simulation parameters are read from config at construction time.
  * Seeds are hardwired for now (TODO: add to config when reproducibility API is defined).
  *
  * Telemetry (ADR-002):
  * - Spans: ensureCached with cache_hit attribute, simulateSubtree on miss
  * - Metrics: simulation duration histogram, trials counter
  */
final case class RiskResultResolverLive(
    cache: RiskResultCache,
    treeIndex: TreeIndex,
    config: SimulationConfig,
    tracing: Tracing,
    simulationDuration: Histogram[Double],
    trialsCounter: Counter[Long]
) extends RiskResultResolver {

  // Read from config at construction time
  private val nTrials: PositiveInt = config.defaultNTrials.refineUnsafe
  private val parallelism: PositiveInt = config.defaultParallelism.refineUnsafe
  // TODO: Add seeds to SimulationConfig when reproducibility API is defined
  private val seed3: Long = 0L
  private val seed4: Long = 0L

  override def ensureCached(nodeId: NodeId): Task[RiskResult] =
    tracing.span("ensureCached", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_id", nodeId.value.toString)
        resultOpt <- cache.get(nodeId)
        result <- resultOpt match {
          case Some(cached) =>
            tracing.setAttribute("cache_hit", true) *> ZIO.succeed(cached)
          case None =>
            tracing.setAttribute("cache_hit", false) *> simulateSubtree(nodeId)
        }
      } yield result
    }

  override def ensureCachedAll(nodeIds: Set[NodeId]): Task[Map[NodeId, RiskResult]] =
    ZIO.foreach(nodeIds.toList)(id => ensureCached(id).map(id -> _)).map(_.toMap)

  /**
    * Simulate subtree rooted at nodeId, caching all results.
    * Wraps simulation in span with timing metrics.
    */
  private def simulateSubtree(nodeId: NodeId): Task[RiskResult] =
    tracing.span("simulateSubtree", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_id", nodeId.value.toString)
        _ <- tracing.setAttribute("n_trials", nTrials.toLong)
        _ <- tracing.setAttribute("parallelism", parallelism.toLong)
        _ <- tracing.addEvent("simulation_started")
        
        startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
        
        node <- ZIO.fromOption(treeIndex.nodes.get(nodeId))
          .orElseFail(ValidationFailed(List(ValidationError(
            field = "nodeId",
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = s"Node not found in tree index: $nodeId"
          ))))
        
        result <- simulateNode(node)
        
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

  private def simulateNode(node: RiskNode): Task[RiskResult] =
    node match {
      case leaf: RiskLeaf =>
        simulateLeaf(leaf)

      case portfolio: RiskPortfolio =>
        for
          childResults <- ZIO.foreach(portfolio.children.toList) { child =>
            cache.get(child.id).flatMap {
              case Some(cached) => ZIO.succeed(cached)
              case None         => simulateNode(child)
            }
          }
          combined <- ZIO.attempt {
            if childResults.isEmpty then
              throw ValidationFailed(List(ValidationError(
                field = s"riskPortfolio.${portfolio.id}.children",
                code = ValidationErrorCode.EMPTY_COLLECTION,
                message = s"RiskPortfolio '${portfolio.id}' has no children"
              )))
            childResults.reduce[RiskResult]((a, b) => Identity[RiskResult].combine(a, b))
              .copy(name = portfolio.id)
          }
          _ <- cache.put(portfolio.id, combined)
        yield combined
    }

  private def simulateLeaf(leaf: RiskLeaf): Task[RiskResult] =
    for
      samplerAndProv <- Simulator.createSamplerFromLeaf(leaf, includeProvenance = false, seed3, seed4)
      (sampler, _) = samplerAndProv
      trials <- Simulator.performTrials(sampler, nTrials, parallelism)
      result = RiskResult(leaf.id, trials, nTrials)
      _ <- cache.put(leaf.id, result)
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
    */
  val layer: ZLayer[RiskResultCache & TreeIndex & SimulationConfig & Tracing & Meter, Throwable, RiskResultResolver] =
    ZLayer.fromZIO {
      for
        cache     <- ZIO.service[RiskResultCache]
        treeIndex <- ZIO.service[TreeIndex]
        config    <- ZIO.service[SimulationConfig]
        tracing   <- ZIO.service[Tracing]
        meter     <- ZIO.service[Meter]
        
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
      yield RiskResultResolverLive(cache, treeIndex, config, tracing, simDuration, trials)
    }
}

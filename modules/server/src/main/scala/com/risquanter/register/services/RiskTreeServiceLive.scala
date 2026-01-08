package com.risquanter.register.services

import zio.*
import zio.prelude.Validation
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Counter, Histogram}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.opentelemetry.api.trace.SpanKind
import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.domain.data.{RiskTree, RiskTreeWithLEC, RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil, PositiveInt, Probability, DistributionType, NonNegativeLong}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.configs.SimulationConfig

/**
 * Live implementation of RiskTreeService with telemetry instrumentation.
 * 
 * Metric instruments (Counter, Histogram) are created once during layer construction
 * and cached for the lifetime of the service, avoiding repeated instrument creation.
 */
class RiskTreeServiceLive private (
  repo: RiskTreeRepository,
  executionService: SimulationExecutionService,
  config: SimulationConfig,
  tracing: Tracing,
  // Pre-created metric instruments (cached at construction time)
  operationsCounter: Counter[Long],
  simulationDuration: Histogram[Double],
  trialsCounter: Counter[Long]
) extends RiskTreeService {
  
  // Helper to record operation with attributes
  private def recordOperation(operation: String, success: Boolean): UIO[Unit] =
    operationsCounter.add(1, Attributes(
      Attribute.string("operation", operation),
      Attribute.boolean("success", success)
    ))
  
  // Helper to record simulation metrics
  private def recordSimulationMetrics(treeName: String, nTrials: Int, durationMs: Long): UIO[Unit] = {
    val attrs = Attributes(Attribute.string("tree_name", treeName))
    simulationDuration.record(durationMs.toDouble, attrs) *>
      trialsCounter.add(nTrials.toLong, attrs)
  }
  
  // Config CRUD - only persist, no execution
  override def create(req: RiskTreeDefinitionRequest): Task[RiskTree] = {
    val operation = for {
      // Use DTO toDomain method for comprehensive validation
      validated <- ZIO.fromEither(
        RiskTreeDefinitionRequest.toDomain(req)
          .toEither
          .left.map(errors => ValidationFailed(errors.toList))
      )
      (safeName, nTrials, rootNode) = validated
      
      // Create RiskTree entity (id will be assigned by repo)
      riskTree = RiskTree(
        id = 0L.refineUnsafe, // repo will assign
        name = safeName,
        nTrials = nTrials,
        root = rootNode
      )
      
      // Persist
      persisted <- repo.create(riskTree)
    } yield persisted
    
    // Record metrics based on success/failure
    operation.tapBoth(
      _ => recordOperation("create", success = false),
      _ => recordOperation("create", success = true)
    )
  }
  
  override def update(id: Long, req: RiskTreeDefinitionRequest): Task[RiskTree] = {
    val operation = for {
      // Use DTO toDomain method for comprehensive validation
      validated <- ZIO.fromEither(
        RiskTreeDefinitionRequest.toDomain(req)
          .toEither
          .left.map(errors => ValidationFailed(errors.toList))
      )
      (safeName, nTrials, rootNode) = validated
      
      updated <- repo.update(id, tree => tree.copy(
        name = safeName,
        nTrials = nTrials,
        root = rootNode
      ))
    } yield updated
    
    operation.tapBoth(
      _ => recordOperation("update", success = false),
      _ => recordOperation("update", success = true)
    )
  }
  
  override def delete(id: Long): Task[RiskTree] =
    repo.delete(id).tapBoth(
      _ => recordOperation("delete", success = false),
      _ => recordOperation("delete", success = true)
    )
  
  override def getAll: Task[List[RiskTree]] =
    repo.getAll.tapBoth(
      _ => recordOperation("getAll", success = false),
      _ => recordOperation("getAll", success = true)
    )
  
  override def getById(id: Long): Task[Option[RiskTree]] =
    repo.getById(id).tapBoth(
      _ => recordOperation("getById", success = false),
      _ => recordOperation("getById", success = true)
    )
  
  // LEC Computation - load config and execute with tracing and metrics
  override def computeLEC(
    id: Long,
    nTrialsOverride: Option[Int],
    parallelism: Int,
    depth: Int = 0,
    includeProvenance: Boolean = false
  ): Task[RiskTreeWithLEC] = {
    // Wrap entire computation in a span with metrics
    val operation = tracing.span("computeLEC", SpanKind.INTERNAL) {
      for {
        // Set span attributes for observability
        _ <- tracing.setAttribute("risk_tree.id", id)
        _ <- tracing.setAttribute("risk_tree.depth", depth.toLong)
        _ <- tracing.setAttribute("risk_tree.parallelism", parallelism.toLong)
        _ <- tracing.setAttribute("risk_tree.include_provenance", includeProvenance)
        _ <- nTrialsOverride.fold(ZIO.unit)(n => tracing.setAttribute("risk_tree.n_trials_override", n.toLong))
        
        result <- computeLECInternal(id, nTrialsOverride, parallelism, depth, includeProvenance)
      } yield result
    }
    
    // Record operation metric (traces already have timing via spans)
    operation.tapBoth(
      _ => recordOperation("computeLEC", success = false),
      _ => recordOperation("computeLEC", success = true)
    )
  }
  
  // Internal implementation without tracing (for cleaner separation)
  private def computeLECInternal(
    id: Long,
    nTrialsOverride: Option[Int],
    parallelism: Int,
    depth: Int,
    includeProvenance: Boolean
  ): Task[RiskTreeWithLEC] = {
    // Validate parameters using Iron
    val validated = for {
      validDepth <- ValidationUtil.refineNonNegativeInt(depth, "depth")
      validParallelism <- ValidationUtil.refinePositiveInt(parallelism, "parallelism")
      validTrials <- nTrialsOverride match {
        case Some(trials) => ValidationUtil.refinePositiveInt(trials, "nTrials").map(Some(_))
        case None => Right(None)
      }
    } yield (validDepth, validParallelism, validTrials)
    
    validated match {
      case Left(errors) => ZIO.fail(ValidationFailed(errors))
      case Right((validDepth, validParallelism, validTrials)) =>
        // Use config for defaults and limits
        val clampedDepth = Math.min(validDepth, config.maxTreeDepth)
        val effectiveParallelism = if (validParallelism <= 0) config.defaultParallelism else validParallelism
        
        for {
          // Load config
          treeOpt <- repo.getById(id)
          tree <- ZIO.fromOption(treeOpt).orElseFail(
            ValidationFailed(List(ValidationError(
              field = "id",
              code = com.risquanter.register.domain.errors.ValidationErrorCode.REQUIRED_FIELD,
              message = s"RiskTree with id=$id not found"
            )))
          )
          
          // Add tree name to span for better observability
          _ <- tracing.setAttribute("risk_tree.name", tree.name.toString)
          _ <- tracing.setAttribute("risk_tree.effective_trials", tree.nTrials.toLong)
          
          // Determine trials (use override or tree config or default)
          nTrials = validTrials.map(t => t: Int).getOrElse(tree.nTrials.toInt)
          
          // Execute simulation with nested span and record metrics
          startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          resultAndProv <- tracing.span("runTreeSimulation", SpanKind.INTERNAL) {
            for {
              _ <- tracing.setAttribute("simulation.id", s"tree-${tree.id}")
              _ <- tracing.setAttribute("simulation.n_trials", nTrials.toLong)
              _ <- tracing.setAttribute("simulation.parallelism", effectiveParallelism.toLong)
              _ <- tracing.addEvent("simulation_started")
              
              result <- executionService.runTreeSimulation(
                simulationId = s"tree-${tree.id}",
                root = tree.root,
                nTrials = nTrials,
                parallelism = effectiveParallelism,
                includeProvenance = includeProvenance
              )
              
              _ <- tracing.addEvent("simulation_completed")
            } yield result
          }
          endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
          
          // Record simulation metrics
          _ <- recordSimulationMetrics(tree.name.toString, nTrials, endTime - startTime)
          
          (result, provenance) = resultAndProv
          
          // Set tree ID in provenance if present
          finalProvenance = provenance.map(_.copy(treeId = tree.id))
          
          // Convert result to LEC data with depth
          lec <- convertResultToLEC(tree, result, clampedDepth, finalProvenance)
        } yield lec
    }
  }
  
  // Helper: Convert TreeResult to RiskTreeWithLEC
  private def convertResultToLEC(
    tree: RiskTree,
    result: com.risquanter.register.domain.data.RiskTreeResult,
    depth: Int,
    provenance: Option[com.risquanter.register.domain.data.TreeProvenance] = None
  ): Task[RiskTreeWithLEC] = {
    import com.risquanter.register.simulation.{LECGenerator, VegaLiteBuilder}
    import com.risquanter.register.domain.data.{LECCurveData, LECPoint}
    
    // Build hierarchical LEC node structure
    def buildLECNode(treeResult: com.risquanter.register.domain.data.RiskTreeResult, currentDepth: Int): LECCurveData = {
      val riskResult = treeResult.result
      
      // Generate curve points
      val curvePoints = LECGenerator.generateCurvePoints(riskResult, nEntries = 100)
      val lecPoints = curvePoints.map { case (loss, prob) => LECPoint(loss, prob) }
      
      // Calculate quantiles
      val quantiles = LECGenerator.calculateQuantiles(riskResult)
      
      // Recursively build children if depth allows
      val children: Option[Vector[LECCurveData]] = treeResult match {
        case com.risquanter.register.domain.data.RiskTreeResult.Branch(_, _, childResults) if currentDepth > 0 =>
          Some(childResults.map(child => buildLECNode(child, currentDepth - 1)))
        case _ => None
      }
      
      LECCurveData(
        id = treeResult.id,
        name = riskResult.name,
        curve = lecPoints,
        quantiles = quantiles,
        children = children
      )
    }
    
    // Build root LEC node
    val lecNode = buildLECNode(result, depth)
    
    // Generate Vega-Lite spec for all visible nodes
    val vegaLiteSpec = VegaLiteBuilder.generateSpec(lecNode)
    
    // Aggregated quantiles from root node
    val rootQuantiles = lecNode.quantiles
    
    ZIO.succeed(RiskTreeWithLEC(
      riskTree = tree,
      quantiles = rootQuantiles,
      vegaLiteSpec = Some(vegaLiteSpec),
      lecCurveData = Some(lecNode),
      depth = depth,
      provenance = provenance
    ))
  }
}

object RiskTreeServiceLive {
  
  /** Metric names and descriptions - centralized for consistency */
  private object MetricNames {
    val operationsCounter = "risk_tree.operations"
    val operationsUnit = "1"
    val operationsDesc = "Number of risk tree operations"
    
    val simulationDuration = "risk_tree.simulation.duration_ms"
    val simulationDurationUnit = "ms"
    val simulationDurationDesc = "Duration of LEC simulation in milliseconds"
    
    val trialsCounter = "risk_tree.simulation.trials"
    val trialsUnit = "1"
    val trialsDesc = "Total number of simulation trials executed"
  }
  
  val layer: ZLayer[RiskTreeRepository & SimulationExecutionService & SimulationConfig & Tracing & Meter, Throwable, RiskTreeService] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      execService <- ZIO.service[SimulationExecutionService]
      config <- ZIO.service[SimulationConfig]
      tracing <- ZIO.service[Tracing]
      meter <- ZIO.service[Meter]
      
      // Create metric instruments once at layer construction time
      opsCounter <- meter.counter(
        MetricNames.operationsCounter,
        Some(MetricNames.operationsUnit),
        Some(MetricNames.operationsDesc)
      )
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
    } yield new RiskTreeServiceLive(repo, execService, config, tracing, opsCounter, simDuration, trials)
  }
}

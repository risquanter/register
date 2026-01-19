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
import com.risquanter.register.domain.data.{RiskTree, TreeProvenance, RiskTreeWithLEC, RiskNode, RiskLeaf, RiskPortfolio, RiskTreeResult, LECCurveResponse, LECPoint}
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil, PositiveInt, NonNegativeInt, Probability, DistributionType, NonNegativeLong}
import com.risquanter.register.domain.tree.{NodeId, TreeIndex}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode, RepositoryFailure, SimulationFailure, SimulationError}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.simulation.{LECGenerator, VegaLiteBuilder}
import com.risquanter.register.services.cache.RiskResultResolver
/**
 * Live implementation of RiskTreeService with telemetry instrumentation.
 * 
 * Metric instruments (Counter, Histogram) are created once during layer construction
 * and cached for the lifetime of the service, avoiding repeated instrument creation.
 * 
 * Concurrency control: SimulationSemaphore limits concurrent simulation executions
 * to prevent resource exhaustion under high load.
 */
class RiskTreeServiceLive private (
  repo: RiskTreeRepository,
  executionService: SimulationExecutionService,
  config: SimulationConfig,
  resolver: RiskResultResolver,
  treeIndex: TreeIndex,
  tracing: Tracing,
  semaphore: SimulationSemaphore,
  // Pre-created metric instruments (cached at construction time)
  operationsCounter: Counter[Long],
  simulationDuration: Histogram[Double],
  trialsCounter: Counter[Long]
) extends RiskTreeService {
  
  import RiskTreeServiceLive.ErrorContext
  
  /** Record operation metric with optional error context
    * 
    * Base attributes (operation, success) are always included.
    * Error attributes (error_type, error_code, error_field) added when error occurs.
    * 
    * @param operation Operation name (create, update, delete, getAll, getById, computeLEC)
    * @param success Whether operation succeeded
    * @param errorInfo Optional error context for failed operations
    */
  private def recordOperation(
    operation: String, 
    success: Boolean,
    errorInfo: Option[ErrorContext] = None
  ): UIO[Unit] = {
    // Build attributes list starting with base attributes
    val baseAttrs = Vector(
      Attribute.string("operation", operation),
      Attribute.boolean("success", success)
    )
    
    // Append error attributes if present
    val allAttrs = errorInfo match {
      case Some(ctx) =>
        val errorAttrs = Vector(
          Attribute.string("error_type", ctx.errorType),
          Attribute.string("error_code", ctx.errorCode)
        ) ++ ctx.errorField.map(field => Vector(Attribute.string("error_field", field))).getOrElse(Vector.empty)
        baseAttrs ++ errorAttrs
      
      case None => baseAttrs
    }
    
    // Convert Vector to individual arguments for Attributes constructor
    val attrs = allAttrs match {
      case v if v.size == 2 => Attributes(v(0), v(1))
      case v if v.size == 4 => Attributes(v(0), v(1), v(2), v(3))
      case v if v.size == 5 => Attributes(v(0), v(1), v(2), v(3), v(4))
      case _ => Attributes(allAttrs.head) // Fallback, should not happen
    }
    
    operationsCounter.add(1, attrs)
  }
  
  /** Extract structured error context from Throwable
    * 
    * Maps domain errors to metric-friendly error context:
    * - ValidationFailed → error_type, error_code (from first validation error), error_field
    * - RepositoryFailure → error_type, REPOSITORY_ERROR code
    * - SimulationFailure → error_type, SIMULATION_ERROR code
    * - Other → error_type (class name), UNKNOWN_ERROR code
    * 
    * @param throwable Error to extract context from
    * @return Structured error context for metrics
    */
  private def extractErrorContext(throwable: Throwable): ErrorContext = throwable match {
    case ValidationFailed(errors) =>
      // Use first error for primary context (could extend to aggregate all)
      val primaryError = errors.headOption.getOrElse(
        ValidationError("unknown", ValidationErrorCode.CONSTRAINT_VIOLATION, "Unknown validation error")
      )
      ErrorContext(
        errorType = "ValidationFailed",
        errorCode = primaryError.code.code,
        errorField = Some(primaryError.field)
      )
    
    case RepositoryFailure(reason) =>
      ErrorContext(
        errorType = "RepositoryFailure", 
        errorCode = "REPOSITORY_ERROR",
        errorField = None
      )
    
    case SimulationFailure(id, _) =>
      ErrorContext(
        errorType = "SimulationFailure",
        errorCode = "SIMULATION_ERROR",
        errorField = Some("simulation")
      )
    
    case _ =>
      ErrorContext(
        errorType = throwable.getClass.getSimpleName,
        errorCode = "UNKNOWN_ERROR",
        errorField = None
      )
  }
  
  /** Log unexpected errors (ADR-002 Decision 5).
    * 
    * Domain errors (SimulationError hierarchy) are expected - each has its own logging point.
    * Other errors are truly unexpected and logged with full stack trace.
    */
  private def logIfUnexpected(operation: String)(error: Throwable): UIO[Unit] = error match {
    case _: SimulationError => ZIO.unit  // Domain errors: logged at origin or expected
    case _ => ZIO.logErrorCause(s"Unexpected error in $operation", Cause.fail(error))
  }
  
  /** Record simulation performance metrics
    * 
    * Tracks:
    * - Simulation duration (histogram for percentiles)
    * - Number of trials executed (counter for aggregation)
    * 
    * @param treeName Name of risk tree being simulated
    * @param nTrials Number of Monte Carlo trials
    * @param durationMs Elapsed time in milliseconds
    */
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
    
    // Record metrics and log unexpected errors (ADR-002 Decision 5)
    operation.tapBoth(
      error => logIfUnexpected("create")(error) *> recordOperation("create", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("create", success = true)
    )
  }
  
  override def update(id: NonNegativeLong, req: RiskTreeDefinitionRequest): Task[RiskTree] = {
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
      error => logIfUnexpected("update")(error) *> recordOperation("update", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("update", success = true)
    )
  }
  
  override def delete(id: NonNegativeLong): Task[RiskTree] =
    repo.delete(id).tapBoth(
      error => logIfUnexpected("delete")(error) *> recordOperation("delete", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("delete", success = true)
    )
  
  override def getAll: Task[List[RiskTree]] =
    repo.getAll.tapBoth(
      error => logIfUnexpected("getAll")(error) *> recordOperation("getAll", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("getAll", success = true)
    )
  
  override def getById(id: NonNegativeLong): Task[Option[RiskTree]] =
    repo.getById(id).tapBoth(
      error => logIfUnexpected("getById")(error) *> recordOperation("getById", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("getById", success = true)
    )
  
  // ========================================
  // New LEC Query APIs (ADR-015)
  // ========================================
  
  override def getLECCurve(nodeId: NodeId): Task[LECCurveResponse] = {
    val operation = tracing.span("getLECCurve", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_id", nodeId.toString)
        
        // Ensure result is cached (cache-aside pattern via RiskResultResolver)
        result <- resolver.ensureCached(nodeId)
        _ <- tracing.setAttribute("cache_resolved", true)
        
        // Get node from tree index for metadata
        node <- ZIO.fromOption(treeIndex.nodes.get(nodeId))
          .orElseFail(ValidationFailed(List(ValidationError(
            field = "nodeId",
            code = ValidationErrorCode.REQUIRED_FIELD,
            message = s"Node not found: $nodeId"
          ))))
        
        // Generate LEC curve points from cached result
        curvePoints = LECGenerator.generateCurvePoints(result)
        _ <- tracing.setAttribute("curve_points", curvePoints.size.toLong)
        
        // Calculate quantiles
        quantiles = LECGenerator.calculateQuantiles(result)
        
        // Get child IDs if portfolio node
        childIds = node match {
          case portfolio: RiskPortfolio => Some(portfolio.children.map(_.toString).toList)
          case _: RiskLeaf => None
        }
        
        // Convert to response format
        lecPoints = curvePoints.map { case (loss, prob) => LECPoint(loss, prob) }
        response = LECCurveResponse(
          id = nodeId.toString,
          name = result.name.toString,
          curve = lecPoints,
          quantiles = quantiles,
          childIds = childIds
        )
      } yield response
    }
    
    operation.tapBoth(
      error => logIfUnexpected("getLECCurve")(error) *> recordOperation("getLECCurve", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("getLECCurve", success = true)
    )
  }
  
  override def probOfExceedance(nodeId: NodeId, threshold: Long): Task[BigDecimal] = {
    val operation = tracing.span("probOfExceedance", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_id", nodeId.toString)
        _ <- tracing.setAttribute("threshold", threshold)
        
        // Ensure result is cached (cache-aside pattern via RiskResultResolver)
        result <- resolver.ensureCached(nodeId)
        _ <- tracing.setAttribute("cache_resolved", true)
        
        // Compute exceedance probability from cached result
        prob = result.probOfExceedance(threshold)
        _ <- tracing.setAttribute("exceedance_probability", prob.toDouble)
      } yield prob
    }
    
    operation.tapBoth(
      error => logIfUnexpected("probOfExceedance")(error) *> recordOperation("probOfExceedance", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("probOfExceedance", success = true)
    )
  }
  
  override def getLECCurvesMulti(nodeIds: Set[NodeId]): Task[Map[NodeId, Vector[LECPoint]]] = {
    val operation = tracing.span("getLECCurvesMulti", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_count", nodeIds.size.toLong)
        _ <- tracing.setAttribute("node_ids", nodeIds.map(_.toString).mkString(","))
        
        // Batch cache-aside: ensure all results are cached
        results <- resolver.ensureCachedAll(nodeIds)
        _ <- tracing.setAttribute("results_resolved", results.size.toLong)
        
        // Generate curves with shared tick domain (ADR-014 render-time strategy)
        curvesData = LECGenerator.generateCurvePointsMulti(results)
        
        // Convert to API response format
        curves = curvesData.map { case (nodeId, points) =>
          nodeId -> points.map { case (loss, prob) => LECPoint(loss, prob) }
        }
        
        _ <- tracing.setAttribute("curves_generated", curves.size.toLong)
      } yield curves
    }
    
    operation.tapBoth(
      error => logIfUnexpected("getLECCurvesMulti")(error) *> recordOperation("getLECCurvesMulti", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("getLECCurvesMulti", success = true)
    )
  }
  
  // ========================================
  // DEPRECATED: Old computeLEC implementation
  // ========================================
  
  @deprecated("Use getLECCurve(nodeId) instead", "0.2.0")
  override def computeLEC(
    id: NonNegativeLong,
    nTrialsOverride: Option[PositiveInt],
    parallelism: PositiveInt,
    depth: NonNegativeInt,
    includeProvenance: Boolean,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): Task[RiskTreeWithLEC] = {
    val operation = tracing.span("computeLEC", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("risk_tree.id", (id: Long))
        _ <- tracing.setAttribute("risk_tree.depth", (depth: Int).toLong)
        _ <- tracing.setAttribute("risk_tree.parallelism", (parallelism: Int).toLong)
        _ <- tracing.setAttribute("risk_tree.include_provenance", includeProvenance)
        _ <- tracing.setAttribute("risk_tree.seed3", seed3)
        _ <- tracing.setAttribute("risk_tree.seed4", seed4)
        _ <- nTrialsOverride.fold(ZIO.unit)(n => tracing.setAttribute("risk_tree.n_trials_override", (n: Int).toLong))
        
        result <- computeLECInternal(id, nTrialsOverride, parallelism, depth, includeProvenance, seed3, seed4)
      } yield result
    }
    
    operation.tapBoth(
      error => logIfUnexpected("computeLEC")(error) *> recordOperation("computeLEC", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("computeLEC", success = true)
    )
  }
  
  // Internal implementation without tracing (for cleaner separation)
  // Uses Iron-refined types to enforce invariants at the type level (DDD principle:
  // make illegal states unrepresentable). The public API validates and refines raw
  // inputs; internal methods can then trust their parameters.
  private def computeLECInternal(
    id: NonNegativeLong,
    nTrialsOverride: Option[PositiveInt],
    parallelism: PositiveInt,
    depth: NonNegativeInt,
    includeProvenance: Boolean,
    seed3: Long,
    seed4: Long
  ): Task[RiskTreeWithLEC] = {
    for {
      // Enforce hard limits first (reject if exceeded)
      _ <- validateHardLimits(nTrialsOverride, parallelism, depth)
      
      // Load tree from repository
      treeOpt <- repo.getById(id)
      tree <- ZIO.fromOption(treeOpt).orElseFail(
        ValidationFailed(List(ValidationError(
          field = "id",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = s"RiskTree with id=$id not found"
        )))
      )
      
      // Add tree name to span for better observability
      _ <- tracing.setAttribute("risk_tree.name", tree.name.toString)
      _ <- tracing.setAttribute("risk_tree.effective_trials", tree.nTrials.toLong)
      
      // Determine trials (use override or tree config or default)
      // nTrialsOverride is already PositiveInt; tree.nTrials is Long (convert via refineUnsafe since stored values are validated)
      nTrials: PositiveInt = nTrialsOverride.getOrElse(tree.nTrials.toInt.refineUnsafe[Greater[0]])
      
      // Execute simulation with nested span and record metrics
      startTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      resultAndProv <- semaphore.withPermit {
        tracing.span("runTreeSimulation", SpanKind.INTERNAL) {
          for {
            _ <- tracing.setAttribute("simulation.id", s"tree-${tree.id}")
            _ <- tracing.setAttribute("simulation.n_trials", (nTrials: Int).toLong)
            _ <- tracing.setAttribute("simulation.parallelism", (parallelism: Int).toLong)
            _ <- tracing.addEvent("simulation_started")
            
            result <- executionService.runTreeSimulation(
              simulationId = s"tree-${tree.id}",
              root = tree.root,
              nTrials = nTrials,
              parallelism = parallelism,
              includeProvenance = includeProvenance,
              seed3 = seed3,
              seed4 = seed4
            )
            
            _ <- tracing.addEvent("simulation_completed")
          } yield result
        }
      }
      endTime <- Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      
      // Record simulation metrics (convert PositiveInt to Int for metrics)
      _ <- recordSimulationMetrics(tree.name.toString, nTrials: Int, endTime - startTime)
      
      (result, provenance) = resultAndProv
      
      // Set tree ID in provenance if present
      finalProvenance = provenance.map(_.copy(treeId = tree.id))
      
      // Convert result to LEC data with validated depth
      // (validation already rejected values > maxTreeDepth, natural recursion stops at actual tree depth)
      lec <- convertResultToLEC(tree, result, depth, finalProvenance)
    } yield lec
  }
  
  /** Validate request parameters against hard limits.
    * 
    * Rejects requests that exceed configured maximum values to prevent
    * resource exhaustion and protect system stability.
    * 
    * Parameters are Iron-refined types, guaranteeing they are already valid
    * (positive/non-negative). This method only checks against config maximums.
    * 
    * @param nTrialsOpt Optional trials override (if None, uses tree's nTrials)
    * @param parallelism Requested parallelism level (already refined as PositiveInt)
    * @param depth Requested tree depth (already refined as NonNegativeInt)
    * @return Effect that fails with ValidationFailed if limits exceeded
    */
  private def validateHardLimits(
    nTrialsOpt: Option[PositiveInt],
    parallelism: PositiveInt,
    depth: NonNegativeInt
  ): IO[ValidationFailed, Unit] = {
    val errors = List(
      nTrialsOpt.flatMap { nTrials =>
        Option.when((nTrials: Int) > config.maxNTrials)(
          ValidationError(
            "nTrials",
            ValidationErrorCode.INVALID_RANGE,
            s"nTrials ($nTrials) exceeds maximum (${config.maxNTrials})"
          )
        )
      },
      Option.when((parallelism: Int) > config.maxParallelism)(
        ValidationError(
          "parallelism",
          ValidationErrorCode.INVALID_RANGE,
          s"parallelism ($parallelism) exceeds maximum (${config.maxParallelism})"
        )
      ),
      Option.when((depth: Int) > config.maxTreeDepth)(
        ValidationError(
          "depth",
          ValidationErrorCode.INVALID_RANGE,
          s"depth ($depth) exceeds maximum (${config.maxTreeDepth})"
        )
      )
    ).flatten
    
    ZIO.when(errors.nonEmpty)(ZIO.fail(ValidationFailed(errors))).unit
  }
  
  // Helper: Convert TreeResult to RiskTreeWithLEC
  private def convertResultToLEC(
    tree: RiskTree,
    result: RiskTreeResult,
    depth: NonNegativeInt,
    provenance: Option[TreeProvenance] = None
  ): Task[RiskTreeWithLEC] = {    
    
    // Build flat LEC curve for a single node (no recursive children)
    def buildLECCurve(treeResult: RiskTreeResult): LECCurveResponse = {
      val riskResult = treeResult.result
      
      // Generate curve points
      val curvePoints = LECGenerator.generateCurvePoints(riskResult, nEntries = 100)
      val lecPoints = curvePoints.map { case (loss, prob) => LECPoint(loss, prob) }
      
      // Calculate quantiles
      val quantiles = LECGenerator.calculateQuantiles(riskResult)
      
      // Extract child IDs (flat reference, not embedded data)
      val childIds: Option[List[String]] = treeResult match {
        case RiskTreeResult.Branch(_, _, childResults) if childResults.nonEmpty =>
          Some(childResults.map(_.id).toList)
        case _ => None
      }
      
      // LECCurveResponse uses String for wire format
      LECCurveResponse(
        id = treeResult.id,
        name = riskResult.name.value.toString,
        curve = lecPoints,
        quantiles = quantiles,
        childIds = childIds
      )
    }
    
    // Build root LEC curve (flat)
    val lecCurve = buildLECCurve(result)
    
    // Generate Vega-Lite spec for root node only
    // Multi-curve charts will be generated via separate endpoint
    val vegaLiteSpec = VegaLiteBuilder.generateSpec(lecCurve)
    
    // Aggregated quantiles from root node
    val rootQuantiles = lecCurve.quantiles
    
    ZIO.succeed(RiskTreeWithLEC(
      riskTree = tree,
      quantiles = rootQuantiles,
      vegaLiteSpec = Some(vegaLiteSpec),
      lecCurve = Some(lecCurve),
      provenance = provenance
    ))
  }
}

object RiskTreeServiceLive {
  
  /** Structured error context for metrics
    * 
    * Used to enrich metric attributes with error details for better observability.
    * 
    * @param errorType High-level error category (ValidationFailed, RepositoryFailure, etc.)
    * @param errorCode Specific error code (REQUIRED_FIELD, INVALID_RANGE, REPOSITORY_ERROR, etc.)
    * @param errorField Optional field name that caused the error (for validation errors)
    */
  private case class ErrorContext(
    errorType: String,
    errorCode: String,
    errorField: Option[String] = None
  )
  
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
  
  val layer: ZLayer[RiskTreeRepository & SimulationExecutionService & SimulationConfig & RiskResultResolver & TreeIndex & Tracing & SimulationSemaphore & Meter, Throwable, RiskTreeService] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      execService <- ZIO.service[SimulationExecutionService]
      config <- ZIO.service[SimulationConfig]
      resolver <- ZIO.service[RiskResultResolver]
      treeIndex <- ZIO.service[TreeIndex]
      tracing <- ZIO.service[Tracing]
      semaphore <- ZIO.service[SimulationSemaphore]
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
    } yield new RiskTreeServiceLive(repo, execService, config, resolver, treeIndex, tracing, semaphore, opsCounter, simDuration, trials)
  }
}

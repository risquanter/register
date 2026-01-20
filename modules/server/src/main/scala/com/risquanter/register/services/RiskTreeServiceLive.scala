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
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio, LECCurveResponse, LECPoint}
import com.risquanter.register.domain.data.iron.{SafeName, ValidationUtil, Probability, DistributionType, NonNegativeLong}
import com.risquanter.register.domain.tree.{NodeId, TreeIndex}
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode, RepositoryFailure, SimulationFailure, SimulationError}
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.simulation.LECGenerator
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
  config: SimulationConfig,
  resolver: RiskResultResolver,
  tracing: Tracing,
  semaphore: SimulationSemaphore,
  // Pre-created metric instruments (cached at construction time)
  operationsCounter: Counter[Long],
  simulationDuration: Histogram[Double],
  trialsCounter: Counter[Long]
) extends RiskTreeService {
  
  import RiskTreeServiceLive.ErrorContext
  
  /** Find which tree contains a given node.
    * 
    * Scans all trees in repository to find the one with the node in its index.
    * 
    * @param nodeId Node to search for
    * @return RiskTree containing the node, or ValidationFailed if not found
    */
  private def findTreeContainingNode(nodeId: NodeId): Task[RiskTree] =
    for {
      allTrees <- repo.getAll
      tree <- ZIO.fromOption(allTrees.find(t => t.index.nodes.contains(nodeId)))
        .orElseFail(ValidationFailed(List(ValidationError(
          field = "nodeId",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = s"Node ${nodeId.value} not found in any tree"
        ))))
    } yield tree
  
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
      // Use DTO validate method for comprehensive validation including cross-field checks
      validated <- ZIO.fromEither(
        RiskTreeDefinitionRequest.validate(req)
          .left.map(errors => ValidationFailed(errors))
      )
      (safeName, nodes, rootId) = validated
      
      // Create RiskTree entity using flat node format (id will be assigned by repo)
      riskTree = RiskTree.fromNodes(
        id = 0L.refineUnsafe, // repo will assign
        name = safeName,
        nodes = nodes,
        rootId = rootId
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
      // Use DTO validate method for comprehensive validation including cross-field checks
      validated <- ZIO.fromEither(
        RiskTreeDefinitionRequest.validate(req)
          .left.map(errors => ValidationFailed(errors))
      )
      (safeName, nodes, rootId) = validated
      
      updated <- repo.update(id, tree => tree.copy(
        name = safeName,
        nodes = nodes,
        rootId = rootId,
        index = TreeIndex.fromNodeSeq(nodes)
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
  
  override def getLECCurve(nodeId: NodeId, includeProvenance: Boolean = false): Task[LECCurveResponse] = {
    val operation = tracing.span("getLECCurve", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_id", nodeId.value)
        _ <- tracing.setAttribute("include_provenance", includeProvenance)
        
        // Find tree containing this node
        tree <- findTreeContainingNode(nodeId)
        
        // Ensure result is cached (cache-aside pattern via RiskResultResolver)
        result <- resolver.ensureCached(tree, nodeId, includeProvenance)
        _ <- tracing.setAttribute("cache_resolved", true)
        
        // Get node from tree index for metadata
        node <- ZIO.fromOption(tree.index.nodes.get(nodeId))
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
          case portfolio: RiskPortfolio => Some(portfolio.childIds.map(_.value.toString).toList)
          case _: RiskLeaf => None
        }
        
        // Convert to response format
        lecPoints = curvePoints.map { case (loss, prob) => LECPoint(loss, prob) }
        response = LECCurveResponse(
          id = nodeId.value,
          name = node.name,  // Use node.name (display name), not result.name (which is the ID)
          curve = lecPoints,
          quantiles = quantiles,
          childIds = childIds,
          provenances = if (includeProvenance) result.provenances else Nil  // Filter provenance on output
        )
      } yield response
    }
    
    operation.tapBoth(
      error => logIfUnexpected("getLECCurve")(error) *> recordOperation("getLECCurve", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("getLECCurve", success = true)
    )
  }
  
  override def probOfExceedance(nodeId: NodeId, threshold: Long, includeProvenance: Boolean = false): Task[BigDecimal] = {
    val operation = tracing.span("probOfExceedance", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_id", nodeId.value)
        _ <- tracing.setAttribute("threshold", threshold)
        _ <- tracing.setAttribute("include_provenance", includeProvenance)
        
        // Find tree containing this node
        tree <- findTreeContainingNode(nodeId)
        
        // Ensure result is cached (cache-aside pattern via RiskResultResolver)
        result <- resolver.ensureCached(tree, nodeId, includeProvenance)
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
  
  override def getLECCurvesMulti(nodeIds: Set[NodeId], includeProvenance: Boolean = false): Task[Map[NodeId, Vector[LECPoint]]] = {
    val operation = tracing.span("getLECCurvesMulti", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("node_count", nodeIds.size.toLong)
        _ <- tracing.setAttribute("node_ids", nodeIds.map(_.value).mkString(","))
        _ <- tracing.setAttribute("include_provenance", includeProvenance)
        
        // Find tree containing the first node (assuming all nodes from same tree)
        tree <- if (nodeIds.isEmpty) {
          ZIO.fail(ValidationFailed(List(ValidationError(
            field = "nodeIds",
            code = ValidationErrorCode.EMPTY_COLLECTION,
            message = "nodeIds set is empty"
          ))))
        } else {
          findTreeContainingNode(nodeIds.head)
        }
        
        // Batch cache-aside: ensure all results are cached
        results <- resolver.ensureCachedAll(tree, nodeIds, includeProvenance)
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
  
  val layer: ZLayer[RiskTreeRepository & SimulationConfig & RiskResultResolver & Tracing & SimulationSemaphore & Meter, Throwable, RiskTreeService] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      config <- ZIO.service[SimulationConfig]
      resolver <- ZIO.service[RiskResultResolver]
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
    } yield new RiskTreeServiceLive(repo, config, resolver, tracing, semaphore, opsCounter, simDuration, trials)
  }
}

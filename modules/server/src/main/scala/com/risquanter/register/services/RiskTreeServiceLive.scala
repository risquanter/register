package com.risquanter.register.services

import zio.*
import zio.prelude.Validation
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Counter, Histogram}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.opentelemetry.api.trace.SpanKind
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, RiskTreeRequests}
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio, LECCurveResponse, LECPoint, Distribution}
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, ValidationUtil, Probability, DistributionType, TreeId, NodeId}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode, RepositoryFailure, SimulationFailure, AppError}
import com.risquanter.register.domain.errors.ValidationExtensions.*
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.simulation.LECGenerator
import com.risquanter.register.services.cache.RiskResultResolver
import com.risquanter.register.util.IdGenerators
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
  
  /** Fetch tree by id or fail with ValidationFailed. */
  private def getTreeOrFail(treeId: TreeId): Task[RiskTree] =
    repo.getById(treeId).flatMap {
      case Some(tree) => ZIO.succeed(tree)
      case None =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          field = "treeId",
          code = ValidationErrorCode.REQUIRED_FIELD,
          message = s"Tree not found: $treeId"
        ))))
    }
    
  /** Fetch tree and node together or fail with ValidationFailed. */
  private def lookupNodeInTree(treeId: TreeId, nodeId: NodeId): Task[(RiskTree, RiskNode)] =
    for
      tree <- getTreeOrFail(treeId)
      node <- ZIO.fromOption(tree.index.nodes.get(nodeId)).orElseFail(ValidationFailed(List(ValidationError(
        field = "nodeId",
        code = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = s"Node ${nodeId.value} not found in tree ${tree.id}"
      ))))
    yield (tree, node)

  /** Fetch tree and all requested nodes; fail with aggregated validation errors when any node is missing. */
  private def lookupNodesInTree(treeId: TreeId, nodeIds: Set[NodeId]): Task[(RiskTree, Map[NodeId, RiskNode])] =
    for
      tree <- getTreeOrFail(treeId)
      missing = nodeIds.filterNot(tree.index.nodes.contains)
      _ <- if missing.isEmpty then ZIO.unit else ZIO.fail(ValidationFailed(missing.toList.map(id => ValidationError(
        field = "nodeIds",
        code = ValidationErrorCode.CONSTRAINT_VIOLATION,
        message = s"Node ${id.value} not found in tree ${tree.id}"
      ))))
      nodes = nodeIds.flatMap(id => tree.index.nodes.get(id).map(id -> _)).toMap
    yield (tree, nodes)

  private def ensureUniqueTree(treeId: TreeId, treeName: SafeName.SafeName, excludeId: Option[TreeId] = None): Task[Unit] =
    collectAllTrees.flatMap { trees =>
      val candidates = trees.filterNot(t => excludeId.contains(t.id))

      val errors = List(
        Option.when(candidates.exists(_.id == treeId))(
          ValidationError(
            field = "id",
            code = ValidationErrorCode.DUPLICATE_VALUE,
            message = s"Tree ID '${treeId.value}' already exists"
          )
        ),
        Option.when(candidates.exists(_.name == treeName))(
          ValidationError(
            field = "name",
            code = ValidationErrorCode.DUPLICATE_VALUE,
            message = s"Tree name '${treeName.value}' already exists"
          )
        )
      ).flatten

      if errors.nonEmpty then ZIO.fail(ValidationFailed(errors)) else ZIO.unit
    }

  private def collectAllTrees: Task[List[RiskTree]] =
    repo.getAll.flatMap { results =>
      val (errs, trees) = results.foldLeft((List.empty[RepositoryFailure], List.empty[RiskTree])) {
        case ((es, ts), Left(err))  => (err :: es, ts)
        case ((es, ts), Right(t))   => (es, t :: ts)
      }
      if errs.nonEmpty then
        ZIO.fail(RepositoryFailure(errs.reverse.map(_.reason).mkString("; ")))
      else
        ZIO.succeed(trees.reverse)
    }
  
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
    * Domain errors (AppError hierarchy) are expected - each has its own logging point.
    * Other errors are truly unexpected and logged with full stack trace.
    */
  private def logIfUnexpected(operation: String)(error: Throwable): UIO[Unit] = error match {
    case _: AppError => ZIO.unit  // Domain errors: logged at origin or expected
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

  /** Allocate a fixed pool of SafeIds for request resolution. */
  private def allocateIds(count: Int): Task[List[SafeId.SafeId]] =
    IdGenerators.batch(count)

  /** Deterministic generator for RiskTreeRequests based on a pre-allocated pool. */
  private def idGeneratorFrom(ids: List[SafeId.SafeId]): RiskTreeRequests.IdGenerator = {
    val iter = ids.iterator
    () => if iter.hasNext then iter.next() else throw new IllegalStateException("ID pool exhausted while resolving request")
  }

  /** Build domain nodes and rootId from a resolved V2 request. */
  private def buildNodes(
    nodesByName: Map[SafeName.SafeName, RiskTreeRequests.ResolvedNode],
    leafDistributions: Map[SafeName.SafeName, Distribution],
    rootName: SafeName.SafeName
  ): Task[(Seq[RiskNode], NodeId)] = ZIO.attempt {
    val nameToId: Map[SafeName.SafeName, NodeId] = nodesByName.view.mapValues(n => NodeId(n.id)).toMap
    val childrenByParent: Map[Option[SafeName.SafeName], List[SafeName.SafeName]] =
      nodesByName.values.groupBy(_.parentName).view.mapValues(_.toList.map(_.name)).toMap

    def parentIdFor(node: RiskTreeRequests.ResolvedNode): Option[NodeId] =
      node.parentName.flatMap(nameToId.get)

    val domainNodes: Seq[RiskNode] = nodesByName.values.toSeq.map { node =>
      node.kind match {
        case RiskTreeRequests.NodeKind.Leaf =>
          val dist = leafDistributions(node.name)
          RiskLeaf.unsafeApply(
            id = node.id.value.toString,
            name = node.name.value.toString,
            distributionType = dist.distributionType.toString,
            probability = dist.probability,
            percentiles = dist.percentiles,
            quantiles = dist.quantiles,
            minLoss = dist.minLoss.map(_.toLong),
            maxLoss = dist.maxLoss.map(_.toLong),
            parentId = parentIdFor(node)
          )

        case RiskTreeRequests.NodeKind.Portfolio =>
          val childIds: Array[NodeId] = childrenByParent.get(Some(node.name)).toList.flatten.flatMap(nameToId.get).toArray
          RiskPortfolio.unsafeApply(
            id = node.id.value.toString,
            name = node.name.value.toString,
            childIds = childIds,
            parentId = parentIdFor(node)
          )
      }
    }

    val rootId = nameToId.getOrElse(rootName, throw new IllegalArgumentException(s"Root name '${rootName.value}' not found in resolved nodes"))
    (domainNodes, rootId)
  }
  
  // Config CRUD - only persist, no execution
  override def create(req: RiskTreeDefinitionRequest): Task[RiskTree] = {
    val operation = for {
      treeId <- IdGenerators.nextTreeId
      ids <- allocateIds(req.portfolios.size + req.leaves.size)
      resolved <- RiskTreeDefinitionRequest.resolve(req, idGeneratorFrom(ids)).toZIOValidation
      _ <- ensureUniqueTree(treeId, resolved.treeName)
      (nodes, rootId) <- buildNodes(resolved.nodes, resolved.leafDistributions, resolved.rootName)
      riskTree <- RiskTree.fromNodes(
        id = treeId,
        name = resolved.treeName,
        nodes = nodes,
        rootId = rootId
      ).toZIOValidation
      persisted <- repo.create(riskTree)
    } yield persisted

    operation.tapBoth(
      error => logIfUnexpected("create")(error) *> recordOperation("create", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("create", success = true)
    )
  }

  override def update(id: TreeId, req: RiskTreeUpdateRequest): Task[RiskTree] = {
    val operation = for {
      ids <- allocateIds(req.newPortfolios.size + req.newLeaves.size)
      resolved <- RiskTreeUpdateRequest.resolve(req, idGeneratorFrom(ids)).toZIOValidation
      _ <- ensureUniqueTree(id, resolved.treeName, excludeId = Some(id))
      allNodes = resolved.existing ++ resolved.added
      allLeafDistributions = resolved.existingLeafDistributions ++ resolved.addedLeafDistributions
      (nodes, rootId) <- buildNodes(allNodes, allLeafDistributions, resolved.rootName)
      riskTree <- RiskTree.fromNodes(
        id = id,
        name = resolved.treeName,
        nodes = nodes,
        rootId = rootId
      ).toZIOValidation
      updated <- repo.update(id, _ => riskTree)
    } yield updated
    
    operation.tapBoth(
      error => logIfUnexpected("update")(error) *> recordOperation("update", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("update", success = true)
    )
  }
  
  override def delete(id: TreeId): Task[RiskTree] =
    repo.delete(id).tapBoth(
      error => logIfUnexpected("delete")(error) *> recordOperation("delete", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("delete", success = true)
    )
  
  override def getAll: Task[List[RiskTree]] =
    collectAllTrees.tapBoth(
      error => logIfUnexpected("getAll")(error) *> recordOperation("getAll", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("getAll", success = true)
    )
  
  override def getById(id: TreeId): Task[Option[RiskTree]] =
    repo.getById(id).tapBoth(
      error => logIfUnexpected("getById")(error) *> recordOperation("getById", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("getById", success = true)
    )
  
  // ========================================
  // New LEC Query APIs (ADR-015)
  // ========================================
  
  override def getLECCurve(treeId: TreeId, nodeId: NodeId, includeProvenance: Boolean = false): Task[LECCurveResponse] = {
    val operation = tracing.span("getLECCurve", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("tree_id", treeId.value)
        _ <- tracing.setAttribute("node_id", nodeId.value)
        _ <- tracing.setAttribute("include_provenance", includeProvenance)
        
        // Fetch requested tree and node
        (tree, node) <- lookupNodeInTree(treeId, nodeId)
        
        // Ensure result is cached (cache-aside pattern via RiskResultResolver)
        result <- resolver.ensureCached(tree, nodeId, includeProvenance)
        _ <- tracing.setAttribute("cache_resolved", true)
        
        // Generate LEC curve points from cached result
        curvePoints = LECGenerator.generateCurvePoints(result)
        _ <- tracing.setAttribute("curve_points", curvePoints.size.toLong)
        
        // Calculate quantiles
        quantiles = LECGenerator.calculateQuantiles(result)
        
        // Get child IDs if portfolio node
        childIds = node match {
          case portfolio: RiskPortfolio => Some(portfolio.childIds.map(_.value).toList)
          case _: RiskLeaf => None
        }
        
        // Convert to response format
        lecPoints = curvePoints.map { case (loss, prob) => LECPoint(loss, prob) }
        response = LECCurveResponse(
          id = nodeId.value,
          name = node.name,  // Use node.name (display name), not result.nodeId (which is the node ID)
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
  
  override def probOfExceedance(treeId: TreeId, nodeId: NodeId, threshold: Long, includeProvenance: Boolean = false): Task[BigDecimal] = {
    val operation = tracing.span("probOfExceedance", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("tree_id", treeId.value)
        _ <- tracing.setAttribute("node_id", nodeId.value)
        _ <- tracing.setAttribute("threshold", threshold)
        _ <- tracing.setAttribute("include_provenance", includeProvenance)
        
        // Fetch requested tree and ensure node exists within it
        (tree, _) <- lookupNodeInTree(treeId, nodeId)
        
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
  
  override def getLECCurvesMulti(treeId: TreeId, nodeIds: Set[NodeId], includeProvenance: Boolean = false): Task[Map[NodeId, Vector[LECPoint]]] = {
    val operation = tracing.span("getLECCurvesMulti", SpanKind.INTERNAL) {
      for {
        _ <- tracing.setAttribute("tree_id", treeId.value)
        _ <- tracing.setAttribute("node_count", nodeIds.size.toLong)
        _ <- tracing.setAttribute("node_ids", nodeIds.map(_.value).mkString(","))
        _ <- tracing.setAttribute("include_provenance", includeProvenance)
        
        // Validate inputs and fetch requested tree + nodes
        treeWithNodes <- if (nodeIds.isEmpty) {
          ZIO.fail(ValidationFailed(List(ValidationError(
            field = "nodeIds",
            code = ValidationErrorCode.EMPTY_COLLECTION,
            message = "nodeIds set is empty"
          ))))
        } else {
          lookupNodesInTree(treeId, nodeIds)
        }
        (tree, _) = treeWithNodes
        
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

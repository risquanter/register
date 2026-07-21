package com.risquanter.register.services

import zio.*
import zio.prelude.Validation
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Counter}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.opentelemetry.api.trace.SpanKind
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, RiskTreeRequests}
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio, LECPoint, LECNodeCurve, Distribution}
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, ValidationUtil, OccurrenceProbability, DistributionType, TreeId, NodeId, WorkspaceId, SeedVarId, SeedEntityId, BranchRef}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.errors.{ValidationFailed, ValidationError, ValidationErrorCode, RepositoryFailure, SimulationFailure, AppError}
import com.risquanter.register.domain.errors.ValidationExtensions.*
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.configs.SimulationConfig
import com.risquanter.register.simulation.LECGenerator
import com.risquanter.register.services.cache.RiskResultResolver
import com.risquanter.register.services.helper.SeedVarIdAssigner
import com.risquanter.register.services.pipeline.InvalidationHandler
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
  invalidationHandler: InvalidationHandler,
  tracing: Tracing,
  semaphore: SimulationSemaphore,
  // Pre-created metric instruments (cached at construction time)
  operationsCounter: Counter[Long]
) extends RiskTreeService {
  
  import RiskTreeServiceLive.ErrorContext
  
  /** Fetch tree by id or fail with ValidationFailed. */
  private def getTreeOrFail(wsId: WorkspaceId, treeId: TreeId, branch: Option[BranchRef] = None): Task[RiskTree] =
    repo.getById(wsId, treeId, branch).flatMap {
      case Some(tree) => ZIO.succeed(tree)
      case None =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          field = "treeId",
          code = ValidationErrorCode.NOT_FOUND,
          message = s"Tree not found: $treeId"
        ))))
    }

  /** Fetch tree and node together or fail with ValidationFailed. */
  private def lookupNodeInTree(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, branch: Option[BranchRef] = None): Task[(RiskTree, RiskNode)] =
    for
      tree <- getTreeOrFail(wsId, treeId, branch)
      node <- ZIO.fromOption(tree.index.nodes.get(nodeId)).orElseFail(ValidationFailed(List(ValidationError(
        field = "nodeId",
        code = ValidationErrorCode.NOT_FOUND,
        message = s"Node ${nodeId.value} not found in tree ${tree.id}"
      ))))
    yield (tree, node)

  /** Fetch tree and all requested nodes; fail with aggregated validation errors when any node is missing. */
  private def lookupNodesInTree(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], branch: Option[BranchRef] = None): Task[(RiskTree, Map[NodeId, RiskNode])] =
    for
      tree <- getTreeOrFail(wsId, treeId, branch)
      missing = nodeIds.filterNot(tree.index.nodes.contains)
      _ <- if missing.isEmpty then ZIO.unit else ZIO.fail(ValidationFailed(missing.toList.map(id => ValidationError(
        field = "nodeIds",
        code = ValidationErrorCode.NOT_FOUND,
        message = s"Node ${id.value} not found in tree ${tree.id}"
      ))))
      nodes = nodeIds.flatMap(id => tree.index.nodes.get(id).map(id -> _)).toMap
    yield (tree, nodes)

  private def ensureUniqueTree(wsId: WorkspaceId, treeId: TreeId, treeName: SafeName.SafeName, excludeId: Option[TreeId] = None): Task[Unit] =
    collectAllTrees(wsId).flatMap { trees =>
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

  private def collectAllTrees(wsId: WorkspaceId): Task[List[RiskTree]] =
    repo.getAllForWorkspace(wsId).flatMap { results =>
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
    val attrs = errorInfo match {
      case None =>
        Attributes(
          Attribute.string("operation", operation),
          Attribute.boolean("success", success)
        )
      case Some(ErrorContext(errorType, errorCode, None)) =>
        Attributes(
          Attribute.string("operation", operation),
          Attribute.boolean("success", success),
          Attribute.string("error_type", errorType),
          Attribute.string("error_code", errorCode)
        )
      case Some(ErrorContext(errorType, errorCode, Some(errorField))) =>
        Attributes(
          Attribute.string("operation", operation),
          Attribute.boolean("success", success),
          Attribute.string("error_type", errorType),
          Attribute.string("error_code", errorCode),
          Attribute.string("error_field", errorField)
        )
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
        ValidationError("unknown", ValidationErrorCode.INTERNAL_ERROR, "Unknown validation error")
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

  /** Wrap a traced LEC query operation with span + metrics + error logging.
    *
    * Eliminates the repeated ceremony of:
    *  1. `tracing.span(name, SpanKind.INTERNAL) { ... }`
    *  2. `.tapBoth(logIfUnexpected ∘ recordOperation, recordOperation)`
    *
    * The body receives the `Tracing` instance for `setAttribute` calls.
    * Span names, metric labels, and log context are derived from `name`.
    *
    * @param name Operation name — used as span name, metric label, and log context
    * @param body Effect to execute within the span (may call `tracing.setAttribute`)
    */
  private def traced[A](name: String)(body: Task[A]): Task[A] =
    tracing.span(name, SpanKind.INTERNAL)(body).tapBoth(
      error => logIfUnexpected(name)(error) *> recordOperation(name, success = false, Some(extractErrorContext(error))),
      _ => recordOperation(name, success = true)
    )
  
  /** Allocate a fixed pool of SafeIds for request resolution. */
  private def allocateIds(count: Int): Task[List[SafeId.SafeId]] =
    IdGenerators.batch(count)

  /** Deterministic generator for RiskTreeRequests based on a pre-allocated pool. */
  private def idGeneratorFrom(ids: List[SafeId.SafeId]): RiskTreeRequests.IdGenerator = {
    val iter = ids.iterator
    () => if iter.hasNext then iter.next() else throw new IllegalStateException("ID pool exhausted while resolving request")
  }

  /** Names of all leaves in a resolved request, for seedVarId assignment. */
  private def leafNamesOf(nodesByName: Map[SafeName.SafeName, RiskTreeRequests.ResolvedNode]): Seq[SafeName.SafeName] =
    nodesByName.values.collect { case n if n.kind == RiskTreeRequests.NodeKind.Leaf => n.name }.toSeq

  /** seedVarIds of the old tree's leaves that survive the update, keyed by their
    * (possibly renamed) name in the request — matched by stable node id, so
    * renames preserve stochastic identity (PLAN-SEED-IDENTITY §5.3 immutability).
    */
  private def carriedOverSeedVarIds(
    oldTree: RiskTree,
    existing: Map[SafeName.SafeName, RiskTreeRequests.ResolvedNode]
  ): Map[SafeName.SafeName, SeedVarId.SeedVarId] =
    existing.values.flatMap { node =>
      oldTree.index.nodes.get(NodeId(node.id)) match {
        case Some(leaf: RiskLeaf) => Some(node.name -> leaf.seedVarId)
        case _                    => None
      }
    }.toMap

  /** Build domain nodes and rootId from a resolved V2 request. */
  private def buildNodes(
    nodesByName: Map[SafeName.SafeName, RiskTreeRequests.ResolvedNode],
    leafOccurrenceAndShape: Map[SafeName.SafeName, (OccurrenceProbability, Distribution)],
    rootName: SafeName.SafeName,
    seedVarIdByLeafName: Map[SafeName.SafeName, SeedVarId.SeedVarId]
  ): Task[(Seq[RiskNode], NodeId)] = ZIO.attempt {
    val nameToId: Map[SafeName.SafeName, NodeId] = nodesByName.view.mapValues(n => NodeId(n.id)).toMap
    val childrenByParent: Map[Option[SafeName.SafeName], List[SafeName.SafeName]] =
      nodesByName.values.groupBy(_.parentName).view.mapValues(_.toList.map(_.name)).toMap

    def parentIdFor(node: RiskTreeRequests.ResolvedNode): Option[NodeId] =
      node.parentName.flatMap(nameToId.get)

    val domainNodes: Seq[RiskNode] = nodesByName.values.toSeq.map { node =>
      node.kind match {
        case RiskTreeRequests.NodeKind.Leaf =>
          val (prob, shape) = leafOccurrenceAndShape(node.name)
          RiskLeaf.fromValidated(
            id = node.id,
            name = node.name,
            distributionType = shape.distributionType,
            probability = prob,
            percentiles = shape.percentiles,
            quantiles = shape.quantiles,
            minLoss = shape.minLoss,
            maxLoss = shape.maxLoss,
            parentId = parentIdFor(node),
            terms = shape.terms,
            seedVarId = seedVarIdByLeafName(node.name)
          )

        case RiskTreeRequests.NodeKind.Portfolio =>
          val childIds: Array[NodeId] = childrenByParent.get(Some(node.name)).toList.flatten.flatMap(nameToId.get).toArray
          RiskPortfolio.fromValidated(
            id = node.id,
            name = node.name,
            childIds = childIds,
            parentId = parentIdFor(node)
          )
      }
    }

    val rootId = nameToId.getOrElse(rootName, throw new IllegalArgumentException(s"Root name '${rootName.value}' not found in resolved nodes"))
    (domainNodes, rootId)
  }
  
  // Config CRUD - only persist, no execution
  override def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: Option[BranchRef] = None)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[RiskTree] = {
    val operation = for {
      treeId <- IdGenerators.nextTreeId
      ids <- allocateIds(req.portfolios.size + req.leaves.size)
      resolved <- RiskTreeDefinitionRequest.resolve(req, idGeneratorFrom(ids)).toZIOValidation
      _ <- ensureUniqueTree(wsId, treeId, resolved.treeName)
      (seedVarIds, seedVarHighWater) <- ZIO
        .fromEither(SeedVarIdAssigner.assign(leafNamesOf(resolved.nodes), resolved.providedSeedVarIds, highWater = 0L))
        .mapError(e => ValidationFailed(List(e)))
      (nodes, rootId) <- buildNodes(resolved.nodes, resolved.leafOccurrenceAndShape, resolved.rootName, seedVarIds)
      riskTree <- RiskTree.fromNodes(
        id = treeId,
        name = resolved.treeName,
        nodes = nodes,
        rootId = rootId,
        seedVarHighWater = Some(seedVarHighWater)
      ).toZIOValidation
      persisted <- repo.create(wsId, riskTree, branch)
    } yield persisted

    operation.tapBoth(
      error => logIfUnexpected("create")(error) *> recordOperation("create", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("create", success = true)
    )
  }

  override def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: Option[BranchRef] = None)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[RiskTree] = {
    val operation = for {
      oldTree <- getTreeOrFail(wsId, id, branch)
      ids <- allocateIds(req.newPortfolios.size + req.newLeaves.size)
      resolved <- RiskTreeUpdateRequest.resolve(req, idGeneratorFrom(ids)).toZIOValidation
      _ <- ensureUniqueTree(wsId, id, resolved.treeName, excludeId = Some(id))
      allNodes = resolved.existing ++ resolved.added
      allLeafOccurrenceAndShape = resolved.existingLeafOccurrenceAndShape ++ resolved.addedLeafOccurrenceAndShape
      // Carried-over IDs (existing leaves, matched by node id) + caller-provided IDs
      // (new leaves). A provided ID clashing with a carried one survives the merge as
      // two names → one value and is rejected by RiskTree.fromNodes' distinctness
      // check with the §5.1 "already used by" message.
      (seedVarIds, seedVarHighWater) <- ZIO
        .fromEither(SeedVarIdAssigner.assign(
          leafNamesOf(allNodes),
          carriedOverSeedVarIds(oldTree, resolved.existing) ++ resolved.providedSeedVarIds,
          highWater = oldTree.seedVarHighWater.value
        ))
        .mapError(e => ValidationFailed(List(e)))
      (nodes, rootId) <- buildNodes(allNodes, allLeafOccurrenceAndShape, resolved.rootName, seedVarIds)
      riskTree <- RiskTree.fromNodes(
        id = id,
        name = resolved.treeName,
        nodes = nodes,
        rootId = rootId,
        seedVarHighWater = Some(seedVarHighWater)
      ).toZIOValidation
      updated <- repo.update(wsId, id, _ => riskTree, branch)
      _ <- invalidationHandler.handleMutation(oldTree, updated)
    } yield updated
    
    operation.tapBoth(
      error => logIfUnexpected("update")(error) *> recordOperation("update", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("update", success = true)
    )
  }
  
  override def delete(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[RiskTree] =
    repo.delete(wsId, id, branch)
      .tap(tree => invalidationHandler.handleTreeDeletion(tree))
      .tapBoth(
      error => logIfUnexpected("delete")(error) *> recordOperation("delete", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("delete", success = true)
    )

  override def getById(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None)(using com.risquanter.register.auth.Checked[com.risquanter.register.auth.Permission]): Task[Option[RiskTree]] =
    repo.getById(wsId, id, branch).tapBoth(
      error => logIfUnexpected("getById")(error) *> recordOperation("getById", success = false, Some(extractErrorContext(error))),
      _ => recordOperation("getById", success = true)
    )
  
  // ========================================
  // New LEC Query APIs (ADR-015)
  // ========================================
  
  override def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean = false, branch: Option[BranchRef] = None): Task[Double] =
    traced("probOfExceedance") {
      for {
        _ <- tracing.setAttribute("tree_id", treeId.value)
        _ <- tracing.setAttribute("node_id", nodeId.value)
        _ <- tracing.setAttribute("threshold", threshold)
        _ <- tracing.setAttribute("include_provenance", includeProvenance)

        // Fetch requested tree and ensure node exists within it
        (tree, _) <- lookupNodeInTree(wsId, treeId, nodeId, branch)
        
        // Ensure result is cached (cache-aside pattern via RiskResultResolver)
        result <- resolver.ensureCached(tree, nodeId, seedEntityId, includeProvenance)
        _ <- tracing.setAttribute("cache_resolved", true)
        
        // Compute exceedance probability from cached result
        prob = result.probOfExceedance(threshold)
        _ <- tracing.setAttribute("exceedance_probability", prob)
      } yield prob
    }
  
  override def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean = false, branch: Option[BranchRef] = None): Task[Map[NodeId, LECNodeCurve]] =
    traced("getLECCurvesMulti") {
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
          lookupNodesInTree(wsId, treeId, nodeIds, branch)
        }
        (tree, nodesMap) = treeWithNodes
        
        // Batch cache-aside: ensure all results are cached
        results <- resolver.ensureCachedAll(tree, nodeIds, seedEntityId, includeProvenance)
        _ <- tracing.setAttribute("results_resolved", results.size.toLong)
        
        // Generate curves with shared tick domain (ADR-014 render-time strategy)
        curvesData = LECGenerator.generateCurvePointsMulti(results)
        
        // Enrich with id + name + quantiles to produce LECNodeCurve per node
        enriched = curvesData.map { case (nodeId, points) =>
          val name = nodesMap.get(nodeId).map(_.name.value.toString).getOrElse(nodeId.value)
          val curvePoints = points.map { case (loss, prob) => LECPoint(loss, prob) }
          val quantiles = results.get(nodeId).map(LECGenerator.calculateQuantiles).getOrElse(Map.empty)
          nodeId -> LECNodeCurve(nodeId, name, curvePoints, quantiles)
        }
        
        _ <- tracing.setAttribute("curves_generated", enriched.size.toLong)
      } yield enriched
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
  }
  
  val layer: ZLayer[RiskTreeRepository & SimulationConfig & RiskResultResolver & InvalidationHandler & Tracing & SimulationSemaphore & Meter, Throwable, RiskTreeService] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      config <- ZIO.service[SimulationConfig]
      resolver <- ZIO.service[RiskResultResolver]
      invalidationHandler <- ZIO.service[InvalidationHandler]
      tracing <- ZIO.service[Tracing]
      semaphore <- ZIO.service[SimulationSemaphore]
      meter <- ZIO.service[Meter]
      
      // Create metric instruments once at layer construction time
      opsCounter <- meter.counter(
        MetricNames.operationsCounter,
        Some(MetricNames.operationsUnit),
        Some(MetricNames.operationsDesc)
      )
    } yield new RiskTreeServiceLive(repo, config, resolver, invalidationHandler, tracing, semaphore, opsCounter)
  }
}

package com.risquanter.register.services

import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.metrics.{Meter, Counter}
import zio.telemetry.opentelemetry.common.{Attributes, Attribute}
import io.opentelemetry.api.trace.SpanKind
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest, RiskTreeRequests}
import com.risquanter.register.domain.data.{RiskTree, RiskNode, RiskLeaf, RiskPortfolio, LECPoint, LECNodeCurve, DistributionShape}
import com.risquanter.register.domain.data.iron.{SafeId, SafeName, OccurrenceProbability, TreeId, NodeId, WorkspaceId, SeedVarId, SeedEntityId, BranchRef, CommitHash, Revision, BranchChoice}
import com.risquanter.register.domain.errors.{ErrorLocation, FieldName, FieldProblem, ValidationFailed, ValidationError, RepositoryFailure, TreeLoadFailure, SimulationFailure, AppError}
import com.risquanter.register.domain.errors.ValidationExtensions.*
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.simulation.LECGenerator
import com.risquanter.register.services.cache.CachedResultResolver
import com.risquanter.register.services.helper.SeedVarIdAssigner
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.util.IdGenerators
import com.risquanter.register.auth.{Authority, Checked}

/**
 * Live implementation of RiskTreeService with telemetry instrumentation.
 * 
 * Metric instruments (Counter, Histogram) are created once during layer construction
 * and cached for the lifetime of the service, avoiding repeated instrument creation.
 */
class RiskTreeServiceLive private (
  repo: RiskTreeRepository,
  resolver: CachedResultResolver,
  invalidationHandler: InvalidationHandler,
  tracing: Tracing,
  // Pre-created metric instruments (cached at construction time)
  operationsCounter: Counter[Long]
) extends RiskTreeService {
  
  import RiskTreeServiceLive.{ErrorContext, Operation}
  
  /** Fetch tree by id, with the commit it resolved to, or fail with
    * ValidationFailed. */
  private def getTreeOrFail(wsId: WorkspaceId, treeId: TreeId, rev: Revision): IO[AppError,(RiskTree, CommitHash)] =
    repo.getById(wsId, treeId, rev).flatMap {
      case Some(loaded) => ZIO.succeed(loaded)
      case None =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          ErrorLocation.requestField(FieldName.TreeId), FieldProblem.TreeNotFound(treeId)))))
    }

  /** Fetch tree, its resolved commit, and the node together, or fail with
    * ValidationFailed. */
  private def lookupNodeInTree(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, rev: Revision): IO[AppError,(RiskTree, CommitHash, RiskNode)] =
    for
      (tree, commit) <- getTreeOrFail(wsId, treeId, rev)
      node <- ZIO.fromOption(tree.index.nodes.get(nodeId)).orElseFail(ValidationFailed(List(ValidationError(
        ErrorLocation.nodeField(nodeId, FieldName.Id), FieldProblem.NodeNotFound))))
    yield (tree, commit, node)

  /** Fetch tree and all requested nodes. With `omitAbsent = false` (the
    * default), fail with aggregated validation errors when any node is missing;
    * with `omitAbsent = true`, absent nodes are simply not in the returned map
    * (the point-in-time read path — a node may not exist at an earlier commit). */
  private def lookupNodesInTree(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], rev: Revision, omitAbsent: Boolean): IO[AppError,(RiskTree, CommitHash, Map[NodeId, RiskNode])] =
    for
      (tree, commit) <- getTreeOrFail(wsId, treeId, rev)
      missing = nodeIds.filterNot(tree.index.nodes.contains)
      _ <- if missing.isEmpty || omitAbsent then ZIO.unit else ZIO.fail(ValidationFailed(missing.toList.map(id =>
        ValidationError(ErrorLocation.nodeField(id, FieldName.Id), FieldProblem.NodeNotFound))))
      nodes = nodeIds.flatMap(id => tree.index.nodes.get(id).map(id -> _)).toMap
    yield (tree, commit, nodes)

  private def ensureUniqueTree(wsId: WorkspaceId, treeId: TreeId, treeName: SafeName.SafeName, branch: BranchRef, excludeId: Option[TreeId]): IO[AppError,Unit] =
    collectAllTrees(wsId, branch).flatMap { trees =>
      val candidates = trees.filterNot(t => excludeId.contains(t.id))

      val errors = List(
        Option.when(candidates.exists(_.id == treeId))(
          ValidationError(ErrorLocation.requestField(FieldName.Id), FieldProblem.TreeIdAlreadyExists(treeId))
        ),
        Option.when(candidates.exists(_.name == treeName))(
          ValidationError(ErrorLocation.requestField(FieldName.Name), FieldProblem.TreeNameAlreadyExists(treeName))
        )
      ).flatten

      if errors.nonEmpty then ZIO.fail(ValidationFailed(errors)) else ZIO.unit
    }

  // Trees at the head of the branch being written; uniqueness (tree ID and
  // name) is checked against exactly this set.
  private def collectAllTrees(wsId: WorkspaceId, branch: BranchRef): IO[AppError,List[RiskTree]] =
    repo.getAllForWorkspace(wsId, Revision.Head(branch)).flatMap { results =>
      val (errs, trees) = results.foldLeft((List.empty[TreeLoadFailure], List.empty[RiskTree])) {
        case ((es, ts), Left(err))  => (err :: es, ts)
        case ((es, ts), Right(t))   => (es, t :: ts)
      }
      if errs.nonEmpty then
        ZIO.fail(RepositoryFailure(errs.reverse.map(_.logMessage).mkString("; ")))
      else
        ZIO.succeed(trees.reverse)
    }
  
  /** Record operation metric with optional error context
    *
    * Base attributes (operation, success) are always included.
    * Error attributes (error_type, error_code, error_field) added when error occurs.
    *
    * @param operation The operation the counter labels
    * @param success Whether operation succeeded
    * @param errorInfo Optional error context for failed operations
    */
  private def recordOperation(
    operation: Operation,
    success: Boolean,
    errorInfo: Option[ErrorContext]
  ): UIO[Unit] = {
    val attrs = errorInfo match {
      case None =>
        Attributes(
          Attribute.string("operation", operation.label),
          Attribute.boolean("success", success)
        )
      case Some(ErrorContext(errorType, errorCode, None)) =>
        Attributes(
          Attribute.string("operation", operation.label),
          Attribute.boolean("success", success),
          Attribute.string("error_type", errorType),
          Attribute.string("error_code", errorCode)
        )
      case Some(ErrorContext(errorType, errorCode, Some(errorField))) =>
        Attributes(
          Attribute.string("operation", operation.label),
          Attribute.boolean("success", success),
          Attribute.string("error_type", errorType),
          Attribute.string("error_code", errorCode),
          Attribute.string("error_field", errorField)
        )
    }
    operationsCounter.add(1, attrs)
  }

  /** Structured error context for metrics, from the failure that occurred.
    *
    * `error_type` is the failure's case name in every arm. The code and the
    * field come from the first validation entry for a `ValidationFailed`, and
    * are fixed per case otherwise.
    */
  private def extractErrorContext(error: AppError): ErrorContext =
    val errorType = error.getClass.getSimpleName
    error match {
      case ValidationFailed(errors) =>
        val primaryError = errors.headOption.getOrElse(
          ValidationError(ErrorLocation.Whole, FieldProblem.Unspecified)
        )
        ErrorContext(
          errorType = errorType,
          errorCode = FieldProblem.code(primaryError.problem).code,
          errorField = Some(ErrorLocation.describe(primaryError.location))
        )

      case RepositoryFailure(_) =>
        ErrorContext(
          errorType = errorType,
          errorCode = RiskTreeServiceLive.ErrorCodes.Repository,
          errorField = None
        )

      case SimulationFailure(_, _) =>
        ErrorContext(
          errorType = errorType,
          errorCode = RiskTreeServiceLive.ErrorCodes.Simulation,
          errorField = Some(RiskTreeServiceLive.ErrorCodes.SimulationField)
        )

      case _ =>
        ErrorContext(
          errorType = errorType,
          errorCode = RiskTreeServiceLive.ErrorCodes.Unknown,
          errorField = None
        )
    }

  /** Wrap a traced LEC query operation with span + metrics + error logging.
    *
    * Eliminates the repeated ceremony of:
    *  1. `tracing.span(name, SpanKind.INTERNAL) { ... }`
    *  2. `.tapBoth(recordOperation, recordOperation)`
    *
    * The body receives the `Tracing` instance for `setAttribute` calls.
    * Span names, metric labels, and log context are derived from `operation`.
    *
    * @param operation The operation — its label is the span name and the metric label
    * @param body Effect to execute within the span (may call `tracing.setAttribute`)
    */
  private def traced[A](operation: Operation)(body: IO[AppError,A]): IO[AppError,A] =
    tracing.span(operation.label, SpanKind.INTERNAL)(body).tapBoth(
      error => recordOperation(operation, success = false, Some(extractErrorContext(error))),
      _ => recordOperation(operation, success = true, None)
    )
  
  /** Allocate a fixed pool of SafeIds for request resolution.
    *
    * Identifier generation failing is a broken invariant, not a request
    * outcome, so it dies rather than entering the error channel.
    */
  private def allocateIds(count: Int): IO[AppError, List[SafeId.SafeId]] =
    IdGenerators.batch(count).orDie

  /** Deterministic generator for RiskTreeRequests based on a pre-allocated pool. */
  private def idGeneratorFrom(ids: List[SafeId.SafeId]): RiskTreeRequests.IdGenerator = {
    val iter = ids.iterator
    () => if iter.hasNext then iter.next() else throw new IllegalStateException("ID pool exhausted while resolving request")
  }

  /** Names of all leaves in a resolved request, for seedVarId assignment. */
  private def leafNamesOf(nodesByName: Map[SafeName.SafeName, RiskTreeRequests.ResolvedNode]): Seq[SafeName.SafeName] =
    nodesByName.values.collect { case RiskTreeRequests.ResolvedNode(_, name, _, RiskTreeRequests.NodeKind.Leaf) => name }.toSeq

  /** seedVarIds of the old tree's leaves that survive the update, keyed by their
    * (possibly renamed) name in the request — matched by stable node id, so
    * renames preserve stochastic identity: a leaf's seedVarId is immutable.
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
    leafOccurrenceAndShape: Map[SafeName.SafeName, (OccurrenceProbability, DistributionShape)],
    rootName: SafeName.SafeName,
    seedVarIdByLeafName: Map[SafeName.SafeName, SeedVarId.SeedVarId]
  ): IO[AppError,(Seq[RiskNode], NodeId)] = ZIO.attempt {
    val nameToId: Map[SafeName.SafeName, NodeId] = nodesByName.view.mapValues(n => NodeId(n.id)).toMap
    val childrenByParent: Map[RiskTreeRequests.ParentRef, List[SafeName.SafeName]] =
      nodesByName.values.groupBy(_.parent).view.mapValues(_.toList.map(_.name)).toMap

    def parentIdFor(node: RiskTreeRequests.ResolvedNode): Option[NodeId] =
      RiskTreeRequests.ParentRef.nameOf(node.parent).flatMap(nameToId.get)

    val domainNodes: Seq[RiskNode] = nodesByName.values.toSeq.map { node =>
      node.kind match {
        case RiskTreeRequests.NodeKind.Leaf =>
          val (prob, shape) = leafOccurrenceAndShape(node.name)
          RiskLeaf.fromValidated(
            id = node.id,
            name = node.name,
            probability = prob,
            shape = shape,
            parentId = parentIdFor(node),
            seedVarId = seedVarIdByLeafName(node.name)
          )

        case RiskTreeRequests.NodeKind.Portfolio =>
          val childIds: Vector[NodeId] =
            childrenByParent.get(RiskTreeRequests.ParentRef.Under(node.name)).toList.flatten.flatMap(nameToId.get).toVector
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
  // The request was already resolved and validated before this point, so an
  // absent root name or an exhausted identifier pool is a broken invariant
  // rather than an outcome a caller can act on. It dies instead of entering the
  // error channel, which carries only failures that have a wire case.
  }.orDie
  
  /** Client-facing branch identity for the change-notification tag: `Main` or
    * the scenario name. Never the internal `BranchRef.toBranchRef` — that
    * embeds the WorkspaceId, which must not cross the client boundary
    * (ADR-036).
    *
    * The branch is always composed from this same `wsId` (ActiveBranch.resolve)
    * moments earlier, so the decomposition cannot fail here; a failure is an
    * unreachable invariant violation, which dies rather than being reported,
    * mirroring `ActiveBranch.resolve`'s own treatment of the composition. */
  private def clientBranch(wsId: WorkspaceId, branch: BranchRef): UIO[BranchChoice] =
    BranchChoice.fromBranchRef(wsId, branch) match
      case Right(choice) => ZIO.succeed(choice)
      case Left(errors)  => ZIO.die(new IllegalStateException(
        s"branch ${branch.toBranchRef} is not a branch of workspace ${wsId.value}: " +
        errors.map(ValidationError.describe).mkString("; ")))

  // Config CRUD - only persist, no execution
  override def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Authority]): IO[AppError,RiskTree] = {
    val operation = for {
      // Identifier generation failing is a broken invariant, as in `allocateIds`.
      treeId <- IdGenerators.nextTreeId.orDie
      ids <- allocateIds(req.portfolios.size + req.leaves.size)
      resolved <- RiskTreeDefinitionRequest.resolve(req, idGeneratorFrom(ids)).toZIOValidation
      _ <- ensureUniqueTree(wsId, treeId, resolved.treeName, branch, excludeId = None)
      (seedVarIds, seedVarHighWater) <- ZIO
        .fromEither(SeedVarIdAssigner.assign(leafNamesOf(resolved.nodes), resolved.providedSeedVarIds, highWater = 0L))
        .mapError(e => ValidationFailed(List(e)))
      (nodes, rootId) <- buildNodes(resolved.nodes, resolved.leafOccurrenceAndShape, resolved.rootName, seedVarIds)
      riskTree <- RiskTree.fromNodes(
        id = treeId,
        name = resolved.treeName,
        nodes = nodes,
        rootId = rootId,
        seedVarHighWater = Some(seedVarHighWater),
        // A tree is created from its node definition alone; mitigations are
        // attached afterwards, never at creation.
        mitigations = Nil
      ).toZIOValidation
      persisted <- repo.create(wsId, riskTree, branch)
    } yield persisted

    operation.tapBoth(
      error => recordOperation(Operation.Create, success = false, Some(extractErrorContext(error))),
      _ => recordOperation(Operation.Create, success = true, None)
    )
  }

  override def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using Checked[Authority]): IO[AppError,RiskTree] = {
    val operation = for {
      (oldTree, _) <- getTreeOrFail(wsId, id, Revision.Head(branch))
      ids <- allocateIds(req.newPortfolios.size + req.newLeaves.size)
      resolved <- RiskTreeUpdateRequest.resolve(req, oldTree.index, idGeneratorFrom(ids)).toZIOValidation
      _ <- ensureUniqueTree(wsId, id, resolved.treeName, branch, excludeId = Some(id))
      allNodes = resolved.existing ++ resolved.added
      allLeafOccurrenceAndShape = resolved.existingLeafOccurrenceAndShape ++ resolved.addedLeafOccurrenceAndShape
      // Every leaf's seedVarId, from two sources. A leaf the update keeps carries
      // its stored seedVarId over, matched by node id, because a seedVarId is
      // immutable once assigned. A new leaf's is whatever the client supplied, or
      // absent so the server assigns one.
      //
      // A supplied seedVarId equal to a carried-over one survives this merge as two
      // leaf names mapping to one value: the map is keyed by name, so neither entry
      // displaces the other. Nothing here rejects that, and nothing needs to —
      // RiskTree.fromNodes runs the per-tree seedVarId distinctness check and is
      // where the collision is refused.
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
        seedVarHighWater = Some(seedVarHighWater),
        // Carried over from the tree being updated. ADR-017's
        // omission-means-delete covers the node collection, which the request
        // enumerates; `RiskTreeUpdateRequest` has no mitigation field at all, so
        // an update cannot express "delete the mitigations" and must not imply
        // it. The repository writes the whole subtree in one commit, so
        // anything not passed here is deleted from the store.
        mitigations = oldTree.mitigations
      ).toZIOValidation
      updated <- repo.update(wsId, id, _ => riskTree, branch)
      tag     <- clientBranch(wsId, branch)
      _ <- invalidationHandler.handleMutation(oldTree, updated, tag)
    } yield updated
    
    operation.tapBoth(
      error => recordOperation(Operation.Update, success = false, Some(extractErrorContext(error))),
      _ => recordOperation(Operation.Update, success = true, None)
    )
  }
  
  override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Authority]): IO[AppError,RiskTree] =
    repo.delete(wsId, id, branch)
      .tap(tree => clientBranch(wsId, branch).flatMap(invalidationHandler.handleTreeDeletion(tree, _)))
      .tapBoth(
      error => recordOperation(Operation.Delete, success = false, Some(extractErrorContext(error))),
      _ => recordOperation(Operation.Delete, success = true, None)
    )

  override def revertTree(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef)(using Checked[Authority]): IO[AppError,RiskTree] = {
    val operation = for {
      // The head state feeds the change notification and nothing else, so a
      // failure to read it must not stop the revert. A stored tree that breaks
      // a tree invariant fails every read, and revert is the only operation
      // that can repair it, because its storage half reads at the target
      // commit and never at the head. The cause is logged rather than
      // discarded: a broken invariant and an unreachable store are
      // indistinguishable here and only the log tells them apart.
      oldTree  <- repo.getById(wsId, id, Revision.Head(branch))
                    .catchAll(error =>
                      ZIO.logWarning(
                        s"revert of tree ${id.value}: head read failed, notifying the whole tree instead: " +
                        s"${error.logMessage}"
                      ).as(None))
      reverted <- repo.revert(wsId, id, toCommit, branch)
      tag      <- clientBranch(wsId, branch)
      // None covers two situations: the tree was deleted at head, and the head
      // state could not be read. Both leave no before-state to diff against.
      _        <- oldTree match
                    case Some((prev, _)) => invalidationHandler.handleMutation(prev, reverted, tag)
                    case None            => invalidationHandler.handleWholeTreeChange(reverted, tag)
    } yield reverted

    operation.tapBoth(
      error => recordOperation(Operation.Revert, success = false, Some(extractErrorContext(error))),
      _ => recordOperation(Operation.Revert, success = true, None)
    )
  }

  override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision)(using Checked[Authority]): IO[AppError,Option[(RiskTree, CommitHash)]] =
    repo.getById(wsId, id, rev).tapBoth(
      error => recordOperation(Operation.GetById, success = false, Some(extractErrorContext(error))),
      _ => recordOperation(Operation.GetById, success = true, None)
    )

  // ========================================
  // LEC Query APIs (ADR-015)
  // ========================================

  override def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision)(using Checked[Authority]): IO[AppError,Double] =
    traced(Operation.ProbOfExceedance) {
      for {
        _ <- tracing.setAttribute("tree_id", treeId.value)
        _ <- tracing.setAttribute("node_id", nodeId.value)
        _ <- tracing.setAttribute("threshold", threshold)
        _ <- tracing.setAttribute("include_provenance", includeProvenance)

        // Fetch requested tree and ensure node exists within it
        (tree, _, _) <- lookupNodeInTree(wsId, treeId, nodeId, rev)
        
        // Ensure result is cached (cache-aside pattern via CachedResultResolver)
        result <- resolver.ensureCached(tree, nodeId, seedEntityId, includeProvenance)
        _ <- tracing.setAttribute("cache_resolved", true)
        
        // Compute exceedance probability from cached result
        prob = result.probOfExceedance(threshold)
        _ <- tracing.setAttribute("exceedance_probability", prob)
      } yield prob
    }
  
  override def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision, omitAbsent: Boolean)(using Checked[Authority]): IO[AppError,Map[NodeId, LECNodeCurve]] =
    traced(Operation.GetLECCurvesMulti) {
      for {
        _ <- tracing.setAttribute("tree_id", treeId.value)
        _ <- tracing.setAttribute("node_count", nodeIds.size.toLong)
        _ <- tracing.setAttribute("node_ids", nodeIds.map(_.value).mkString(","))
        _ <- tracing.setAttribute("include_provenance", includeProvenance)

        // Validate inputs and fetch requested tree + nodes
        treeWithNodes <- if (nodeIds.isEmpty) {
          ZIO.fail(ValidationFailed(List(ValidationError(
            ErrorLocation.requestField(FieldName.Nodes), FieldProblem.NodeIdsEmpty))))
        } else {
          lookupNodesInTree(wsId, treeId, nodeIds, rev, omitAbsent)
        }
        (tree, _, nodesMap) = treeWithNodes
        // With omitAbsent, nodesMap holds only the ids present at this revision;
        // resolve and log against that present set (empty input still failed above).
        presentIds = nodesMap.keySet
        _ <- ZIO.when(omitAbsent && presentIds.size < nodeIds.size)(
               ZIO.logInfo(s"lec-multi omitAbsent: ${nodeIds.size - presentIds.size} absent node(s) omitted at $rev"))

        // Batch cache-aside: ensure all present results are cached
        results <- resolver.ensureCachedAll(tree, presentIds, seedEntityId, includeProvenance)
        _ <- tracing.setAttribute("results_resolved", results.size.toLong)
        
        // Generate curves with shared tick domain (ADR-014 render-time strategy)
        curvesData = LECGenerator.generateCurvePointsMulti(results)
        
        // Enrich with id + name + quantiles + AAL + no-loss probability to produce LECNodeCurve per node
        enriched = curvesData.map { case (nodeId, points) =>
          val name = nodesMap.get(nodeId).map(_.name.value.toString).getOrElse(nodeId.value)
          val curvePoints = points.map { case (loss, prob) => LECPoint(loss, prob) }
          val quantiles = results.get(nodeId).map(LECGenerator.calculateQuantiles).getOrElse(Map.empty)
          val aal = results.get(nodeId).map(LECGenerator.averageAnnualLoss).getOrElse(0.0)
          val noLossProb = results.get(nodeId).map(LECGenerator.probabilityOfNoLoss).getOrElse(1.0)
          nodeId -> LECNodeCurve(nodeId, name, curvePoints, quantiles, aal, noLossProb)
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
    errorField: Option[String]
  )

  /** The operations the counter and the spans label. The labels are a contract
    * with the metrics dashboards, so they are declared rather than derived from
    * the case names.
    */
  private enum Operation(val label: String):
    case Create            extends Operation("create")
    case Update            extends Operation("update")
    case Delete            extends Operation("delete")
    case Revert            extends Operation("revert")
    case GetById           extends Operation("getById")
    case ProbOfExceedance  extends Operation("probOfExceedance")
    case GetLECCurvesMulti extends Operation("getLECCurvesMulti")

  /** The `error_code` values this service emits for failures that carry no
    * validation code of their own, and the `error_field` a simulation failure
    * names. Contracts with the metrics dashboards.
    */
  private object ErrorCodes {
    val Repository: String      = "REPOSITORY_ERROR"
    val Simulation: String      = "SIMULATION_ERROR"
    val Unknown: String         = "UNKNOWN_ERROR"
    val SimulationField: String = "simulation"
  }

  /** Metric names and descriptions - centralized for consistency */
  private object MetricNames {
    val operationsCounter = "risk_tree.operations"
    val operationsUnit = "1"
    val operationsDesc = "Number of risk tree operations"
  }
  
  val layer: ZLayer[RiskTreeRepository & CachedResultResolver & InvalidationHandler & Tracing & Meter, Throwable, RiskTreeService] = ZLayer {
    for {
      repo <- ZIO.service[RiskTreeRepository]
      resolver <- ZIO.service[CachedResultResolver]
      invalidationHandler <- ZIO.service[InvalidationHandler]
      tracing <- ZIO.service[Tracing]
      meter <- ZIO.service[Meter]
      
      // Create metric instruments once at layer construction time
      opsCounter <- meter.counter(
        MetricNames.operationsCounter,
        Some(MetricNames.operationsUnit),
        Some(MetricNames.operationsDesc)
      )
    } yield new RiskTreeServiceLive(repo, resolver, invalidationHandler, tracing, opsCounter)
  }
}

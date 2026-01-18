package com.risquanter.register.services.cache

import zio.*
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
  *
  * == TODO: Configuration Migration ==
  * Simulation parameters are currently hardwired. See tasks 5-7 in implementation plan:
  * - Move nTrials, parallelism, seeds to configuration management
  * - Remove from external APIs
  * - Wire config values at this layer
  */
final case class RiskResultResolverLive(
    cache: RiskResultCache,
    treeIndex: TreeIndex
) extends RiskResultResolver {

  // TODO: Move to configuration management (task 5)
  private val nTrials: PositiveInt = 10000.refineUnsafe
  private val parallelism: PositiveInt = 8.refineUnsafe
  private val seed3: Long = 0L
  private val seed4: Long = 0L

  override def ensureCached(nodeId: NodeId): Task[RiskResult] =
    cache.get(nodeId).flatMap {
      case Some(result) =>
        ZIO.succeed(result)
      case None =>
        simulateSubtree(nodeId)
    }

  override def ensureCachedAll(nodeIds: Set[NodeId]): Task[Map[NodeId, RiskResult]] =
    ZIO.foreach(nodeIds.toList)(id => ensureCached(id).map(id -> _)).map(_.toMap)

  /**
    * Simulate subtree rooted at nodeId, caching all results.
    */
  private def simulateSubtree(nodeId: NodeId): Task[RiskResult] =
    treeIndex.nodes.get(nodeId) match
      case Some(node) => simulateNode(node)
      case None =>
        ZIO.fail(ValidationFailed(List(ValidationError(
          field = "nodeId",
          code = ValidationErrorCode.CONSTRAINT_VIOLATION,
          message = s"Node not found in tree index: $nodeId"
        ))))

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

  /**
    * Create ZLayer for RiskResultResolver.
    */
  def layer: ZLayer[RiskResultCache & TreeIndex, Nothing, RiskResultResolver] =
    ZLayer.fromZIO {
      for
        cache     <- ZIO.service[RiskResultCache]
        treeIndex <- ZIO.service[TreeIndex]
      yield RiskResultResolverLive(cache, treeIndex)
    }
}

package com.risquanter.register.services.pipeline

import zio.*
import com.risquanter.register.services.cache.TreeCacheManager
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, NodeId}

/**
  * Handles cache invalidation and SSE notification when nodes change.
  *
  * Per ADR-005-proposal: "When Irmin notifies of a change, invalidate from node to root"
  * Per ADR-004a-proposal: "SSE provides simple unidirectional streaming for server→client push"
  *
  * This service bridges the gap between data changes and client notifications:
  * 1. Invalidates affected cache entries (node + ancestors) using TreeCacheManager
  * 2. Broadcasts CacheInvalidated event via SSE to all subscribers
  *
  * Callers provide the tree directly — no internal tree lookup.
  * Triggered by RiskTreeServiceLive mutations (update, delete) and manually
  * via API endpoint. Future: also by Irmin watch subscription
  * (Irmin-specific, for external mutations).
  *
  * == Example ==
  * {{{
  * // User modifies "cyber-risk" node parameters
  * InvalidationHandler.handleNodeChange(nodeId = "cyber-risk", tree)
  * 
  * // Result:
  * // 1. Cache entries cleared: cyber-risk, ops-risk, portfolio (ancestors)
  * // 2. SSE event sent: CacheInvalidated(List("cyber-risk", "ops-risk", "portfolio"), tree.id)
  * // 3. Connected browsers receive event (can refresh if needed)
  * }}}
  */
/**
  * Result of a cache invalidation operation.
  *
  * @param invalidatedNodes Node IDs whose cache entries were cleared (node + ancestors)
  * @param subscribersNotified Number of SSE subscribers who received the invalidation event
  */
final case class InvalidationResult(
    invalidatedNodes: List[NodeId],
    subscribersNotified: Int
)

trait InvalidationHandler {

  /**
    * Handle a node change by invalidating cache and notifying clients.
    *
    * '''Deprecation candidate:''' This method is a special case of `handleMutation`
    * for single-node invalidation (manual API / future Irmin watch). Review when
    * implementing ADR-017 Phase 2 (TreeOp algebra) — if TreeOp covers all mutation
    * entry points, this method should be removed in favour of `handleMutation`.
    *
    * @param nodeId Changed node identifier
    * @param tree   Current tree (treeId derived from tree.id)
    * @return Invalidation result containing affected nodes and subscriber count
    */
  def handleNodeChange(nodeId: NodeId, tree: RiskTree): UIO[InvalidationResult]

  /**
    * Handle a tree mutation by diffing old vs new tree, invalidating affected
    * ancestor paths, cleaning up orphaned cache entries, and publishing a
    * single SSE event.
    *
    * Affected nodes are determined by:
    * - Added nodes: parent in new tree
    * - Removed nodes: parent in old tree (if it survives in new tree) + orphan cleanup
    * - Reparented nodes: old parent + new parent
    * - Parameter-changed nodes: the node itself
    *
    * TODO: Review when implementing ADR-017 Phase 2 (Batch Operations / TreeOp algebra).
    * TreeOp operations carry explicit change semantics, which may simplify or replace
    * the old-vs-new tree diffing logic.
    *
    * @param oldTree Tree state before mutation
    * @param newTree Tree state after mutation (treeId derived from newTree.id)
    * @return Invalidation result containing affected nodes and subscriber count
    */
  def handleMutation(oldTree: RiskTree, newTree: RiskTree): UIO[InvalidationResult]

  /**
    * Handle tree deletion by clearing the entire cache and publishing a
    * CacheInvalidated event with all node IDs. Browsers that receive this
    * event will re-fetch and get NOT_FOUND, signaling the tree is gone.
    *
    * @param tree The deleted tree (treeId and node IDs derived from tree)
    * @return Invalidation result containing all node IDs and subscriber count
    */
  def handleTreeDeletion(tree: RiskTree): UIO[InvalidationResult]
}

object InvalidationHandler {

  /**
    * Create live InvalidationHandler layer.
    */
  val live: ZLayer[TreeCacheManager & SSEHub, Nothing, InvalidationHandler] =
    ZLayer.fromFunction(InvalidationHandlerLive(_, _))

  // Accessor methods for ZIO service pattern
  def handleNodeChange(nodeId: NodeId, tree: RiskTree): URIO[InvalidationHandler, InvalidationResult] =
    ZIO.serviceWithZIO[InvalidationHandler](_.handleNodeChange(nodeId, tree))

  def handleMutation(oldTree: RiskTree, newTree: RiskTree): URIO[InvalidationHandler, InvalidationResult] =
    ZIO.serviceWithZIO[InvalidationHandler](_.handleMutation(oldTree, newTree))

  def handleTreeDeletion(tree: RiskTree): URIO[InvalidationHandler, InvalidationResult] =
    ZIO.serviceWithZIO[InvalidationHandler](_.handleTreeDeletion(tree))
}

/**
  * Live implementation of InvalidationHandler.
  */
final case class InvalidationHandlerLive(
    cacheManager: TreeCacheManager,
    hub: SSEHub
) extends InvalidationHandler {

  /**
    * Publish a CacheInvalidated SSE event and return the InvalidationResult.
    * Shared by all three handler methods to ensure consistent event shape and logging.
    */
  private def publishInvalidation(treeId: TreeId, nodeIds: List[NodeId], context: String): UIO[InvalidationResult] =
    if nodeIds.isEmpty then
      ZIO.succeed(InvalidationResult(invalidatedNodes = Nil, subscribersNotified = 0))
    else
      val event = SSEEvent.CacheInvalidated(nodeIds = nodeIds.map(_.value), treeId = treeId)
      for
        subscriberCount <- hub.publish(treeId, event)
        _               <- ZIO.logInfo(s"$context: treeId=$treeId, nodes=${nodeIds.size}, subscribers=$subscriberCount")
      yield InvalidationResult(invalidatedNodes = nodeIds, subscribersNotified = subscriberCount)

  override def handleNodeChange(nodeId: NodeId, tree: RiskTree): UIO[InvalidationResult] =
    for
      invalidated <- cacheManager.invalidate(tree, nodeId)
      result      <- publishInvalidation(tree.id, invalidated, s"Cache invalidated: nodeId=${nodeId.value}")
    yield result

  override def handleMutation(oldTree: RiskTree, newTree: RiskTree): UIO[InvalidationResult] =
    // Defense-in-depth (ADR-010 §3): internal precondition — caller controls both trees
    require(oldTree.id == newTree.id,
      s"handleMutation precondition violated: oldTree.id=${oldTree.id} != newTree.id=${newTree.id}")

    val treeId = newTree.id
    val (affected, orphans) = computeAffectedNodes(oldTree, newTree)

    if affected.isEmpty && orphans.isEmpty then
      ZIO.succeed(InvalidationResult(invalidatedNodes = Nil, subscribersNotified = 0))
    else
      for
        // Invalidate ancestor paths for each affected node
        invalidatedPerNode <- ZIO.foreach(affected.toList)(nid =>
          cacheManager.invalidate(newTree, nid)
        )
        allInvalidated = invalidatedPerNode.flatten.toSet

        // Clean up orphaned cache entries (removed nodes)
        _ <- if orphans.nonEmpty then
          for
            cache <- cacheManager.cacheFor(treeId)
            _     <- cache.removeAll(orphans.toList)
            _     <- ZIO.logInfo(s"Orphan cleanup: treeId=$treeId, removed=${orphans.map(_.value).mkString(", ")}")
          yield ()
        else ZIO.unit

        // Publish single SSE event covering invalidated + orphans
        allAffectedNodes = (allInvalidated ++ orphans).toList
        result <- publishInvalidation(treeId, allAffectedNodes, "Mutation invalidation")
      yield result

  override def handleTreeDeletion(tree: RiskTree): UIO[InvalidationResult] =
    val treeId = tree.id
    for
      _          <- cacheManager.deleteTree(treeId)
      allNodeIds  = tree.index.nodes.keys.toList  // browser re-fetches → gets NOT_FOUND
      result     <- publishInvalidation(treeId, allNodeIds, "Tree deletion invalidation")
    yield result

  // ========================================
  // Tree diff logic
  // ========================================

  /**
    * Compute affected nodes and orphans from old vs new tree diff.
    *
    * Affected nodes are the "entry points" for ancestor-path invalidation:
    * - Added node → its parent in new tree
    * - Removed node → its parent in old tree (if parent survives in new tree)
    * - Reparented node → old parent + new parent
    * - Parameter-changed node → the node itself
    *
    * Orphans are removed nodes whose cache entries need cleanup (they no longer
    * exist in the tree so their cache entries are unreachable dead weight).
    *
    * @return (affectedNodes, orphanNodes)
    */
  private def computeAffectedNodes(oldTree: RiskTree, newTree: RiskTree): (Set[NodeId], Set[NodeId]) = {
    val oldKeys = oldTree.index.nodes.keySet
    val newKeys = newTree.index.nodes.keySet

    val added   = newKeys -- oldKeys
    val removed = oldKeys -- newKeys
    val common  = oldKeys.intersect(newKeys)

    // Added nodes: invalidate their parent's ancestor path
    val affectedFromAdded: Set[NodeId] =
      added.flatMap(nid => newTree.index.parents.get(nid))

    // Removed nodes: invalidate the surviving parent's ancestor path
    // Only include parents that still exist in the new tree — if the parent
    // was also removed, its own removal entry handles the surviving ancestor.
    val affectedFromRemoved: Set[NodeId] =
      removed.flatMap { nid =>
        oldTree.index.parents.get(nid).filter(newTree.index.nodes.contains)
      }

    // Common nodes: check for reparent or parameter change
    val affectedFromChanged: Set[NodeId] =
      common.flatMap { nid =>
        val oldParent = oldTree.index.parents.get(nid)
        val newParent = newTree.index.parents.get(nid)

        if oldParent != newParent then
          // Reparented: invalidate both old parent (if it survives) and new parent
          oldParent.filter(newTree.index.nodes.contains).toSet ++ newParent.toSet
        else if oldTree.index.nodes.get(nid) != newTree.index.nodes.get(nid) then
          // Node data changed (params, name, etc.) — invalidate the node itself
          Set(nid)
        else
          Set.empty
      }

    val affected = affectedFromAdded ++ affectedFromRemoved ++ affectedFromChanged

    // Orphans: all removed nodes need cache entry cleanup
    val orphans = removed

    (affected, orphans)
  }
}

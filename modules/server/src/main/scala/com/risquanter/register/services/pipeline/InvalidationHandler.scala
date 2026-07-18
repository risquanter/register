package com.risquanter.register.services.pipeline

import zio.*
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.http.sse.SSEEvent
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{TreeId, NodeId}

/**
  * Notifies SSE subscribers which nodes' figures changed after a tree
  * mutation (ADR-004a: SSE provides unidirectional server→client push).
  *
  * SSE-only since milestone 2b Phase A: the content-addressed `ContentCache`
  * has no invalidation operation — an edited leaf hashes to a new key and
  * misses naturally, so this handler's former cache half (ancestor-path
  * invalidation via `TreeCacheManager`) is gone. What remains is the tree
  * diff that tells browsers which nodes to re-fetch: the changed node plus
  * every ancestor up to the root (their aggregates changed too).
  *
  * The published event is still `SSEEvent.CacheInvalidated` — for a browser
  * the semantics are unchanged ("these nodes' figures are stale, re-fetch").
  *
  * Triggered by RiskTreeServiceLive mutations (update, delete). Future: also
  * by Irmin watch subscription (for external mutations).
  */
/**
  * Result of a mutation notification.
  *
  * @param invalidatedNodes Node IDs whose figures changed (nodes + ancestors)
  * @param subscribersNotified Number of SSE subscribers who received the event
  */
final case class InvalidationResult(
    invalidatedNodes: List[NodeId],
    subscribersNotified: Int
)

trait InvalidationHandler {

  /**
    * Handle a tree mutation by diffing old vs new tree and publishing a
    * single SSE event covering every node whose figures changed.
    *
    * Affected nodes are determined by:
    * - Added nodes: parent in new tree
    * - Removed nodes: parent in old tree (if it survives in new tree)
    * - Reparented nodes: old parent (if surviving) + new parent
    * - Content-changed nodes: the node itself
    * Reparent and content-change contributions are unioned ADDITIVELY — a
    * node that is both reparented and param-changed in one mutation yields
    * both contributions (TODO item 17: the pre-Phase-A exclusive if/else-if
    * dropped the content change in that case, which was the invalidation
    * bug; the cache no longer depends on this diff, but the SSE node list
    * must not repeat it).
    *
    * Each affected node expands to its ancestor path in the new tree, plus
    * removed nodes verbatim (browsers drop them after re-fetch).
    *
    * TODO: Review when implementing ADR-017 Phase 2 (Batch Operations / TreeOp
    * algebra). TreeOp operations carry explicit change semantics, which may
    * simplify or replace the old-vs-new tree diffing logic.
    *
    * @param oldTree Tree state before mutation
    * @param newTree Tree state after mutation (treeId derived from newTree.id)
    * @return Notification result containing affected nodes and subscriber count
    */
  def handleMutation(oldTree: RiskTree, newTree: RiskTree): UIO[InvalidationResult]

  /**
    * Handle tree deletion by publishing a CacheInvalidated event with all
    * node IDs. Browsers that receive this event will re-fetch and get
    * NOT_FOUND, signaling the tree is gone.
    *
    * @param tree The deleted tree (treeId and node IDs derived from tree)
    * @return Notification result containing all node IDs and subscriber count
    */
  def handleTreeDeletion(tree: RiskTree): UIO[InvalidationResult]
}

object InvalidationHandler {

  /**
    * Create live InvalidationHandler layer.
    */
  val live: ZLayer[SSEHub, Nothing, InvalidationHandler] =
    ZLayer.fromFunction(InvalidationHandlerLive(_))

  // Accessor methods for ZIO service pattern
  def handleMutation(oldTree: RiskTree, newTree: RiskTree): URIO[InvalidationHandler, InvalidationResult] =
    ZIO.serviceWithZIO[InvalidationHandler](_.handleMutation(oldTree, newTree))

  def handleTreeDeletion(tree: RiskTree): URIO[InvalidationHandler, InvalidationResult] =
    ZIO.serviceWithZIO[InvalidationHandler](_.handleTreeDeletion(tree))
}

/**
  * Live implementation of InvalidationHandler.
  */
final case class InvalidationHandlerLive(
    hub: SSEHub
) extends InvalidationHandler {

  /**
    * Publish a CacheInvalidated SSE event and return the InvalidationResult.
    * Shared by both handler methods to ensure consistent event shape and logging.
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

  override def handleMutation(oldTree: RiskTree, newTree: RiskTree): UIO[InvalidationResult] =
    // Defense-in-depth (ADR-010 §3): internal precondition — caller controls both trees
    require(oldTree.id == newTree.id,
      s"handleMutation precondition violated: oldTree.id=${oldTree.id} != newTree.id=${newTree.id}")

    val treeId = newTree.id
    val (affected, removed) = computeAffectedNodes(oldTree, newTree)

    if affected.isEmpty && removed.isEmpty then
      ZIO.succeed(InvalidationResult(invalidatedNodes = Nil, subscribersNotified = 0))
    else
      // Each affected node's aggregates up to the root changed with it
      val withAncestors = affected.flatMap(nid => newTree.index.ancestorPath(nid))
      val allAffectedNodes = (withAncestors ++ removed).toList
      publishInvalidation(treeId, allAffectedNodes, "Mutation notification")

  override def handleTreeDeletion(tree: RiskTree): UIO[InvalidationResult] =
    val allNodeIds = tree.index.nodes.keys.toList // browser re-fetches → gets NOT_FOUND
    publishInvalidation(tree.id, allNodeIds, "Tree deletion notification")

  // ========================================
  // Tree diff logic
  // ========================================

  /**
    * Compute affected nodes and removed nodes from old vs new tree diff.
    *
    * Affected nodes are the "entry points" for ancestor-path expansion:
    * - Added node → its parent in new tree
    * - Removed node → its parent in old tree (if parent survives in new tree)
    * - Reparented node → old parent (if surviving) + new parent
    * - Content-changed node → the node itself
    *
    * Reparent and content-change checks are independent and their
    * contributions are unioned ADDITIVELY (TODO item 17 — see trait doc).
    *
    * Removed nodes are reported verbatim so subscribers learn they are gone.
    *
    * @return (affectedNodes, removedNodes)
    */
  private def computeAffectedNodes(oldTree: RiskTree, newTree: RiskTree): (Set[NodeId], Set[NodeId]) = {
    val oldKeys = oldTree.index.nodes.keySet
    val newKeys = newTree.index.nodes.keySet

    val added   = newKeys -- oldKeys
    val removed = oldKeys -- newKeys
    val common  = oldKeys.intersect(newKeys)

    // Added nodes: their parent's aggregates changed
    val affectedFromAdded: Set[NodeId] =
      added.flatMap(nid => newTree.index.parents.get(nid))

    // Removed nodes: the surviving parent's aggregates changed.
    // Only include parents that still exist in the new tree — if the parent
    // was also removed, its own removal entry handles the surviving ancestor.
    val affectedFromRemoved: Set[NodeId] =
      removed.flatMap { nid =>
        oldTree.index.parents.get(nid).filter(newTree.index.nodes.contains)
      }

    // Common nodes: reparent and content change are INDEPENDENT contributions.
    // A node can be both reparented and param-changed in a single mutation —
    // it must then contribute its parents AND itself (additive union; the
    // exclusive if/else-if here was TODO item 17's bug).
    val affectedFromChanged: Set[NodeId] =
      common.flatMap { nid =>
        val oldParent = oldTree.index.parents.get(nid)
        val newParent = newTree.index.parents.get(nid)

        val fromReparent: Set[NodeId] =
          if oldParent != newParent then
            oldParent.filter(newTree.index.nodes.contains).toSet ++ newParent.toSet
          else Set.empty

        val fromContentChange: Set[NodeId] =
          if oldTree.index.nodes.get(nid) != newTree.index.nodes.get(nid) then Set(nid)
          else Set.empty

        fromReparent ++ fromContentChange
      }

    (affectedFromAdded ++ affectedFromRemoved ++ affectedFromChanged, removed)
  }
}

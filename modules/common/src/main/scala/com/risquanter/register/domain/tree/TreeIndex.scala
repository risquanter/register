package com.risquanter.register.domain.tree

import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.SafeId

/** Type alias for node ID - uses Iron-refined SafeId throughout */
type NodeId = SafeId.SafeId

/**
  * Parent-pointer index for O(depth) cache invalidation.
  *
  * This data structure enables efficient ancestor lookup when a node changes:
  * - O(1) node lookup by ID
  * - O(1) parent lookup
  * - O(depth) ancestor path construction
  *
  * Uses SafeId.SafeId (Iron-refined type) for all node identifiers per ADR-001.
  *
  * Example tree:
  * {{{
  *   root (ops-risk)
  *     ├── cyber
  *     └── it-risk
  *           ├── hardware
  *           └── software
  * }}}
  *
  * Index structure:
  * - nodes: { SafeId("ops-risk") → RiskPortfolio, SafeId("cyber") → RiskLeaf, ... }
  * - parents: { SafeId("cyber") → SafeId("ops-risk"), ... }
  * - children: { SafeId("ops-risk") → [SafeId("cyber"), SafeId("it-risk")], ... }
  *
  * When "hardware" changes:
  * 1. Look up SafeId("hardware") → parent = SafeId("it-risk")
  * 2. Look up SafeId("it-risk") → parent = SafeId("ops-risk")
  * 3. Look up SafeId("ops-risk") → parent = None (root)
  * 4. Invalidate cache for: [SafeId("hardware"), SafeId("it-risk"), SafeId("ops-risk")]
  *
  * @param nodes Map from node ID to RiskNode (all nodes in tree)
  * @param parents Map from child ID to parent ID (no entry for root)
  * @param children Map from parent ID to list of child IDs
  */
final case class TreeIndex(
    nodes: Map[NodeId, RiskNode],
    parents: Map[NodeId, NodeId],
    children: Map[NodeId, List[NodeId]]
) {

  /**
    * Get ancestor path from node to root (inclusive).
    *
    * Returns nodes in top-down order: [root, ..., grandparent, parent, nodeId]
    *
    * @param nodeId Starting node ID
    * @return List of ancestor IDs from root to nodeId, or empty list if nodeId not found
    */
  def ancestorPath(nodeId: NodeId): List[NodeId] = {
    def walk(current: NodeId, acc: List[NodeId]): List[NodeId] =
      if !nodes.contains(current) then acc // Node not in tree
      else
        val updated = current :: acc
        parents.get(current) match
          case Some(parentId) => walk(parentId, updated)
          case None           => updated // Reached root

    walk(nodeId, Nil) // Returns in top-down order (root first)
  }

  /**
    * Get all descendant IDs of a node (including the node itself).
    *
    * Useful for subtree operations.
    *
    * @param nodeId Root of subtree
    * @return Set of all descendant IDs (including nodeId)
    */
  def descendants(nodeId: NodeId): Set[NodeId] = {
    def walk(current: NodeId, acc: Set[NodeId]): Set[NodeId] =
      if !nodes.contains(current) then acc
      else
        val updated = acc + current
        children.get(current) match
          case Some(childIds) => childIds.foldLeft(updated)((a, c) => walk(c, a))
          case None           => updated

    walk(nodeId, Set.empty)
  }

  /**
    * Check if a node is an ancestor of another node.
    *
    * @param ancestorId Potential ancestor
    * @param descendantId Potential descendant
    * @return true if ancestorId is an ancestor of descendantId
    */
  def isAncestor(ancestorId: NodeId, descendantId: NodeId): Boolean =
    ancestorPath(descendantId).contains(ancestorId)

  /**
    * Get the root node ID (node with no parent).
    *
    * Assumes single-rooted tree.
    *
    * @return Root node ID, or None if tree is empty
    */
  def rootId: Option[NodeId] =
    nodes.keys.find(id => !parents.contains(id))

  /**
    * Get all leaf node IDs (nodes with no children).
    *
    * @return Set of leaf node IDs
    */
  def leafIds: Set[NodeId] =
    nodes.keys.filter(id => !children.contains(id) || children(id).isEmpty).toSet
}

object TreeIndex {

  /**
    * Build index from a flat collection of nodes.
    * 
    * Each node carries its own parentId field and portfolios carry childIds.
    * This is the primary constructor for the new flat node model.
    *
    * @param nodes Map from node ID to RiskNode (all nodes in tree)
    * @return TreeIndex with parent/child maps derived from node fields
    */
  def fromNodes(nodes: Map[NodeId, RiskNode]): TreeIndex = {
    // Parents map: directly from each node's parentId field
    val parents: Map[NodeId, NodeId] = nodes.collect {
      case (nodeId, node) if node.parentId.isDefined =>
        nodeId -> node.parentId.get
    }

    // Children map: directly from each portfolio's childIds field
    val children: Map[NodeId, List[NodeId]] = nodes.collect {
      case (nodeId, portfolio: RiskPortfolio) =>
        nodeId -> portfolio.childIds.toList
    }

    TreeIndex(nodes, parents, children)
  }

  /**
    * Build index from a sequence of nodes.
    * 
    * Convenience method that converts to Map first.
    *
    * @param nodes Sequence of RiskNodes
    * @return TreeIndex with all nodes indexed
    */
  def fromNodeSeq(nodes: Seq[RiskNode]): TreeIndex = {
    val nodeMap = nodes.map(n => extractSafeId(n) -> n).toMap
    fromNodes(nodeMap)
  }

  /**
    * Extract SafeId from a RiskNode.
    *
    * Both RiskLeaf and RiskPortfolio store safeId as SafeId.SafeId.
    */
  private def extractSafeId(node: RiskNode): NodeId =
    node match
      case leaf: RiskLeaf         => leaf.safeId
      case portfolio: RiskPortfolio => portfolio.safeId

  /**
    * Empty tree index.
    */
  val empty: TreeIndex = TreeIndex(Map.empty, Map.empty, Map.empty)
}

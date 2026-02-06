package com.risquanter.register.domain.tree

import com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{SafeId, NodeId}
import com.risquanter.register.domain.errors.{ValidationError, ValidationErrorCode}
import zio.prelude.Validation

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
    * Validates consistency between parentId and childIds:
    * - If a node has parentId=Some(pid), the parent must list it as a child
    * - If a portfolio lists a childId, that child must have parentId pointing back
    *
    * Returns accumulated validation errors per ADR-010 (errors as values).
    *
    * @param nodes Map from node ID to RiskNode (all nodes in tree)
    * @return Validation with accumulated errors or TreeIndex
    */
  def fromNodes(nodes: Map[NodeId, RiskNode]): Validation[ValidationError, TreeIndex] = {
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

    def combineValidations(validations: List[Validation[ValidationError, Unit]]): Validation[ValidationError, Unit] =
      validations.foldLeft[Validation[ValidationError, Unit]](Validation.succeed(())) { (acc, v) =>
        Validation.validateWith(acc, v)((_, _) => ())
      }

    val childToParentV: Validation[ValidationError, Unit] = combineValidations(
      children.toList.flatMap { case (parentId, childIds) =>
        childIds.map(childId => validateChildToParent(nodes, parentId, childId))
      }
    )

    val parentToChildV: Validation[ValidationError, Unit] = combineValidations(
      parents.toList.map { case (nodeId, parentId) =>
        validateParentToChild(nodes, nodeId, parentId)
      }
    )

    Validation.validateWith(childToParentV, parentToChildV)((_, _) => TreeIndex(nodes, parents, children))
  }

  /**
    * Build index from a sequence of nodes.
    * 
    * Convenience method that converts to Map first.
    *
    * @param nodes Sequence of RiskNodes
    * @return Validation with accumulated errors or TreeIndex
    */
  def fromNodeSeq(nodes: Seq[RiskNode]): Validation[ValidationError, TreeIndex] = {
    val nodeMap = nodes.map(n => extractSafeId(n) -> n).toMap
    fromNodes(nodeMap)
  }

  /**
    * Unsafe version for internal use where consistency is guaranteed.
    * 
    * Use only when nodes come from trusted sources (e.g., already validated).
    * Throws IllegalArgumentException if validation fails.
    *
    * @param nodes Map from node ID to RiskNode
    * @return TreeIndex
    * @throws IllegalArgumentException if parent-child relationships are inconsistent
    */
  def fromNodesUnsafe(nodes: Map[NodeId, RiskNode]): TreeIndex = {
    fromNodes(nodes).toEither match {
      case Right(index) => index
      case Left(errors) => 
        throw new IllegalArgumentException(
          s"TreeIndex invariant violated: ${errors.map(_.message).mkString("; ")}"
        )
    }
  }

  /**
    * Extract NodeId from a RiskNode.
    *
    * Wraps the raw SafeId.SafeId in NodeId for type-safe indexing.
    */
  private def extractSafeId(node: RiskNode): NodeId =
    node match
      case leaf: RiskLeaf         => NodeId(leaf.safeId)
      case portfolio: RiskPortfolio => NodeId(portfolio.safeId)

  private def validateChildToParent(
      nodes: Map[NodeId, RiskNode],
      parentId: NodeId,
      childId: NodeId
  ): Validation[ValidationError, Unit] =
    nodes.get(childId) match {
      case Some(child) if !child.parentId.contains(parentId) =>
        Validation.fail(
          ValidationError(
            field = s"nodes[${childId.value}].parentId",
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = s"Node '${childId.value}' is listed as child of '${parentId.value}' but has parentId=${child.parentId.map(_.value).getOrElse("None")}"
          )
        )
      case None =>
        Validation.fail(
          ValidationError(
            field = s"nodes[${parentId.value}].childIds",
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = s"Child '${childId.value}' referenced by '${parentId.value}' does not exist in nodes"
          )
        )
      case _ => Validation.succeed(())
    }

  private def validateParentToChild(
      nodes: Map[NodeId, RiskNode],
      nodeId: NodeId,
      parentId: NodeId
  ): Validation[ValidationError, Unit] =
    nodes.get(parentId) match {
      case Some(parent: RiskPortfolio) if !parent.childIds.contains(nodeId) =>
        Validation.fail(
          ValidationError(
            field = s"nodes[${nodeId.value}].parentId",
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = s"Node '${nodeId.value}' has parentId='${parentId.value}' but parent doesn't list it as child"
          )
        )
      case Some(_: RiskLeaf) =>
        Validation.fail(
          ValidationError(
            field = s"nodes[${nodeId.value}].parentId",
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = s"Node '${nodeId.value}' has parentId='${parentId.value}' but that node is a leaf, not a portfolio"
          )
        )
      case None =>
        Validation.fail(
          ValidationError(
            field = s"nodes[${nodeId.value}].parentId",
            code = ValidationErrorCode.CONSTRAINT_VIOLATION,
            message = s"Node '${nodeId.value}' has parentId='${parentId.value}' but parent doesn't exist in nodes"
          )
        )
      case _ => Validation.succeed(())
    }

  /**
    * Empty tree index.
    */
  val empty: TreeIndex = TreeIndex(Map.empty, Map.empty, Map.empty)
}

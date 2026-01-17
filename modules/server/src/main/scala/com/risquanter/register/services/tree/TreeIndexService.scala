package com.risquanter.register.services.tree

import zio.*
import com.risquanter.register.domain.data.RiskNode
import com.risquanter.register.domain.tree.{TreeIndex, NodeId}

/**
  * Service for managing TreeIndex.
  *
  * Provides operations to build and update the tree index as the risk tree changes.
  * Used by cache invalidation logic to determine affected ancestors.
  *
  * Uses NodeId (SafeId.SafeId) throughout per ADR-001.
  */
trait TreeIndexService {

  /**
    * Build index from a risk tree.
    *
    * @param root Root node of the tree
    * @return Updated TreeIndex
    */
  def buildIndex(root: RiskNode): UIO[TreeIndex]

  /**
    * Get current tree index.
    *
    * @return Current TreeIndex
    */
  def getIndex: UIO[TreeIndex]

  /**
    * Update the tree index with a new tree structure.
    *
    * @param root New root node
    */
  def updateIndex(root: RiskNode): UIO[Unit]

  /**
    * Get ancestor path for a node.
    *
    * Convenience method that delegates to TreeIndex.ancestorPath.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @return List of ancestor IDs from root to nodeId
    */
  def ancestorPath(nodeId: NodeId): UIO[List[NodeId]]

  /**
    * Check if tree contains a node.
    *
    * @param nodeId Node identifier (SafeId.SafeId)
    * @return true if node exists in tree
    */
  def contains(nodeId: NodeId): UIO[Boolean]

  /**
    * Get root node ID.
    *
    * @return Root node ID if tree is non-empty
    */
  def rootId: UIO[Option[NodeId]]
}

object TreeIndexService {

  /**
    * Create live implementation starting with empty index.
    *
    * @return ZLayer providing TreeIndexService
    */
  def layer: ZLayer[Any, Nothing, TreeIndexService] =
    ZLayer.fromZIO {
      for indexRef <- Ref.make(TreeIndex.empty)
      yield TreeIndexServiceLive(indexRef)
    }

  // Accessor methods for ZIO service pattern
  def buildIndex(root: RiskNode): URIO[TreeIndexService, TreeIndex] =
    ZIO.serviceWithZIO[TreeIndexService](_.buildIndex(root))

  def getIndex: URIO[TreeIndexService, TreeIndex] =
    ZIO.serviceWithZIO[TreeIndexService](_.getIndex)

  def updateIndex(root: RiskNode): URIO[TreeIndexService, Unit] =
    ZIO.serviceWithZIO[TreeIndexService](_.updateIndex(root))

  def ancestorPath(nodeId: NodeId): URIO[TreeIndexService, List[NodeId]] =
    ZIO.serviceWithZIO[TreeIndexService](_.ancestorPath(nodeId))

  def contains(nodeId: NodeId): URIO[TreeIndexService, Boolean] =
    ZIO.serviceWithZIO[TreeIndexService](_.contains(nodeId))

  def rootId: URIO[TreeIndexService, Option[NodeId]] =
    ZIO.serviceWithZIO[TreeIndexService](_.rootId)
}

/**
  * Live implementation of TreeIndexService.
  *
  * Maintains tree index in a Ref for thread-safe updates.
  *
  * @param indexRef Reference to current TreeIndex
  */
final class TreeIndexServiceLive(
    indexRef: Ref[TreeIndex]
) extends TreeIndexService {

  override def buildIndex(root: RiskNode): UIO[TreeIndex] =
    for
      index <- ZIO.succeed(TreeIndex.fromTree(root))
      _     <- ZIO.logDebug(s"Built tree index: ${index.nodes.size} nodes")
    yield index

  override def getIndex: UIO[TreeIndex] =
    indexRef.get

  override def updateIndex(root: RiskNode): UIO[Unit] =
    for
      index <- buildIndex(root)
      _     <- indexRef.set(index)
      _     <- ZIO.logInfo(s"Tree index updated: ${index.nodes.size} nodes, root=${index.rootId.map(_.value)}")
    yield ()

  override def ancestorPath(nodeId: NodeId): UIO[List[NodeId]] =
    for
      index <- indexRef.get
      path   = index.ancestorPath(nodeId)
    yield path

  override def contains(nodeId: NodeId): UIO[Boolean] =
    for
      index <- indexRef.get
      result = index.nodes.contains(nodeId)
    yield result

  override def rootId: UIO[Option[NodeId]] =
    for
      index <- indexRef.get
      root   = index.rootId
    yield root
}

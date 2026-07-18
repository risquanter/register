package com.risquanter.register.repositories

import zio.*
import zio.json.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoCastIron
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, WorkspaceId, BranchRef}
import com.risquanter.register.domain.errors.{RepositoryFailure, AppError, IrminError}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.repositories.model.TreeMetadata

import java.util.UUID

/** Irmin-backed implementation of RiskTreeRepository using per-node storage.
  *
  * Path conventions (per ADR-004a, workspace-scoped):
  * - Nodes: workspaces/{wsId}/risk-trees/{treeId}/nodes/{nodeId}
  * - Meta:  workspaces/{wsId}/risk-trees/{treeId}/meta
  */
final class RiskTreeRepositoryIrmin(irmin: IrminClient) extends RiskTreeRepository:

  override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: Option[BranchRef] = None): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${riskTree.id.value}"
    for
      _        <- ensureRootPresent(riskTree.rootId, riskTree.nodes)
      now      <- Clock.instant
      txn       = txnId()
      _        <- writeNodes(basePath, riskTree.nodes, txn, wsId, riskTree.id, branch)
      meta      = TreeMetadata(
                    id = riskTree.id,
                    name = riskTree.name,
                    rootId = riskTree.rootId,
                    seedVarHighWater = riskTree.seedVarHighWater,
                    schemaVersion = CurrentSchemaVersion,
                    createdAt = now,
                    updatedAt = now
                  )
      _        <- writeMeta(basePath, meta, txn, wsId, isUpdate = false, branch)
    yield riskTree

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: Option[BranchRef] = None): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
    for
      existing      <- getTreeWithMeta(wsId, id, branch)
      updatedTree    = op(existing.tree)
      _             <- ensureRootPresent(updatedTree.rootId, updatedTree.nodes)
      now           <- Clock.instant
      txn            = txnId()
      _             <- writeNodes(basePath, updatedTree.nodes, txn, wsId, id, branch)
      updatedMeta     = existing.meta.copy(
                          name = updatedTree.name,
                          rootId = updatedTree.rootId,
                          seedVarHighWater = updatedTree.seedVarHighWater,
                          schemaVersion = CurrentSchemaVersion,
                          updatedAt = now
                        )
      _             <- writeMeta(basePath, updatedMeta, txn, wsId, isUpdate = true, branch)
      obsoleteNodes  = obsoleteNodeIds(existing.tree.nodes, updatedTree.nodes)
      _             <- removeNodes(basePath, obsoleteNodes, txn, wsId, id, branch)
    yield updatedTree

  override def delete(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
    for
      existing <- getTreeWithMeta(wsId, id, branch)
      _        <- ZIO.foreachDiscard(existing.tree.nodes)(node => removeNode(basePath, node.id, deleteMessage(wsId, id, node.id), branch))
      _        <- removeMeta(basePath, deleteMetaMessage(wsId, id), branch)
    yield existing.tree

  override def getById(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef] = None): Task[Option[RiskTree]] =
    loadTree(wsId, id, branch).map(_.map(_.tree))

  override def getAllForWorkspace(wsId: WorkspaceId, branch: Option[BranchRef] = None): Task[List[Either[RepositoryFailure, RiskTree]]] =
    val root = IrminPath.unsafeFrom(s"workspaces/${wsId.value}/risk-trees")
    handleIrmin(irmin.list(root, branch)).flatMap { treeIds =>
      ZIO.foreach(treeIds)(treeIdPath =>
        for
          treeId  <- parseTreeId(treeIdPath.value)
          loaded  <- loadTree(wsId, treeId, branch).either
        yield loaded match
          case Right(Some(value)) => Right(value.tree)
          case Right(None)        => Left(RepositoryFailure(s"Tree ${treeIdPath.value} not found (missing meta and nodes)"))
          case Left(err: RepositoryFailure) => Left(err)
          case Left(err: AppError)          => Left(RepositoryFailure(err.getMessage))
          case Left(err)                    => Left(RepositoryFailure(err.getMessage))
      )
    }

  // ----------------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------------

  private def writeMeta(basePath: String, meta: TreeMetadata, txn: String, wsId: WorkspaceId, isUpdate: Boolean, branch: Option[BranchRef]): Task[Unit] =
    val message = if isUpdate then updateMetaMessage(wsId, meta.id, txn) else createMetaMessage(wsId, meta.id, txn)
    handleIrmin(irmin.set(IrminPath.unsafeFrom(s"$basePath/meta"), meta.toJson, message, branch)).unit

  private def writeNodes(basePath: String, nodes: Seq[RiskNode], txn: String, wsId: WorkspaceId, treeId: TreeId, branch: Option[BranchRef]): Task[Unit] =
    ZIO.foreachDiscard(nodes)(node => writeNode(basePath, node, txn, wsId, treeId, branch))

  private def writeNode(basePath: String, node: RiskNode, txn: String, wsId: WorkspaceId, treeId: TreeId, branch: Option[BranchRef]): Task[Unit] =
    val json = node match
      case leaf: RiskLeaf           => leaf.toJson
      case portfolio: RiskPortfolio => portfolio.toJson
    handleIrmin(irmin.set(IrminPath.unsafeFrom(s"$basePath/nodes/${node.id.value}"), json, upsertNodeMessage(wsId, treeId, node.id, txn), branch)).unit

  private def removeNodes(basePath: String, nodeIds: Set[NodeId], txn: String, wsId: WorkspaceId, treeId: TreeId, branch: Option[BranchRef]): Task[Unit] =
    ZIO.foreachDiscard(nodeIds)(id => removeNode(basePath, id, deleteNodeMessage(wsId, treeId, id, txn), branch))

  private def removeNode(basePath: String, nodeId: NodeId, message: String, branch: Option[BranchRef]): Task[Unit] =
    handleIrmin(irmin.remove(IrminPath.unsafeFrom(s"$basePath/nodes/${nodeId.value}"), message, branch)).unit

  private def removeMeta(basePath: String, message: String, branch: Option[BranchRef]): Task[Unit] =
    handleIrmin(irmin.remove(IrminPath.unsafeFrom(s"$basePath/meta"), message, branch)).unit

  private def decodeMeta(json: String): Task[TreeMetadata] =
    ZIO.fromEither(json.fromJson[TreeMetadata].left.map(err => RepositoryFailure(s"Decode meta failed: $err")))

  private def readNodes(basePath: String, branch: Option[BranchRef]): Task[Seq[RiskNode]] =
    val nodePrefix = IrminPath.unsafeFrom(s"$basePath/nodes")
    for
      childNames <- handleIrmin(irmin.list(nodePrefix, branch))
      nodes      <- ZIO.foreach(childNames) { child =>
                      val fullPath = IrminPath.unsafeFrom(s"${nodePrefix.value}/${child.value}")
                      handleIrmin(irmin.get(fullPath, branch)).flatMap {
                        case Some(json) =>
                          val decoded: Either[String, RiskNode] =
                            json.fromJson[RiskLeaf].map(node => node: RiskNode)
                              .orElse(json.fromJson[RiskPortfolio].map(node => node: RiskNode))
                          ZIO.fromEither(decoded.left.map(err => RepositoryFailure(s"Decode node ${child.value}: $err")))
                        case None =>
                          ZIO.fail(RepositoryFailure(s"Missing node value at ${fullPath.value}"))
                      }
                    }
    yield nodes

  private def rebuildTree(meta: TreeMetadata, nodes: Seq[RiskNode]): Task[RiskTree] =
    if nodes.isEmpty then
      ZIO.fail(RepositoryFailure(s"No nodes found for tree ${meta.id}"))
    else
      // Route through the smart constructor so every tree invariant (structure,
      // rootId, seedVarId distinctness, high-water >= max) also holds on load.
      ZIO.fromEither(
        RiskTree
          .fromNodes(meta.id, meta.name, nodes, meta.rootId, Some(meta.seedVarHighWater))
          .toEither
          .left.map(errors => RepositoryFailure(errors.map(e => s"[${e.field}] ${e.message}").mkString("; ")))
      )

  private def parseTreeId(raw: String): Task[TreeId] =
    ZIO.fromEither(TreeId.fromString(raw)).mapError(errs => RepositoryFailure(errs.map(_.message).mkString("; ")))

  private def loadTree(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef]): Task[Option[TreeWithMeta]] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
    val metaPath = IrminPath.unsafeFrom(s"$basePath/meta")
    val nodePrefix = IrminPath.unsafeFrom(s"$basePath/nodes")
    for
      maybeMetaJson <- handleIrmin(irmin.get(metaPath, branch))
      maybeMeta <- maybeMetaJson match
        case None =>
          handleIrmin(irmin.list(nodePrefix, branch)).flatMap { children =>
            if children.nonEmpty then ZIO.fail(RepositoryFailure(s"Metadata missing for tree $id but nodes exist"))
            else ZIO.succeed(None)
          }
        case Some(json) => decodeMeta(json).map(Some(_))
      result <- maybeMeta match
        case None => ZIO.succeed(None)
        case Some(meta) =>
          for
            nodes <- readNodes(basePath, branch)
            tree  <- rebuildTree(meta, nodes)
          yield Some(TreeWithMeta(meta, tree))
    yield result

  private def getTreeWithMeta(wsId: WorkspaceId, id: TreeId, branch: Option[BranchRef]): Task[TreeWithMeta] =
    loadTree(wsId, id, branch).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(RepositoryFailure(s"RiskTree $id not found in workspace ${wsId.value}"))
    }

  private def ensureRootPresent(rootId: NodeId, nodes: Seq[RiskNode]): Task[Unit] =
    if nodes.exists(_.id == rootId) then ZIO.unit
    else ZIO.fail(RepositoryFailure(s"Root ${rootId.value} not found in provided nodes"))

  private def obsoleteNodeIds(previous: Seq[RiskNode], current: Seq[RiskNode]): Set[NodeId] =
    val before = previous.map(_.id).toSet
    val after  = current.map(_.id).toSet
    before.diff(after)

  private def txnId(): String = UUID.randomUUID().toString.take(8)

  private def upsertNodeMessage(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, txn: String): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:update:$txn:set-node:${nodeId.value}"

  private def deleteNodeMessage(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, txn: String): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:update:$txn:remove-node:${nodeId.value}"

  private def deleteMessage(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:delete:remove-node:${nodeId.value}"

  private def createMetaMessage(wsId: WorkspaceId, treeId: TreeId, txn: String): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:create:$txn:meta"

  private def updateMetaMessage(wsId: WorkspaceId, treeId: TreeId, txn: String): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:update:$txn:meta"

  private def deleteMetaMessage(wsId: WorkspaceId, treeId: TreeId): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:delete:meta"

  private def handleIrmin[A](effect: IO[IrminError, A]): Task[A] =
    effect.mapError { err =>
      val reason = Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.toString)
      RepositoryFailure(reason)
    }

private final case class TreeWithMeta(meta: TreeMetadata, tree: RiskTree)

// v2: nodes carry seedVarId, meta carries seedVarHighWater (PLAN-SEED-IDENTITY).
// v1 stores are wiped, not migrated (plan §12.1) — no legacy decode path.
private val CurrentSchemaVersion: Int = 2

object RiskTreeRepositoryIrmin:
  val layer: ZLayer[IrminClient, Nothing, RiskTreeRepository] =
    ZLayer.fromFunction(new RiskTreeRepositoryIrmin(_))

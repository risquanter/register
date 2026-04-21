package com.risquanter.register.repositories

import zio.*
import zio.json.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoCastIron
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{SafeName, TreeId, NodeId, WorkspaceId}
import com.risquanter.register.domain.data.RiskTree.{safeNameEncoder, safeNameDecoder}
import com.risquanter.register.domain.tree.TreeIndex
import com.risquanter.register.domain.errors.{RepositoryFailure, AppError, IrminError}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.repositories.model.TreeMetadata

import java.time.Instant
import java.util.UUID

/** Irmin-backed implementation of RiskTreeRepository using per-node storage.
  *
  * Path conventions (per ADR-004a, workspace-scoped):
  * - Nodes: workspaces/{wsId}/risk-trees/{treeId}/nodes/{nodeId}
  * - Meta:  workspaces/{wsId}/risk-trees/{treeId}/meta
  */
final class RiskTreeRepositoryIrmin(irmin: IrminClient) extends RiskTreeRepository:

  override def create(wsId: WorkspaceId, riskTree: RiskTree): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${riskTree.id.value}"
    for
      _        <- ensureRootPresent(riskTree.rootId, riskTree.nodes)
      now      <- Clock.instant
      txn       = txnId()
      _        <- writeNodes(basePath, riskTree.nodes, txn, wsId, riskTree.id)
      meta      = TreeMetadata(
                    id = riskTree.id,
                    name = riskTree.name,
                    rootId = riskTree.rootId,
                    schemaVersion = CurrentSchemaVersion,
                    createdAt = now,
                    updatedAt = now
                  )
      _        <- writeMeta(basePath, meta, txn, wsId, isUpdate = false)
    yield riskTree

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
    for
      existing      <- getTreeWithMeta(wsId, id)
      updatedTree    = op(existing.tree)
      _             <- ensureRootPresent(updatedTree.rootId, updatedTree.nodes)
      now           <- Clock.instant
      txn            = txnId()
      _             <- writeNodes(basePath, updatedTree.nodes, txn, wsId, id)
      updatedMeta     = existing.meta.copy(
                          name = updatedTree.name,
                          rootId = updatedTree.rootId,
                          schemaVersion = CurrentSchemaVersion,
                          updatedAt = now
                        )
      _             <- writeMeta(basePath, updatedMeta, txn, wsId, isUpdate = true)
      obsoleteNodes  = obsoleteNodeIds(existing.tree.nodes, updatedTree.nodes)
      _             <- removeNodes(basePath, obsoleteNodes, txn, wsId, id)
    yield updatedTree

  override def delete(wsId: WorkspaceId, id: TreeId): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
    for
      existing <- getTreeWithMeta(wsId, id)
      _        <- ZIO.foreachDiscard(existing.tree.nodes)(node => removeNode(basePath, node.id, deleteMessage(wsId, id, node.id)))
      _        <- removeMeta(basePath, deleteMetaMessage(wsId, id))
    yield existing.tree

  override def getById(wsId: WorkspaceId, id: TreeId): Task[Option[RiskTree]] =
    loadTree(wsId, id).map(_.map(_.tree))

  override def getAllForWorkspace(wsId: WorkspaceId): Task[List[Either[RepositoryFailure, RiskTree]]] =
    val root = IrminPath.unsafeFrom(s"workspaces/${wsId.value}/risk-trees")
    handleIrmin(irmin.list(root)).flatMap { treeIds =>
      ZIO.foreach(treeIds)(treeIdPath =>
        for
          treeId  <- parseTreeId(treeIdPath.value)
          loaded  <- loadTree(wsId, treeId).either
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

  private def writeMeta(basePath: String, meta: TreeMetadata, txn: String, wsId: WorkspaceId, isUpdate: Boolean): Task[Unit] =
    val message = if isUpdate then updateMetaMessage(wsId, meta.id, txn) else createMetaMessage(wsId, meta.id, txn)
    handleIrmin(irmin.set(IrminPath.unsafeFrom(s"$basePath/meta"), meta.toJson, message)).unit

  private def writeNodes(basePath: String, nodes: Seq[RiskNode], txn: String, wsId: WorkspaceId, treeId: TreeId): Task[Unit] =
    ZIO.foreachDiscard(nodes)(node => writeNode(basePath, node, txn, wsId, treeId))

  private def writeNode(basePath: String, node: RiskNode, txn: String, wsId: WorkspaceId, treeId: TreeId): Task[Unit] =
    val json = node match
      case leaf: RiskLeaf           => leaf.toJson
      case portfolio: RiskPortfolio => portfolio.toJson
    handleIrmin(irmin.set(IrminPath.unsafeFrom(s"$basePath/nodes/${node.id.value}"), json, upsertNodeMessage(wsId, treeId, node.id, txn))).unit

  private def removeNodes(basePath: String, nodeIds: Set[NodeId], txn: String, wsId: WorkspaceId, treeId: TreeId): Task[Unit] =
    ZIO.foreachDiscard(nodeIds)(id => removeNode(basePath, id, deleteNodeMessage(wsId, treeId, id, txn)))

  private def removeNode(basePath: String, nodeId: NodeId, message: String): Task[Unit] =
    handleIrmin(irmin.remove(IrminPath.unsafeFrom(s"$basePath/nodes/${nodeId.value}"), message)).unit

  private def removeMeta(basePath: String, message: String): Task[Unit] =
    handleIrmin(irmin.remove(IrminPath.unsafeFrom(s"$basePath/meta"), message)).unit

  private def decodeMeta(json: String): Task[TreeMetadata] =
    ZIO.fromEither(json.fromJson[TreeMetadata].left.map(err => RepositoryFailure(s"Decode meta failed: $err")))

  private def decodeLegacyMeta(json: String, treeId: TreeId): Task[TreeMetadata] =
    ZIO.fromEither(json.fromJson[Meta].left.map(err => RepositoryFailure(s"Decode legacy meta failed: $err"))).map { meta =>
      val now = Instant.EPOCH
      TreeMetadata(
        id = treeId,
        name = meta.name,
        rootId = meta.rootId,
        schemaVersion = CurrentSchemaVersion,
        createdAt = now,
        updatedAt = now
      )
    }

  private def readNodes(basePath: String): Task[Seq[RiskNode]] =
    val nodePrefix = IrminPath.unsafeFrom(s"$basePath/nodes")
    for
      childNames <- handleIrmin(irmin.list(nodePrefix))
      nodes      <- ZIO.foreach(childNames) { child =>
                      val fullPath = IrminPath.unsafeFrom(s"${nodePrefix.value}/${child.value}")
                      handleIrmin(irmin.get(fullPath)).flatMap {
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
      ZIO.fromEither(TreeIndex.fromNodeSeq(nodes).toEither.left.map(errors => RepositoryFailure(errors.map(_.message).mkString("; ")))).map { index =>
        RiskTree(id = meta.id, name = meta.name, rootId = meta.rootId, nodes = nodes, index = index)
      }

  private def getRequired(wsId: WorkspaceId, id: TreeId): Task[TreeWithMeta] =
    getTreeWithMeta(wsId, id).either.flatMap {
      case Right(value) => ZIO.succeed(value)
      case Left(err)    => ZIO.fail(err)
    }

  private def parseTreeId(raw: String): Task[TreeId] =
    ZIO.fromEither(TreeId.fromString(raw)).mapError(errs => RepositoryFailure(errs.map(_.message).mkString("; ")))

  private def loadTree(wsId: WorkspaceId, id: TreeId): Task[Option[TreeWithMeta]] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
    val metaPath = IrminPath.unsafeFrom(s"$basePath/meta")
    val nodePrefix = IrminPath.unsafeFrom(s"$basePath/nodes")
    for
      maybeMetaJson <- handleIrmin(irmin.get(metaPath))
      maybeMeta <- maybeMetaJson match
        case None =>
          handleIrmin(irmin.list(nodePrefix)).flatMap { children =>
            if children.nonEmpty then ZIO.fail(RepositoryFailure(s"Metadata missing for tree $id but nodes exist"))
            else ZIO.succeed(None)
          }
        case Some(json) => decodeMetaWithFallback(json, id).map(Some(_))
      result <- maybeMeta match
        case None => ZIO.succeed(None)
        case Some(meta) =>
          for
            nodes <- readNodes(basePath)
            tree  <- rebuildTree(meta, nodes)
          yield Some(TreeWithMeta(meta, tree))
    yield result

  private def getTreeWithMeta(wsId: WorkspaceId, id: TreeId): Task[TreeWithMeta] =
    loadTree(wsId, id).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(RepositoryFailure(s"RiskTree $id not found in workspace ${wsId.value}"))
    }

  private def decodeMetaWithFallback(json: String, treeId: TreeId): Task[TreeMetadata] =
    decodeMeta(json).catchSome { case _ => decodeLegacyMeta(json, treeId) }

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

private final case class Meta(name: SafeName.SafeName, rootId: NodeId)
object Meta:
  given JsonCodec[Meta] = DeriveJsonCodec.gen[Meta]

private final case class TreeWithMeta(meta: TreeMetadata, tree: RiskTree)

private val CurrentSchemaVersion: Int = 1

object RiskTreeRepositoryIrmin:
  val layer: ZLayer[IrminClient, Nothing, RiskTreeRepository] =
    ZLayer.fromFunction(new RiskTreeRepositoryIrmin(_))

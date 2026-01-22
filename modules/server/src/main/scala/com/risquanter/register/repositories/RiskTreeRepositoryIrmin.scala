package com.risquanter.register.repositories

import zio.*
import zio.json.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoCastIron
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{NonNegativeLong, SafeName}
import com.risquanter.register.domain.data.RiskTree.{safeNameEncoder, safeNameDecoder, nodeIdEncoder, nodeIdDecoder}
import com.risquanter.register.domain.tree.{NodeId, TreeIndex}
import com.risquanter.register.domain.errors.{RepositoryFailure, AppError, IrminError}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.repositories.model.TreeMetadata

import java.time.Instant
import java.util.UUID

/** Irmin-backed implementation of RiskTreeRepository using per-node storage.
  *
  * Path conventions (per ADR-004a):
  * - Nodes: risk-trees/{treeId}/nodes/{nodeId}
  * - Meta:  risk-trees/{treeId}/meta
  */
final class RiskTreeRepositoryIrmin(irmin: IrminClient) extends RiskTreeRepository:

  override def create(riskTree: RiskTree): Task[RiskTree] =
    val basePath = s"risk-trees/${riskTree.id}"
    for
      _        <- ensureRootPresent(riskTree.rootId, riskTree.nodes)
      now      <- Clock.instant
      txn       = txnId()
      _        <- writeNodes(basePath, riskTree.nodes, txn, riskTree.id)
      meta      = TreeMetadata(
                    id = riskTree.id,
                    name = riskTree.name,
                    rootId = riskTree.rootId,
                    schemaVersion = CurrentSchemaVersion,
                    createdAt = now,
                    updatedAt = now
                  )
      _        <- writeMeta(basePath, meta, txn, isUpdate = false)
    yield riskTree

  override def update(id: NonNegativeLong, op: RiskTree => RiskTree): Task[RiskTree] =
    val basePath = s"risk-trees/$id"
    for
      existing      <- getTreeWithMeta(id)
      updatedTree    = op(existing.tree)
      _             <- ensureRootPresent(updatedTree.rootId, updatedTree.nodes)
      now           <- Clock.instant
      txn            = txnId()
      _             <- writeNodes(basePath, updatedTree.nodes, txn, id)
      updatedMeta     = existing.meta.copy(
                          name = updatedTree.name,
                          rootId = updatedTree.rootId,
                          schemaVersion = CurrentSchemaVersion,
                          updatedAt = now
                        )
      _             <- writeMeta(basePath, updatedMeta, txn, isUpdate = true)
      obsoleteNodes  = obsoleteNodeIds(existing.tree.nodes, updatedTree.nodes)
      _             <- removeNodes(basePath, obsoleteNodes, txn, id)
    yield updatedTree

  override def delete(id: NonNegativeLong): Task[RiskTree] =
    val basePath = s"risk-trees/$id"
    for
      existing <- getTreeWithMeta(id)
      _        <- ZIO.foreachDiscard(existing.tree.nodes)(node => removeNode(basePath, node.id, deleteMessage(id, node.id)))
      _        <- removeMeta(basePath, deleteMetaMessage(id))
    yield existing.tree

  override def getById(id: NonNegativeLong): Task[Option[RiskTree]] =
    loadTree(id).map(_.map(_.tree))

  override def getAll: Task[List[Either[RepositoryFailure, RiskTree]]] =
    val root = IrminPath.unsafeFrom("risk-trees")
    handleIrmin(irmin.list(root)).flatMap { treeIds =>
      ZIO.foreach(treeIds)(treeIdPath =>
        for
          treeId  <- parseTreeId(treeIdPath.value)
          loaded  <- loadTree(treeId).either
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

  private def writeMeta(basePath: String, meta: TreeMetadata, txn: String, isUpdate: Boolean): Task[Unit] =
    val message = if isUpdate then updateMetaMessage(meta.id, txn) else createMetaMessage(meta.id, txn)
    handleIrmin(irmin.set(IrminPath.unsafeFrom(s"$basePath/meta"), meta.toJson, message)).unit

  private def writeNodes(basePath: String, nodes: Seq[RiskNode], txn: String, treeId: NonNegativeLong): Task[Unit] =
    ZIO.foreachDiscard(nodes)(node => writeNode(basePath, node, txn, treeId))

  private def writeNode(basePath: String, node: RiskNode, txn: String, treeId: NonNegativeLong): Task[Unit] =
    val json = node match
      case leaf: RiskLeaf           => leaf.toJson
      case portfolio: RiskPortfolio => portfolio.toJson
    handleIrmin(irmin.set(IrminPath.unsafeFrom(s"$basePath/nodes/${node.id.value}"), json, upsertNodeMessage(treeId, node.id, txn))).unit

  private def removeNodes(basePath: String, nodeIds: Set[NodeId], txn: String, treeId: NonNegativeLong): Task[Unit] =
    ZIO.foreachDiscard(nodeIds)(id => removeNode(basePath, id, deleteNodeMessage(treeId, id, txn)))

  private def removeNode(basePath: String, nodeId: NodeId, message: String): Task[Unit] =
    handleIrmin(irmin.remove(IrminPath.unsafeFrom(s"$basePath/nodes/${nodeId.value}"), message)).unit

  private def removeMeta(basePath: String, message: String): Task[Unit] =
    handleIrmin(irmin.remove(IrminPath.unsafeFrom(s"$basePath/meta"), message)).unit

  private def decodeMeta(json: String): Task[TreeMetadata] =
    ZIO.fromEither(json.fromJson[TreeMetadata].left.map(err => RepositoryFailure(s"Decode meta failed: $err")))

  private def decodeLegacyMeta(json: String, treeId: NonNegativeLong): Task[TreeMetadata] =
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
                          ZIO.fromEither(json.fromJson[RiskNode].left.map(err => RepositoryFailure(s"Decode node ${child.value}: $err")))
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

  private def getRequired(id: NonNegativeLong): Task[TreeWithMeta] =
    getTreeWithMeta(id).either.flatMap {
      case Right(value) => ZIO.succeed(value)
      case Left(err)    => ZIO.fail(err)
    }

  private def parseTreeId(raw: String): Task[NonNegativeLong] =
    for
      asLong <- ZIO.fromOption(raw.toLongOption).orElseFail(RepositoryFailure(s"Invalid tree id '$raw'"))
      refined <- ZIO.fromEither(asLong.refineEither[constraint.numeric.GreaterEqual[0L]].left.map(err => RepositoryFailure(s"Invalid tree id '$raw': $err")))
    yield refined

  private def loadTree(id: NonNegativeLong): Task[Option[TreeWithMeta]] =
    val basePath = s"risk-trees/$id"
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

  private def getTreeWithMeta(id: NonNegativeLong): Task[TreeWithMeta] =
    loadTree(id).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(RepositoryFailure(s"RiskTree $id not found"))
    }

  private def decodeMetaWithFallback(json: String, treeId: NonNegativeLong): Task[TreeMetadata] =
    decodeMeta(json).catchSome { case _ => decodeLegacyMeta(json, treeId) }

  private def ensureRootPresent(rootId: NodeId, nodes: Seq[RiskNode]): Task[Unit] =
    if nodes.exists(_.id == rootId) then ZIO.unit
    else ZIO.fail(RepositoryFailure(s"Root ${rootId.value} not found in provided nodes"))

  private def obsoleteNodeIds(previous: Seq[RiskNode], current: Seq[RiskNode]): Set[NodeId] =
    val before = previous.map(_.id).toSet
    val after  = current.map(_.id).toSet
    before.diff(after)

  private def txnId(): String = UUID.randomUUID().toString.take(8)

  private def upsertNodeMessage(treeId: NonNegativeLong, nodeId: NodeId, txn: String): String =
    s"risk-tree:$treeId:update:$txn:set-node:${nodeId.value}"

  private def deleteNodeMessage(treeId: NonNegativeLong, nodeId: NodeId, txn: String): String =
    s"risk-tree:$treeId:update:$txn:remove-node:${nodeId.value}"

  private def deleteMessage(treeId: NonNegativeLong, nodeId: NodeId): String =
    s"risk-tree:$treeId:delete:remove-node:${nodeId.value}"

  private def createMetaMessage(treeId: NonNegativeLong, txn: String): String =
    s"risk-tree:$treeId:create:$txn:meta"

  private def updateMetaMessage(treeId: NonNegativeLong, txn: String): String =
    s"risk-tree:$treeId:update:$txn:meta"

  private def deleteMetaMessage(treeId: NonNegativeLong): String =
    s"risk-tree:$treeId:delete:meta"

  private def handleIrmin[A](effect: IO[IrminError, A]): Task[A] =
    effect.mapError { err => RepositoryFailure(err.getMessage) }

private final case class Meta(name: SafeName.SafeName, rootId: NodeId)
object Meta:
  given JsonCodec[Meta] = DeriveJsonCodec.gen[Meta]

private final case class TreeWithMeta(meta: TreeMetadata, tree: RiskTree)

private val CurrentSchemaVersion: Int = 1

object RiskTreeRepositoryIrmin:
  val layer: ZLayer[IrminClient, Nothing, RiskTreeRepository] =
    ZLayer.fromFunction(new RiskTreeRepositoryIrmin(_))

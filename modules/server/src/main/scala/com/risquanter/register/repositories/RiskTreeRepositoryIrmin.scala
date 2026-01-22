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
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.domain.errors.{RepositoryFailure, AppError, IrminError}

/** Irmin-backed implementation of RiskTreeRepository using per-node storage.
  *
  * Path conventions (per ADR-004a):
  * - Nodes: risk-trees/{treeId}/nodes/{nodeId}
  * - Meta:  risk-trees/{treeId}/meta
  */
final class RiskTreeRepositoryIrmin(irmin: IrminClient) extends RiskTreeRepository:

  override def create(riskTree: RiskTree): Task[RiskTree] =
    val basePath = s"risk-trees/${riskTree.id}" // treeId is NonNegativeLong refined
    for
      _ <- writeMeta(basePath, riskTree)
      _ <- ZIO.foreachDiscard(riskTree.nodes)(node => writeNode(basePath, node))
    yield riskTree

  override def update(id: NonNegativeLong, op: RiskTree => RiskTree): Task[RiskTree] =
    for
      tree    <- getRequired(id)
      updated  = op(tree)
      _       <- delete(id) // remove old tree paths before writing updated
      _       <- create(updated)
    yield updated

  override def delete(id: NonNegativeLong): Task[RiskTree] =
    val basePath = s"risk-trees/$id"
    for
      tree <- getRequired(id)
      // Remove nodes then meta
      _    <- ZIO.foreachDiscard(tree.nodes)(node => removeNode(basePath, node.id))
      _    <- removeMeta(basePath)
    yield tree

  override def getById(id: NonNegativeLong): Task[Option[RiskTree]] =
    val basePath = s"risk-trees/$id"
    for
      maybeMetaJson  <- handleIrmin(irmin.get(IrminPath.unsafeFrom(s"$basePath/meta")))
      meta           <- ZIO.foreach(maybeMetaJson)(decodeMeta)
      result <- meta match
        case None => ZIO.succeed(None)
        case Some((name, rootId)) =>
          for
            nodesMap <- readNodes(basePath)
            tree     <- rebuildTree(id, name, rootId, nodesMap)
          yield Some(tree)
    yield result

  override def getAll: Task[List[Either[RepositoryFailure, RiskTree]]] =
    val root = IrminPath.unsafeFrom("risk-trees")
    handleIrmin(irmin.list(root)).flatMap { treeIds =>
      ZIO.foreach(treeIds)(treeIdPath =>
        for
          treeId   <- parseTreeId(treeIdPath.value)
          treeOpt  <- getById(treeId).either
        yield treeOpt match
          case Right(Some(tree)) => Right(tree)
          case Right(None)       => Left(RepositoryFailure(s"Tree ${treeIdPath.value} not found (missing meta)"))
          case Left(err: RepositoryFailure) => Left(err)
          case Left(err: AppError)          => Left(RepositoryFailure(err.getMessage))
          case Left(err)                    => Left(RepositoryFailure(err.getMessage))
      )
    }

  // ----------------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------------

  private def writeMeta(basePath: String, riskTree: RiskTree): Task[Unit] =
    val metaJson = Meta(name = riskTree.name, rootId = riskTree.rootId).toJson
    handleIrmin(irmin.set(IrminPath.unsafeFrom(s"$basePath/meta"), metaJson, s"create tree ${riskTree.id}")).unit

  private def writeNode(basePath: String, node: RiskNode): Task[Unit] =
    val json = node match
      case leaf: RiskLeaf           => leaf.toJson
      case portfolio: RiskPortfolio => portfolio.toJson
    handleIrmin(irmin.set(IrminPath.unsafeFrom(s"$basePath/nodes/${node.id.value}"), json, s"upsert node ${node.id.value}")).unit

  private def removeNode(basePath: String, nodeId: NodeId): Task[Unit] =
    handleIrmin(irmin.remove(IrminPath.unsafeFrom(s"$basePath/nodes/${nodeId.value}"), s"delete node ${nodeId.value}")).unit

  private def removeMeta(basePath: String): Task[Unit] =
    handleIrmin(irmin.remove(IrminPath.unsafeFrom(s"$basePath/meta"), s"delete tree meta")).unit

  private def decodeMeta(json: String): Task[(SafeName.SafeName, NodeId)] =
    ZIO.fromEither(json.fromJson[Meta].left.map(err => RepositoryFailure(s"Decode meta failed: $err"))).map(meta => (meta.name, meta.rootId))

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

  private def rebuildTree(id: NonNegativeLong, name: SafeName.SafeName, rootId: NodeId, nodes: Seq[RiskNode]): Task[RiskTree] =
    if nodes.isEmpty then
      ZIO.fail(RepositoryFailure(s"No nodes found for tree $id"))
    else
      ZIO.fromEither(TreeIndex.fromNodeSeq(nodes).toEither.left.map(errors => RepositoryFailure(errors.map(_.message).mkString("; ")))).map { index =>
        RiskTree(id = id, name = name, rootId = rootId, nodes = nodes, index = index)
      }

  private def getRequired(id: NonNegativeLong): Task[RiskTree] =
    getById(id).flatMap {
      case Some(tree) => ZIO.succeed(tree)
      case None       => ZIO.fail(RepositoryFailure(s"RiskTree $id not found"))
    }

  private def parseTreeId(raw: String): Task[NonNegativeLong] =
    for
      asLong <- ZIO.fromOption(raw.toLongOption).orElseFail(RepositoryFailure(s"Invalid tree id '$raw'"))
      refined <- ZIO.fromEither(asLong.refineEither[constraint.numeric.GreaterEqual[0L]].left.map(err => RepositoryFailure(s"Invalid tree id '$raw': $err")))
    yield refined

  private def handleIrmin[A](effect: IO[IrminError, A]): Task[A] =
    effect.mapError { err => RepositoryFailure(err.getMessage) }

private final case class Meta(name: SafeName.SafeName, rootId: NodeId)
object Meta:
  given JsonCodec[Meta] = DeriveJsonCodec.gen[Meta]

object RiskTreeRepositoryIrmin:
  val layer: ZLayer[IrminClient, Nothing, RiskTreeRepository] =
    ZLayer.fromFunction(new RiskTreeRepositoryIrmin(_))

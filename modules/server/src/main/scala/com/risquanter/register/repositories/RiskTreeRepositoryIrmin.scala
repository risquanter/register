package com.risquanter.register.repositories

import zio.*
import zio.json.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoCastIron
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, WorkspaceId, BranchRef}
import com.risquanter.register.domain.errors.{RepositoryFailure, AppError, IrminError}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminTreeEntry}
import com.risquanter.register.repositories.model.TreeMetadata

/** Irmin-backed implementation of RiskTreeRepository using per-node storage.
  *
  * Path conventions (per ADR-004a, workspace-scoped):
  * - Nodes: workspaces/{wsId}/risk-trees/{treeId}/nodes/{nodeId}
  * - Meta:  workspaces/{wsId}/risk-trees/{treeId}/meta
  *
  * Write path (DD-7): every mutation is ONE `set_tree` commit replacing the
  * whole tree subtree — atomic saves, one history entry per user action, and
  * obsolete nodes vanish via subtree-replace semantics (unlisted keys are
  * deleted). Delete is `set_tree` with an empty entry list.
  */
final class RiskTreeRepositoryIrmin(irmin: IrminClient) extends RiskTreeRepository:

  override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef = BranchRef.Main): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${riskTree.id.value}"
    for
      _   <- ensureRootPresent(riskTree.rootId, riskTree.nodes)
      now <- Clock.instant
      meta = TreeMetadata(
               id = riskTree.id,
               name = riskTree.name,
               rootId = riskTree.rootId,
               seedVarHighWater = riskTree.seedVarHighWater,
               schemaVersion = CurrentSchemaVersion,
               createdAt = now,
               updatedAt = now
             )
      _   <- writeTree(basePath, meta, riskTree.nodes, createMessage(wsId, riskTree.id), branch)
    yield riskTree

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef = BranchRef.Main): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
    for
      existing    <- getTreeWithMeta(wsId, id, branch)
      updatedTree  = op(existing.tree)
      _           <- ensureRootPresent(updatedTree.rootId, updatedTree.nodes)
      now         <- Clock.instant
      updatedMeta  = existing.meta.copy(
                       name = updatedTree.name,
                       rootId = updatedTree.rootId,
                       seedVarHighWater = updatedTree.seedVarHighWater,
                       schemaVersion = CurrentSchemaVersion,
                       updatedAt = now
                     )
      _           <- writeTree(basePath, updatedMeta, updatedTree.nodes, updateMessage(wsId, id), branch)
    yield updatedTree

  override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef = BranchRef.Main): Task[RiskTree] =
    val basePath = s"workspaces/${wsId.value}/risk-trees/${id.value}"
    for
      existing <- getTreeWithMeta(wsId, id, branch)
      _        <- handleIrmin(irmin.setTree(IrminPath.unsafeFrom(basePath), Nil, deleteMessage(wsId, id), branch))
    yield existing.tree

  override def getById(wsId: WorkspaceId, id: TreeId, branch: BranchRef = BranchRef.Main): Task[Option[RiskTree]] =
    loadTree(wsId, id, branch).map(_.map(_.tree))

  override def getAllForWorkspace(wsId: WorkspaceId, branch: BranchRef = BranchRef.Main): Task[List[Either[RepositoryFailure, RiskTree]]] =
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

  /** One atomic commit: meta + every node, replacing the whole subtree (DD-7). */
  private def writeTree(basePath: String, meta: TreeMetadata, nodes: Seq[RiskNode], message: String, branch: BranchRef): Task[Unit] =
    val entries =
      IrminTreeEntry(IrminPath.unsafeFrom("meta"), meta.toJson) ::
        nodes.toList.map(node => IrminTreeEntry(IrminPath.unsafeFrom(s"nodes/${node.id.value}"), nodeJson(node)))
    handleIrmin(irmin.setTree(IrminPath.unsafeFrom(basePath), entries, message, branch)).unit

  private def nodeJson(node: RiskNode): String = node match
    case leaf: RiskLeaf           => leaf.toJson
    case portfolio: RiskPortfolio => portfolio.toJson

  private def decodeMeta(json: String): Task[TreeMetadata] =
    ZIO.fromEither(json.fromJson[TreeMetadata].left.map(err => RepositoryFailure(s"Decode meta failed: $err")))

  private def readNodes(basePath: String, branch: BranchRef): Task[Seq[RiskNode]] =
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

  private def loadTree(wsId: WorkspaceId, id: TreeId, branch: BranchRef): Task[Option[TreeWithMeta]] =
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

  private def getTreeWithMeta(wsId: WorkspaceId, id: TreeId, branch: BranchRef): Task[TreeWithMeta] =
    loadTree(wsId, id, branch).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(RepositoryFailure(s"RiskTree $id not found in workspace ${wsId.value}"))
    }

  private def ensureRootPresent(rootId: NodeId, nodes: Seq[RiskNode]): Task[Unit] =
    if nodes.exists(_.id == rootId) then ZIO.unit
    else ZIO.fail(RepositoryFailure(s"Root ${rootId.value} not found in provided nodes"))

  private def createMessage(wsId: WorkspaceId, treeId: TreeId): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:create"

  private def updateMessage(wsId: WorkspaceId, treeId: TreeId): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:update"

  private def deleteMessage(wsId: WorkspaceId, treeId: TreeId): String =
    s"workspace:${wsId.value}:risk-tree:${treeId.value}:delete"

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

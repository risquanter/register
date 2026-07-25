package com.risquanter.register.repositories

import zio.*
import zio.json.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoCastIron
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, WorkspaceId, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.{RepositoryFailure, AppError, IrminError}
import com.risquanter.register.infra.irmin.{IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminTreeEntry}
import com.risquanter.register.repositories.model.TreeMetadata

/** Irmin-backed implementation of RiskTreeRepository using per-node storage.
  *
  * Path conventions (per ADR-004a, workspace-scoped): see
  * [[WorkspaceStoragePaths]] — the single owner of the storage layout.
  *
  * Write path (DD-7): every mutation is ONE `set_tree` commit replacing the
  * whole tree subtree — atomic saves, one history entry per user action, and
  * obsolete nodes vanish via subtree-replace semantics (unlisted keys are
  * deleted). Delete is `set_tree` with an empty entry list.
  */
final class RiskTreeRepositoryIrmin(irmin: IrminClient) extends RiskTreeRepository:

  override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef = BranchRef.Main): Task[RiskTree] =
    val basePath = IrminPath.unsafeFrom(WorkspaceStoragePaths.treeRoot(wsId, riskTree.id))
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
    val basePath = IrminPath.unsafeFrom(WorkspaceStoragePaths.treeRoot(wsId, id))
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
    val basePath = IrminPath.unsafeFrom(WorkspaceStoragePaths.treeRoot(wsId, id))
    for
      existing <- getTreeWithMeta(wsId, id, branch)
      _        <- handleIrmin(irmin.setTree(basePath, Nil, deleteMessage(wsId, id), branch))
    yield existing.tree

  override def getById(wsId: WorkspaceId, id: TreeId, branch: BranchRef = BranchRef.Main): Task[Option[RiskTree]] =
    loadTree(wsId, id, branch).map(_.map(_.tree))

  override def getAllForWorkspace(wsId: WorkspaceId, branch: BranchRef = BranchRef.Main): Task[List[Either[RepositoryFailure, RiskTree]]] =
    val root = IrminPath.unsafeFrom(WorkspaceStoragePaths.treesRoot(wsId))
    // Resolve ONE head for the whole listing (enumeration + every tree load),
    // so the returned set is a single consistent snapshot.
    resolveHead(branch).flatMap {
      case None       => ZIO.succeed(Nil) // branch absent → empty listing, as before
      case Some(head) =>
        handleIrmin(irmin.listAtCommit(head, root)).flatMap { treeIds =>
          ZIO.foreach(treeIds)(treeIdPath =>
            for
              treeId  <- parseTreeId(treeIdPath.value)
              loaded  <- loadTreeAt(wsId, treeId, head).either
            yield loaded match
              case Right(Some(value)) => Right(value.tree)
              case Right(None)        => Left(RepositoryFailure(s"Tree ${treeIdPath.value} not found (missing meta and nodes)"))
              case Left(err: RepositoryFailure) => Left(err)
              case Left(err: AppError)          => Left(RepositoryFailure(err.getMessage))
              case Left(err)                    => Left(RepositoryFailure(err.getMessage))
          )
        }
    }

  // ----------------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------------

  /** One atomic commit: meta + every node, replacing the whole subtree (DD-7). */
  private def writeTree(base: IrminPath, meta: TreeMetadata, nodes: Seq[RiskNode], message: String, branch: BranchRef): Task[Unit] =
    val entries =
      IrminTreeEntry(IrminPath.unsafeFrom("meta"), meta.toJson) ::
        nodes.toList.map(node => IrminTreeEntry(IrminPath.unsafeFrom(s"nodes/${node.id.value}"), nodeJson(node)))
    handleIrmin(irmin.setTree(base, entries, message, branch)).unit

  private def nodeJson(node: RiskNode): String = node match
    case leaf: RiskLeaf           => leaf.toJson
    case portfolio: RiskPortfolio => portfolio.toJson

  private def decodeMeta(json: String): Task[TreeMetadata] =
    ZIO.fromEither(json.fromJson[TreeMetadata].left.map(err => RepositoryFailure(s"Decode meta failed: $err")))

  private def readNodesAt(prefix: IrminPath, at: CommitHash): Task[Seq[RiskNode]] =
    for
      childNames <- handleIrmin(irmin.listAtCommit(at, prefix))
      nodes      <- ZIO.foreach(childNames) { child =>
                      val fullPath = IrminPath.unsafeFrom(s"${prefix.value}/${child.value}")
                      handleIrmin(irmin.getAtCommit(at, fullPath)).flatMap {
                        case Some(json) => decodeNode(child, json)
                        case None       => ZIO.fail(RepositoryFailure(s"Missing node value at ${fullPath.value}"))
                      }
                    }
    yield nodes

  private def decodeNode(child: IrminPath, json: String): Task[RiskNode] =
    val decoded: Either[String, RiskNode] =
      json.fromJson[RiskLeaf].map(node => node: RiskNode)
        .orElse(json.fromJson[RiskPortfolio].map(node => node: RiskNode))
    ZIO.fromEither(decoded.left.map(err => RepositoryFailure(s"Decode node ${child.value}: $err")))

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

  /** Resolve the branch head once, then read every constituent path pinned to
    * that commit — a commit landing mid-load cannot tear the read. A branch
    * that is absent or has no commits yields None, preserving today's
    * semantics. Shared by getById, update, and delete. */
  private def loadTree(wsId: WorkspaceId, id: TreeId, branch: BranchRef): Task[Option[TreeWithMeta]] =
    resolveHead(branch).flatMap {
      case None       => ZIO.succeed(None)
      case Some(head) => loadTreeAt(wsId, id, head)
    }

  private def resolveHead(branch: BranchRef): Task[Option[CommitHash]] =
    handleIrmin(irmin.getBranch(branch)).flatMap { branchInfo =>
      // None = branch absent OR branch exists with no commits; both preserve
      // today's "no head → empty read" semantics.
      branchInfo.flatMap(_.head) match
        case None         => ZIO.succeed(None)
        case Some(commit) =>
          ZIO.fromEither(CommitHash.fromString(commit.hash))
            .mapBoth(
              errs => RepositoryFailure(s"Invalid branch head hash: ${errs.map(_.message).mkString("; ")}"),
              Some(_)
            )
    }

  private def loadTreeAt(wsId: WorkspaceId, id: TreeId, at: CommitHash): Task[Option[TreeWithMeta]] =
    val metaPath = IrminPath.unsafeFrom(WorkspaceStoragePaths.treeMeta(wsId, id))
    val nodePrefix = IrminPath.unsafeFrom(WorkspaceStoragePaths.treeNodes(wsId, id))
    for
      maybeMetaJson <- handleIrmin(irmin.getAtCommit(at, metaPath))
      maybeMeta <- maybeMetaJson match
        case None =>
          handleIrmin(irmin.listAtCommit(at, nodePrefix)).flatMap { children =>
            if children.nonEmpty then ZIO.fail(RepositoryFailure(s"Metadata missing for tree $id but nodes exist"))
            else ZIO.succeed(None)
          }
        case Some(json) => decodeMeta(json).map(Some(_))
      result <- maybeMeta match
        case None => ZIO.succeed(None)
        case Some(meta) =>
          for
            nodes <- readNodesAt(nodePrefix, at)
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

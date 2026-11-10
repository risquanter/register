package com.risquanter.register.repositories

import zio.*
import zio.json.*
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode, Mitigation}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, WorkspaceId, BranchRef, CommitHash, Revision}
import com.risquanter.register.domain.errors.{AppError, Failure, RepositoryFailure, TreeAbsentAtRevision, TreeLoadFailure, TreeLoadProblem, ValidationError}
import com.risquanter.register.http.responses.TreeWriteKind
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.WorkspaceStoragePaths.TreePath
import com.risquanter.register.infra.irmin.model.{IrminPath, IrminSegment, IrminTreeEntry}
import com.risquanter.register.repositories.model.TreeMetadata

/** Irmin-backed implementation of RiskTreeRepository using per-node storage.
  *
  * Path conventions (per ADR-004a, workspace-scoped): see
  * [[WorkspaceStoragePaths]] — the single owner of the storage layout.
  *
  * Write path: every mutation is ONE `set_tree` commit replacing the
  * whole tree subtree — atomic saves, one history entry per user action, and
  * obsolete nodes vanish via subtree-replace semantics (unlisted keys are
  * deleted). Delete is `set_tree` with an empty entry list.
  */
final class RiskTreeRepositoryIrmin(irmin: IrminClient) extends RiskTreeRepository:

  override def create(wsId: WorkspaceId, riskTree: RiskTree, branch: BranchRef): IO[AppError | Failure,RiskTree] =
    val basePath = WorkspaceStoragePaths.treeRoot(wsId, riskTree.id)
    for
      _   <- ensureRootPresent(riskTree.rootId, riskTree.nodes)
      now <- Clock.instant
      meta = TreeMetadata(
               id = riskTree.id,
               name = riskTree.name,
               rootId = riskTree.rootId,
               seedVarHighWater = riskTree.seedVarHighWater,
               schemaVersion = TreeMetadata.CurrentSchemaVersion,
               createdAt = now,
               updatedAt = now
             )
      _   <- writeTree(basePath, meta, riskTree.nodes, riskTree.mitigations, treeWriteMessage(wsId, riskTree.id, TreeWriteKind.Create), branch)
    yield riskTree

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): IO[AppError | Failure,RiskTree] =
    val basePath = WorkspaceStoragePaths.treeRoot(wsId, id)
    for
      existing    <- getTreeWithMeta(wsId, id, branch)
      updatedTree  = op(existing.tree)
      _           <- ensureRootPresent(updatedTree.rootId, updatedTree.nodes)
      now         <- Clock.instant
      updatedMeta  = existing.meta.copy(
                       name = updatedTree.name,
                       rootId = updatedTree.rootId,
                       seedVarHighWater = updatedTree.seedVarHighWater,
                       schemaVersion = TreeMetadata.CurrentSchemaVersion,
                       updatedAt = now
                     )
      _           <- writeTree(basePath, updatedMeta, updatedTree.nodes, updatedTree.mitigations, treeWriteMessage(wsId, id, TreeWriteKind.Update), branch)
    yield updatedTree

  override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[AppError | Failure,RiskTree] =
    val basePath = WorkspaceStoragePaths.treeRoot(wsId, id)
    for
      existing <- getTreeWithMeta(wsId, id, branch)
      _        <- irmin.setTree(basePath, Nil, treeWriteMessage(wsId, id, TreeWriteKind.Delete), branch)
    yield existing.tree

  /** Revert a tree to `toCommit`. Loads the tree state at
    * that commit — absent (commit or path) → NotFound — then writes it forward
    * as ONE `set_tree` commit with a `:revert` message. Loading at the commit
    * preserves the tree's original `createdAt` and handles reverting a tree
    * that was deleted at head (no head-meta dependency). */
  override def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): IO[AppError | Failure,RiskTree] =
    val basePath = WorkspaceStoragePaths.treeRoot(wsId, id)
    loadTreeAt(wsId, id, toCommit).flatMap {
      case None =>
        ZIO.fail(TreeAbsentAtRevision(wsId, id, Revision.At(toCommit)))
      case Some(existing) =>
        for
          now         <- Clock.instant
          revertedMeta = existing.meta.copy(updatedAt = now, schemaVersion = TreeMetadata.CurrentSchemaVersion)
          _           <- writeTree(basePath, revertedMeta, existing.tree.nodes, existing.tree.mitigations, treeWriteMessage(wsId, id, TreeWriteKind.Revert), branch)
        yield existing.tree
    }

  override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): IO[AppError | Failure,Option[(RiskTree, CommitHash)]] =
    resolveRevision(rev).flatMap {
      case None       => ZIO.succeed(None)
      case Some(head) => loadTreeAt(wsId, id, head).map(_.map(twm => (twm.tree, head)))
    }

  override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): IO[AppError | Failure,List[Either[TreeLoadFailure, RiskTree]]] =
    val root = WorkspaceStoragePaths.treesRoot(wsId)
    // Resolve ONE commit for the whole listing (enumeration + every tree load),
    // so the returned set is a single consistent snapshot.
    resolveRevision(rev).flatMap {
      case None       => ZIO.succeed(Nil) // branch absent → empty listing, as before
      case Some(head) =>
        irmin.listAtCommit(head, root).flatMap { treeIds =>
          ZIO.foreach(treeIds)(treeIdPath =>
            for
              // The id is parsed before the load, so every entry below can name
              // the tree it is about without the inner helpers carrying the id.
              treeId  <- parseTreeId(treeIdPath.value)
              loaded  <- perTree(loadTreeAt(wsId, treeId, head))
            yield loaded match
              case Right(Some(value)) => Right(value.tree)
              case Right(None)        => Left(TreeLoadFailure(treeId, TreeLoadProblem.Empty))
              case Left(err)          => Left(TreeLoadFailure(treeId, TreeLoadProblem.Failed(err)))
          )
        }
    }

  // ----------------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------------

  /** One atomic commit: meta + every node + every mitigation, replacing the
    * whole subtree. An omitted mitigation is deleted by the same
    * subtree-replace that omits it, exactly as for nodes. */
  private def writeTree(base: IrminPath, meta: TreeMetadata, nodes: Seq[RiskNode],
                        mitigations: Seq[Mitigation], message: CommitMessage, branch: BranchRef): IO[AppError | Failure,Unit] =
    val entries =
      IrminTreeEntry(WorkspaceStoragePaths.withinTree(TreePath.Meta(meta.id)), meta.toJson) ::
        nodes.toList.map(node => IrminTreeEntry(WorkspaceStoragePaths.withinTree(TreePath.Node(meta.id, node.id)), RiskTreeRepositoryIrmin.encodeStoredNode(node))) :::
        mitigations.toList.map(m => IrminTreeEntry(WorkspaceStoragePaths.withinTree(TreePath.Mitigation(meta.id, m.id)), m.toJson))
    irmin.setTree(base, entries, message, branch).unit

  private def decodeMeta(json: String): IO[AppError | Failure,TreeMetadata] =
    ZIO.fromEither(json.fromJson[TreeMetadata].left.map(err => RepositoryFailure(s"Decode meta failed: $err")))

  private def readNodesAt(prefix: IrminPath, at: CommitHash): IO[AppError | Failure,Seq[RiskNode]] =
    for
      childNames <- irmin.listAtCommit(at, prefix)
      nodes      <- ZIO.foreach(childNames) { child =>
                      val fullPath = prefix / child
                      irmin.getAtCommit(at, fullPath).flatMap {
                        case Some(json) => decodeNode(child, json)
                        case None       => ZIO.fail(RepositoryFailure(s"Missing node value at ${fullPath.value}"))
                      }
                    }
    yield nodes

  private def decodeNode(child: IrminSegment, json: String): IO[AppError | Failure,RiskNode] =
    ZIO.fromEither(
      RiskTreeRepositoryIrmin.decodeStoredNode(json)
        .left.map(err => RepositoryFailure(s"Decode node ${child.value}: $err"))
    )

  private def readMitigationsAt(prefix: IrminPath, at: CommitHash): IO[AppError | Failure,Seq[Mitigation]] =
    for
      childNames <- irmin.listAtCommit(at, prefix)
      mits       <- ZIO.foreach(childNames) { child =>
                      val fullPath = prefix / child
                      irmin.getAtCommit(at, fullPath).flatMap {
                        case Some(json) => decodeMitigation(child, json)
                        case None       => ZIO.fail(RepositoryFailure(s"Missing mitigation value at ${fullPath.value}"))
                      }
                    }
    yield mits

  private def decodeMitigation(child: IrminSegment, json: String): IO[AppError | Failure,Mitigation] =
    ZIO.fromEither(json.fromJson[Mitigation].left.map(err => RepositoryFailure(s"Decode mitigation ${child.value}: $err")))

  private def rebuildTree(meta: TreeMetadata, nodes: Seq[RiskNode], mitigations: Seq[Mitigation]): IO[AppError | Failure,RiskTree] =
    if nodes.isEmpty then
      ZIO.fail(RepositoryFailure(s"No nodes found for tree ${meta.id}"))
    else
      // Route through the smart constructor so every tree invariant (structure,
      // rootId, seedVarId distinctness, high-water >= max, mitigation id/name
      // uniqueness and count bound) also holds on load.
      ZIO.fromEither(
        RiskTree
          .fromNodes(meta.id, meta.name, nodes, meta.rootId, Some(meta.seedVarHighWater), mitigations.toList)
          .toEither
          .left.map(errors => RepositoryFailure(errors.map(ValidationError.describe).mkString("; ")))
      )

  private def parseTreeId(raw: String): IO[AppError | Failure,TreeId] =
    ZIO.fromEither(TreeId.fromString(raw)).mapError(errs => RepositoryFailure(errs.map(ValidationError.describe).mkString("; ")))

  /** Resolve the branch head once, then read every constituent path pinned to
    * that commit — a commit landing mid-load cannot tear the read. A branch
    * that is absent or has no commits yields None, preserving today's
    * semantics. Shared by getById, update, and delete. */
  private def loadTree(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[AppError | Failure,Option[TreeWithMeta]] =
    resolveHead(branch).flatMap {
      case None       => ZIO.succeed(None)
      case Some(head) => loadTreeAt(wsId, id, head)
    }

  /** Resolve a read `Revision` to the commit to read at: `Head` resolves the
    * branch head once (None if the branch is absent/empty, preserving today's
    * empty-read semantics); `At` uses the pinned commit directly. */
  private def resolveRevision(rev: Revision): IO[AppError | Failure,Option[CommitHash]] =
    rev match
      case Revision.Head(branch) => resolveHead(branch)
      case Revision.At(commit)   => ZIO.succeed(Some(commit))

  private def resolveHead(branch: BranchRef): IO[AppError | Failure,Option[CommitHash]] =
    // None = branch absent OR branch exists with no commits; both read as empty.
    irmin.getBranch(branch).map(_.flatMap(_.head).map(_.hash))

  private def loadTreeAt(wsId: WorkspaceId, id: TreeId, at: CommitHash): IO[AppError | Failure,Option[TreeWithMeta]] =
    val metaPath = WorkspaceStoragePaths.treeMeta(wsId, id)
    val nodePrefix = WorkspaceStoragePaths.treeNodes(wsId, id)
    val mitPrefix = WorkspaceStoragePaths.treeMitigations(wsId, id)
    for
      maybeMetaJson <- irmin.getAtCommit(at, metaPath)
      maybeMeta <- maybeMetaJson match
        case None =>
          irmin.listAtCommit(at, nodePrefix).flatMap { children =>
            if children.nonEmpty then ZIO.fail(RepositoryFailure(s"Metadata missing for tree $id but nodes exist"))
            else ZIO.succeed(None)
          }
        case Some(json) => decodeMeta(json).map(Some(_))
      result <- maybeMeta match
        case None => ZIO.succeed(None)
        case Some(meta) =>
          for
            nodes       <- readNodesAt(nodePrefix, at)
            // Mitigations may legitimately be empty (a tree with no mitigations),
            // so — unlike nodes — there is no non-empty guard on them.
            mitigations <- readMitigationsAt(mitPrefix, at)
            tree        <- rebuildTree(meta, nodes, mitigations)
          yield Some(TreeWithMeta(meta, tree))
    yield result

  private def getTreeWithMeta(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[AppError | Failure,TreeWithMeta] =
    loadTree(wsId, id, branch).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => ZIO.fail(TreeAbsentAtRevision(wsId, id, Revision.Head(branch)))
    }

  private def ensureRootPresent(rootId: NodeId, nodes: Seq[RiskNode]): IO[AppError | Failure,Unit] =
    if nodes.exists(_.id == rootId) then ZIO.unit
    else ZIO.fail(RepositoryFailure(s"Root ${rootId.value} not found in provided nodes"))

  private def treeWriteMessage(wsId: WorkspaceId, treeId: TreeId, kind: TreeWriteKind): CommitMessage =
    CommitMessage.TreeWritten(wsId, treeId, kind)

  /** One tree's load inside a listing. A refusal of that tree's content is
    * recorded against the tree and the listing goes on; a `Failure`, such as the
    * store not answering, is not about one tree and fails the listing. */
  private def perTree[A](load: IO[AppError | Failure, A]): IO[Failure, Either[AppError, A]] =
    load.foldZIO(
      {
        case error: AppError => ZIO.succeed(Left(error))
        case failure: Failure => ZIO.fail(failure)
      },
      value => ZIO.succeed(Right(value))
    )

private final case class TreeWithMeta(meta: TreeMetadata, tree: RiskTree)

object RiskTreeRepositoryIrmin:
  val layer: ZLayer[IrminClient, Nothing, RiskTreeRepository] =
    ZLayer.fromFunction(new RiskTreeRepositoryIrmin(_))

  /** The stored form of one node, written and read here as a pair.
    *
    * A node is stored as its concrete type's own JSON — `RiskLeaf` or
    * `RiskPortfolio` — with no discriminator, so the writer picks the concrete
    * encoder and the reader tries both decoders in turn. The derived
    * `JsonCodec[RiskNode]` expects a discriminated wrapper and does not read
    * this encoding.
    *
    * The two halves must agree, so they sit together. `decodeStoredNode` is
    * public because `ScenarioMergeServiceLive` reads the same blobs to predict
    * a merge's node set; the writer has no reader outside this package.
    */
  private[repositories] def encodeStoredNode(node: RiskNode): String = node match
    case leaf: RiskLeaf           => leaf.toJson
    case portfolio: RiskPortfolio => portfolio.toJson

  def decodeStoredNode(json: String): Either[String, RiskNode] =
    json.fromJson[RiskLeaf].map(node => node: RiskNode)
      .orElse(json.fromJson[RiskPortfolio].map(node => node: RiskNode))

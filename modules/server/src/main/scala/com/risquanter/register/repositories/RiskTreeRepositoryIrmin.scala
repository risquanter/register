package com.risquanter.register.repositories

import zio.*
import zio.json.*
import zio.prelude.{NonEmptyList, Validation}
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree, RiskNode, Mitigation}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId, MitigationId, SafeId, WorkspaceId, BranchRef, CommitHash, Revision, ValidationUtil}
import com.risquanter.register.domain.errors.{
  AppError, Core, Diagnostic, ErrorLocation, ErrorSubject, ExpectedInput, Failure, FieldName, FieldProblem,
  PeerZone, TrustZone, ValidationError
}
import com.risquanter.register.http.errors.NotFoundKind
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
      _   <- writeTree(basePath, meta, riskTree, treeWriteMessage(wsId, riskTree.id, TreeWriteKind.Create), branch)
    yield riskTree

  override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): IO[AppError | Failure,RiskTree] =
    val basePath = WorkspaceStoragePaths.treeRoot(wsId, id)
    for
      existing    <- getTreeWithMeta(wsId, id, branch)
      updatedTree  = op(existing.tree)
      now         <- Clock.instant
      updatedMeta  = existing.meta.copy(
                       name = updatedTree.name,
                       rootId = updatedTree.rootId,
                       seedVarHighWater = updatedTree.seedVarHighWater,
                       schemaVersion = TreeMetadata.CurrentSchemaVersion,
                       updatedAt = now
                     )
      _           <- writeTree(basePath, updatedMeta, updatedTree, treeWriteMessage(wsId, id, TreeWriteKind.Update), branch)
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
        RiskTreeRepository.treeAbsent(wsId, id, Revision.At(toCommit))
      case Some(existing) =>
        for
          now         <- Clock.instant
          revertedMeta = existing.meta.copy(updatedAt = now, schemaVersion = TreeMetadata.CurrentSchemaVersion)
          _           <- writeTree(basePath, revertedMeta, existing.tree, treeWriteMessage(wsId, id, TreeWriteKind.Revert), branch)
        yield existing.tree
    }

  override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): IO[AppError | Failure,Option[(RiskTree, CommitHash)]] =
    resolveRevision(rev).flatMap {
      case None       => ZIO.succeed(None)
      case Some(head) => loadTreeAt(wsId, id, head).map(_.map(twm => (twm.tree, head)))
    }

  override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): IO[AppError | Failure, List[RiskTree]] =
    val root = WorkspaceStoragePaths.treesRoot(wsId)
    // Resolve ONE commit for the whole listing (enumeration + every tree load),
    // so the returned set is a single consistent snapshot.
    resolveRevision(rev).flatMap {
      case None       => ZIO.succeed(Nil) // branch absent → empty listing, as before
      case Some(head) =>
        irmin.listAtCommit(head, root).flatMap { treeIds =>
          ZIO.foreach(treeIds)(treeIdPath =>
            for
              // The id is parsed before the load, so every refusal below can be
              // filed under the tree it is about.
              treeId <- parseTreeId(treeIdPath)
              loaded <- perTree(loadTreeAt(wsId, treeId, head))
              tree   <- loaded match
                          case Right(Some(value)) => ZIO.succeed(Right(value.tree))
                          case Right(None)        => ZIO.fail(listedTreeEmpty(treeId))
                          case Left(refusals)     => ZIO.succeed(Left(treeId -> refusals))
            yield tree
          ).flatMap(accumulateListing)
        }
    }

  // ----------------------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------------------

  /** One atomic commit: meta + every node + every mitigation of `tree`,
    * replacing the whole subtree. An omitted mitigation is deleted by the same
    * subtree-replace that omits it, exactly as for nodes. The tree holds its
    * root among its nodes by construction, so the write has nothing to check. */
  private def writeTree(base: IrminPath, meta: TreeMetadata, tree: RiskTree, message: CommitMessage, branch: BranchRef): IO[Failure, Unit] =
    val entries =
      IrminTreeEntry(WorkspaceStoragePaths.withinTree(TreePath.Meta(meta.id)), meta.toJson) ::
        tree.nodes.toList.map(node => IrminTreeEntry(WorkspaceStoragePaths.withinTree(TreePath.Node(meta.id, node.id)), RiskTreeRepositoryIrmin.encodeStoredNode(node))) :::
        tree.mitigations.toList.map(m => IrminTreeEntry(WorkspaceStoragePaths.withinTree(TreePath.Mitigation(meta.id, m.id)), m.toJson))
    irmin.setTree(base, entries, message, branch).unit

  /** The value at every path under `prefix`, with the segment it was listed
    * under. A listed path the store holds no value at is absent content,
    * located at the identifier its segment names through `subject`. */
  private def readValuesAt(
    prefix:  IrminPath,
    at:      CommitHash,
    subject: SafeId.SafeId => ErrorSubject
  ): IO[AppError | Failure, List[(IrminSegment, String)]] =
    irmin.listAtCommit(at, prefix).flatMap(children =>
      ZIO.foreach(children) { child =>
        val fullPath = prefix / child
        irmin.getAtCommit(at, fullPath).flatMap {
          case Some(json) => ZIO.succeed(child -> json)
          case None       => ZIO.fail(listedValueAbsent(child, fullPath, subject))
        }
      }
    )

  /** A listed path with no value, located at the identifier its segment names.
    * A segment that is not an identifier is refused itself, as content this
    * code did not write. */
  private def listedValueAbsent(segment: IrminSegment, path: IrminPath, subject: SafeId.SafeId => ErrorSubject): Failure =
    ValidationUtil.refineId(segment.value, ErrorLocation.Whole) match
      case Right(id) =>
        storeAbsence(ErrorLocation.Field(subject(id), FieldName.Id), NotFoundKind.StoredValueAbsent(), List(Diagnostic.StoragePath(path)))
      case Left(errors) =>
        storeRefusal(NonEmptyList(errors.head, errors.tail*), List(Diagnostic.RawValue(segment.value)))

  /** The `meta` value, the nodes and the mitigations of one tree decoded, every
    * refusal across all three accumulated. A node or mitigation is reported at
    * the identifier its path names, and a path segment that is not one is
    * refused itself. */
  private def decodeStored(
    treeId:      TreeId,
    metaJson:    String,
    nodes:       List[(IrminSegment, String)],
    mitigations: List[(IrminSegment, String)]
  ): Validation[ValidationError, (TreeMetadata, List[RiskNode], List[Mitigation])] =
    val metaV = fromEither(TreeMetadata.decodeValidated(metaJson, ErrorSubject.Tree(treeId)))
    val nodesV = Validation.validateAll(nodes.map { (segment, json) =>
      refineSegment(segment)(NodeId(_)).flatMap(nodeId =>
        fromEither(RiskTreeRepositoryIrmin.decodeStoredNode(json, nodeId)))
    })
    val mitigationsV = Validation.validateAll(mitigations.map { (segment, json) =>
      refineSegment(segment)(MitigationId(_)).flatMap(mitigationId =>
        fromEither(Mitigation.decodeValidated(json, ErrorSubject.Mitigation(mitigationId))))
    })
    Validation.validateWith(metaV, nodesV, mitigationsV)((meta, ns, ms) => (meta, ns, ms))

  /** A listed path segment refined as the identifier it names. This server
    * writes only refined identifiers, so one that does not refine is content
    * this code did not write. */
  private def refineSegment[A](segment: IrminSegment)(wrap: SafeId.SafeId => A): Validation[ValidationError, A] =
    ValidationUtil.toValidation(ValidationUtil.refineId(segment.value, ErrorLocation.Whole).map(wrap))

  private def fromEither[A](decoded: Either[NonEmptyList[ValidationError], A]): Validation[ValidationError, A] =
    decoded match
      case Right(value) => Validation.succeed(value)
      case Left(errors) => Validation.failNonEmptyChunk(NonEmptyChunk(errors.head, errors.tail*))

  /** The tree rebuilt through the smart constructor, so every tree invariant
    * (structure, rootId, seedVarId distinctness, high-water >= max, mitigation
    * id/name uniqueness and count bound) also holds on load. A tree with
    * metadata and no nodes is absent content. */
  private def rebuildTree(meta: TreeMetadata, nodes: Seq[RiskNode], mitigations: Seq[Mitigation]): IO[AppError | Failure, RiskTree] =
    if nodes.isEmpty then
      ZIO.fail(storeAbsence(ErrorLocation.Field(ErrorSubject.Tree(meta.id), FieldName.Nodes), NotFoundKind.NodesAbsent(), Nil))
    else
      ZIO.fromEither(
        RiskTree
          .fromNodes(meta.id, meta.name, nodes, meta.rootId, Some(meta.seedVarHighWater), mitigations.toList)
          .toEither
          .left.map(errors => storeRefusal(NonEmptyList(errors.head, errors.tail*), Nil))
      )

  /** A listed tree segment refined as a `TreeId`, or the listing refused. */
  private def parseTreeId(segment: IrminSegment): IO[Failure, TreeId] =
    ZIO.fromEither(ValidationUtil.refineId(segment.value, ErrorLocation.Whole).map(TreeId(_)))
      .mapError(errors => storeRefusal(NonEmptyList(errors.head, errors.tail*), List(Diagnostic.RawValue(segment.value))))

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
          irmin.listAtCommit(at, nodePrefix).flatMap {
            case Nil => ZIO.succeed(None)
            case _   => ZIO.fail(storeAbsence(ErrorLocation.Field(ErrorSubject.Tree(id), FieldName.Id), NotFoundKind.MetadataAbsent(), Nil))
          }
        case Some(json) => ZIO.succeed(Some(json))
      result <- maybeMeta match
        case None => ZIO.succeed(None)
        case Some(metaJson) =>
          for
            nodes       <- readValuesAt(nodePrefix, at, segmentId => ErrorSubject.ExistingNode(NodeId(segmentId)))
            // Mitigations may legitimately be empty (a tree with no mitigations),
            // so — unlike nodes — there is no non-empty guard on them.
            mitigations <- readValuesAt(mitPrefix, at, segmentId => ErrorSubject.Mitigation(MitigationId(segmentId)))
            decoded     <- ZIO.fromEither(decodeStored(id, metaJson, nodes, mitigations).toEither)
                             .mapError(errors => storeRefusal(NonEmptyList(errors.head, errors.tail*), Nil))
            (meta, ns, ms) = decoded
            tree        <- rebuildTree(meta, ns, ms)
          yield Some(TreeWithMeta(meta, tree))
    yield result

  private def getTreeWithMeta(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[AppError | Failure,TreeWithMeta] =
    loadTree(wsId, id, branch).flatMap {
      case Some(value) => ZIO.succeed(value)
      case None        => RiskTreeRepository.treeAbsent(wsId, id, Revision.Head(branch))
    }

  private def treeWriteMessage(wsId: WorkspaceId, treeId: TreeId, kind: TreeWriteKind): CommitMessage =
    CommitMessage.TreeWritten(wsId, treeId, kind)

  /** One tree's load inside a listing. A refusal of that tree's stored content
    * is kept against the tree and the listing goes on; every other failure,
    * such as the store not answering, fails the listing. */
  private def perTree[A](load: IO[AppError | Failure, A]): IO[AppError | Failure, Either[NonEmptyList[(ErrorLocation, FieldProblem)], A]] =
    load.foldZIO(
      {
        case Failure.Rejected(Core(TrustZone.Peer(PeerZone.Store), errors, _)) => ZIO.succeed(Left(errors))
        case other                                                            => ZIO.fail(other)
      },
      value => ZIO.succeed(Right(value))
    )

  /** The listing's trees, or one refusal accumulating every refused tree's
    * entries, with each refused tree named in the diagnostics. */
  private def accumulateListing(
    loaded: List[Either[(TreeId, NonEmptyList[(ErrorLocation, FieldProblem)]), RiskTree]]
  ): IO[Failure, List[RiskTree]] =
    loaded.collect { case Left(refused) => refused } match
      case Nil => ZIO.succeed(loaded.collect { case Right(tree) => tree })
      case (firstId, firstErrors) :: rest =>
        val errors = rest.foldLeft(firstErrors) { case (acc, (_, more)) => acc ++ more }
        val trees  = (firstId :: rest.map(_._1)).map(Diagnostic.TreeRef(_))
        ZIO.fail(Failure.Rejected(Core(TrustZone.Peer(PeerZone.Store), errors, trees)))

  /** A listed tree whose commit holds neither its metadata nor any node. */
  private def listedTreeEmpty(treeId: TreeId): Failure =
    storeAbsence(ErrorLocation.Field(ErrorSubject.Tree(treeId), FieldName.Nodes), NotFoundKind.NodesAbsent(), Nil)

  /** Content the store's own layout says it holds and the store does not. */
  private def storeAbsence(location: ErrorLocation, kind: NotFoundKind, diagnostics: List[Diagnostic]): Failure =
    Failure.NotFound(Core(TrustZone.Peer(PeerZone.Store), NonEmptyList(location -> kind), diagnostics))

  /** Content the store returned that this service refuses (ADR-039). */
  private def storeRefusal(errors: NonEmptyList[ValidationError], diagnostics: List[Diagnostic]): Failure =
    Failure.rejected(TrustZone.Peer(PeerZone.Store), errors, diagnostics)

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

  /** One stored node read back at `nodeId`. The leaf form is tried first and
    * the portfolio form only when the text is not a leaf at all, so a stored
    * leaf with a refused field is reported as that leaf's refusal.
    *
    * {{{
    * decodeStoredNode(leafJsonWithProbability1_5, nodeId)
    * // Left: OutOfRange(ClosedUnitInterval) at node <nodeId>.Probability
    * }}}
    */
  def decodeStoredNode(json: String, nodeId: NodeId): Either[NonEmptyList[ValidationError], RiskNode] =
    val subject = ErrorSubject.ExistingNode(nodeId)
    RiskLeaf.decodeValidated(json, subject) match
      case Right(leaf) => Right(leaf)
      case Left(NonEmptyList.Single(ValidationError(ErrorLocation.Whole, FieldProblem.Unreadable(ExpectedInput.Json)))) =>
        RiskPortfolio.decodeValidated(json, subject)
      case Left(refusals) => Left(refusals)

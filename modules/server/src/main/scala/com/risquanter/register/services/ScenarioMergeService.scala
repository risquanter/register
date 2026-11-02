package com.risquanter.register.services

import zio.*
import zio.json.*
import java.time.Instant
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.{Mitigation, RiskLeaf, RiskNode, RiskPortfolio}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, MergeStagingRef, MitigationId, NodeId, Revision, SafeName, TreeId, ValidationUtil}
import com.risquanter.register.domain.errors.{AppError, BranchAlreadyExists, BranchHeadStale, ErrorLocation, FieldName, IrminMergeConflict, MergeAlreadyRunning, MergeConflict, MergeRefusal, MergeTargetMoved, RepositoryFailure, ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.http.responses.{MergeConflictEntry, TreeLabel, TreeMetadataFields, Versions}
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.WorkspaceStoragePaths.TreePath
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}
import com.risquanter.register.repositories.model.TreeMetadata

/** The three-way merge rule over one value: the two sides merge cleanly when
  * they are equal, or when one side still equals the fork and the other side
  * wins. `None` means the value is absent at that point.
  *
  * Over a stored path's bytes this is the rule Irmin's merge applies (ADR-032,
  * storage relation). It compares the full persisted values, never the domain
  * content hashes: a renamed node is byte-different while domain-hash-identical,
  * so predicting merge outcomes from the semantic diff misses real conflicts.
  * Over a single field of a tree's `meta` value it is the rule the merge
  * applies itself.
  */
object MergeConflictRule:
  def isConflict[A](versions: Versions[A]): Boolean =
    versions.onMain != versions.onScenario && versions.onMain != versions.base && versions.onScenario != versions.base

  /** The value a three-way merge produces where the sides do not conflict: the
    * two sides agree, or exactly one side moved away from the fork and that
    * side wins. `None` means the value is absent in the result.
    *
    * Defined only where `isConflict` is false. The two functions partition the
    * same three inputs, so a caller decides conflict first and asks for the
    * merged value second.
    */
  def merged[A](versions: Versions[A]): Option[A] =
    versions match
      case Versions(_, m, s) if m == s    => m
      case Versions(b, m, s) if m == b    => s
      case Versions(_, m, _)              => m

  /** A tree's `meta` value merged field by field over `name`, `rootId` and
    * `seedVarHighWater`, each through `isConflict` and `merged`. `updatedAt`
    * takes no part, because every save rewrites it. The result is either the
    * conflicting fields, or main's `meta` value carrying the merged fields, the
    * current schema version and `updatedAt` set to `at`.
    *
    * {{{
    * // main renamed the tree, the scenario raised seedVarHighWater, both saved
    * mergeMetadata(Some(fork), main, scenario, at)
    * // Right(main.copy(name = main.name, seedVarHighWater = scenario.seedVarHighWater, updatedAt = at, …))
    * }}}
    */
  def mergeMetadata(
    base: Option[TreeMetadata],
    onMain: TreeMetadata,
    onScenario: TreeMetadata,
    at: Instant
  ): Either[List[FieldName], TreeMetadata] =
    val name     = mergeField(FieldName.Name, base.map(_.name), onMain.name, onScenario.name)
    val rootId   = mergeField(FieldName.RootId, base.map(_.rootId), onMain.rootId, onScenario.rootId)
    val seedHigh = mergeField(FieldName.SeedVarHighWater, base.map(_.seedVarHighWater), onMain.seedVarHighWater, onScenario.seedVarHighWater)
    (name, rootId, seedHigh) match
      case (Right(n), Right(r), Right(h)) =>
        Right(onMain.copy(
          name = n,
          rootId = r,
          seedVarHighWater = h,
          schemaVersion = TreeMetadata.CurrentSchemaVersion,
          updatedAt = at
        ))
      case _ =>
        Left(List(name, rootId, seedHigh).collect { case Left(field) => field })

  /** One field present on both branches, merged or named as conflicting. Both
    * sides hold a value, so a non-conflicting merge always holds one too, and
    * main's value stands in only for that unreachable absence. */
  private def mergeField[A](field: FieldName, base: Option[A], onMain: A, onScenario: A): Either[FieldName, A] =
    val versions = Versions(base, Some(onMain), Some(onScenario))
    if isConflict(versions) then Left(field)
    else Right(merged(versions).getOrElse(onMain))

/** The fields that differ between two versions of one node, one mitigation or
  * one tree's `meta` fields. The scan passes main's and the scenario's versions
  * when both exist, and the fork's and the surviving branch's versions when one
  * branch deleted it.
  */
object ConflictFields:
  /** Fails with `RepositoryFailure` when one is a leaf and the other a
    * portfolio: no request can store that, so the store holds what this code
    * did not write. */
  def node(one: RiskNode, other: RiskNode): Either[RepositoryFailure, List[FieldName]] =
    (one, other) match
      case (a: RiskLeaf, b: RiskLeaf) =>
        Right(differing(
          FieldName.Name             -> (a.name != b.name),
          FieldName.ParentId         -> (a.parentId != b.parentId),
          FieldName.Probability      -> (a.probability != b.probability),
          FieldName.DistributionType -> (a.shape != b.shape),
          FieldName.SeedVarId        -> (a.seedVarId != b.seedVarId)
        ))
      case (a: RiskPortfolio, b: RiskPortfolio) =>
        Right(differing(
          FieldName.Name     -> (a.name != b.name),
          FieldName.ParentId -> (a.parentId != b.parentId),
          FieldName.ChildIds -> (a.childIds != b.childIds)
        ))
      case _ =>
        Left(RepositoryFailure(s"node ${one.id.value} is a leaf on one side of a merge and a portfolio on the other"))

  def mitigation(one: Mitigation, other: Mitigation): List[FieldName] =
    differing(
      FieldName.Name            -> (one.name != other.name),
      FieldName.TargetingSource -> (one.target != other.target),
      FieldName.Spec            -> (one.spec != other.spec),
      FieldName.Precedence      -> (one.precedence != other.precedence)
    )

  def metadata(one: TreeMetadataFields, other: TreeMetadataFields): List[FieldName] =
    differing(
      FieldName.Name             -> (one.name != other.name),
      FieldName.RootId           -> (one.rootId != other.rootId),
      FieldName.SeedVarHighWater -> (one.seedVarHighWater != other.seedVarHighWater)
    )

  /** The fields whose comparison found a difference, in the order given. */
  private def differing(comparisons: (FieldName, Boolean)*): List[FieldName] =
    comparisons.collect { case (field, true) => field }.toList

/** Outcome of a merge preview. A plain multi-case enum (mirrors
  * `ChangedNodesResult`): a missing scenario is a distinct non-error outcome
  * of a read-only preview, not a failure.
  */
enum MergePreviewResult:
  case Clean
  case Conflicts(entries: List[MergeConflictEntry])
  case DuplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]])
  case ScenarioMissing

/** Scenario → main merge.
  *
  * Conflict handling is two-layered. The pre-check names every node,
  * mitigation and tree `meta` value both branches changed into versions that
  * differ (Irmin's conflict error names nothing), and lets both `preview` and
  * `merge` answer with that list; `merge` refuses up front when it is not
  * empty. The patched Irmin backend (see `IrminClient.mergeBranch`) is the
  * backstop for the remaining race: a write that introduces a conflict between
  * the scan and the merge makes the merge itself fail typed
  * (`IrminMergeConflict`), which is mapped to the domain `MergeConflict` here.
  *
  * The pre-check compares full persisted contents byte-for-byte between main,
  * the scenario, and their lowest common ancestor — the storage relation of
  * ADR-032. A tree's `meta` value is the one exception: the merge compares its
  * fields with `updatedAt` excluded and writes the merged value itself. The
  * pre-check never consults the domain content hashes (`ChangedNodesService`),
  * which by design ignore renames/moves and therefore cannot predict merge
  * outcomes.
  *
  * Scanning is scoped to the workspace's own subtree: scenario branches only
  * ever receive writes through this workspace's endpoints, so any path that
  * changed on both sides since the fork lies under the workspace root
  * ([[com.risquanter.register.infra.irmin.WorkspaceStoragePaths]]).
  */
trait ScenarioMergeService:

  /** Report whether merging the scenario into main would conflict, without
    * changing anything.
    */
  def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): IO[AppError, MergePreviewResult]

  /** Merge the scenario into main via Irmin's native three-way merge.
    *
    * @return main's new head commit
    * @see MergeConflict — pre-check found conflicting values (409), or a
    *      concurrent write introduced one mid-merge
    * @see ValidationFailed NOT_FOUND — the scenario does not exist
    */
  def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): IO[AppError, CommitHash]

final class ScenarioMergeServiceLive(irmin: IrminClient, repo: RiskTreeRepository) extends ScenarioMergeService:

  override def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): IO[AppError, MergePreviewResult] =
    for
      branch    <- ScenarioBranchOps.scenarioBranch(wsId, name)
      maybeHead <- irmin.getBranch(branch).map(_.flatMap(_.head))
      result    <- maybeHead match
                     case None => ZIO.succeed(MergePreviewResult.ScenarioMissing)
                     case Some(commit) =>
                       for
                         head <- ScenarioBranchOps.refineCommitHash(commit.hash)
                         // A preview writes nothing, so the merged `meta`
                         // values it computes only need some instant.
                         now  <- Clock.instant
                         s    <- scan(wsId, branch, head, now)
                       yield
                         if s.conflicts.nonEmpty then MergePreviewResult.Conflicts(s.conflicts)
                         else if s.duplicateNames.nonEmpty then MergePreviewResult.DuplicateNames(s.duplicateNames)
                         else MergePreviewResult.Clean
    yield result

  override def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): IO[AppError, CommitHash] =
    for
      branch       <- ScenarioBranchOps.scenarioBranch(wsId, name)
      maybeHead    <- irmin.getBranch(branch).map(_.flatMap(_.head))
      scenarioHead <- maybeHead match
                        case Some(commit) => ScenarioBranchOps.refineCommitHash(commit.hash)
                        case None =>
                          ZIO.fail(ValidationFailed(List(ValidationError(
                            location = ErrorLocation.requestField(FieldName.Scenario),
                            code = ValidationErrorCode.NOT_FOUND,
                            message = s"Scenario not found: ${name.value}"
                          ))))
      at           <- Clock.instant
      result       <- scan(wsId, branch, scenarioHead, at)
      _            <- ZIO.when(result.conflicts.nonEmpty)(ZIO.fail(MergeConflict(
                        name,
                        MergeRefusal.Conflicts(result.conflicts)
                      )))
      _            <- ZIO.when(result.duplicateNames.nonEmpty)(ZIO.fail(MergeConflict(
                        name,
                        MergeRefusal.DuplicateNames(result.duplicateNames)
                      )))
      // The head the merge is assembled against and published against. A
      // scenario branch can only be created at a commit that already exists and
      // a branch head never moves backwards, so main always holds one by the
      // time a merge into it runs. An absent head therefore means the store no
      // longer holds what it held, which is a storage failure the caller sees
      // as one rather than a defect.
      mainBefore   <- irmin.mainBranch.map(_.flatMap(_.head))
                        .flatMap {
                          case Some(c) => ScenarioBranchOps.refineCommitHash(c.hash)
                          case None    => ZIO.fail(RepositoryFailure("main has no head"))
                        }
      newHead      <- mergeOnStaging(wsId, name, branch, mainBefore, result.metadataToWrite)
    yield newHead

  /** Assemble the merge on a staging branch, validate it there, and publish it
    * to main with one compare-and-set against the head observed before the
    * merge began.
    *
    * For each `meta` value in `metadataToWrite`, the scenario's contents the
    * scan compared are written to staging before Irmin's merge, so Irmin finds
    * equal bytes on both sides of that path. The merged value is written after
    * it. A scenario save between the scan and the merge leaves different bytes
    * on the scenario, and Irmin's merge then refuses rather than taking a value
    * the scan never compared.
    *
    * Main is never pointed at an unvalidated commit. A guard failure discards
    * the staging branch and leaves main untouched, so there is nothing to undo.
    * A commit landing on main while the merge runs makes the publish fail
    * rather than be absorbed into the merge and then discarded by an undo. An
    * interruption at any point leaves main as it was.
    *
    * The staging branch is removed on every exit path. Its name is derived from
    * the scenario, so a concurrent merge of the same scenario collides on it
    * and is refused rather than interleaved.
    */
  private def mergeOnStaging(
    wsId: WorkspaceId,
    name: ScenarioName.ScenarioName,
    scenarioBranch: BranchRef,
    mainBefore: CommitHash,
    metadataToWrite: List[(PathComparison, TreeMetadata)]
  ): IO[AppError, CommitHash] =
    val prepareMessage = CommitMessage.render(CommitMessage.MergePrepared(wsId, name))
    val mergedMessage  = CommitMessage.render(CommitMessage.ScenarioMerged(wsId, name))
    for
      staging <- ZIO.fromEither(MergeStagingRef.forScenario(wsId, name))
                   .orElseFail(RepositoryFailure(
                     s"staging branch for workspace ${wsId.value} + scenario '${name.value}' failed refinement"))
      head    <- ZIO.acquireReleaseWith(
                   irmin.createBranchAt(staging, mainBefore)
                     .catchSome { case BranchAlreadyExists(_) => ZIO.fail(MergeAlreadyRunning(name)) }
                 )(_ =>
                   // Best effort: the staging branch is unreachable once main
                   // holds the merge, and a leftover one only blocks the next
                   // merge of this scenario, which reports it as still running.
                   irmin.getBranch(staging)
                     .flatMap(b => ZIO.foreachDiscard(b.flatMap(_.head))(h =>
                       ScenarioBranchOps.refineCommitHash(h.hash)
                         .flatMap(c => irmin.deleteBranch(staging, c))))
                     .ignore
                 ) { _ =>
                   for
                     _      <- ZIO.foreachDiscard(metadataToWrite) { case (comparison, _) =>
                                 ZIO.foreachDiscard(comparison.versions.onScenario)(contents =>
                                   irmin.set(WorkspaceStoragePaths.of(wsId, comparison.path), contents, prepareMessage, staging))
                               }
                     merge  <- irmin.mergeBranch(scenarioBranch, staging, mergedMessage)
                                 .catchSome { case IrminMergeConflict(_) =>
                                   ZIO.fail(MergeConflict(name, MergeRefusal.BranchesConflict()))
                                 }
                     last   <- ZIO.foldLeft(metadataToWrite)(merge) { case (_, (comparison, merged)) =>
                                 irmin.set(WorkspaceStoragePaths.of(wsId, comparison.path), merged.toJson, mergedMessage, staging)
                               }
                     staged <- ScenarioBranchOps.refineCommitHash(last.hash)
                     _      <- guardStagedState(wsId, name, staged)
                     _      <- irmin.moveBranchTo(BranchRef.Main, expectedHead = mainBefore, to = staged)
                                 .catchSome { case BranchHeadStale(_, _) =>
                                   ZIO.fail(MergeTargetMoved(name))
                                 }
                   yield staged
                 }
    yield head

  /** Read every tree in the workspace at `staged` through `RiskTree.fromNodes`
    * and fail if any read fails.
    *
    * Irmin merges one storage path at a time and byte-for-byte, so it cannot
    * see an invariant that is a property of the whole node set — global name
    * uniqueness, seed-variable distinctness, the mitigation bounds. The merge
    * is the one write path the application does not perform itself, so this is
    * where those invariants are checked instead of at construction.
    *
    * `staged` is on the staging branch and nothing has been published to main,
    * so a failure needs no repair: the caller discards the staging branch and
    * main is untouched. The repository's own failure text goes to the log and
    * nowhere else — it can name an absolute storage path, which embeds the
    * `WorkspaceId` (ADR-036).
    */
  private def guardStagedState(
    wsId: WorkspaceId,
    scenario: ScenarioName.ScenarioName,
    staged: CommitHash
  ): IO[AppError, Unit] =
    repo.getAllForWorkspace(wsId, Revision.At(staged)).flatMap { loaded =>
      loaded.collect { case Left(failure) => failure } match
        case Nil      => ZIO.unit
        case failures =>
          ZIO.logError(
            s"merge guard rejected ${staged.value} for scenario ${scenario.value}: " +
            failures.map(f => s"${f.treeId.value}: ${f.reason}").mkString("; ")
          ) *> ZIO.fail(MergeConflict(scenario, MergeRefusal.InvariantBroken(failures.map(_.treeId))))
    }

  // ── conflict scan ────────────────────────────────────────────────────────

  /** One candidate path with the three Irmin contents the merge decides
    * between. */
  private final case class PathComparison(path: TreePath, versions: Versions[String])

  private final case class MergeScan(
    conflicts: List[MergeConflictEntry],
    duplicateNames: Map[TreeId, List[SafeName.SafeName]],
    /** Each `meta` value both branches changed without a conflicting field,
      * as the comparison the scan made and the merged value: the merge writes
      * these itself. */
    metadataToWrite: List[(PathComparison, TreeMetadata)]
  )

  /** Compute the conflict set between main and the scenario against their
    * merge base. No conflicts by construction when main has no head (nothing
    * to collide with), when the scenario is fully contained in main (base =
    * scenario head), or when main has not moved since the fork (base = main
    * head). `at` is the `updatedAt` every merged `meta` value carries.
    *
    * With several LCAs (criss-cross histories) the first is used; scenario
    * branches fork linearly from a single commit, so multiple LCAs do
    * not arise through this application's own writes.
    */
  private def scan(wsId: WorkspaceId, branch: BranchRef, scenarioHead: CommitHash, at: Instant): IO[AppError, MergeScan] =
    irmin.mainBranch.map(_.flatMap(_.head)).flatMap {
      case None => ZIO.succeed(MergeScan(Nil, Map.empty, Nil))
      case Some(mainCommit) =>
        for
          mainHead    <- ScenarioBranchOps.refineCommitHash(mainCommit.hash)
          lcas        <- irmin.lca(BranchRef.Main, scenarioHead)
          base        <- lcas.headOption match
                           case Some(c) => ScenarioBranchOps.refineCommitHash(c.hash)
                           case None =>
                             ZIO.die(new IllegalStateException(
                               s"no common ancestor between main and ${branch.toBranchRef} — " +
                               "a scenario always forks from an existing commit"
                             ))
          comparisons <- if base == scenarioHead || base == mainHead then ZIO.succeed(Nil)
                         else comparePaths(wsId, branch, base)
          conflicting  = comparisons.filter(c => MergeConflictRule.isConflict(c.versions))
          metas       <- treeMetadata(comparisons, conflicting.map(_.path.treeId).toSet)
          decisions   <- ZIO.foreach(conflicting)(decide(_, metas, at))
          duplicates  <- duplicateNodeNames(comparisons)
        yield MergeScan(
          conflicts = decisions.collect { case Left(entry) => entry },
          duplicateNames = duplicates,
          metadataToWrite = decisions.collect { case Right(write) => write }
        )
    }

  /** The `meta` value at the three points of each tree in `trees`, decoded
    * once and read for the tree's names and for the `meta` merge. Only trees
    * with a conflicting path are named by an entry, so only those are
    * decoded. */
  private def treeMetadata(comparisons: List[PathComparison], trees: Set[TreeId]): IO[AppError, Map[TreeId, Versions[TreeMetadata]]] =
    ZIO.foreach(comparisons.collect { case PathComparison(TreePath.Meta(treeId), versions) if trees(treeId) => (treeId, versions) }) {
      (treeId, versions) =>
        decodeVersions(versions, s"meta of tree ${treeId.value}")(_.fromJson[TreeMetadata]).map(treeId -> _)
    }.map(_.toMap)

  /** One path both branches changed into contents that differ: a conflict
    * entry (`Left`), or a `meta` value whose fields merge (`Right`), which the
    * merge then writes. */
  private def decide(
    comparison: PathComparison,
    metas: Map[TreeId, Versions[TreeMetadata]],
    at: Instant
  ): IO[AppError, Either[MergeConflictEntry, (PathComparison, TreeMetadata)]] =
    val treeMeta = metas.getOrElse(comparison.path.treeId, Versions(None, None, None))
    val label    = TreeLabel(comparison.path.treeId, treeMeta.map(_.name))
    comparison.path match
      case TreePath.Meta(treeId) =>
        (treeMeta.onMain, treeMeta.onScenario) match
          case (Some(onMain), Some(onScenario)) =>
            ZIO.succeed(MergeConflictRule.mergeMetadata(treeMeta.base, onMain, onScenario, at) match
              case Left(fields)  => Left(MergeConflictEntry.TreeMetadataConflict(treeId, treeMeta.map(_.toMergeFields), fields))
              case Right(merged) => Right((comparison, merged)))
          case _ =>
            val fields = treeMeta.map(_.toMergeFields)
            ZIO.succeed(Left(MergeConflictEntry.TreeMetadataConflict(
              treeId,
              fields,
              changedFields(fields)((a, b) => Right(ConflictFields.metadata(a, b))).merge
            )))
      case TreePath.Node(treeId, nodeId) =>
        for
          versions <- decodeVersions(comparison.versions, s"node ${nodeId.value} of tree ${treeId.value}")(RiskTreeRepositoryIrmin.decodeStoredNode)
          fields   <- ZIO.fromEither(changedFields(versions)(ConflictFields.node))
        yield Left(MergeConflictEntry.NodeConflict(label, nodeId, versions, fields))
      case TreePath.Mitigation(treeId, mitigationId) =>
        decodeVersions(comparison.versions, s"mitigation ${mitigationId.value} of tree ${treeId.value}")(_.fromJson[Mitigation])
          .map(versions => Left(MergeConflictEntry.MitigationConflict(
            label,
            mitigationId,
            versions,
            changedFields(versions)((a, b) => Right(ConflictFields.mitigation(a, b))).merge
          )))

  /** The fields that changed for a value both branches changed into versions
    * that differ: main's against the scenario's when both hold it, otherwise
    * the fork's against the surviving branch's.
    *
    * Those two shapes are the only ones a conflict has: when one branch
    * deleted the value, the fork holds it, because a value absent at the fork
    * and on one branch has changed on one branch only. The empty list stands in
    * for the shapes `MergeConflictRule.isConflict` excludes.
    */
  private def changedFields[A, E](versions: Versions[A])(compare: (A, A) => Either[E, List[FieldName]]): Either[E, List[FieldName]] =
    (versions.base, versions.onMain, versions.onScenario) match
      case (_, Some(main), Some(scenario)) => compare(main, scenario)
      case (Some(base), Some(main), None)  => compare(base, main)
      case (Some(base), None, Some(scen))  => compare(base, scen)
      case _                               => Right(Nil)

  /** The three Irmin contents decoded, or a `RepositoryFailure` naming what
    * failed to decode. The description names tree, node and mitigation
    * identifiers only, never a storage path, which embeds the `WorkspaceId`. */
  private def decodeVersions[A](versions: Versions[String], what: String)(decode: String => Either[String, A]): IO[AppError, Versions[A]] =
    ZIO.fromEither(versions.traverse(decode)).mapError(err => RepositoryFailure(s"Decode $what: $err"))

  /** Fetch, for every candidate path, the contents on main, on the scenario
    * branch, and at the merge base. The conflict verdict and the merged node
    * set are both derived from these three, so they are fetched once and kept.
    */
  private def comparePaths(wsId: WorkspaceId, branch: BranchRef, base: CommitHash): IO[AppError, List[PathComparison]] =
    for
      paths       <- candidatePaths(wsId, branch)
      comparisons <- ZIO.withParallelism(8) {
                       ZIO.foreachPar(paths.toList.sortBy(WorkspaceStoragePaths.of(wsId, _).value)) { path =>
                         val abs = WorkspaceStoragePaths.of(wsId, path)
                         irmin.get(abs, BranchRef.Main)
                           .zipPar(irmin.get(abs, branch))
                           .zipPar(irmin.getAtCommit(base, abs))
                           .map { case (onMain, onScenario, atBase) =>
                             PathComparison(path, Versions(atBase, onMain, onScenario))
                           }
                       }
                     }
    yield comparisons

  /** Node names the merged result would repeat within one tree, computed from
    * the same fetched contents the conflict list uses.
    *
    * Only node paths participate, because a tree's name uniqueness is a
    * property of its node set. A conflicting path is skipped: the merge will
    * not happen at all. A merged value of `None` was deleted on the winning
    * side and contributes no name.
    *
    * Which names count as repeated is decided by `ValidationUtil.duplicateGroups`,
    * the same grouping `SafeName.requireDistinct` uses inside
    * `RiskTree.fromNodes`, so this scan and the invariant it anticipates cannot
    * disagree. This one reports the names rather than failing, because a merge
    * preview describes what a merge would do.
    */
  private def duplicateNodeNames(comparisons: List[PathComparison]): IO[AppError, Map[TreeId, List[SafeName.SafeName]]] =
    val nodeValues = comparisons.flatMap { c =>
      Option.unless(MergeConflictRule.isConflict(c.versions))((c.path, MergeConflictRule.merged(c.versions))).collect {
        case (TreePath.Node(treeId, nodeId), Some(json)) => (treeId, nodeId, json)
      }
    }
    ZIO.foreach(nodeValues) { case (treeId, nodeId, json) =>
      ZIO.fromEither(RiskTreeRepositoryIrmin.decodeStoredNode(json))
        .mapBoth(
          err  => RepositoryFailure(s"Decode node ${nodeId.value} of tree ${treeId.value}: $err"),
          node => treeId -> node.name
        )
    }.map { pairs =>
      pairs
        .groupMap(_._1)(_._2)
        .view
        // Every member of a group is the same name, so the first one names it.
        .mapValues(names => ValidationUtil.duplicateGroups(names, (n: SafeName.SafeName) => n.value).map(_._2.head))
        .filter(_._2.nonEmpty)
        .toMap
    }

  /** Union of the workspace's tree paths on both branches. Paths present at
    * the base but deleted on both branches need no entry: both sides agree
    * (absent = absent), which is never a conflict.
    */
  private def candidatePaths(wsId: WorkspaceId, branch: BranchRef): IO[AppError, Set[TreePath]] =
    pathsOn(wsId, BranchRef.Main).zipPar(pathsOn(wsId, branch)).map(_ ++ _)

  /** Every path of the workspace's trees on one branch, with the identifiers
    * Irmin lists refined where they arrive (ADR-039). */
  private def pathsOn(wsId: WorkspaceId, branch: BranchRef): IO[AppError, Set[TreePath]] =
    for
      listedTrees <- irmin.list(IrminPath.unsafeFrom(WorkspaceStoragePaths.treesRoot(wsId)), branch)
      perTree     <- ZIO.foreach(listedTrees) { listedTree =>
                       for
                         treeId        <- refineListed(listedTree, "tree")(TreeId.fromString)
                         listed        <- irmin.list(IrminPath.unsafeFrom(WorkspaceStoragePaths.treeNodes(wsId, treeId)), branch)
                                            .zipPar(irmin.list(IrminPath.unsafeFrom(WorkspaceStoragePaths.treeMitigations(wsId, treeId)), branch))
                         (nodes, mits)  = listed
                         nodeIds       <- ZIO.foreach(nodes)(refineListed(_, s"node of tree ${treeId.value}")(NodeId.fromString))
                         mitigationIds <- ZIO.foreach(mits)(refineListed(_, s"mitigation of tree ${treeId.value}")(MitigationId.fromString))
                       yield TreePath.Meta(treeId) ::
                         nodeIds.map(TreePath.Node(treeId, _)) :::
                         mitigationIds.map(TreePath.Mitigation(treeId, _))
                     }
    yield perTree.flatten.toSet

  /** One identifier Irmin listed, refined, or a `RepositoryFailure`: this
    * server writes only refined identifiers, so one that does not refine means
    * the store holds what this code did not write. */
  private def refineListed[A](segment: IrminPath, what: String)(refine: String => Either[List[ValidationError], A]): IO[AppError, A] =
    ZIO.fromEither(refine(segment.value)).mapError(errs =>
      RepositoryFailure(s"Listed $what '${segment.value}' does not refine: ${errs.map(_.message).mkString("; ")}"))

object ScenarioMergeServiceLive:
  val layer: ZLayer[IrminClient & RiskTreeRepository, Nothing, ScenarioMergeService] =
    ZLayer.fromFunction(new ScenarioMergeServiceLive(_, _))

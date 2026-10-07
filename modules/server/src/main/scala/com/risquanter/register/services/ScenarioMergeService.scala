package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, MergeStagingRef, Revision, SafeName, TreeId, NodeId}
import com.risquanter.register.domain.errors.{BranchAlreadyExists, BranchHeadStale, IrminMergeConflict, MergeAlreadyRunning, MergeConflict, MergeTargetMoved, RepositoryFailure, ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.infra.irmin.{IrminClient, WorkspaceStoragePaths}
import com.risquanter.register.infra.irmin.model.IrminPath
import com.risquanter.register.repositories.{RiskTreeRepository, RiskTreeRepositoryIrmin}

/** Byte-level three-way merge rule for one storage path (ADR-032, storage
  * relation): Irmin merges a path cleanly iff the two sides hold equal bytes,
  * or one side still equals the merge base (the other side wins). `None`
  * means the path is absent at that point.
  *
  * Deliberately compares the full persisted values, never the domain content
  * hashes — a renamed node is byte-different while domain-hash-identical, so
  * predicting merge outcomes from the semantic diff misses real conflicts
  * (ADR-032 Code Smells).
  */
object MergeConflictRule:
  def isConflict(base: Option[String], onMain: Option[String], onScenario: Option[String]): Boolean =
    onMain != onScenario && onMain != base && onScenario != base

  /** The value Irmin's three-way merge produces for one path that does not
    * conflict: the two sides agree, or exactly one side moved away from the
    * base and that side wins. `None` means the path is absent in the result.
    *
    * Defined only where `isConflict` is false. The two functions partition the
    * same three inputs, so a caller decides conflict first and asks for the
    * merged value second.
    */
  def merged(base: Option[String], onMain: Option[String], onScenario: Option[String]): Option[String] =
    (onMain, onScenario) match
      case (m, s) if m == s    => m
      case (m, _) if m == base => onScenario
      case (m, _)              => m

/** One conflicting storage path, workspace-relative — never contains the
  * `WorkspaceId` (workspace identity must not reach the wire).
  *
  * `treeId`/`nodeId` are parsed out of the known path shapes for the UI;
  * `nodeId` is empty for a tree's `meta` or `mitigations/{id}` conflict, both
  * are empty for an unrecognised path shape (the raw relative path is always
  * carried, and for a mitigation conflict it holds the mitigation id segment).
  */
final case class MergeConflictPath(path: String, treeId: Option[TreeId], nodeId: Option[NodeId])

object MergeConflictPath:
  /** Parse `risk-trees/{treeId}/meta`, `risk-trees/{treeId}/nodes/{nodeId}`,
    * and `risk-trees/{treeId}/mitigations/{mitigationId}` into structured
    * coordinates; anything else keeps only the raw path. A mitigation conflict
    * carries its `treeId` and the raw path (which holds the mitigation id
    * segment); it has no node coordinate.
    */
  def fromRelativePath(rel: String): MergeConflictPath =
    rel.split('/').toList match
      case "risk-trees" :: treeId :: "meta" :: Nil =>
        MergeConflictPath(rel, TreeId.fromString(treeId).toOption, None)
      case "risk-trees" :: treeId :: "nodes" :: nodeId :: Nil =>
        MergeConflictPath(rel, TreeId.fromString(treeId).toOption, NodeId.fromString(nodeId).toOption)
      case "risk-trees" :: treeId :: "mitigations" :: _ :: Nil =>
        MergeConflictPath(rel, TreeId.fromString(treeId).toOption, None)
      case _ =>
        MergeConflictPath(rel, None, None)

/** Outcome of a merge preview. A plain multi-case enum (mirrors
  * `ChangedNodesResult`): a missing scenario is a distinct non-error outcome
  * of a read-only preview, not a failure.
  */
enum MergePreviewResult:
  case Clean
  case Conflicts(paths: List[MergeConflictPath])
  case DuplicateNames(details: String)
  case ScenarioMissing

/** Scenario → main merge (DD-10).
  *
  * Conflict handling is two-layered. The byte-level pre-check enumerates the
  * conflicting paths (Irmin's conflict error names no paths) and lets both
  * `preview` and `merge` answer with the exact per-node conflict list;
  * `merge` refuses up front when the pre-check finds conflicts. The patched
  * Irmin backend (see `IrminClient.mergeBranch`) is the backstop for the
  * remaining race: a main write that introduces a conflict between the scan
  * and the merge makes the merge itself fail typed (`IrminMergeConflict`),
  * which is mapped to the domain `MergeConflict` here.
  *
  * The pre-check compares full persisted node JSON byte-for-byte between
  * main, the scenario, and their lowest common ancestor — the storage
  * relation of ADR-032. It never consults the domain content hashes
  * (`ChangedNodesService`), which by design ignore renames/moves and
  * therefore cannot predict merge outcomes.
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
    (using Checked[Permission]): Task[MergePreviewResult]

  /** Merge the scenario into main via Irmin's native three-way merge.
    *
    * @return main's new head commit
    * @see MergeConflict — pre-check found conflicting paths (409), or a
    *      concurrent main write introduced one mid-merge
    * @see ValidationFailed NOT_FOUND — the scenario does not exist
    */
  def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): Task[CommitHash]

final class ScenarioMergeServiceLive(irmin: IrminClient, repo: RiskTreeRepository) extends ScenarioMergeService:

  override def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): Task[MergePreviewResult] =
    for
      branch    <- ScenarioBranchOps.scenarioBranch(wsId, name)
      maybeHead <- irmin.getBranch(branch).map(_.flatMap(_.head))
      result    <- maybeHead match
                     case None => ZIO.succeed(MergePreviewResult.ScenarioMissing)
                     case Some(commit) =>
                       ScenarioBranchOps.refineCommitHash(commit.hash)
                         .flatMap(scan(wsId, branch, _))
                         .map(s =>
                           if s.conflicts.nonEmpty then MergePreviewResult.Conflicts(s.conflicts)
                           else if s.duplicateNames.nonEmpty then MergePreviewResult.DuplicateNames(duplicateNameDetails(s.duplicateNames))
                           else MergePreviewResult.Clean)
    yield result

  override def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): Task[CommitHash] =
    for
      branch       <- ScenarioBranchOps.scenarioBranch(wsId, name)
      maybeHead    <- irmin.getBranch(branch).map(_.flatMap(_.head))
      scenarioHead <- maybeHead match
                        case Some(commit) => ScenarioBranchOps.refineCommitHash(commit.hash)
                        case None =>
                          ZIO.fail(ValidationFailed(List(ValidationError(
                            field = "scenario",
                            code = ValidationErrorCode.NOT_FOUND,
                            message = s"Scenario '${name.value}' not found in workspace ${wsId.value}"
                          ))))
      result       <- scan(wsId, branch, scenarioHead)
      _            <- ZIO.when(result.conflicts.nonEmpty)(ZIO.fail(MergeConflict(
                        name,
                        s"${result.conflicts.size} conflicting path(s): ${result.conflicts.map(_.path).mkString(", ")}"
                      )))
      _            <- ZIO.when(result.duplicateNames.nonEmpty)(ZIO.fail(MergeConflict(
                        name,
                        duplicateNameDetails(result.duplicateNames)
                      )))
      // The head the merge is assembled against and published against. A
      // scenario branch can only be created at a commit that already exists and
      // a branch head never moves backwards, so main always holds one by the
      // time a merge into it runs; an absent head is an invariant violation.
      mainBefore   <- irmin.mainBranch.map(_.flatMap(_.head))
                        .flatMap {
                          case Some(c) => ScenarioBranchOps.refineCommitHash(c.hash)
                          case None    => ZIO.die(new IllegalStateException(
                            s"merge of scenario ${name.value} found no head on main"))
                        }
      newHead      <- mergeOnStaging(wsId, name, branch, mainBefore)
    yield newHead

  /** Assemble the merge on a staging branch, validate it there, and publish it
    * to main with one compare-and-set against the head observed before the
    * merge began.
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
    mainBefore: CommitHash
  ): Task[CommitHash] =
    for
      staging <- ZIO.fromEither(MergeStagingRef.forScenario(wsId, name))
                   .orElseFail(new IllegalStateException(
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
                     commit <- irmin.mergeBranch(scenarioBranch, staging, mergeMessage(wsId, name))
                                 .catchSome { case IrminMergeConflict(_) =>
                                   ZIO.fail(MergeConflict(
                                     name,
                                     "merge was refused — the branches conflict; re-run the preview and retry"
                                   ))
                                 }
                     staged <- ScenarioBranchOps.refineCommitHash(commit.hash)
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
  ): Task[Unit] =
    repo.getAllForWorkspace(wsId, Revision.At(staged)).flatMap { loaded =>
      loaded.collect { case Left(failure) => failure } match
        case Nil      => ZIO.unit
        case failures =>
          ZIO.logError(
            s"merge guard rejected ${staged.value} for scenario ${scenario.value}: " +
            failures.map(f => s"${f.treeId.value}: ${f.reason}").mkString("; ")
          ) *> ZIO.fail(MergeConflict(scenario,
            s"the merged state breaks a tree invariant in ${failures.size} tree(s) " +
            s"(${failures.map(_.treeId.value).sorted.mkString(", ")}), so the merge was not applied"))
    }

  // ── conflict scan ────────────────────────────────────────────────────────

  /** One candidate path with the three values the merge decides between. */
  private final case class PathComparison(
    rel: String,
    atBase: Option[String],
    onMain: Option[String],
    onScenario: Option[String]
  )

  private final case class MergeScan(
    conflicts: List[MergeConflictPath],
    duplicateNames: Map[TreeId, List[SafeName.SafeName]]
  )

  /** Compute the byte-level conflict set between main and the scenario
    * against their merge base. No conflicts by construction when main has no
    * head (nothing to collide with), when the scenario is fully contained in
    * main (base = scenario head), or when main has not moved since the fork
    * (base = main head).
    *
    * With several LCAs (criss-cross histories) the first is used; scenario
    * branches fork linearly from a single commit (DD-5), so multiple LCAs do
    * not arise through this application's own writes.
    */
  private def scan(wsId: WorkspaceId, branch: BranchRef, scenarioHead: CommitHash): Task[MergeScan] =
    irmin.mainBranch.map(_.flatMap(_.head)).flatMap {
      case None => ZIO.succeed(MergeScan(Nil, Map.empty))
      case Some(mainCommit) =>
        for
          mainHead    <- ScenarioBranchOps.refineCommitHash(mainCommit.hash)
          lcas        <- irmin.lca(BranchRef.Main, scenarioHead)
          base        <- lcas.headOption match
                           case Some(c) => ScenarioBranchOps.refineCommitHash(c.hash)
                           case None =>
                             ZIO.die(new IllegalStateException(
                               s"no common ancestor between main and ${branch.toBranchRef} — " +
                               "violates DD-5 (scenarios always fork from an existing commit)"
                             ))
          comparisons <- if base == scenarioHead || base == mainHead then ZIO.succeed(Nil)
                         else comparePaths(wsId, branch, base)
          conflicts    = comparisons.flatMap(c =>
                           Option.when(MergeConflictRule.isConflict(c.atBase, c.onMain, c.onScenario))(
                             MergeConflictPath.fromRelativePath(c.rel)))
          duplicates  <- duplicateNodeNames(comparisons)
        yield MergeScan(conflicts, duplicates)
    }

  /** Fetch, for every candidate path, the value on main, the value on the
    * scenario branch, and the value at the merge base. The conflict verdict and
    * the merged node set are both derived from these three, so they are fetched
    * once and kept.
    */
  private def comparePaths(wsId: WorkspaceId, branch: BranchRef, base: CommitHash): Task[List[PathComparison]] =
    for
      paths       <- candidatePaths(wsId, branch)
      comparisons <- ZIO.withParallelism(8) {
                       ZIO.foreachPar(paths.toList.sorted) { rel =>
                         val abs = IrminPath.unsafeFrom(s"${WorkspaceStoragePaths.workspaceRoot(wsId)}/$rel")
                         irmin.get(abs, BranchRef.Main)
                           .zipPar(irmin.get(abs, branch))
                           .zipPar(irmin.getAtCommit(base, abs))
                           .map { case (onMain, onScenario, atBase) =>
                             PathComparison(rel, atBase, onMain, onScenario)
                           }
                       }
                     }
    yield comparisons

  /** Node names the merged result would repeat within one tree, computed from
    * the same fetched values the conflict list uses.
    *
    * Only `nodes/{nodeId}` paths participate, because a tree's name uniqueness
    * is a property of its node set. A conflicting path is skipped: the merge
    * will not happen at all. A merged value of `None` was deleted on the
    * winning side and contributes no name. A path whose node id segment does
    * not refine is skipped here and caught by the post-merge guard, which reads
    * through `fromNodes`.
    *
    * Which names count as repeated is decided by `SafeName.duplicates`, the
    * same definition `RiskTree.fromNodes` reads, so this scan and the invariant
    * it anticipates cannot disagree.
    */
  private def duplicateNodeNames(comparisons: List[PathComparison]): Task[Map[TreeId, List[SafeName.SafeName]]] =
    val nodeValues = comparisons.flatMap { c =>
      Option.unless(MergeConflictRule.isConflict(c.atBase, c.onMain, c.onScenario)) {
        (MergeConflictPath.fromRelativePath(c.rel), MergeConflictRule.merged(c.atBase, c.onMain, c.onScenario))
      }.collect {
        case (MergeConflictPath(rel, Some(treeId), Some(_)), Some(json)) => (rel, treeId, json)
      }
    }
    ZIO.foreach(nodeValues) { case (rel, treeId, json) =>
      ZIO.fromEither(RiskTreeRepositoryIrmin.decodeStoredNode(json))
        .mapBoth(
          err  => RepositoryFailure(s"Decode node at $rel: $err"),
          node => treeId -> node.name
        )
    }.map { pairs =>
      pairs
        .groupMap(_._1)(_._2)
        .view
        .mapValues(SafeName.duplicates)
        .filter(_._2.nonEmpty)
        .toMap
    }

  /** One line naming the duplicated names per tree, used by both the merge
    * refusal and the preview response. The names stay refined until here,
    * which is the point they are rendered.
    */
  private def duplicateNameDetails(duplicates: Map[TreeId, List[SafeName.SafeName]]): String =
    duplicates.toList.sortBy(_._1.value)
      .map { case (treeId, names) => s"tree ${treeId.value}: ${names.map(_.value).mkString(", ")}" }
      .mkString("merging would duplicate node name(s) — ", "; ", "")

  /** Union of the workspace's storage paths on both branches, relative to the
    * workspace root. Paths present at the base but deleted on both branches
    * need no entry: both sides agree (absent = absent), which is never a
    * conflict.
    */
  private def candidatePaths(wsId: WorkspaceId, branch: BranchRef): Task[Set[String]] =
    pathsOn(wsId, BranchRef.Main).zipPar(pathsOn(wsId, branch)).map(_ ++ _)

  private def pathsOn(wsId: WorkspaceId, branch: BranchRef): Task[Set[String]] =
    val treesRoot = IrminPath.unsafeFrom(WorkspaceStoragePaths.treesRoot(wsId))
    for
      treeIds <- irmin.list(treesRoot, branch)
      perTree <- ZIO.foreach(treeIds) { treeId =>
                   val base = s"${treesRoot.value}/${treeId.value}"
                   irmin.list(IrminPath.unsafeFrom(s"$base/nodes"), branch)
                     .zipPar(irmin.list(IrminPath.unsafeFrom(s"$base/mitigations"), branch))
                     .map { case (nodes, mits) =>
                       s"risk-trees/${treeId.value}/meta" ::
                         nodes.map(n => s"risk-trees/${treeId.value}/nodes/${n.value}") :::
                         mits.map(m => s"risk-trees/${treeId.value}/mitigations/${m.value}")
                     }
                 }
    yield perTree.flatten.toSet

  private def mergeMessage(wsId: WorkspaceId, name: ScenarioName.ScenarioName): String =
    s"workspace:${wsId.value}:merge-scenario:${name.value}"

object ScenarioMergeServiceLive:
  val layer: ZLayer[IrminClient & RiskTreeRepository, Nothing, ScenarioMergeService] =
    ZLayer.fromFunction(new ScenarioMergeServiceLive(_, _))

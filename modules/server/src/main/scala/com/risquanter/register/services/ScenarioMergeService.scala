package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, TreeId, NodeId}
import com.risquanter.register.domain.errors.{MergeConflict, ValidationFailed, ValidationError, ValidationErrorCode}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.IrminPath

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

/** One conflicting storage path, workspace-relative — never contains the
  * `WorkspaceId` (workspace identity must not reach the wire).
  *
  * `treeId`/`nodeId` are parsed out of the known path shapes for the UI;
  * `nodeId` is empty for a tree's `meta` conflict, both are empty for an
  * unrecognised path shape (the raw relative path is always carried).
  */
final case class MergeConflictPath(path: String, treeId: Option[TreeId], nodeId: Option[NodeId])

object MergeConflictPath:
  /** Parse `risk-trees/{treeId}/meta` and `risk-trees/{treeId}/nodes/{nodeId}`
    * into structured coordinates; anything else keeps only the raw path.
    */
  def fromRelativePath(rel: String): MergeConflictPath =
    rel.split('/').toList match
      case "risk-trees" :: treeId :: "meta" :: Nil =>
        MergeConflictPath(rel, TreeId.fromString(treeId).toOption, None)
      case "risk-trees" :: treeId :: "nodes" :: nodeId :: Nil =>
        MergeConflictPath(rel, TreeId.fromString(treeId).toOption, NodeId.fromString(nodeId).toOption)
      case _ =>
        MergeConflictPath(rel, None, None)

/** Outcome of a merge preview. A plain multi-case enum (mirrors
  * `ScenarioDiffResult`): a missing scenario is a distinct non-error outcome
  * of a read-only preview, not a failure.
  */
enum MergePreviewResult:
  case Clean
  case Conflicts(paths: List[MergeConflictPath])
  case ScenarioMissing

/** Scenario → main merge (DD-10).
  *
  * The conflict pre-check is AUTHORITATIVE, not advisory: Irmin's GraphQL
  * `merge_with_branch` silently swallows a conflicting merge — no error, the
  * target head simply does not advance (live-pinned by
  * `IrminMergeSemanticsSpec`). So `merge` refuses to run when the pre-check
  * finds conflicts, and verifies after the merge that the scenario head
  * became an ancestor of main (which also closes the pre-check → merge race
  * against concurrent main writes).
  *
  * The pre-check compares full persisted node JSON byte-for-byte between
  * main, the scenario, and their lowest common ancestor — the storage
  * relation of ADR-032. It never consults the domain content hashes
  * (`ScenarioDiffService`), which by design ignore renames/moves and
  * therefore cannot predict merge outcomes.
  *
  * Scanning is scoped to the workspace's own subtree: scenario branches only
  * ever receive writes through this workspace's endpoints, so any path that
  * changed on both sides since the fork lies under `workspaces/{wsId}/`.
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

final class ScenarioMergeServiceLive(irmin: IrminClient) extends ScenarioMergeService:

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
                           if s.conflicts.isEmpty then MergePreviewResult.Clean
                           else MergePreviewResult.Conflicts(s.conflicts))
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
                        branch,
                        s"${result.conflicts.size} conflicting path(s): ${result.conflicts.map(_.path).mkString(", ")}"
                      )))
      commit       <- irmin.mergeBranch(branch, BranchRef.Main, mergeMessage(wsId, name))
      newHead      <- ScenarioBranchOps.refineCommitHash(commit.hash)
      // A swallowed conflict (see trait scaladoc) leaves the scenario head
      // outside main's ancestry — the merge is only done when the scenario
      // head is an ancestor of (an LCA with) main's new head.
      merged       <- irmin.lca(BranchRef.Main, scenarioHead).map(_.map(_.hash).contains(scenarioHead.value))
      _            <- ZIO.unless(merged)(ZIO.fail(MergeConflict(
                        branch,
                        "merge was not applied — main changed concurrently and now conflicts; re-run the preview and retry"
                      )))
    yield newHead

  // ── conflict scan ────────────────────────────────────────────────────────

  private final case class MergeScan(conflicts: List[MergeConflictPath])

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
      case None => ZIO.succeed(MergeScan(Nil))
      case Some(mainCommit) =>
        for
          mainHead  <- ScenarioBranchOps.refineCommitHash(mainCommit.hash)
          lcas      <- irmin.lca(BranchRef.Main, scenarioHead)
          base      <- lcas.headOption match
                         case Some(c) => ScenarioBranchOps.refineCommitHash(c.hash)
                         case None =>
                           ZIO.die(new IllegalStateException(
                             s"no common ancestor between main and ${branch.toBranchRef} — " +
                             "violates DD-5 (scenarios always fork from an existing commit)"
                           ))
          conflicts <- if base == scenarioHead || base == mainHead then ZIO.succeed(Nil)
                       else findConflicts(wsId, branch, base)
        yield MergeScan(conflicts)
    }

  private def findConflicts(wsId: WorkspaceId, branch: BranchRef, base: CommitHash): Task[List[MergeConflictPath]] =
    for
      paths     <- candidatePaths(wsId, branch)
      conflicts <- ZIO.withParallelism(8) {
                     ZIO.foreachPar(paths.toList.sorted) { rel =>
                       val abs = IrminPath.unsafeFrom(s"${workspaceRoot(wsId)}/$rel")
                       irmin.get(abs, BranchRef.Main)
                         .zipPar(irmin.get(abs, branch))
                         .zipPar(irmin.getAtCommit(base, abs))
                         .map { case (onMain, onScenario, atBase) =>
                           Option.when(MergeConflictRule.isConflict(atBase, onMain, onScenario))(
                             MergeConflictPath.fromRelativePath(rel))
                         }
                     }
                   }.map(_.flatten)
    yield conflicts

  /** Union of the workspace's storage paths on both branches, relative to the
    * workspace root. Paths present at the base but deleted on both branches
    * need no entry: both sides agree (absent = absent), which is never a
    * conflict.
    */
  private def candidatePaths(wsId: WorkspaceId, branch: BranchRef): Task[Set[String]] =
    pathsOn(wsId, BranchRef.Main).zipPar(pathsOn(wsId, branch)).map(_ ++ _)

  private def pathsOn(wsId: WorkspaceId, branch: BranchRef): Task[Set[String]] =
    val treesRoot = IrminPath.unsafeFrom(s"${workspaceRoot(wsId)}/risk-trees")
    for
      treeIds <- irmin.list(treesRoot, branch)
      perTree <- ZIO.foreach(treeIds) { treeId =>
                   irmin.list(IrminPath.unsafeFrom(s"${treesRoot.value}/${treeId.value}/nodes"), branch)
                     .map(nodes =>
                       s"risk-trees/${treeId.value}/meta" ::
                         nodes.map(n => s"risk-trees/${treeId.value}/nodes/${n.value}"))
                 }
    yield perTree.flatten.toSet

  private def workspaceRoot(wsId: WorkspaceId): String =
    s"workspaces/${wsId.value}"

  private def mergeMessage(wsId: WorkspaceId, name: ScenarioName.ScenarioName): String =
    s"workspace:${wsId.value}:merge-scenario:${name.value}"

object ScenarioMergeServiceLive:
  val layer: ZLayer[IrminClient, Nothing, ScenarioMergeService] =
    ZLayer.fromFunction(new ScenarioMergeServiceLive(_))

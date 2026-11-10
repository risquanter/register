package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchChoice, BranchRef, CommitHash, MergeStagingRef, StoreBranch}
import com.risquanter.register.domain.errors.{AppError, Failure, DataConflict, ErrorLocation, FieldName, FieldProblem, ScenarioHeadStale, ValidationFailed, ValidationError}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminWriteOutcome}

/** Irmin-backed `ScenarioService`.
  *
  * Branch shape: `scenarios.<workspaceId-lowercased-ulid>.<name-slug>`.
  * `WorkspaceId`/`ScenarioName` are already Iron-refined at this point (validate
  * once at the boundary), so composing them into a `BranchRef` can never fail —
  * a failure there is an invariant violation (`ZIO.die`), not a domain error.
  *
  * A refused compare-and-set is an outcome `IrminClient` returns, and this
  * service decides what it means: a refused create is a name collision,
  * `DataConflict`, and a refused delete is a moved head, `ScenarioHeadStale`.
  * The match on the outcome is exhaustive, so a refusal cannot go untranslated.
  */
final class ScenarioServiceLive(irmin: IrminClient) extends ScenarioService:

  override def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)
    (using Checked[Authority]): IO[AppError | Failure, BranchRef] =
    for
      branch     <- scenarioBranch(wsId, name)
      sourceHead <- resolveSourceHead(wsId, source)
      outcome    <- irmin.createBranchAt(branch, sourceHead)
      _          <- outcome match
                      case IrminWriteOutcome.Applied    => ZIO.unit
                      case IrminWriteOutcome.Refused(_) => ZIO.fail(DataConflict(s"Scenario '${name.value}' already exists"))
    yield branch

  override def list(wsId: WorkspaceId)(using Checked[Authority]): IO[AppError | Failure, List[ScenarioSummary]] =
    for
      all       <- irmin.branches
      names      = all.flatMap(scenarioNameOf(wsId, _))
      summaries <- ZIO.foreach(names)(summaryFor(wsId, _))
    yield summaries

  override def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Authority]): IO[AppError | Failure, Unit] =
    for
      branch <- scenarioBranch(wsId, name)
      outcome <- irmin.deleteBranch(branch, expectedHead)
      _       <- outcome match
                   case IrminWriteOutcome.Applied       => ZIO.unit
                   case IrminWriteOutcome.Refused(head) => ZIO.fail(ScenarioHeadStale(name, expectedHead, head))
    yield ()

  // ── source resolution ────────────────────────────────────────────────────

  private def resolveSourceHead(wsId: WorkspaceId, source: ScenarioSource): IO[AppError | Failure, CommitHash] =
    source match
      case ScenarioSource.Main =>
        irmin.mainBranch.flatMap {
          case Some(IrminBranch(_, Some(commit))) => ZIO.succeed(commit.hash)
          case _ =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              ErrorLocation.requestField(FieldName.Source), FieldProblem.WorkspaceHasNoContent))))
        }
      case ScenarioSource.ForkOf(scenario) =>
        for
          branch      <- scenarioBranch(wsId, scenario)
          maybeBranch <- irmin.getBranch(branch)
          head        <- maybeBranch.flatMap(_.head) match
                           case Some(commit) => ZIO.succeed(commit.hash)
                           case None =>
                             ZIO.fail(ValidationFailed(List(ValidationError(
                               ErrorLocation.requestField(FieldName.Source), FieldProblem.ScenarioNotFound(scenario)))))
        yield head
      case ScenarioSource.AtCommit(commit) =>
        // Fork-from-history: the commit must be in this workspace's own
        // history. A commit that exists elsewhere is answered as not found,
        // oracle-constant with a hash that was never issued.
        reachableFromOwnHistory(wsId, commit).flatMap {
          case true => ZIO.succeed(commit)
          case false =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              ErrorLocation.requestField(FieldName.Source), FieldProblem.CommitNotFound(commit)))))
        }

  /** Whether the commit lies in the history this workspace may fork from: the
    * shared main branch, or one of this workspace's own scenario branches.
    *
    * A commit names a snapshot of the whole repository and not of one
    * workspace, because every workspace lives under its own path prefix inside
    * one store and `main` is shared by all of them. So a branch pointed at an
    * arbitrary commit holds every other workspace's data as it stood at that
    * commit, and merging that branch carries their unpublished edits onto
    * `main`: the pre-merge scan compares only paths under the caller's own
    * prefix, so it cannot see them. Requiring the commit to be in this
    * workspace's history closes that, because the merge base then equals the
    * fork point on every foreign path and the merge has nothing foreign to
    * apply.
    *
    * `lca` answers the question directly. The lowest common ancestor of a
    * branch and a commit is the commit itself exactly when the commit is an
    * ancestor of that branch.
    */
  private def reachableFromOwnHistory(wsId: WorkspaceId, commit: CommitHash): IO[AppError | Failure, Boolean] =
    for
      listed      <- irmin.branches
      ownNames     = listed.flatMap(scenarioNameOf(wsId, _))
      ownBranches <- ZIO.foreach(ownNames)(scenarioBranch(wsId, _))
      reachable   <- ZIO.exists(BranchRef.Main :: ownBranches)(isAncestorOf(_, commit))
    yield reachable

  /** Whether `commit` is an ancestor of `branch`, read off the lowest common
    * ancestors Irmin reports for the pair. A branch Irmin does not know is not
    * a history this commit can be reached from, so a failure to read it is
    * false rather than an error. */
  private def isAncestorOf(branch: BranchRef, commit: CommitHash): IO[AppError | Failure, Boolean] =
    irmin.lca(branch, commit)
      .map(_.exists(_.hash == commit))
      .orElseSucceed(false)

  private def summaryFor(wsId: WorkspaceId, name: ScenarioName.ScenarioName): IO[AppError | Failure, ScenarioSummary] =
    for
      branch      <- scenarioBranch(wsId, name)
      maybeBranch <- irmin.getBranch(branch)
      irminBranch <- maybeBranch match
                       case Some(b) => ZIO.succeed(b)
                       case None =>
                         ZIO.die(new IllegalStateException(
                           s"scenario branch ${branch.toBranchRef} was listed by branches() but getBranch found nothing"
                         ))
      commit      <- irminBranch.head match
                       case Some(c) => ZIO.succeed(c)
                       case None =>
                         ZIO.die(new IllegalStateException(
                           s"scenario branch ${branch.toBranchRef} has no head — creation always forks at a commit"
                         ))
    yield ScenarioSummary(name, commit.hash)

  // ── naming ───────────────────────────────────────────────────────────────

  /** The scenario name a listed store branch carries, or `None` when the branch
    * is main, belongs to another workspace, or is a merge's staging branch.
    *
    * Reads the same inverse `BranchChoice.fromBranchRef` applies, so the
    * listing and the composition cannot disagree about how a scenario branch is
    * named. A branch this returns `None` for is skipped rather than failing the
    * listing: another workspace's branch is not this caller's concern, and a
    * staging branch is nobody's scenario.
    */
  private def scenarioNameOf(wsId: WorkspaceId, branch: StoreBranch): Option[ScenarioName.ScenarioName] =
    branch match
      case ref: BranchRef =>
        BranchChoice.fromBranchRef(wsId, ref).toOption.collect { case BranchChoice.Scenario(name) => name }
      case _: MergeStagingRef =>
        None

  private def scenarioBranch(wsId: WorkspaceId, name: ScenarioName.ScenarioName): IO[AppError | Failure, BranchRef] =
    ScenarioBranchOps.scenarioBranch(wsId, name)

object ScenarioServiceLive:
  val layer: ZLayer[IrminClient, Nothing, ScenarioService] =
    ZLayer.fromFunction(new ScenarioServiceLive(_))

package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchChoice, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.{AppError, DataConflict, ErrorLocation, FieldName, ScenarioHeadStale, ValidationFailed, ValidationError, ValidationErrorCode, BranchAlreadyExists, BranchHeadStale}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.IrminBranch

/** Irmin-backed `ScenarioService`.
  *
  * Branch shape: `scenarios.<workspaceId-lowercased-ulid>.<name-slug>`.
  * `WorkspaceId`/`ScenarioName` are already Iron-refined at this point (validate
  * once at the boundary), so composing them into a `BranchRef` can never fail —
  * a failure there is an invariant violation (`ZIO.die`), not a domain error.
  *
  * Compare-and-set errors from `IrminClient` are translated into domain errors
  * here rather than left to reach the wire as raw `IrminError`s, so a
  * `BranchAlreadyExists` or `BranchHeadStale` arriving at the projection means
  * this translation was skipped. Reuses `DataConflict`/`VersionConflict` rather
  * than introducing new types.
  */
final class ScenarioServiceLive(irmin: IrminClient) extends ScenarioService:

  override def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)
    (using Checked[Permission]): IO[AppError, BranchRef] =
    for
      branch     <- scenarioBranch(wsId, name)
      sourceHead <- resolveSourceHead(wsId, source)
      _          <- irmin.createBranchAt(branch, sourceHead).catchSome { case BranchAlreadyExists(_) =>
                      ZIO.fail(DataConflict(s"Scenario '${name.value}' already exists"))
                    }
    yield branch

  override def list(wsId: WorkspaceId)(using Checked[Permission]): IO[AppError, List[ScenarioSummary]] =
    for
      all       <- irmin.branches
      names      = all.flatMap(scenarioNameOf(wsId, _))
      summaries <- ZIO.foreach(names)(summaryFor(wsId, _))
    yield summaries

  override def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Permission]): IO[AppError, Unit] =
    for
      branch <- scenarioBranch(wsId, name)
      _      <- irmin.deleteBranch(branch, expectedHead).catchSome { case BranchHeadStale(_, expected) =>
                  irmin.getBranch(branch).orElseSucceed(None).flatMap { maybeBranch =>
                    val actualHash = maybeBranch.flatMap(_.head).map(_.hash)
                    ZIO.foreach(actualHash)(refineCommitHash).flatMap { actual =>
                      ZIO.fail(ScenarioHeadStale(name, expected, actual))
                    }
                  }
                }
    yield ()

  // ── source resolution ────────────────────────────────────────────────────

  private def resolveSourceHead(wsId: WorkspaceId, source: ScenarioSource): IO[AppError, CommitHash] =
    source match
      case ScenarioSource.Main =>
        irmin.mainBranch.flatMap {
          case Some(IrminBranch(_, Some(commit))) => refineCommitHash(commit.hash)
          case _ =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              location = ErrorLocation.requestField(FieldName.Source),
              code = ValidationErrorCode.NOT_FOUND,
              message = "Workspace has no content yet — nothing to fork a scenario from"
            ))))
        }
      case ScenarioSource.ForkOf(scenario) =>
        for
          branch      <- scenarioBranch(wsId, scenario)
          maybeBranch <- irmin.getBranch(branch)
          head        <- maybeBranch.flatMap(_.head) match
                           case Some(commit) => refineCommitHash(commit.hash)
                           case None =>
                             ZIO.fail(ValidationFailed(List(ValidationError(
                               location = ErrorLocation.requestField(FieldName.Source),
                               code = ValidationErrorCode.NOT_FOUND,
                               message = s"Scenario not found: ${scenario.value}"
                             ))))
        yield head
      case ScenarioSource.AtCommit(commit) =>
        // Fork-from-history: the commit must be in this workspace's own
        // history. A commit that exists elsewhere is answered as not found,
        // oracle-constant with a hash that was never issued.
        reachableFromOwnHistory(wsId, commit).flatMap {
          case true => ZIO.succeed(commit)
          case false =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              location = ErrorLocation.requestField(FieldName.Source),
              code = ValidationErrorCode.NOT_FOUND,
              message = s"Commit not found: ${commit.value}"
            ))))
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
  private def reachableFromOwnHistory(wsId: WorkspaceId, commit: CommitHash): IO[AppError, Boolean] =
    for
      rawNames    <- irmin.branches
      ownNames     = rawNames.flatMap(scenarioNameOf(wsId, _))
      ownBranches <- ZIO.foreach(ownNames)(scenarioBranch(wsId, _))
      reachable   <- ZIO.exists(BranchRef.Main :: ownBranches)(isAncestorOf(_, commit))
    yield reachable

  /** Whether `commit` is an ancestor of `branch`, read off the lowest common
    * ancestors Irmin reports for the pair. A branch Irmin does not know is not
    * a history this commit can be reached from, so a failure to read it is
    * false rather than an error. */
  private def isAncestorOf(branch: BranchRef, commit: CommitHash): IO[AppError, Boolean] =
    irmin.lca(branch, commit)
      .map(_.exists(_.hash == commit.value))
      .orElseSucceed(false)

  private def summaryFor(wsId: WorkspaceId, name: ScenarioName.ScenarioName): IO[AppError, ScenarioSummary] =
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
      hash        <- refineCommitHash(commit.hash)
    yield ScenarioSummary(name, hash)

  // ── naming ───────────────────────────────────────────────────────────────

  /** The scenario name a listed store branch carries, or `None` when the branch
    * is main, belongs to another workspace, or was not composed by
    * `BranchRef.scenario`.
    *
    * Reads the same inverse `BranchChoice.fromBranchRef` applies, so the
    * listing and the composition cannot disagree about how a scenario branch is
    * named. A branch this returns `None` for is skipped rather than failing the
    * listing: another workspace's branch is not this caller's concern, and the
    * store holds branch kinds that are nobody's scenario.
    */
  private def scenarioNameOf(wsId: WorkspaceId, rawName: String): Option[ScenarioName.ScenarioName] =
    BranchRef.fromString(rawName).toOption
      .flatMap(BranchChoice.fromBranchRef(wsId, _).toOption)
      .collect { case BranchChoice.Scenario(name) => name }

  private def scenarioBranch(wsId: WorkspaceId, name: ScenarioName.ScenarioName): IO[AppError, BranchRef] =
    ScenarioBranchOps.scenarioBranch(wsId, name)

  private def refineCommitHash(raw: String): IO[AppError, CommitHash] =
    ScenarioBranchOps.refineCommitHash(raw)

object ScenarioServiceLive:
  val layer: ZLayer[IrminClient, Nothing, ScenarioService] =
    ZLayer.fromFunction(new ScenarioServiceLive(_))

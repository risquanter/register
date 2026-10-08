package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchChoice, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.{DataConflict, ScenarioHeadStale, ValidationFailed, ValidationError, ValidationErrorCode, BranchAlreadyExists, BranchHeadStale}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.IrminBranch

/** Irmin-backed `ScenarioService` (milestone-2b Phase B, DD-5/DD-11).
  *
  * Branch shape: `scenarios.<workspaceId-lowercased-ulid>.<name-slug>` (DD-5).
  * `WorkspaceId`/`ScenarioName` are already Iron-refined at this point (validate
  * once at the boundary), so composing them into a `BranchRef` can never fail —
  * a failure there is an invariant violation (`ZIO.die`), not a domain error.
  *
  * CAS errors from `IrminClient` are translated into domain `SimError`s here,
  * not left to reach HTTP as raw `IrminError`s (see `ErrorResponse.encodeIrminError`'s
  * "safety net" comment — `BranchAlreadyExists`/`BranchHeadStale` reaching that
  * point means this translation was skipped). Reuses `DataConflict`/`VersionConflict`
  * rather than new types, mirroring the DD-10 `MergeConflict` reuse decision.
  */
final class ScenarioServiceLive(irmin: IrminClient) extends ScenarioService:

  override def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)
    (using Checked[Permission]): Task[BranchRef] =
    for
      branch     <- scenarioBranch(wsId, name)
      sourceHead <- resolveSourceHead(wsId, source)
      _          <- irmin.createBranchAt(branch, sourceHead).catchSome { case BranchAlreadyExists(_) =>
                      ZIO.fail(DataConflict(s"Scenario '${name.value}' already exists"))
                    }
    yield branch

  override def list(wsId: WorkspaceId)(using Checked[Permission]): Task[List[ScenarioSummary]] =
    for
      all       <- irmin.branches
      names      = all.flatMap(scenarioNameOf(wsId, _))
      summaries <- ZIO.foreach(names)(summaryFor(wsId, _))
    yield summaries

  override def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Permission]): Task[Unit] =
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

  private def resolveSourceHead(wsId: WorkspaceId, source: ScenarioSource): Task[CommitHash] =
    source match
      case ScenarioSource.Main =>
        irmin.mainBranch.flatMap {
          case Some(IrminBranch(_, Some(commit))) => refineCommitHash(commit.hash)
          case _ =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = "source",
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
                               field = "source",
                               code = ValidationErrorCode.NOT_FOUND,
                               message = s"Scenario not found: ${scenario.value}"
                             ))))
        yield head
      case ScenarioSource.AtCommit(commit) =>
        // Fork-from-history: verify the commit exists (absent → NOT_FOUND,
        // oracle-constant with any other not-found), then fork at it directly.
        irmin.getCommit(commit).flatMap {
          case Some(_) => ZIO.succeed(commit)
          case None =>
            ZIO.fail(ValidationFailed(List(ValidationError(
              field = "source",
              code = ValidationErrorCode.NOT_FOUND,
              message = s"Commit not found: ${commit.value}"
            ))))
        }

  private def summaryFor(wsId: WorkspaceId, name: ScenarioName.ScenarioName): Task[ScenarioSummary] =
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
                           s"scenario branch ${branch.toBranchRef} has no head — violates DD-5/A9 fact 3 (creation always forks at a commit)"
                         ))
      hash        <- refineCommitHash(commit.hash)
    yield ScenarioSummary(name, hash)

  // ── naming (DD-5/DD-11) ──────────────────────────────────────────────────

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

  private def scenarioBranch(wsId: WorkspaceId, name: ScenarioName.ScenarioName): Task[BranchRef] =
    ScenarioBranchOps.scenarioBranch(wsId, name)

  private def refineCommitHash(raw: String): Task[CommitHash] =
    ScenarioBranchOps.refineCommitHash(raw)

object ScenarioServiceLive:
  val layer: ZLayer[IrminClient, Nothing, ScenarioService] =
    ZLayer.fromFunction(new ScenarioServiceLive(_))

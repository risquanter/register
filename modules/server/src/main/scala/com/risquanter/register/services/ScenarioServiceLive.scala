package com.risquanter.register.services

import zio.*
import io.github.iltotore.iron.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, ScenarioNameConstraint, BranchRef, CommitHash}
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

  override def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource = ScenarioSource.Main)
    (using Checked[Permission]): Task[BranchRef] =
    for
      branch     <- scenarioBranch(wsId, name)
      sourceHead <- resolveSourceHead(wsId, source)
      _          <- irmin.createBranchAt(branch, sourceHead).catchSome { case BranchAlreadyExists(_) =>
                      ZIO.fail(DataConflict(s"Scenario '${name.value}' already exists in workspace ${wsId.value}"))
                    }
    yield branch

  override def list(wsId: WorkspaceId)(using Checked[Permission]): Task[List[ScenarioSummary]] =
    val prefix = scenarioPrefix(wsId)
    for
      all       <- irmin.branches
      rawNames   = all.collect { case b if b.startsWith(prefix) => b.stripPrefix(prefix) }
      names     <- ZIO.foreach(rawNames)(parseScenarioName)
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
                      ZIO.fail(ScenarioHeadStale(branch, expected, actual))
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
                               message = s"Scenario '${scenario.value}' not found in workspace ${wsId.value}"
                             ))))
        yield head

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

  private def scenarioPrefix(wsId: WorkspaceId): String =
    s"scenarios.${wsId.value.toLowerCase}."

  private def scenarioBranch(wsId: WorkspaceId, name: ScenarioName.ScenarioName): Task[BranchRef] =
    BranchRef.scenario(wsId, name) match
      case Right(branch) => ZIO.succeed(branch)
      case Left(errors) =>
        ZIO.die(new IllegalStateException(
          s"composed branch for workspace ${wsId.value} + scenario '${name.value}' failed BranchRef validation: $errors — " +
          "unreachable given a valid WorkspaceId + ScenarioName"
        ))

  private def parseScenarioName(rawSegment: String): Task[ScenarioName.ScenarioName] =
    rawSegment.refineEither[ScenarioNameConstraint] match
      case Right(slug) => ZIO.succeed(ScenarioName.ScenarioName(slug))
      case Left(error) =>
        ZIO.die(new IllegalStateException(
          s"branch matched the scenarios.* prefix but its name segment '$rawSegment' is not a valid " +
          s"ScenarioName: $error — DD-11 invariant violation (only ScenarioService creates these branches)"
        ))

  private def refineCommitHash(raw: String): Task[CommitHash] =
    CommitHash.fromString(raw) match
      case Right(hash) => ZIO.succeed(hash)
      case Left(errors) =>
        ZIO.die(new IllegalStateException(
          s"Irmin returned commit hash '$raw' not matching CommitHash's pinned format: $errors"
        ))

object ScenarioServiceLive:
  val layer: ZLayer[IrminClient, Nothing, ScenarioService] =
    ZLayer.fromFunction(new ScenarioServiceLive(_))

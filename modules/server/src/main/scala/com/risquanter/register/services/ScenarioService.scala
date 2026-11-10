package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, PositiveInt}
import com.risquanter.register.domain.errors.{AppError, Core, Failure, ScenariosNotSupported}
import com.risquanter.register.http.errors.ConflictKind
import com.risquanter.register.http.responses.ScenarioSummaryResponse

/** What a new scenario forks from. A create always
  * forks from exactly one source — main, or another scenario's current head —
  * never from nothing, so this is a mandatory two-case selector, not an
  * `Option[ScenarioName]`: `None` would have no distinct meaning from `Main`.
  *
  * Deliberately NOT `BranchChoice`, despite the identical structure:
  * `BranchChoice` answers "which branch does this request operate on",
  * this answers "what does a new scenario fork from". Reusing one type for
  * two distinct meanings would be the reverse of the ADR-018 principle
  * (semantically distinct concepts sharing an encoding get distinct nominal
  * types).
  */
enum ScenarioSource:
  case Main
  case ForkOf(scenario: ScenarioName.ScenarioName)
  case AtCommit(commit: CommitHash)

/** One scenario as returned by `list`. `head` is the branch's current commit,
  * needed by the caller to satisfy `delete`'s compare-and-set precondition:
  * the caller holds the head it observed here and passes
  * it back to `delete`, preserving optimistic concurrency across the whole
  * list -> look -> delete interaction, not just the instant of the delete call.
  */
final case class ScenarioSummary(name: ScenarioName.ScenarioName, head: CommitHash):
  /** This scenario as the list endpoint answers it. The projection is owned
    * here, by the source (ADR-038 §5).
    */
  def toWire: ScenarioSummaryResponse = ScenarioSummaryResponse(name, head)

/** Scenario lifecycle on top of Irmin branches.
  *
  * No `rename`: `createBranchAt` and `deleteBranch` are each individually atomic
  * via compare-and-set, but nothing makes the pair atomic together. The
  * interface composes a rename as two independently-scoped calls: `create`
  * (duplicate under the new name) then `delete` (remove the old one).
  *
  * Every method takes `wsId` explicitly so workspace scoping is visible and
  * compile-time enforced at every call site, matching `RiskTreeService`.
  */
trait ScenarioService:

  /** Create a scenario by forking `source`'s current head. An explicit fork is
    * required: a bare first write starts a branch empty, not as a copy of
    * anything.
    *
    * @param wsId Workspace that owns the scenario
    * @param name User-supplied scenario name (already validated `ScenarioName`)
    * @param source What to fork from — main, an existing scenario, or a
    *               specific commit (fork-from-history). Required; no default.
    * @return The new scenario's branch reference
    * @see Failure.Conflict with `ConflictKind.Rejected` — the name is taken. The compare-and-set on
    *      `createBranchAt` guards the new branch's name, never the source's
    *      staleness: forking is by commit hash, so it is immune to the source
    *      changing mid-fork.
    */
  def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)
    (using Checked[Authority]): IO[AppError | Failure, BranchRef]

  /** List a workspace's scenarios, where ownership is a branch-name prefix
    * filter. Never includes `main` — main is not a scenario.
    */
  def list(wsId: WorkspaceId)(using Checked[Authority]): IO[AppError | Failure, List[ScenarioSummary]]

  /** Delete a scenario via compare-and-set. Only removes the branch
    * pointer — commits remain reachable by hash, so forks survive.
    *
    * @param expectedHead The head the caller last observed (from `list`) —
    *                     required, not resolved internally, so the
    *                     compare-and-set guards the caller's whole
    *                     interaction, not just this call
    * @see Failure.Conflict with `ConflictKind.ScenarioHeadStale` — the head is no longer
    *      `expectedHead` (concurrent modification)
    */
  def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Authority]): IO[AppError | Failure, Unit]

object ScenarioService:
  def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)
    (using Checked[Authority]): ZIO[ScenarioService, AppError | Failure, BranchRef] =
    ZIO.serviceWithZIO[ScenarioService](_.create(wsId, name, source))

  def list(wsId: WorkspaceId)(using Checked[Authority]): ZIO[ScenarioService, AppError | Failure, List[ScenarioSummary]] =
    ZIO.serviceWithZIO[ScenarioService](_.list(wsId))

  def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Authority]): ZIO[ScenarioService, AppError | Failure, Unit] =
    ZIO.serviceWithZIO[ScenarioService](_.delete(wsId, name, expectedHead))

  /** Best-effort cascade deletion — lists a workspace's scenarios, then deletes
    * each one via its own CAS precondition (the head observed from this same
    * `list` call), swallowing individual failures (a stale head race, or a
    * backend that doesn't support scenarios at all).
    *
    * A stale-head conflict (the branch moved between `list` and this
    * `delete` — e.g. a write still in flight when workspace teardown started)
    * is retried exactly once against a freshly re-resolved head, rather than
    * being swallowed immediately: cascade-delete exists specifically to avoid
    * leaving orphaned scenario branches, so silently giving up on the first
    * stale-head race would reproduce the exact leak it closes. If the
    * scenario is gone by the retry, there's nothing left to delete.
    *
    * `list` failing with `ScenariosNotSupported` is routine (the in-memory
    * backend has no scenario support) and is not logged. Any other `list`
    * failure, and any individual `delete` failure surviving the retry, is
    * logged as a warning before being swallowed, so a genuinely orphaned
    * scenario branch is observable instead of silent.
    *
    * Used by `WorkspaceLifecycleController.deleteWorkspace` (explicit delete),
    * `WorkspaceLifecycleController.evictExpired` (admin sweep), and
    * `WorkspaceReaper` (TTL expiry). Single source of truth for the scenario
    * cascade-delete semantic, mirroring `RiskTreeService.cascadeDeleteTrees`.
    */
  extension (self: ScenarioService)
    def cascadeDeleteScenarios(wsId: WorkspaceId, parallelism: PositiveInt)(using Checked[Authority]): UIO[Unit] =
      def deleteWithFreshHeadRetry(s: ScenarioSummary): IO[AppError | Failure, Unit] =
        self.delete(wsId, s.name, s.head).catchSome { case StaleHead() =>
          self.list(wsId).flatMap { fresh =>
            fresh.find(_.name == s.name) match
              case Some(refreshed) => self.delete(wsId, refreshed.name, refreshed.head)
              case None             => ZIO.unit
          }
        }
      self.list(wsId)
        .tapError {
          case _: ScenariosNotSupported => ZIO.unit
          case e => ZIO.logWarning(s"cascadeDeleteScenarios: failed to list scenarios for workspace ${wsId.value}: ${messageOf(e)}")
        }
        .flatMap(scenarios => ZIO.withParallelism(parallelism) {
          ZIO.foreachParDiscard(scenarios)(s =>
            deleteWithFreshHeadRetry(s)
              .tapError(e => ZIO.logWarning(s"cascadeDeleteScenarios: failed to delete scenario '${s.name.value}' in workspace ${wsId.value}: ${messageOf(e)}"))
              .ignore)
        })
        .ignore

  /** Matches the conflict a delete answers when the scenario's head moved. */
  private object StaleHead:
    def unapply(error: AppError | Failure): Boolean = error match
      case Failure.Conflict(Core(_, errors, _)) =>
        errors.exists {
          case (_, ConflictKind.ScenarioHeadStale(_)) => true
          case _                                      => false
        }
      case _ => false

  /** The log text of either failure type. */
  private def messageOf(error: AppError | Failure): String = error match
    case e: AppError => e.logMessage
    case f: Failure  => f.logMessage

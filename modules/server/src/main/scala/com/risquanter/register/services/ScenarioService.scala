package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.ScenariosNotSupported

/** What a new scenario forks from (DD-5, amended 2026-07-20). A create always
  * forks from exactly one source — main, or another scenario's current head —
  * never from nothing, so this is a mandatory two-case selector, not an
  * `Option[ScenarioName]`: `None` would have no distinct meaning from `Main`.
  */
enum ScenarioSource:
  case Main
  case ForkOf(scenario: ScenarioName.ScenarioName)

/** One scenario as returned by `list`. `head` is the branch's current commit,
  * needed by the caller to satisfy `delete`'s CAS precondition (DD-5 Option A,
  * locked 2026-07-20): the caller holds the head it observed here and passes
  * it back to `delete`, preserving optimistic concurrency across the whole
  * list -> look -> delete interaction, not just the instant of the delete call.
  */
final case class ScenarioSummary(name: ScenarioName.ScenarioName, head: CommitHash)

/** Scenario lifecycle on top of Irmin branches (milestone-2b Phase B, DD-5/DD-11).
  *
  * No `rename`: `createBranchAt` and `deleteBranch` are each individually atomic
  * via CAS, but nothing makes the pair atomic together (2026-07-20 amendment to
  * DD-5). The UI composes a rename as two independently-scoped calls: `create`
  * (duplicate under the new name) then `delete` (remove the old one).
  *
  * Every method takes `wsId` explicitly so workspace scoping is visible and
  * compile-time enforced at every call site, matching `RiskTreeService`.
  */
trait ScenarioService:

  /** Create a scenario by forking `source`'s current head (DD-5, A9 fact 3 —
    * an explicit fork is required; a bare first write starts a branch EMPTY,
    * not as a copy of anything).
    *
    * @param wsId Workspace that owns the scenario
    * @param name User-supplied scenario name (already validated `ScenarioName`)
    * @param source What to fork from — main (default) or an existing scenario
    * @return The new scenario's branch reference
    * @see BranchAlreadyExists translated to `DataConflict` — name collision (CAS).
    *      `BranchHeadStale` cannot occur here: `createBranchAt`'s CAS only
    *      guards the new branch's name, never the source's staleness — forking
    *      is by commit hash, immune to the source changing mid-fork (A9).
    */
  def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource = ScenarioSource.Main)
    (using Checked[Permission]): Task[BranchRef]

  /** List a workspace's scenarios (DD-11: ownership = branch-name prefix filter).
    * Never includes `main` — main is not a scenario (DD-5/DD-9 §0).
    */
  def list(wsId: WorkspaceId)(using Checked[Permission]): Task[List[ScenarioSummary]]

  /** Delete a scenario via CAS (DD-5, A9 fact 2). Only removes the branch
    * pointer — commits remain reachable by hash (A9 fact 5); forks survive.
    *
    * @param expectedHead The head the caller last observed (from `list`) —
    *                     required, not resolved internally, so the CAS guards
    *                     the caller's whole interaction, not just this call
    *                     (DD-5 Option A, locked 2026-07-20)
    * @see BranchHeadStale translated to `ScenarioHeadStale` — concurrent modification
    */
  def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Permission]): Task[Unit]

object ScenarioService:
  def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource = ScenarioSource.Main)
    (using Checked[Permission]): ZIO[ScenarioService, Throwable, BranchRef] =
    ZIO.serviceWithZIO[ScenarioService](_.create(wsId, name, source))

  def list(wsId: WorkspaceId)(using Checked[Permission]): ZIO[ScenarioService, Throwable, List[ScenarioSummary]] =
    ZIO.serviceWithZIO[ScenarioService](_.list(wsId))

  def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Permission]): ZIO[ScenarioService, Throwable, Unit] =
    ZIO.serviceWithZIO[ScenarioService](_.delete(wsId, name, expectedHead))

  /** Best-effort cascade deletion — lists a workspace's scenarios, then deletes
    * each one via its own CAS precondition (the head observed from this same
    * `list` call), swallowing individual failures (a stale head race, or a
    * backend that doesn't support scenarios at all).
    *
    * `list` failing with `ScenariosNotSupported` is routine (the in-memory
    * backend has no scenario support) and is not logged. Any other `list`
    * failure, and any individual `delete` failure, is logged as a warning
    * before being swallowed, so a genuinely orphaned scenario branch is
    * observable instead of silent.
    *
    * Used by `WorkspaceLifecycleController.deleteWorkspace` (explicit delete),
    * `WorkspaceLifecycleController.evictExpired` (admin sweep), and
    * `WorkspaceReaper` (TTL expiry). Single source of truth for the scenario
    * cascade-delete semantic, mirroring `RiskTreeService.cascadeDeleteTrees`.
    */
  extension (self: ScenarioService)
    def cascadeDeleteScenarios(wsId: WorkspaceId)(using Checked[Permission]): UIO[Unit] =
      self.list(wsId)
        .tapError {
          case _: ScenariosNotSupported => ZIO.unit
          case e => ZIO.logWarning(s"cascadeDeleteScenarios: failed to list scenarios for workspace ${wsId.value}: ${e.getMessage}")
        }
        .flatMap(scenarios => ZIO.foreachDiscard(scenarios)(s =>
          self.delete(wsId, s.name, s.head)
            .tapError(e => ZIO.logWarning(s"cascadeDeleteScenarios: failed to delete scenario '${s.name.value}' in workspace ${wsId.value}: ${e.getMessage}"))
            .ignore))
        .ignore

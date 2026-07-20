package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.ScenariosNotSupported

/** `ScenarioService` for the in-memory backend (milestone-2b Phase B item 6).
  *
  * The in-memory `RiskTreeRepository` has no Irmin, so no branches exist to
  * create/list/delete. Every method fails with `ScenariosNotSupported` (501) —
  * a distinct, typed, expected condition, not a silently-empty list or a
  * generic 500 (which `RepositoryFailure` would give and would also scrub the
  * reason from the response — wrong here, since the reason is exactly what
  * the caller needs).
  */
object ScenarioServiceNotSupported extends ScenarioService:
  private val reason = "Scenarios require the Irmin backend (repository.type=irmin)"

  override def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource = ScenarioSource.Main)
    (using Checked[Permission]): Task[BranchRef] =
    ZIO.fail(ScenariosNotSupported(reason))

  override def list(wsId: WorkspaceId)(using Checked[Permission]): Task[List[ScenarioSummary]] =
    ZIO.fail(ScenariosNotSupported(reason))

  override def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Permission]): Task[Unit] =
    ZIO.fail(ScenariosNotSupported(reason))

  val layer: ULayer[ScenarioService] = ZLayer.succeed(ScenarioServiceNotSupported)

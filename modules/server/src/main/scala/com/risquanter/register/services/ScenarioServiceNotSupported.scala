package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash}
import com.risquanter.register.domain.errors.{Core, Diagnostic, ErrorLocation, Failure, TrustZone}
import com.risquanter.register.http.errors.Capability

/** `ScenarioService` for the in-memory backend.
  *
  * The in-memory `RiskTreeRepository` has no Irmin, so no branches exist to
  * create/list/delete. Every method fails as `NotSupported` for scenarios
  * (501) — a distinct, typed, expected condition, not a silently-empty list or
  * a generic 500, which would scrub the reason from the response the caller
  * needs.
  */
object ScenarioServiceNotSupported extends ScenarioService:
  /** The deployment has no Irmin store, so it holds no branches. The sentence
    * names the setting that enables scenarios, for the operator's log. */
  private[services] val scenariosUnavailable: Failure =
    Failure.NotSupported(Core(TrustZone.Environment, NonEmptyChunk(ErrorLocation.Whole -> Capability.Scenarios),
      List(Diagnostic.PeerMessage("Scenarios require the Irmin backend (repository.type=irmin)"))))

  override def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)
    (using Checked[Authority]): IO[Failure, BranchRef] =
    ZIO.fail(scenariosUnavailable)

  override def list(wsId: WorkspaceId)(using Checked[Authority]): IO[Failure, List[ScenarioSummary]] =
    ZIO.fail(scenariosUnavailable)

  override def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)
    (using Checked[Authority]): IO[Failure, Unit] =
    ZIO.fail(scenariosUnavailable)

  val layer: ULayer[ScenarioService] = ZLayer.succeed(ScenarioServiceNotSupported)

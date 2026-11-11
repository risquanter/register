package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Authority, Checked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, CommitHash}
import com.risquanter.register.domain.errors.Failure
import com.risquanter.register.http.responses.MergePreviewStatus

/** `ScenarioMergeService` for the in-memory backend: no Irmin, no branches,
  * nothing to merge. Fails as `NotSupported` for scenarios (501), with the
  * same value as `ScenarioServiceNotSupported`.
  */
object ScenarioMergeServiceNotSupported extends ScenarioMergeService:
  override def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Authority]): IO[Failure, MergePreviewStatus] =
    ZIO.fail(ScenarioServiceNotSupported.scenariosUnavailable)

  override def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Authority]): IO[Failure, CommitHash] =
    ZIO.fail(ScenarioServiceNotSupported.scenariosUnavailable)

  val layer: ULayer[ScenarioMergeService] = ZLayer.succeed(ScenarioMergeServiceNotSupported)

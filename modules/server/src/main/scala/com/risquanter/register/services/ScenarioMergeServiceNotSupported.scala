package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, CommitHash}
import com.risquanter.register.domain.errors.ScenariosNotSupported

/** `ScenarioMergeService` for the in-memory backend: no Irmin, no branches,
  * nothing to merge. Fails with `ScenariosNotSupported` (501), mirroring
  * `ScenarioServiceNotSupported`.
  */
object ScenarioMergeServiceNotSupported extends ScenarioMergeService:
  private val reason = "Scenarios require the Irmin backend (repository.type=irmin)"

  override def preview(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): Task[MergePreviewResult] =
    ZIO.fail(ScenariosNotSupported(reason))

  override def merge(wsId: WorkspaceId, name: ScenarioName.ScenarioName)
    (using Checked[Permission]): Task[CommitHash] =
    ZIO.fail(ScenariosNotSupported(reason))

  val layer: ULayer[ScenarioMergeService] = ZLayer.succeed(ScenarioMergeServiceNotSupported)

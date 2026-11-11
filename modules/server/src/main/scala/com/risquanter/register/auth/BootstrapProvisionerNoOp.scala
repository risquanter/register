package com.risquanter.register.auth

import zio.{IO, UIO, ZIO, ZLayer}
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId}
import com.risquanter.register.domain.errors.Failure

/** Always-unit stub. Wired when register.auth.mode = "capability-only" or "identity".
  * No SpiceDB instance required. Ownership is tracked via the DB `owner_id` column only
  * in those modes.
  */
object BootstrapProvisionerNoOp extends BootstrapProvisioner:

  def recordOwnership(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId
  ): IO[Failure, Unit] = ZIO.unit

  def bootstrapToken(): UIO[Checked[LifecycleMarker.Bootstrap.type]] =
    ZIO.succeed(Checked[LifecycleMarker.Bootstrap.type]())

  def systemMaintenanceToken(): UIO[Checked[LifecycleMarker.SystemMaintenance.type]] =
    ZIO.succeed(Checked[LifecycleMarker.SystemMaintenance.type]())

  val layer: ZLayer[Any, Nothing, BootstrapProvisioner] =
    ZLayer.succeed(BootstrapProvisionerNoOp)

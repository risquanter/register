package com.risquanter.register.auth

import zio.{IO, UIO, ZIO, ZLayer}
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId}
import com.risquanter.register.domain.errors.AuthError

/** Always-unit stub. Wired when register.auth.mode = "capability-only" or "identity".
  * No SpiceDB instance required. Ownership is tracked via the DB `owner_id` column only
  * in those modes.
  *
  * @see AUTHORIZATION-PLAN.md — Wave 0: BootstrapProvisionerNoOp wired by default in all modes.
  * @see AUTHORIZATION-IMPLEMENTATION-PLAN.md §D
  */
object BootstrapProvisionerNoOp extends BootstrapProvisioner:

  def recordOwnership(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId
  ): IO[AuthError, Unit] = ZIO.unit

  def bootstrapToken(): UIO[Checked[Permission.Bootstrap.type]] =
    ZIO.succeed(Checked[Permission.Bootstrap.type]())

  def systemMaintenanceToken(): UIO[Checked[Permission.SystemMaintenance.type]] =
    ZIO.succeed(Checked[Permission.SystemMaintenance.type]())

  val layer: ZLayer[Any, Nothing, BootstrapProvisioner] =
    ZLayer.succeed(BootstrapProvisionerNoOp)

package com.risquanter.register.auth

import zio.{IO, Ref, UIO, ZIO, ZLayer}
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId}
import com.risquanter.register.domain.errors.{AuthError, AuthServiceUnavailable}

/** Recording stub for unit tests.
  *
  * Captures every `recordOwnership` call so tests can assert:
  *   - it was invoked (and with what args) in fine-grained mode
  *   - it was not invoked in capability-only mode (when NoOp is wired instead)
  *
  * Use `BootstrapProvisionerStub.make` for the happy-path recording variant.
  * Use `BootstrapProvisionerStub.makeFailing` to test `recordOwnership` failure propagation.
  *
  * @see AUTHORIZATION-IMPLEMENTATION-PLAN.md §Wave 6 — test requirements
  */
final class BootstrapProvisionerStub private (
  callsRef:   Ref[List[(UserId.Authenticated, WorkspaceId)]],
  shouldFail: Boolean
) extends BootstrapProvisioner:

  /** All (userId, workspaceId) pairs passed to `recordOwnership` in call order. */
  def calls: UIO[List[(UserId.Authenticated, WorkspaceId)]] = callsRef.get

  def recordOwnership(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId
  ): IO[AuthError, Unit] =
    if shouldFail then ZIO.fail(AuthServiceUnavailable("SpiceDB unavailable (stub)"))
    else callsRef.update(_ :+ (userId, workspaceId))

  def bootstrapToken(): UIO[Checked[Permission.Bootstrap.type]] =
    ZIO.succeed(Checked[Permission.Bootstrap.type]())

  def systemMaintenanceToken(): UIO[Checked[Permission.SystemMaintenance.type]] =
    ZIO.succeed(Checked[Permission.SystemMaintenance.type]())

object BootstrapProvisionerStub:

  /** Happy-path recording stub. */
  val make: UIO[BootstrapProvisionerStub] =
    Ref.make(List.empty[(UserId.Authenticated, WorkspaceId)])
      .map(new BootstrapProvisionerStub(_, false))

  /** Failing stub — `recordOwnership` always returns `AuthServiceUnavailable`. */
  val makeFailing: UIO[BootstrapProvisionerStub] =
    Ref.make(List.empty[(UserId.Authenticated, WorkspaceId)])
      .map(new BootstrapProvisionerStub(_, true))

  /** ZLayer factory for the happy-path recording variant. */
  val layer: ZLayer[Any, Nothing, BootstrapProvisioner] =
    ZLayer.fromZIO(make)

  /** ZLayer factory for the failing variant. */
  val failingLayer: ZLayer[Any, Nothing, BootstrapProvisioner] =
    ZLayer.fromZIO(makeFailing)

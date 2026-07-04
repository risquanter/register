package com.risquanter.register.auth

import zio.{IO, UIO, ZIO}
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId}
import com.risquanter.register.domain.errors.AuthError

/** Records workspace ownership in SpiceDB as part of workspace creation.
  *
  * This is resource lifecycle management, not policy administration (PAP).
  * The Zanzibar paper (2019) explicitly models this pattern: the creating service
  * records initial ownership inline with resource creation.
  *
  * The distinction from PAP: this write is system-initiated (not driven by a user
  * delegation request). The service account's write scope is limited to `owner_user`
  * and `owner_team` relations on workspace definitions — not arbitrary ACL grants.
  *
  * [[AuthorizationService]] remains a pure PEP: `check()` and `listAccessible()` only.
  * This trait owns the single lifecycle write; all other tuple management is ops-only.
  *
  * @see ADR-024 §1 — resource lifecycle write clarification
  * @see AUTHORIZATION-IMPLEMENTATION-PLAN.md §D — BootstrapProvisioner design
  */
trait BootstrapProvisioner:

  /** Record the creator as owner_user of the newly created workspace.
    *
    * Called once, immediately after workspace creation, within the workspace
    * bootstrap for-comprehension.
    *
    * Writes: workspace:{workspaceId}#owner_user@user:{userId}
    *
    * In capability-only and identity modes this is a no-op — SpiceDB is not
    * active and ownership is tracked via the `owner_id` DB column only.
    */
  def recordOwnership(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId
  ): IO[AuthError, Unit]

  /** Produce a compile-time proof for resource-creation operations.
    *
    * Called at the start of bootstrapWorkspace, before riskTreeService.create()
    * and workspaceStore.addTree(). No SpiceDB call is made — Bootstrap is a
    * lifecycle marker only.
    *
    * @see ADR-030 §5 — Bootstrap Lifecycle Token
    */
  def bootstrapToken(): UIO[Checked[Permission.Bootstrap.type]]

  /** Produce a compile-time proof for background system maintenance operations.
    *
    * Called by WorkspaceReaper before cascadeDeleteTrees and delete.
    * No SpiceDB call is made — SystemMaintenance is a lifecycle marker only.
    *
    * @see ADR-030 §1 — Orchestration Boundary (background orchestrators)
    */
  def systemMaintenanceToken(): UIO[Checked[Permission.SystemMaintenance.type]]

object BootstrapProvisioner:
  val noOp: BootstrapProvisioner = new BootstrapProvisioner:
    def recordOwnership(userId: UserId.Authenticated, workspaceId: WorkspaceId): IO[AuthError, Unit] =
      ZIO.unit
    def bootstrapToken(): UIO[Checked[Permission.Bootstrap.type]] =
      ZIO.succeed(Checked[Permission.Bootstrap.type]())
    def systemMaintenanceToken(): UIO[Checked[Permission.SystemMaintenance.type]] =
      ZIO.succeed(Checked[Permission.SystemMaintenance.type]())

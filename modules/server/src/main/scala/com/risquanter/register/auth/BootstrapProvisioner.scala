package com.risquanter.register.auth

import zio.{IO, ZIO}
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

object BootstrapProvisioner:
  val noOp: BootstrapProvisioner = (_, _) => ZIO.unit

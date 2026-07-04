package com.risquanter.register.auth

import zio.IO
import com.risquanter.register.domain.data.iron.{UserId, SafeId, TreeId, WorkspaceId}
import com.risquanter.register.domain.errors.AuthError

/** Permission names — values match SpiceDB Zed schema `permission` names exactly.
  * A schema rename that doesn't update this enum fails CI at all check() call sites.
  *
  * @see infra/spicedb/schema.zed (planned)
  * @see AUTHORIZATION-PLAN.md — Task L2.2: Authorization Service
  */
enum Permission(val zedName: String):
  case DesignWrite         extends Permission("design_write")
  case AnalyzeRun          extends Permission("analyze_run")
  case ViewWorkspace       extends Permission("view_workspace")
  case AdminWorkspace      extends Permission("admin_workspace")
  case ViewTree            extends Permission("view_tree")
  case ViewOrg             extends Permission("view_org")
  case ViewTeam            extends Permission("view_team")
  case ManageTeam          extends Permission("manage_team")
  /** Lifecycle marker for resource creation. Never sent to SpiceDB.
    * Produced only by BootstrapProvisioner.bootstrapToken().
    * @see ADR-030 §5
    */
  case Bootstrap           extends Permission("__bootstrap__")
  /** Lifecycle marker for background system maintenance. Never sent to SpiceDB.
    * Produced only by BootstrapProvisioner.systemMaintenanceToken().
    * @see ADR-030 §1
    */
  case SystemMaintenance   extends Permission("__system__")

/** Resource type identifiers — values match SpiceDB Zed schema `definition` names exactly.
  *
  * @see infra/spicedb/schema.zed (planned)
  */
enum ResourceType(val zedType: String):
  case Organization extends ResourceType("organization")
  case Team         extends ResourceType("team")
  case Workspace    extends ResourceType("workspace")
  case RiskTree     extends ResourceType("risk_tree")

/** Typed resource reference for check() calls.
  * resourceId reuses SafeId (ULID) since all app resources are identified by ULIDs.
  */
case class ResourceRef(resourceType: ResourceType, resourceId: SafeId.SafeId)

object ResourceRef:
  extension (id: TreeId)
    /** Construct a RiskTree resource reference from a TreeId. */
    def asResource: ResourceRef = ResourceRef(ResourceType.RiskTree, id.toSafeId)

  extension (id: WorkspaceId)
    /** Construct a Workspace resource reference from a WorkspaceId. */
    def asResource: ResourceRef = ResourceRef(ResourceType.Workspace, id.toSafeId)

/** Stable identifier type returned by listAccessible.
  * Callers promote to TreeId / WorkspaceId as needed.
  */
type ResourceId = SafeId.SafeId

/** Compile-time proof that AuthorizationService.check() was called and succeeded.
  *
  * Opaque — no public constructor. Only constructible inside the auth package via
  * AuthorizationService.check() or BootstrapProvisioner.bootstrapToken() /
  * systemMaintenanceToken(). Erased to Unit at runtime; carries no runtime value.
  *
  * Uses the base Permission type in service method `using` parameters — not a
  * specific subtype — to avoid duplicating SpiceDB policy in Scala types (ADR-024).
  * Any Checked[P] where P <: Permission satisfies using Checked[Permission] via
  * covariance.
  *
  * @see ADR-030 — Authorization Enforcement at the Orchestration Boundary
  */
opaque type Checked[+P <: Permission] = Unit

object Checked:
  /** Package-private — only auth-package code may produce proofs. */
  private[auth] def apply[P <: Permission](): Checked[P] = ()

/** Authorization service — pure Policy Enforcement Point (PEP).
  *
  * Callers call check() and listAccessible() only. This service never writes
  * authorization data (no grant/revoke/tuple writes) — all tuple management
  * is handled via the CI/CD provisioning job.
  *
  * @see ADR-024: Externalized Authorization / PEP Pattern
  * @see AUTHORIZATION-PLAN.md — Task L2.2: Authorization Service
  */
trait AuthorizationService:

  /** Check whether `user` has `permission` on `resource`.
    *
    * Fails the ZIO effect — callers use flatMap, never fold/map (fail-closed).
    * - PERMISSIONSHIP_NO_PERMISSION  → AuthForbidden
    * - Connectivity / timeout        → AuthServiceUnavailable (mapped to 403 at HTTP layer)
    *
    * @see ADR-024: Fail-Closed by Default
    */
  def check[P <: Permission](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef
  ): IO[AuthError, Checked[P]]

  /** List all resource IDs of `resourceType` where `user` has `permission`.
    *
    * Calls SpiceDB LookupResources API.
    * Used for "show my workspaces" queries — no local DB join required.
    */
  def listAccessible(
    user:         UserId.Authenticated,
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]]

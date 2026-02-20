package com.risquanter.register.auth

import zio.{IO, ZIO, ZLayer}
import com.risquanter.register.domain.data.iron.UserId
import com.risquanter.register.domain.errors.{AuthError, AuthForbidden}

/** Set-backed stub for unit tests. Allow/deny by explicit (user, permission, resource) set.
  * No live SpiceDB required; inject via ZLayer in test scope.
  *
  * @see AUTHORIZATION-PLAN.md — Wave 0: AuthorizationServiceStub for tests.
  */
final class AuthorizationServiceStub(
  allowed: Set[(UserId, Permission, ResourceRef)]
) extends AuthorizationService:

  def check(
    user:       UserId,
    permission: Permission,
    resource:   ResourceRef
  ): IO[AuthError, Unit] =
    if allowed.contains((user, permission, resource)) then ZIO.unit
    else ZIO.fail(AuthForbidden(
      userId       = user.value,
      permission   = permission.zedName,
      resourceType = resource.resourceType.zedType,
      resourceId   = resource.resourceId.value
    ))

  def listAccessible(
    user:         UserId,
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]] =
    ZIO.succeed(
      allowed.collect {
        case (u, p, ResourceRef(rt, id)) if u == user && p == permission && rt == resourceType => id
      }.toList
    )

object AuthorizationServiceStub:

  /** Stub that denies all checks — for testing denial paths. */
  val denyAll: AuthorizationServiceStub = new AuthorizationServiceStub(Set.empty)

  /** ZLayer factory for test scope injection. */
  def layer(allowed: Set[(UserId, Permission, ResourceRef)]): ZLayer[Any, Nothing, AuthorizationService] =
    ZLayer.succeed(new AuthorizationServiceStub(allowed))

  /** ZLayer factory for deny-all in test scope. */
  val denyAllLayer: ZLayer[Any, Nothing, AuthorizationService] =
    ZLayer.succeed(denyAll)
package com.risquanter.register.auth

import zio.{IO, ZIO, ZLayer}
import com.risquanter.register.domain.data.iron.UserId
import com.risquanter.register.domain.errors.AuthError

/** Always-allow stub. Wired when register.auth.mode = "capability-only" or "identity".
  * No SpiceDB instance required. All checks pass; listAccessible returns Nil
  * (no fine-grained filtering in those modes).
  *
  * @see AUTHORIZATION-PLAN.md — Wave 0: NoOp wired by default in all modes.
  */
object AuthorizationServiceNoOp extends AuthorizationService:

  def check(
    user:       UserId,
    permission: Permission,
    resource:   ResourceRef
  ): IO[AuthError, Unit] = ZIO.unit

  def listAccessible(
    user:         UserId,
    resourceType: ResourceType,
    permission:   Permission
  ): IO[AuthError, List[ResourceId]] = ZIO.succeed(Nil)

  val layer: ZLayer[Any, Nothing, AuthorizationService] =
    ZLayer.succeed(AuthorizationServiceNoOp)

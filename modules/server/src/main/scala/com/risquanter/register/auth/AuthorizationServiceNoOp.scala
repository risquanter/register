package com.risquanter.register.auth

import zio.{IO, ZIO, ZLayer}
import com.risquanter.register.domain.data.iron.UserId
import com.risquanter.register.domain.errors.Failure

/** Always-allow stub. Wired when register.auth.mode = "capability-only" or "identity".
  * No SpiceDB instance required. All checks pass.
  */
object AuthorizationServiceNoOp extends AuthorizationService:

  def check[P <: Permission](
    user:       UserId.Authenticated,
    permission: P,
    resource:   ResourceRef
  ): IO[Failure, Checked[P]] = ZIO.succeed(Checked[P]())

  val layer: ZLayer[Any, Nothing, AuthorizationService] =
    ZLayer.succeed(AuthorizationServiceNoOp)

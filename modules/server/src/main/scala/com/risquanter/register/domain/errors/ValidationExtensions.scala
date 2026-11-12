package com.risquanter.register.domain.errors

import zio.*
import zio.prelude.Validation

/** The bridge from an accumulated `Validation` to an effect.
  *
  * Domain validation accumulates `Validation[ValidationError, A]` (ADR-010).
  * This is the one place such a result enters an effect's error channel, and
  * it does so as a `Failure.Rejected` carrying every accumulated refusal.
  *
  * {{{
  * import com.risquanter.register.domain.errors.ValidationExtensions.*
  *
  * val validation: Validation[ValidationError, RiskTree] = RiskTree.fromNodes(...)
  * val effect: IO[Failure, RiskTree] = validation.toZIOValidation(TrustZone.Client)
  * }}}
  */
object ValidationExtensions:

  extension [A](v: Validation[ValidationError, A])
    /** Succeed with the value, or fail with every accumulated refusal as a
      * `Rejected` at `zone`. The zone has no default, so a call site cannot be
      * written without saying where the refused value came from. */
    def toZIOValidation(zone: TrustZone): IO[Failure, A] =
      ZIO.fromEither(
        v.toEither.left.map(errors => Failure.rejected(zone, errors, Nil))
      )

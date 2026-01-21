package com.risquanter.register.domain.errors

import zio.*
import zio.prelude.Validation

/**
 * Extension methods for bridging ZIO Prelude Validation to ZIO effects.
 * 
 * Per ADR-010, domain validation uses `Validation[ValidationError, A]` for error
 * accumulation. These extensions provide clean conversion to ZIO effects with
 * proper error mapping to `ValidationFailed`.
 * 
 * Usage:
 * {{{
 * import com.risquanter.register.domain.errors.ValidationExtensions.*
 * 
 * val validation: Validation[ValidationError, RiskTree] = RiskTree.fromNodes(...)
 * val effect: IO[ValidationFailed, RiskTree] = validation.toZIOValidation
 * }}}
 */
object ValidationExtensions {

  extension [A](v: Validation[ValidationError, A])
    /**
     * Convert a Validation to a ZIO effect with ValidationFailed error channel.
     * 
     * Successful validation produces a succeed effect.
     * Failed validation produces a fail effect with all accumulated errors.
     * 
     * @return ZIO effect that succeeds with A or fails with ValidationFailed
     */
    def toZIOValidation: IO[ValidationFailed, A] =
      ZIO.fromEither(
        v.toEither.left.map(errors => ValidationFailed(errors.toList))
      )
    
    /**
     * Convert a Validation to a Task by mapping ValidationFailed to Throwable.
     * 
     * Useful when integrating with service methods that use Task error channel.
     * 
     * @return Task that succeeds with A or fails with ValidationFailed (as Throwable)
     */
    def toZIOTask: Task[A] =
      toZIOValidation.mapError(identity)
}

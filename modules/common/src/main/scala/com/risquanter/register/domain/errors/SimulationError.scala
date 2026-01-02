package com.risquanter.register.domain.errors

sealed trait SimulationError extends Throwable

/** Validation failure with accumulated error messages */
case class ValidationFailed(errors: List[String]) extends SimulationError

/** Repository operation failure */
case class RepositoryFailure(reason: String) extends SimulationError

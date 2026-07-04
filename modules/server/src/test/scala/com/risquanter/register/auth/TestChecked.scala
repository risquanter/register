package com.risquanter.register.auth

/** Test-scope only. Produces a Checked[Permission] for service-level tests that
  * operate below the authorization layer (testing persistence/domain behavior,
  * not authorization behavior).
  *
  * Lives in src/test — NOT accessible in production code.
  * Same package as auth — has private[auth] access to Checked.apply.
  *
  * Usage in test suites that call protected service methods directly:
  * {{{
  *   import com.risquanter.register.auth.TestChecked
  *   given Checked[Permission] = TestChecked.value
  * }}}
  *
  * @see ADR-030 — Authorization Enforcement at the Orchestration Boundary
  */
object TestChecked:
  /** Satisfies any `using Checked[Permission]` parameter.
    * Checked[Permission.Bootstrap.type] <: Checked[Permission] via covariance.
    */
  val value: Checked[Permission] = Checked[Permission.Bootstrap.type]()

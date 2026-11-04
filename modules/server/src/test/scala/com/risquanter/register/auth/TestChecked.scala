package com.risquanter.register.auth

/** Test-scope only. Produces a Checked[Authority] for service-level tests that
  * operate below the authorization layer (testing persistence/domain behavior,
  * not authorization behavior).
  *
  * Lives in src/test — NOT accessible in production code.
  * Same package as auth — has private[auth] access to Checked.apply.
  *
  * Usage in test suites that call protected service methods directly:
  * {{{
  *   import com.risquanter.register.auth.TestChecked
  *   given Checked[Authority] = TestChecked.value
  * }}}
  *
  * @see ADR-030 — Authorization Enforcement at the Orchestration Boundary
  */
object TestChecked:
  /** Satisfies any `using Checked[Authority]` parameter.
    * Checked[LifecycleMarker.Bootstrap.type] <: Checked[Authority] via covariance.
    */
  val value: Checked[Authority] = Checked[LifecycleMarker.Bootstrap.type]()

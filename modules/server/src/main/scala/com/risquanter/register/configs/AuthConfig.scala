package com.risquanter.register.configs

/** Authorization mode configuration.
  *
  * Controls which authorization layers are active at runtime:
  *   - "capability-only": Layer 0 only (free-tier default — workspace key is sole credential)
  *   - "identity":        Layer 0+1 (Keycloak JWT required, SpiceDB NoOp)
  *   - "fine-grained":    Layer 0+1+2 (Keycloak JWT + SpiceDB ACL)
  *
  * @see AUTHORIZATION-PLAN.md — Single Codebase, Config-Driven
  * @see ADR-012: Service Mesh / Istio
  */
final case class AuthConfig(
  mode: String = "capability-only"
):
  val normalizedMode: String = mode.trim.toLowerCase

  def isCapabilityOnly: Boolean = normalizedMode == "capability-only"
  def isIdentity: Boolean       = normalizedMode == "identity"
  def isFineGrained: Boolean    = normalizedMode == "fine-grained"

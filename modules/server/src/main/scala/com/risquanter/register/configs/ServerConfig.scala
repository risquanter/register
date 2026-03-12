package com.risquanter.register.configs

/** Server HTTP configuration.
  *
  * @param host    Bind address for both API and health servers
  * @param port    Main API port (default 8090, mTLS STRICT in mesh)
  * @param healthPort Dedicated health-probe port (default 8091, mTLS PERMISSIVE in mesh).
  *                   Serves only /health and /ready — no business endpoints, no interceptors.
  *                   @see ADR-012 (service mesh), AUTHORIZATION-PLAN.md Phase K.5
  */
final case class ServerConfig(
  host: String,
  port: Int,
  healthPort: Int
)

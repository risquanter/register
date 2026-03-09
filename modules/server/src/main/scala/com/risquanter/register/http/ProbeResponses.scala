package com.risquanter.register.http

/** Canonical probe response bodies shared between the main API health endpoint
  * (port 8080, `RiskTreeController.health`) and the dedicated health-probe
  * server (port 8081, `HealthProbeServer`).
  *
  * '''Why two ports serve the same `/health` response:'''
  *   - Port 8080 `/health` — consumed by the frontend `HealthState` component
  *     and Swagger documentation. Served through the full Tapir pipeline with
  *     CORS and security-header interceptors.
  *   - Port 8081 `/health` — consumed exclusively by kubelet liveness probes.
  *     Served on a bare Tapir interpreter (no interceptors) so it can accept
  *     plaintext kubelet probes while the API port enforces mTLS STRICT.
  *
  * Centralising the response maps here ensures both ports return identical JSON,
  * and any future field changes (e.g. adding a `version` key) are made in one
  * place.
  */
object ProbeResponses:

  /** Liveness probe response: `{"status":"healthy","service":"risk-register"}`.
    *
    * Indicates the process is alive and able to serve HTTP. Does NOT imply
    * that downstream dependencies (Irmin, future Postgres) are reachable —
    * that is the responsibility of readiness checks.
    */
  val healthy: Map[String, String] =
    Map("status" -> "healthy", "service" -> "risk-register")

  /** Readiness probe response: `{"status":"ready","service":"risk-register"}`.
    *
    * Currently unconditional (the application has no slow-start dependencies
    * that would delay readiness). When downstream health checks are added
    * (e.g. Postgres connection pool warmup), this response should be gated
    * on those checks returning healthy.
    */
  val ready: Map[String, String] =
    Map("status" -> "ready", "service" -> "risk-register")

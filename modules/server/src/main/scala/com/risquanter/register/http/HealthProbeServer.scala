package com.risquanter.register.http

import zio.*
import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.ziohttp.{ZioHttpInterpreter, ZioHttpServerOptions}
import zio.http.Server

/** Dedicated health-probe HTTP server for kubelet liveness/readiness probes.
  *
  * Runs on a separate port (default 8081) from the main API server (8080) to
  * allow per-port mTLS policy in an Istio ambient mesh:
  *   - Port 8080 (API):    PeerAuthentication STRICT
  *   - Port 8081 (probes): PeerAuthentication PERMISSIVE
  *
  * This follows the established OPA pattern of separating gRPC (9191, STRICT)
  * from diagnostics (8282, PERMISSIVE).
  *
  * '''Design constraints (FR-7, NFR-2):'''
  *   - No application dependencies (no repository, no auth, no Keycloak)
  *   - No Tapir interceptors, no CORS, no security headers, no Swagger
  *   - Only `/health` and `/ready` are served; all other paths return 404 (FR-3)
  *
  * '''Infrastructure note (THREAT-CATALOG.md T1):'''
  *   Port 8081 uses PeerAuthentication PERMISSIVE to accept plaintext kubelet
  *   probes. The accompanying NetworkPolicy must restrict 8081 ingress to
  *   kubelet/ztunnel source only — otherwise an attacker with direct pod access
  *   could reach this port without traversing the waypoint. Since the health
  *   port serves no business data and no auth context, the blast radius is
  *   limited to information disclosure of service liveness (acceptable).
  *
  * @see ADR-012 (service mesh strategy)
  * @see AUTHORIZATION-PLAN.md Phase K.5 (Istio ambient mode)
  * @see THREAT-CATALOG.md T1 (mesh bypass prevention)
  */
object HealthProbeServer:

  // ── Endpoint definitions ─────────────────────────────────────────

  private val healthEndpoint: PublicEndpoint[Unit, Unit, Map[String, String], Any] =
    endpoint.get
      .in("health")
      .out(jsonBody[Map[String, String]])

  private val readyEndpoint: PublicEndpoint[Unit, Unit, Map[String, String], Any] =
    endpoint.get
      .in("ready")
      .out(jsonBody[Map[String, String]])

  // ── Server logic ─────────────────────────────────────────────────

  private val healthRoute: ServerEndpoint[Any, Task] =
    healthEndpoint.serverLogicSuccess { _ =>
      ZIO.succeed(ProbeResponses.healthy)
    }

  private val readyRoute: ServerEndpoint[Any, Task] =
    readyEndpoint.serverLogicSuccess { _ =>
      ZIO.succeed(ProbeResponses.ready)
    }

  /** All probe endpoints exposed on the health port. */
  val endpoints: List[ServerEndpoint[Any, Task]] =
    List(healthRoute, readyRoute)

  /** Pre-built zio-http application for the health probe endpoints.
    *
    * Uses `ZioHttpServerOptions.default` (no interceptors, no CORS, no security
    * headers). Exposed as a `val` so that unit tests exercise the exact same
    * interpreter pipeline as production — no reconstruction needed in test code.
    */
  val httpApp: zio.http.Routes[Any, zio.http.Response] =
    ZioHttpInterpreter(ZioHttpServerOptions.default).toHttp(endpoints)

  // ── Server lifecycle ─────────────────────────────────────────────

  /** Start the health probe server on the given host/port.
    *
    * The returned effect runs forever (until interrupted), binding the health
    * server lifecycle to the calling fiber. Use `<&>` (zipPar) with the main
    * API server so both start and stop together.
    */
  def serve(host: String, port: Int): ZIO[Any, Throwable, Nothing] =
    Server
      .serve(httpApp)
      .provide(
        ZLayer.succeed(Server.Config.default.binding(host, port)),
        Server.live
      )

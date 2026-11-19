# Threat Model Notes — Application

This document holds the assumptions and observations that concern one component
of [APPLICATION-TM.md](APPLICATION-TM.md). An **assumption** is a premise the
model relies on and the repository does not settle. Each one names the check
that would settle it. An **observation** is a fact read from the code or
configuration, with its source.

---

## Docker host and daemon

**Assumption — the host and its Docker daemon are trusted.** Root on the host,
and every member of the `docker` group, can read each container's environment
(including `REGISTER_DB_PASSWORD`), its volumes and its logs, and can exec into
it. The model draws no boundary against them. Whether the host is trusted is
the operator's premise, and the repository cannot settle it.

## All flows

**Observation — compose encrypts no flow.** Every flow in compose is plain HTTP,
GraphQL over HTTP, gRPC over plain HTTP/2, or JDBC with no TLS configured
(APPLICATION-TM §2.5, Transport column). ADR-021 §4 lists "**HTTPS-only:**
Prevents URL sniffing on the wire" as a control for the workspace key. ADR-023
§1 requires TLS for "All local cluster entry points", which covers k3d and not
compose.

## register-server

**Observation — workspace creation has no rate limit.** ADR-021 §4: "an
application-level per-address limit was implemented and removed", and "**This
is a known, unclosed gap:** no rate limit exists at the gateway today, so
workspace creation is currently unbounded." Compose has no gateway at all. The
local `.env.irmin` sets `REGISTER_WORKSPACE_MAX_CREATES_PER_IP`, and no server
code reads it.

**Observation — the admin route checks no credential.**
`WorkspaceLifecycleController.evictExpired` takes the system maintenance token
and checks nothing about the caller. AUTHORIZATION-PLAN L1.5 gives reachability
of `DELETE /admin/workspaces/expired` to the edge. Compose has no edge in front
of port 8090, so every element of B1 and every host process can call the route
(APPLICATION-TM §2.6, row B2).

**Observation — nothing bounds how many simulations run at once.**
`maxConcurrentSimulations` is a field of `SimulationConfig`, set to 4 in
`application.conf`, and no server code reads it. `docker-compose.yml` says of
`REGISTER_MAX_CONCURRENT_SIMULATIONS`: "Not read by the server yet — the
resolver's fan-out across sibling nodes is unbounded." The container's limits of
2 CPUs and 256 MiB are the only bound (APPLICATION-TM §2.2).

**Observation — the schema migrations run as the application's database user.**
`application.conf` gives `register.flyway.user` and `register.db.dataSource.user`
the same variable, `REGISTER_DB_USER`. The user register-server reads and writes
workspace records with therefore also holds the right to change the schema
(APPLICATION-TM §2.5, DF8).

**Observation — comments in the server still describe Istio.** `UserContext.scala`
("populated from Istio-injected claim headers"), `AuthConfig.scala` and
`HealthProbeServer.scala` name it. Istio is no longer installed in the k3d
cluster (register-infra commit `f2e7567`).

**Observation — the health port's rationale names Istio.** The comment on
`healthPort` in
[application.conf](../../../modules/server/src/main/resources/application.conf)
explains the separate port by Istio `PeerAuthentication` modes. Istio is no
longer installed in the k3d cluster (register-infra commit `f2e7567`).

## frontend (nginx)

**Observation — its API proxy is used only in compose.** The nginx template in
[Dockerfile.frontend-prod](../../../containers/prod/Dockerfile.frontend-prod)
passes `/w/*`, `/workspaces`, `/distribution`, `/health` and `/docs` on to
register-server, and does not pass `/admin`. In k3d, register-infra
`docs/plans/PLAN-EGC-ENVOY-GATEWAY-CLUSTER.md` §3.1 states: "The frontend has no
connection to register: its image still holds proxy rules for the API paths,
which Docker Compose uses, and in the cluster no request reaches them."

**Observation — its logs hold no workspace key.** Under path routing (ADR-025)
the key is in the request line, and in the `Referer` of every request a page
under `/w/` makes. The nginx template writes the access log in its `masked`
format, which replaces the segment after `/w/` and records no `Referer`. Its
error log runs at `crit`, because nginx's lines at `error` and `warn` quote the
request line. register-server itself logs only a request's first path segment
(`RequestSpanInterceptor.spanName`). This is threat T5 in APPLICATION-TM §4.

**Observation — the SPA's HTML carries no Content-Security-Policy.** Neither
`modules/app/index.html` nor the nginx template sets one. register-server's
`SecurityHeadersInterceptor` sets one only on responses register-server writes
(APPLICATION-TM §2.6, row B5).

## Irmin

**Observation — register-server calls Irmin without a credential.**
`modules/server/src/main/scala/com/risquanter/register/infra/irmin/` sends no
authorization header or token. In compose, Irmin's port 8080 is published on the
host as 9080.

**Observation — in k3d, the network policy that would enclose Irmin is
described as unenforced.** register-infra `infra/k8s/network-policy/register.yaml`
still carries the Istio-era comment: "per-service Cilium NetworkPolicy rules
(e.g. allow register→irmin on port 8080) cannot be enforced at the Cilium layer
for intra-namespace traffic." Istio has since been removed. Whether the rule is
enforced now is settled in the k3d models, by a connection test from another pod
in the `register` namespace.

## PostgreSQL

**Assumption — the official image requires a password for connections from
other containers.** The model's B3 control is password authentication. The
compose service sets `POSTGRES_PASSWORD`, and the repository does not show the
resulting `pg_hba.conf`. Check:
`docker compose --profile persistence exec postgres cat /var/lib/postgresql/data/pg_hba.conf`.

**Observation — its image is not pinned.** Compose runs `postgres:17-alpine`, a
tag that moves. ADR-020 §1: "Floating ranges or unpinned installs are
forbidden." Every `FROM` line in `containers/` is pinned by digest. This image
is not, so a moved tag changes what runs.

**Observation — the password is a fixed public value.** `docker-compose.yml`
sets `POSTGRES_PASSWORD: dev-only-password`, and register-server's
`REGISTER_DB_PASSWORD` defaults to the same value. The default in
`application.conf` is `register`.

## SpiceDB

**Observation — fine-grained mode does not start without a token.**
`application.conf` states: "The url and the token have no in-file default, so
fine-grained mode fails at startup unless SPICEDB_URL and SPICEDB_TOKEN are set
— fail-closed by construction."

**Observation — the SpiceDB URL may be plain HTTP, on the premise of a mesh that
is gone.** `SpiceDbConfig` takes its URL as `MeshServiceUrl`. The comment on that
type in `OpaqueTypes.scala` says it "Accepts both http:// and https:// —
transport security is the mesh's responsibility", and that "The Istio control
plane provides mutual TLS between all mesh-enrolled pods transparently". Istio
is no longer installed (register-infra commit `f2e7567`). The request carries
`SPICEDB_TOKEN`. What protects DF9 in transit now is settled in the k3d Layer 2
model.

## OpenTelemetry collector

**Observation — its receivers take no credential.** The collector runs with
[otel-collector-config.yaml](../../../otel-collector-config.yaml), and compose
publishes ports 4317, 4318, 8888 and 8889 on the host when the `observability`
profile runs.

**Observation — its configuration file is mounted writable.** Compose mounts
`./otel-collector-config.yaml` into the collector with no `:ro` flag, so a
process inside the collector container can rewrite the file in the repository
checkout on the host (APPLICATION-TM §2.5, DF15).

**Observation — its image is pinned by version, not by digest.** Compose runs
`otel/opentelemetry-collector-contrib:0.95.0`. ADR-020 §1 requires exact pins,
and the Dockerfiles in `containers/` pin their images by digest as well.

## Vite dev server

**Observation — the SPA it serves calls register-server directly.**
[vite.config.js](../../../modules/app/vite.config.js) sets port 5173 and no
proxy. The SPA then calls port 8090, and register-server's CORS configuration
admits browser reads from `http://localhost:3000` and `http://localhost:5173`
(`REGISTER_CORS_ORIGINS`).

## Single-page application

**Assumption — the browser isolates the SPA's process and origin.** B5's control
is the browser's process and same-origin isolation. The model takes the browser
as correct and does not model browser extensions, which run with access to the
page.

## Existing threat documents

**Observation — three documents still describe Istio.**
[THREAT-CATALOG-(INCOMPLETE).md](../THREAT-CATALOG-(INCOMPLETE).md) T1–T3,
register-infra `docs/THREAT-CATALOG.md` and register-infra
`docs/SECURITY-FLOW.md` name the Istio waypoint, `EnvoyFilter`,
`RequestAuthentication` and `PeerAuthentication`. Phase K.5 of
[AUTHORIZATION-PLAN.md](../plans/AUTHORIZATION-PLAN.md) reads "Istio Ambient
Mode Install". The k3d edge is now Envoy Gateway with Cilium (register-infra
commit `f2e7567`). T4 concerns the application and moves into APPLICATION-TM §4.
T1–T3 concern the edge and move to the k3d models.

# Threat Model Notes — Application

This document holds the assumptions and observations that concern one component
of [APPLICATION-TM.md](APPLICATION-TM.md). An **assumption** is a premise the
model relies on and the repository does not settle. Each one names the check
that would settle it. An **observation** is a fact read from the code or
configuration, with its source.

---

## Docker host and daemon

**Assumption — the host's kernel and the Docker daemon are correct.** B9's
control is the host operating system's accounts and file permissions, and the
access rule on the daemon's socket. The model takes the kernel that enforces
them, and the daemon that isolates the containers, as correct.

**Assumption — only the operator's account passes B9's control on the host.**
The repository sets no account, no file mode and no socket rule, so it cannot
settle who else passes. Three checks settle it on a given host:
`getent group docker` lists the accounts the socket admits besides root,
`ls -l /var/run/docker.sock` shows the socket's own rule, and
`ls -l docker-compose.yml otel-collector-config.yaml .env .env.irmin .env.inmemory`
shows who can read and write the configuration. On the host this model was
assessed on, the socket admits root and group `docker`, whose one member is the
operator. The configuration files are writable by the operator and that group,
and readable by every account. None of them holds a secret that is not already
in the repository.

**Observation — an account the daemon's socket admits holds every container.**
Through the daemon it reads each container's definition, which includes its
environment and with it `REGISTER_DB_PASSWORD`. It reads the container's logs
and volumes, and runs commands inside it (APPLICATION-TM §2.6, row B9). This is
the daemon's interface and not a setting in the repository.

**Observation — the env files exist only on the host.** `.gitignore` excludes
`.env` and `.env.*` and keeps `.env.*.example`. The content and the file mode of
`.env`, `.env.irmin` and `.env.inmemory` are therefore set on each host and are
not in the repository.

**Observation — compose takes values from its caller's shell.**
`docker-compose.yml` writes many values as `${NAME:-default}`, for example
`REGISTER_DB_PASSWORD: "${REGISTER_DB_PASSWORD:-dev-only-password}"`. A variable
of that name in the shell that runs `docker compose` replaces the default
(APPLICATION-TM §2.5, DF11).

## All containers

**Observation — no service drops a capability or bounds its log.**
`docker-compose.yml` sets no `cap_drop`, no `user:` and no `logging:` on any
service. Only register-server has a resource limit
(APPLICATION-TM §4, T19 and T23).

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

**Observation — a scaladoc describes a rate limit that does not exist.** The
scaladoc of `DistributionPreviewEndpoints` says "Rate limiting is handled at the
nginx/ingress level", and the nginx template holds no `limit_req` and no
`limit_conn`.

**Observation — a provided seed entity id moves the shared counter.**
`WorkspaceStoreLive` sets the next assigned id to the provided value plus one,
and `WorkspaceStorePostgres` runs `setval` on `workspace_seed_entity_id_seq`.
`V2__workspace_seed_entity_id.sql` gives that sequence `MAXVALUE 99999999`
(APPLICATION-TM §4, T9).

**Observation — workspace creation requires a user identity outside
`capability-only` mode.** ADR-021 §4 says "`POST /workspaces` is anonymous in
every authorization mode". `WorkspaceLifecycleController.bootstrapWorkspace`
calls `userCtx.requireAuthenticated`, which in `identity` and `fine-grained`
mode refuses a request with no `x-user-id`.

**Observation — an event stream checks its key once.** `SSEController` calls
`workspaceStore.resolveTree` when the stream opens and not again
(APPLICATION-TM §4, T16).

**Observation — spans carry query text and identifiers.** `QueryServiceLive`
sets the attribute `query.text`, and `RiskTreeServiceLive` sets `tree_id`,
`node_id` and `node_ids` (APPLICATION-TM §4, T14).

**Observation — the probe server's scaladoc names other ports.** The scaladoc
of `HealthProbeServer` names ports 8080 and 8081. `application.conf` sets 8090
and 8091.

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

**Observation — its process settings come from the image, and compose adds
none.** [Dockerfile.irmin-prod](../../../containers/prod/Dockerfile.irmin-prod)
sets the entrypoint `irmin graphql` and the command `--port 8080 --address
0.0.0.0 --root /data`. The compose service sets no `command:` and no
`environment:` (APPLICATION-TM §2.5, DF11 and DF19).

**Observation — the Irmin binary accepts a configuration file, and none is
given.** `docker run --rm --network none local/irmin-prod:3.11-p2 --help=plain`
lists `--config=PATH (absent=null)`: "Allows configuration file to be specified
on the command-line." The image's command does not pass it. A `command:` added
to the compose service would replace the image's command and could.

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

**Observation — the image requires a password for connections from other
containers.** With compose's settings, `postgres:17-alpine` writes a
`pg_hba.conf` that trusts local and loopback connections and ends in
`host all all all scram-sha-256`. A connection from another container with a
wrong password is refused. That is the model's B3 control.

**Observation — the application's role is a superuser.** The image creates
`POSTGRES_USER`, here `register_app`, as a superuser, and
`select rolsuper from pg_roles` answers true for it (APPLICATION-TM §4, T20).

**Observation — its image is not pinned.** Compose runs `postgres:17-alpine`, a
tag that moves. ADR-020 §1: "Floating ranges or unpinned installs are
forbidden." Every `FROM` line in `containers/` is pinned by digest. This image
is not, so a moved tag changes what runs.

**Observation — no BATS suite runs the PostgreSQL workspace store.** Suite A
starts the `persistence` profile and sets only `REGISTER_REPOSITORY_TYPE=irmin`,
so the workspace store stays in memory while the PostgreSQL container runs
(`tests/bats/suite-a-full-prod.bats`).

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

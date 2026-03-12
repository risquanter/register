# Agent Prompt: Sync register-infra with register v2 Architecture

## Situation

The `~/projects/register` application repo has been updated.  The
`~/projects/register-infra` Kubernetes infrastructure repo has **not** been
updated to match.  As a result, the local k3d cluster has three degraded
applications:

```
NAME     STATUS     HEALTH       NOTES
irmin    OutOfSync  Progressing  SyncError
opa      OutOfSync  Progressing  SyncError
register Synced     Degraded     running but broken (port/health mismatch)
```

`frontend` does not appear in ArgoCD at all — the nginx SPA service has never
been wired into the cluster.

Your task is to **read and understand** the current state of both repos, then
produce a **concrete, ordered plan** for what must change in `register-infra`.
Do NOT make any changes yet.  Deliver a prioritised list of diffs with the
exact field values that need to change and why.

---

## Step 1 — Read the application architecture docs (register repo)

These documents define the authoritative contract the infra must implement.
Read them in this order:

### Container image strategy
```
~/projects/register/docs/ADR-026-container-image-strategy.md
```
Understand the three-tier image hierarchy (`builder → prod → dev`), folder
conventions, and which image tags are used in production versus dev.

### Runtime ports and environment variables
```
~/projects/register/docs/DOCKER-DEVELOPMENT.md   §Services, §Configuration
~/projects/register/modules/server/src/main/resources/application.conf
```
Pay close attention to:
- The register server binds API on **port 8090** (`REGISTER_SERVER_PORT`) and
  health probes on a dedicated **port 8091** (`REGISTER_HEALTH_PORT`).  These
  are distinct ports with different mTLS policies in the mesh (see ADR-012).
- Every environment variable in the `REGISTER_*`, `IRMIN_*`, and `OTEL_*`
  families — their names, types, and defaults.  Cross-check the table in
  `DOCKER-DEVELOPMENT.md §Environment Variables` against `application.conf`
  to make sure every mapped key is accurately named (a mismatch is silently
  ignored at runtime).
- The `docker-compose.yml` in the register root as a canonical reference for
  which env vars to set and which default values to use.

### nginx frontend serving (new service, not yet in cluster)
```
~/projects/register/docs/ADR-027-frontend-nginx-serving.md
~/projects/register/containers/prod/Dockerfile.frontend-prod
```
Understand:
- The frontend image (`local/frontend:dev` in compose) is a multi-stage build:
  `node:22-alpine` builder → `nginx:1.27.5-alpine-slim` runtime.
- It needs one env var at runtime: `BACKEND_URL` (default:
  `http://register-server:8090`; in k8s: use the service FQDN).
- nginx listens on port **8080** (non-privileged, uid 101).
- nginx.conf is baked into the image; the DNS resolver and backend URL are
  injected at container startup from `/etc/resolv.conf` and `BACKEND_URL`.
- The `/w/*` path is dual-purpose (ADR-021): JSON requests proxy to the
  backend; HTML requests serve the SPA shell.  Do NOT split this path at the
  Istio HTTPRoute level — routing happens inside nginx.

### Capability URLs and Ingress path rules
```
~/projects/register/docs/ADR-021-capability-urls.md
```
Understand the `/w/<token>` path semantics before designing any HTTPRoute or
Gateway routing rules for the frontend.

### Config management (env var naming)
```
~/projects/register/docs/ADR-016-config-management.md
~/projects/register/modules/server/src/main/scala/com/risquanter/register/configs/
```
Read the Scala config case classes (`ServerConfig`, `IrminConfig`,
`WorkspaceConfig`, `ApiConfig`, `TelemetryConfig`, `CorsConfig`,
`RepositoryConfig`, `AuthConfig`) to understand exactly which env vars
each accepts.  The mapping is always:
`REGISTER_<HOCON_KEY_UPPERCASE>` → `register.<section>.<field>`.

### Secret handling
```
~/projects/register/docs/ADR-022-secret-handling.md
~/projects/register-infra/docs/adr/ADR-INFRA-006.md
```
Understand which values must come from Kubernetes Secrets (not `env.value:`
literals) and the SOPS-per-namespace convention for encrypted secret files.

### Authorization mode
```
~/projects/register/docs/ADR-024-externalized-authorization-pep-pattern.md
~/projects/register/modules/server/src/main/scala/com/risquanter/register/configs/AuthConfig.scala
```
`AuthConfig.mode` drives which auth layers are active.  The default in
`application.conf` is `"capability-only"`.  Know what the k8s deployment
should use and whether it needs to be set explicitly.

---

## Step 2 — Read the current infra implementation

```
~/projects/register-infra/infra/helm/register/values.yaml
~/projects/register-infra/infra/helm/register/templates/deployment.yaml
~/projects/register-infra/infra/helm/register/templates/service.yaml
~/projects/register-infra/infra/helm/irmin/values.yaml
~/projects/register-infra/infra/helm/irmin/templates/statefulset.yaml
~/projects/register-infra/infra/k8s/istio/peer-authentication.yaml
~/projects/register-infra/infra/k8s/istio/authorization-policy.yaml
~/projects/register-infra/infra/argocd/apps/register.yaml
~/projects/register-infra/infra/argocd/apps/irmin.yaml
~/projects/register-infra/infra/argocd/apps/opa.yaml
```

For each file, note every value that contradicts what you learned in Step 1.
Specifically look for:

- **Port numbers**: The register Helm chart currently declares
  `service.port: 8080`.  The server does not listen on 8080.
- **Health probe ports**: Both liveness and readiness probes currently hit
  `port: http` (8080).  They should target the dedicated health port (8091)
  on a separate named port.
- **Missing containerPort declarations**: There should be two named ports in
  the container spec — the API port and the health-probe port.
- **Env var completeness**: Compare the `env:` block in `values.yaml` against
  the full env var table from Step 1.  Note any vars that are absent but
  required, any that use wrong names (silently ignored), and any sensitive
  values that should be `secretKeyRef` instead of inline `value:`.
- **Irmin image tier**: The statefulset comment acknowledges that
  `readOnlyRootFilesystem` is disabled because the dev image writes to
  `/home/opam/.opam`.  The production Irmin image (`local/irmin-prod:3.11`)
  is now available and does not have this constraint.  Note what security
  context changes become possible with the prod image.
- **Missing frontend Helm chart**: There is no `infra/helm/frontend/` chart
  and no `infra/argocd/apps/frontend.yaml`.  This entire service is absent.

---

## Step 3 — Read the infra ADRs for constraints

```
~/projects/register-infra/docs/adr/ADR-INFRA-007.md
~/projects/register-infra/docs/adr/ADR-INFRA-006.md
~/projects/register-infra/docs/adr/ADR-INFRA-004.md
~/projects/register-infra/docs/adr/ADR-INFRA-003.md
```

`ADR-INFRA-007` covers the frontend nginx serving architecture for the infra
side (this is the authoritative companion to `ADR-027` in the register repo).
It tells you how the HTTPRoute / Istio Gateway should be structured, which
this is the primary ADR driving the missing frontend Helm chart.

`ADR-INFRA-004` and `ADR-INFRA-003` may constrain how you wire services
together (check for mesh, RBAC, or network-policy rules that apply to a new
frontend service).

---

## Step 4 — Check the ArgoCD SyncError causes

Run these commands locally to understand _why_ `irmin` and `opa` are in
`SyncError` before proposing fixes:

```bash
argocd app get irmin --show-operation
argocd app get opa --show-operation
kubectl describe pods -n register   # see crash loops / image pull failures
kubectl events -n register --sort-by='.lastTimestamp' | tail -30
```

The SyncErrors could be caused by:
- Image pull failures (image not imported into k3d, wrong tag)
- Helm template rendering errors (schema validation, missing required fields)
- PersistentVolumeClaim binding issues (irmin StatefulSet)
- OPA policy or ConfigMap format errors

Understanding the root cause is required before proposing a fix.

---

## Step 5 — Check the register Degraded status

```bash
kubectl describe pod -n register -l app.kubernetes.io/name=register
kubectl logs -n register -l app.kubernetes.io/name=register --previous 2>/dev/null || true
kubectl logs -n register -l app.kubernetes.io/name=register
```

The `Degraded` health with `Synced` status means the Helm chart deployed
successfully but the pod or service is unhealthy.  The most likely cause is
that the container exposes port 8090 but the Helm Service targets port 8080,
so the readiness probe never passes.

---

## What to produce

After completing Steps 1–5, write a **prioritised remediation plan** with the
following structure for each item:

```
### <Title>
Priority: P1 / P2 / P3
Files to change: <list>
Root cause: <one-sentence explanation>
Proposed change:
  <exact YAML diff or description of the new value>
Validation: <command to verify the fix worked>
```

Order by priority:
- **P1** — things causing the current cluster health failures (register
  Degraded, irmin SyncError, opa SyncError)
- **P2** — things that are silently wrong but not yet causing pod restarts
  (wrong env var names, missing vars with viable defaults, security-context
  gaps that can be improved now the prod Irmin image is available)
- **P3** — net-new work (frontend Helm chart + ArgoCD app, HTTPRoute for SPA,
  any docs updates required in register-infra)

---

## Constraints

- Do NOT change `~/projects/register` — that repo is the source of truth.
  All changes go into `~/projects/register-infra`.
- Preserve existing Helm conventions (indentation, comment style, helper
  function usage).
- Do NOT inline sensitive values; use `secretKeyRef` for anything that is or
  should be a Secret.
- Any new ArgoCD Application must follow the pattern of existing ones in
  `infra/argocd/apps/` and be added to the root App-of-Apps in `root.yaml`.
- Commit only after all targeted pods are `Running` and `Ready`, and after
  `argocd app list` shows `Synced / Healthy` for the affected apps.

---

## Key reference facts (do not re-derive these — read to verify)

| Fact | Source |
|------|--------|
| Register API port: **8090** | `application.conf` / `ServerConfig.scala` |
| Register health-probe port: **8091** | `application.conf` / `ServerConfig.scala` |
| Register image uid: **65532** (distroless nonroot) | `Dockerfile.register-prod` |
| Irmin prod image uid: **1000** (opam user — same as dev) | `Dockerfile.irmin-prod` |
| Irmin GraphQL port (container): **8080** | `docker-compose.yml` |
| Frontend nginx port: **8080**, uid **101** | `Dockerfile.frontend-prod` |
| Frontend backend env var: `BACKEND_URL` | `docker-compose.yml` / `Dockerfile.frontend-prod` |
| Irmin client env var: `IRMIN_URL` (full base URL, not graphql path) | `IrminConfig.scala` |
| Auth default: `"capability-only"` (no Keycloak JWT required) | `AuthConfig.scala` |
| Simulation parallelism env var: `REGISTER_TRIAL_PARALLELISM` | `application.conf` |

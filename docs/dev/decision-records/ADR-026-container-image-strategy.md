# ADR-026: Container Image Strategy — Builder and Prod Separation

**Status:** Accepted  
**Date:** 2026-03-10  
**Tags:** containers, docker, deployment, security, irmin

---

## Context

- The project produces three deployed images: a Scala/ZIO risk register server, an OCaml/Irmin GraphQL persistence server, and an nginx image carrying the compiled single-page application
- The server toolchains (GraalVM + sbt, opam + OCaml) are expensive to install — 2 minutes for the former, 15–40 for the latter
- Deployed images should be minimal: no compilers, no package managers, no unnecessary shared libraries
- A common operational workflow is purging deployed images and rebuilding without re-downloading toolchains
- Docker layer caching alone does not survive `docker builder prune` or CI ephemeral runners — only named tagged images are durable

## Decision

### 1. Two Tiers: Builder → Prod

| Tier | Purpose | Lifetime | Images |
|------|---------|----------|--------|
| **Builder** | Cached toolchain, reused by a prod build | Survives `docker builder prune` | `local/graalvm-builder:21`, `local/irmin-builder:3.11-p1` |
| **Prod** | Multi-stage build ending in a minimal runtime | Rebuilt on code or config changes | `local/register-server:<version>`, `local/irmin-prod:3.11-p1`, `local/frontend:<version>` |

A third image, `local/bats-runner:1.11`, is neither: it is a test harness that
drives the host's Docker daemon and ships nothing. It lives in `containers/dev/`.

**Purge-and-rebuild workflow:**
```bash
docker rmi local/register-server:<version> local/irmin-prod:3.11-p1   # purge deployed
docker compose build                                      # rebuild in seconds
# Builder images untouched — no 40-min opam reinstall
```

### 2. Folder Structure: `containers/{builders,dev,prod}/`

```
containers/
  builders/
    Dockerfile.graalvm-builder   # GraalVM + sbt (consumed by register-prod)
    Dockerfile.irmin-builder     # opam + irmin packages (consumed by irmin-prod)
  dev/
    Dockerfile.bats-runner       # bats-core + Docker CLI, for the smoke suites
  prod/
    Dockerfile.register-prod     # graalvm-builder → GraalVM native → distroless
    Dockerfile.irmin-prod        # irmin-builder → slim Alpine
    Dockerfile.frontend-prod     # node + sbt inline → nginx (see §4)
```

**Naming convention:** `Dockerfile.{service}-{tier}`. The service prefix
(`register-`, `irmin-`, `frontend-`) disambiguates in a multi-service repo. The
tier suffix signals intent and deployment role.

### 3. Prod Images Are Minimal ("Distroless in Spirit")

**Register server:** `gcr.io/distroless/static-debian12:nonroot` — no shell, no
package manager, static binary only.

**Irmin server:** `alpine:3.24` with only `libgmp` + `libffi` — the two shared
libraries the irmin binary links against. No opam, no compiler, no git. Alpine
provides busybox `wget` for the HEALTHCHECK; this is acceptable for the
"distroless in spirit" stance.

**Frontend:** `nginx:1.27.5-alpine-slim` holding only the Vite output under
`/srv/app/` and a baked-in `nginx.conf`. No Node, no JDK, no sbt — the entire
build toolchain stays in the discarded first stage. Runs as uid 101. ADR-027
covers its routing rules.

Both server images:
- Run as non-root (UID 65532)
- Support `readOnlyRootFilesystem: true` (only `/data` PVC is writable)
- Set `no-new-privileges` security option

### 4. The Frontend Builds Its First Stage Inline, With No Builder Base

`register-prod` and `irmin-prod` each begin `FROM local/<x>-builder`.
`frontend-prod` instead assembles its build stage from `node:22.23.3-alpine3.24`,
installing a JDK and sbt in the Dockerfile. Four reasons, in order of weight:

1. **A builder base cannot reach the layer that costs the most.** The expensive
   step in the frontend build is `sbt "app/update; commonJS/update"`, which
   resolves and downloads the whole Scala and Scala.js dependency tree. It is
   invalidated by any change to `build.sbt` or `project/`, and it sits *below*
   the toolchain in the layer order, so no toolchain image protects it.
   `graalvm-builder` has the same limit: it warms only the sbt launcher, and
   `register-prod` runs its own `sbt update` afterwards.
2. **The toolchain here is cheap to reconstruct.** The named-builder pattern was
   adopted for a 15–40 minute opam compilation. The frontend's toolchain is one
   `apk add` and one archive download. The pattern's motivating cost does not
   apply at anything like the same scale.
3. **Every builder base is a manual prerequisite.** Docker cannot pull a
   `local/` name, so `docker compose up` fails on a machine where the builder has
   not been built by hand. `frontend-prod` needs no such step today.
4. **The drift a builder base would also have fixed is now fixed directly.**
   Pinning `node:22.23.3-alpine3.24` by digest (ADR-020 §1) stops the base moving
   under the build, which was the other recurring cause of toolchain rebuilds.

What this costs: after `docker builder prune`, the frontend re-installs its JDK
and re-downloads sbt, where the two server images do not. That is the trade, and
it is accepted.

### 5. Builder Rebuild Triggers

| Builder | Rebuild When |
|---------|-------------|
| `graalvm-builder` | GraalVM version change, sbt version change |
| `irmin-builder` | Irmin version bump, OCaml version change, new opam packages needed |

Builder images are NOT rebuilt on application code changes.

## Code Smells

### ❌ Toolchain in Production Image

```dockerfile
# BAD: opam, compiler, git all in deployed image (~650 MB)
FROM ocaml/opam:alpine-ocaml-5.2
RUN opam install -y irmin-cli irmin-graphql irmin-pack irmin-git
ENTRYPOINT ["opam", "exec", "--", "irmin", "graphql"]
```

```dockerfile
# GOOD: only the binary + minimal shared libs (~87 MB)
FROM local/irmin-builder:3.11-p1 AS builder
FROM alpine:3.24.2@sha256:<digest>
COPY --from=builder /home/opam/.opam/default/bin/irmin /usr/local/bin/irmin
ENTRYPOINT ["/usr/local/bin/irmin", "graphql"]
```

### ❌ Relying on Docker Layer Cache for Expensive Builds

```bash
# BAD: docker builder prune destroys the opam install cache
docker builder prune -a
docker compose build irmin  # 40-minute rebuild
```

```bash
# GOOD: named builder image survives prune
docker rmi local/irmin-prod:3.11-p1
docker compose build irmin  # seconds — builder image intact
```

### ❌ Dockerfiles Scattered Without Convention

```
# BAD: mixed locations, no naming convention
Dockerfile              # what service? what tier?
Dockerfile.native       # "native" is implementation detail
dev/Dockerfile.irmin    # dev or prod?
```

```
# GOOD: containers/{tier}/Dockerfile.{service}-{tier}
containers/prod/Dockerfile.register-prod
containers/prod/Dockerfile.irmin-prod
containers/builders/Dockerfile.graalvm-builder
```

## Implementation

| Location | Pattern |
|----------|---------|
| `containers/builders/Dockerfile.graalvm-builder` | GraalVM + sbt toolchain base |
| `containers/builders/Dockerfile.irmin-builder` | opam + irmin packages base |
| `containers/prod/Dockerfile.register-prod` | `FROM graalvm-builder` → distroless |
| `containers/prod/Dockerfile.irmin-prod` | `FROM irmin-builder` → slim Alpine |
| `containers/prod/Dockerfile.frontend-prod` | node + JDK + sbt inline → nginx (§4) |
| `containers/dev/Dockerfile.bats-runner` | bats-core + Docker CLI, drives the host daemon |
| `docker-compose.yml` | References `containers/prod/Dockerfile.*-prod` |

## References

- [ADR-020: Supply Chain Security](ADR-020-supply-chain-security.md) — base image and package pinning
- [ADR-027: Frontend nginx Serving](ADR-027-frontend-nginx-serving.md) — the frontend image's routing rules and stage layout
- [ADR-012: Service Mesh Strategy](ADR-012.md) — Irmin behind mesh, no app-level retries
- [IMAGE-BUILD-REFERENCE.md](../../user/IMAGE-BUILD-REFERENCE.md) — every build command and its rebuild triggers
- [DOCKER-DEVELOPMENT.md](../../user/DOCKER-DEVELOPMENT.md) — compose use cases and dev workflow

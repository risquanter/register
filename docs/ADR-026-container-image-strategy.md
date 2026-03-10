# ADR-026: Container Image Strategy — Builder / Dev / Prod Separation

**Status:** Accepted  
**Date:** 2026-03-10  
**Tags:** containers, docker, deployment, security, irmin

---

## Context

- The project produces two services: a Scala/ZIO risk register server and an OCaml/Irmin GraphQL persistence server
- Each service has different build toolchains (GraalVM + sbt vs opam + OCaml) that are expensive to install (2-40 min)
- Deployed images should be minimal: no compilers, no package managers, no unnecessary shared libraries
- A common operational workflow is purging deployed images and rebuilding without re-downloading toolchains
- Docker layer caching alone does not survive `docker builder prune` or CI ephemeral runners — only named tagged images are durable
- Dev images need debugging tools (shell, git, full toolchain); prod images need minimal attack surface

## Decision

### 1. Three-Tier Image Hierarchy: Builder → Prod → Dev

Each service follows a builder / prod / dev separation:

| Tier | Purpose | Lifetime | Example |
|------|---------|----------|---------|
| **Builder** | Cached toolchain + compiled dependencies | Survives `docker builder prune` | `local/graalvm-builder:21`, `local/irmin-builder:3.11` |
| **Prod** | Multi-stage: `FROM builder` → minimal runtime | Rebuilt on code/config changes | `register-server:prod`, `local/irmin-prod:3.11` |
| **Dev** | Self-contained, full toolchain, debuggable | Built once locally | `local/irmin-dev:3.11` |

**Purge-and-rebuild workflow:**
```bash
docker rmi register-server:prod local/irmin-prod:3.11   # purge deployed
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
    Dockerfile.register-dev      # JVM dev server (sbt at runtime)
    Dockerfile.irmin-dev         # Full opam + irmin-git (self-contained)
  prod/
    Dockerfile.register-prod     # GraalVM native → distroless
    Dockerfile.irmin-prod        # irmin-builder → slim Alpine
```

**Naming convention:** `Dockerfile.{service}-{tier}`. The service prefix
(`register-`, `irmin-`) disambiguates in a multi-service repo. The tier suffix
(`-dev`, `-prod`, `-builder`) signals intent and deployment role.

### 3. Prod Images Are Minimal ("Distroless in Spirit")

**Register server:** `gcr.io/distroless/static-debian12:nonroot` — no shell, no
package manager, static binary only.

**Irmin server:** `alpine:3.21` with only `libgmp` + `libffi` — the two shared
libraries the irmin binary links against. No opam, no compiler, no git. Alpine
provides busybox `wget` for the HEALTHCHECK; this is acceptable for the
"distroless in spirit" stance.

Both images:
- Run as non-root (UID 65532)
- Support `readOnlyRootFilesystem: true` (only `/data` PVC is writable)
- Set `no-new-privileges` security option

### 4. Dev Images Are Self-Contained

Dev images do NOT depend on builder base images. They contain the full toolchain
inline. This keeps the dev workflow simple:

```bash
# One command, no prerequisites
docker build -f containers/dev/Dockerfile.irmin-dev -t local/irmin-dev:3.11 .
```

> **Note:** If rebuild times for the dev image become a concern, it is acceptable
> to refactor `Dockerfile.irmin-dev` to `FROM local/irmin-builder:3.11` and add
> `irmin-git` + runtime config on top. This trades build independence for speed.
> The current self-contained approach is preferred while the dev image is rarely
> rebuilt.

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
FROM local/irmin-builder:3.11 AS builder
FROM alpine:3.21
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
docker rmi local/irmin-prod:3.11
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
| `containers/dev/Dockerfile.register-dev` | JVM + sbt (self-contained) |
| `containers/dev/Dockerfile.irmin-dev` | opam + irmin-git (self-contained) |
| `docker-compose.yml` | References `containers/prod/Dockerfile.*-prod` |

## References

- [ADR-020: Supply Chain Security](ADR-020-supply-chain-security.md) — dependency management
- [ADR-012: Service Mesh Strategy](ADR-012.md) — Irmin behind mesh, no app-level retries
- [DOCKER-DEVELOPMENT.md](DOCKER-DEVELOPMENT.md) — build commands and dev workflow
- [GRAALVM_DISTROLESS_MIGRATION.md](GRAALVM_DISTROLESS_MIGRATION.md) — register server migration log

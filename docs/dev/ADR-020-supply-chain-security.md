# ADR-020: Supply Chain Security — Dependency and Image Provenance Hardening

**Status:** Accepted  
**Date:** 2026-02-11 (revised 2026-03-11)  
**Tags:** security, supply-chain, dependencies, docker, npm, opam, sbt, apk

---

## Context

The project uses multiple package ecosystems and build toolchains:

| Toolchain | Ecosystem | Used in |
|---|---|---|
| npm / Vite | JavaScript | `modules/app/` |
| sbt / Scala | JVM | `modules/common/`, `modules/server/`, `modules/app/` (Scala.js) |
| opam / OCaml | Native | `containers/builders/Dockerfile.irmin-builder` |
| apk | Alpine Linux | Builder and runtime Docker images |
| Docker base images | OCI Registry | All Dockerfiles |
| Binary downloads (wget) | GitHub Releases | sbt launcher |

Each of these is a potential supply-chain attack vector:

- **Version drift**: floating specifiers (`^1.0`, `>=3`, `*`) allow any compatible version to install on the next build, including a compromised one.
- **Install-time code execution**: package managers (npm, opam, pip) may run arbitrary scripts during installation (postinstall hooks, build.sh, etc.).
- **Unverified downloads**: `wget`/`curl` without checksum verification allows silent MITM substitution.
- **Mutable image tags**: Docker tags like `node:22` or `latest` are reassigned by maintainers; a rebuild pulls a different image.

Supply chain incidents that motivate this ADR: event-stream (2018), ua-parser-js (2021), XZ Utils (2024), Shai-Hulud worm (2025).

---

## Decision

### 1. Pin All External Dependencies Exactly

Every dependency in every ecosystem must be pinned to a specific version. Floating ranges or unpinned installs are forbidden.

#### npm

`save-exact=true` in `.npmrc` writes exact versions instead of ranges:

```ini
# modules/app/.npmrc
ignore-scripts=true
save-exact=true
```

```jsonc
// GOOD: exact pin — no silent drift
{ "devDependencies": { "vite": "6.4.1", "@scala-js/vite-plugin-scalajs": "1.1.0" } }

// BAD: range — any compatible version installs on next `npm install`
{ "devDependencies": { "vite": "^6.4.1" } }
```

#### opam (OCaml)

Use the `package.version` pin syntax in every `opam install` invocation:

```dockerfile
# GOOD: solver is constrained to exact versions
RUN opam install -y \
    irmin-cli.3.11.0 \
    irmin-graphql.3.11.0 \
    irmin-pack.3.11.0

# BAD: opam picks the current latest from the repository snapshot
RUN opam install -y irmin-cli irmin-graphql irmin-pack
```

To find installed versions after a first run: `opam list --installed <package>`.

#### sbt / Scala

The sbt launcher version is pinned in `project/build.properties`:

```properties
sbt.version=1.12.0-RC1
```

Library dependency versions are controlled by `build.sbt`. All library version strings must be exact (no `+` or snapshot suffixes in production builds).

#### Docker base images

Prefer specific semver tags over floating major/minor tags. Never use `:latest`.

```dockerfile
# GOOD: patch version pinned — limited drift window
FROM nginx:1.27.5-alpine-slim
FROM node:22.14.0-alpine3.21
FROM alpine:3.21.3
FROM ocaml/opam:alpine-3.21-ocaml-5.2

# ACCEPTABLE: minor version pinned — common convention for Alpine-based images
FROM node:22-alpine     # locks major only; rebuilds may change patch

# BAD: completely unpinned
FROM node:latest
FROM nginx
```

For the highest assurance, pin base images by digest:

```dockerfile
FROM nginx:1.27.5-alpine-slim@sha256:<digest>
```

Digest pinning is recommended for production/release builds. Track digest updates with Dependabot or a manual review trigger when upstream tags move.

### 2. Verify Downloaded Artifacts

Any binary or archive fetched at build time via `wget` or `curl` must be checksum-verified before use:

```dockerfile
# GOOD: download then verify before extract
ARG SBT_VERSION=1.12.0-RC1
ARG SBT_SHA256=16a47628ac572108374d2443e66482c245b80b3e825873fbe96de4f7bf2e6483
RUN wget -q ".../sbt-${SBT_VERSION}.tgz" -O sbt.tgz && \
    echo "${SBT_SHA256}  sbt.tgz" | sha256sum -c - && \
    tar -xzf sbt.tgz -C /opt && rm sbt.tgz

# BAD: extract without verifying integrity
RUN wget -q ".../sbt-${SBT_VERSION}.tgz" -O sbt.tgz && \
    tar -xzf sbt.tgz -C /opt && rm sbt.tgz
```

Obtain the canonical checksum from the official release page (not from the same download server). For GitHub releases, the `.sha256` file is published alongside the `.tgz`:

```
https://github.com/sbt/sbt/releases/download/v<VERSION>/sbt-<VERSION>.tgz.sha256
```

When bumping a version, update both the `ARG <TOOL>_VERSION` and `ARG <TOOL>_SHA256` together.

### 3. Disable Side-Effects During Installation

Where the ecosystem allows it, block install-time script execution by default. Re-enable selectively only for packages with a legitimate need.

#### npm

```ini
# modules/app/.npmrc
ignore-scripts=true   # blocks postinstall, preinstall hooks for all packages
```

Packages requiring scripts (e.g., `esbuild` native binary) are rebuilt explicitly:

```bash
npm rebuild esbuild   # runs only esbuild's scripts, not the whole tree
```

#### opam

opam packages may define a custom `build.sh`. Mitigate by:

1. Installing only pinned versions (§1) — limits the attack window.
2. Preferring packages published in the official opam-repository (reviewed by maintainers).
3. Auditing build commands for unfamiliar packages: `opam show <pkg> --field=build`.

#### apk (Alpine)

Alpine packages run post-install scripts by default. Use `--no-scripts` when installing packages where scripts provide no value (pure file installations):

```dockerfile
RUN apk add --no-cache --no-scripts <package>
```

Note: most standard Alpine packages use scripts only for service enabling; for container images where no init system runs this is safe to suppress. Omit `--no-scripts` for packages that genuinely require it.

### 4. Pre-Install Review Checklist

Before adding **any** new external dependency from any ecosystem:

1. **Check provenance**: is the package published by the expected maintainer/organisation?
2. **Review install-time side effects**: check for postinstall/build/install scripts and understand what they do.
3. **Audit the dependency tree**: prefer packages with a small transitive graph.
4. **Check for known vulnerabilities**:
   - npm: `npm audit` + [socket.dev](https://socket.dev)
   - opam: check upstream CVEs; `opam list --required-by <pkg> --rec` to enumerate transitive deps
   - Alpine: `apk list --installed | xargs apk audit`
5. **Apply zero-tolerance policy for high/critical vulnerabilities** before merging.

### 5. Self-Host Static Assets When Practical

Package dependencies that exist only to provide static files (fonts, icons, CSS resets) can often be replaced by vendored copies, eliminating the dependency and its transitive supply chain entirely.

Example: Geist font family (SIL OFL 1.1):

```
modules/app/fonts/
  geist-sans/style.css + *.woff2     ← copied from node_modules/geist
  geist-mono/style.css + *.woff2
  LICENSE.txt                         ← required by SIL OFL 1.1
```

This removes a direct npm dependency and all associated transitive risk.

The same principle applies to vendoring other asset-only opam or pip packages when feasible.

### 6. Periodic Maintenance

Run monthly (or before each release) across all applicable ecosystems:

```bash
# npm
npm audit
npm outdated
npm ls --all | wc -l                  # monitor transitive growth

# opam (run inside opam env)
opam update && opam upgrade --dry-run # shows what would change
opam list --installed                 # record current pins

# Docker base image freshness
# Check https://hub.docker.com / ghcr.io for new patch releases
# Update FROM tags and, if digest-pinning, update digests too
```

---

## Code Smells

### ❌ Version Ranges in Any Ecosystem

```jsonc
// npm — BAD
{ "vite": "^6.4.1" }
```

```dockerfile
# opam — BAD
RUN opam install -y irmin-cli irmin-graphql
```

```dockerfile
# Docker — BAD
FROM node:latest
FROM nginx
```

### ❌ Downloading Without Verification

```dockerfile
# BAD: no integrity check — silent MITM or upstream compromise goes undetected
RUN wget -q "https://example.com/tool.tgz" -O tool.tgz && tar -xzf tool.tgz
```

### ❌ Installing Without Audit

```bash
# npm — BAD
npm install some-fancy-package   # scripts run, no vulnerability check

# GOOD: check first, install with scripts blocked, then audit
# 1. Review on socket.dev
# 2. npm install some-fancy-package  (scripts blocked by .npmrc)
# 3. npm audit
# 4. npm rebuild some-fancy-package  (only if build scripts are needed)
```

---

## Implementation Reference

| Location / Pattern | Ecosystem | Directive |
|---|---|---|
| `modules/app/.npmrc` | npm | `ignore-scripts=true`, `save-exact=true` |
| `modules/app/package.json` | npm | Exact version pins (no `^` or `~`) |
| `containers/builders/Dockerfile.irmin-builder` | opam | `opam install pkg.3.11.0` (version-pinned) |
| `containers/builders/Dockerfile.graalvm-builder` | wget | `SBT_SHA256` arg + `sha256sum -c` |
| `containers/prod/Dockerfile.frontend-prod` | wget | `SBT_SHA256` arg + `sha256sum -c` |
| `project/build.properties` | sbt | `sbt.version=<exact>` |
| All `FROM` lines | Docker | Pin to specific semver tags; use digest for releases |
| `zed` CLI (CI runner) | SpiceDB | Pin version + checksum verify (same pattern as `opa`, `conftest`) |
| Pre-install checklist | All | socket.dev / opam-show / apk audit review |

---

## References

- [socket.dev](https://socket.dev) — npm/PyPI package supply chain analysis
- [SLSA Framework](https://slsa.dev) — supply chain levels for software artifacts
- [opam-repository](https://github.com/ocaml/opam-repository) — reviewed OCaml package registry
- [SIL Open Font License 1.1](https://openfontlicense.org) — permits bundling font files with software
- [npm ignore-scripts](https://docs.npmjs.com/cli/v10/using-npm/config#ignore-scripts) — official npm docs

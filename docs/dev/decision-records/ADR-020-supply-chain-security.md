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

**These pins reach the three named packages and nothing beneath them.** Everything
they depend on is resolved by the solver against the opam repository as it stands
at build time, so the transitive set can change between two builds of an unchanged
Dockerfile. It is not merely a version drift: the set of *shared libraries* the
produced binary links against can change with it, and the runtime image installs
that set by hand.

That is why `Dockerfile.irmin-prod` verifies the binary with `RUN irmin --version`
immediately after copying it. A library added beneath a pin fails that line during
the build rather than when the container is deployed. After any opam change, read
the current link-time set with `ldd $(opam var bin)/irmin` inside the builder image
and reconcile the runtime image's `apk add` against it.

Pinning the transitive set as well needs an opam lock file, which this build does
not yet produce; the verification line is the compensating control.

#### sbt / Scala

The sbt launcher version is pinned in `project/build.properties`:

```properties
sbt.version=1.12.0-RC1
```

Library dependency versions are controlled by `build.sbt`. All library version strings must be exact (no `+` or snapshot suffixes in production builds).

#### Docker base images

A specific semver tag plus a digest. Never `:latest`, and never a tag alone.

```dockerfile
# GOOD: specific tag as the readable label, digest as the binding pin
FROM alpine:3.24.2@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6
FROM node:22.23.3-alpine3.24@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402

# WEAK: patch version pinned, no digest — the publisher can still move the tag
FROM nginx:1.27.5-alpine-slim

# WEAKER: major only — the underlying distribution moves too. `node:22-alpine`
# silently advanced from Alpine 3.21 to 3.24 while it was written this way.
FROM node:22-alpine

# BAD: completely unpinned
FROM node:latest
FROM nginx
```

**One Alpine branch for the whole repository.** Every image here is built on
Alpine 3.24, so `irmin-prod` runs a binary against the same musl and shared
libraries `irmin-builder` compiled it with, and the BATS runner's Docker CLI
matches the daemon it drives. A new base image joins that branch or states why
it cannot.

Every externally sourced base image in this repository is pinned by digest, which
is what actually fixes the image bytes — a tag, however specific, can be moved by
its publisher:

```dockerfile
FROM nginx:1.27.5-alpine-slim@sha256:<digest>
```

The tag is kept alongside the digest as a human-readable label; both are bumped
together. `docker buildx imagetools inspect <tag>` prints the digest to use.

A digest pin freezes upstream security patches as well as upstream drift, so a
bump is a deliberate act, not an automatic one. That makes a review trigger
mandatory rather than optional: without one, a digest-pinned base silently ages.
The image scanning that supplies the trigger is commissioned in `TODO.md` item 39.

Two `FROM` lines are not digest-pinned and cannot be: `local/graalvm-builder:21`
and `local/irmin-builder:3.11-p1` are built on the machine that consumes them, so
there is no publisher digest to pin against. Their content is fixed by the pins
inside their own Dockerfiles.

#### OS packages (apk, microdnf/dnf, apt)

The distribution package managers verify every package they install against the
distribution's signing keys, which are baked into the base image — `/etc/apk/keys`
on Alpine, the RPM GPG keys on Oracle Linux. That covers authenticity. It does
not cover **which version** you get: a bare `apk add gmp` installs whatever
revision is current in the branch on the day of the build.

Signature verification alone is not enough, because it does not survive an
upstream account compromise: a package signed with a legitimately-held key is
accepted by every check. Version determinism is therefore required on top of it.

No package manager here exposes a per-package hash argument, so the exact version
is the strongest available pin:

```dockerfile
# GOOD: exact revision — reproducible, and a substitution is visible in the diff
RUN apk add --no-cache gmp=6.3.0-r2 libffi=3.4.7-r0
RUN microdnf install -y wget-1.21.1-11.el9_8 tar-1.34-13.el9_8

# BAD: whatever the branch holds at build time
RUN apk add --no-cache gmp libffi
```

Finding the current revision: `apk policy <package>` on Alpine,
`microdnf repoquery <package>` on Oracle Linux. Both read the repository index
and install nothing.

**The cost, stated plainly.** Alpine's repositories carry only the current
revision of a package for a release branch. When a package is updated inside the
branch, the previous revision is removed, and a Dockerfile pinned to it fails to
build with `unable to select packages`. That is the intended behaviour: the build
stops and a person decides, rather than the image changing silently. It makes a
pin bump part of routine maintenance, which is the same review trigger the digest
pins need.

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

### 7. Incident: npm auto-installed a required peerDependency (2026-07-21)

**What happened:** `geist@1.7.0` (a direct dependency, used only for its
static font files/CSS — see §5) declares `next: >=13.2.0` as a **required**
(non-optional) peerDependency. Since npm v7, a plain `npm install` silently
auto-installs a satisfying version of any unmet required peer. This pulled
in `next@16.2.2` — which itself has multiple disclosed high-severity CVEs
(SSRF via WebSocket upgrades, middleware/proxy auth bypass, cache poisoning
— see the [GitHub Advisory Database](https://github.com/advisories) entries
for `next`) — and `next`'s own dependency on `sharp` (image optimization),
pulling in ~35 platform-specific native binary packages. None of this is
imported or executed anywhere in `modules/app/src`; it was dead code sitting
in `node_modules`, but it was real attack surface and inflated the dependency
count from ~99 to 194 packages.

Separately, `vite@6.4.1` (a real, used, direct dependency) had two
independently disclosed high-severity CVEs of its own (arbitrary file read
via the dev server WebSocket; a Windows `server.fs.deny` bypass), and
`vite`'s own transitive `postcss` dependency resolved to a version with a
disclosed moderate XSS CVE.

**Fixed 2026-07-21:**
- `legacy-peer-deps=true` added to `.npmrc` — npm no longer auto-installs
  required peers; it only reports them. This is the one npm setting in this
  file that is *not* purely restrictive: it changes resolution behaviour for
  every future `npm install`, so any future package that *legitimately*
  needs a peer auto-installed will need that peer added explicitly to
  `package.json` instead.
- `vite` bumped `6.4.1` → `6.4.3` (patches both CVEs, non-major).
- `postcss` pinned via `package.json`'s `"overrides"` field to `8.5.21`
  (patches the XSS CVE; `postcss` is not a direct dependency, so `overrides`
  — npm's mechanism for forcing a transitive dependency's resolved version —
  is required here rather than a normal version bump).
- `npm audit` confirmed 0 vulnerabilities (moderate/high/critical) after
  the fix, verified via `npm install --package-lock-only` (resolves and
  updates the lockfile only, without writing `node_modules` or running any
  scripts) *before* the real install — see §8's mandatory workflow.
- `containers/prod/Dockerfile.frontend-prod` was not copying
  `package-lock.json` into its build context at all and ran `npm install`,
  which re-resolves from the registry on every build — meaning the
  committed lockfile was never actually enforced in production. Changed to
  `COPY` the lockfile and run `npm ci` (fails if `package.json` and
  `package-lock.json` disagree; installs exactly what's locked, nothing
  re-resolved).

**Cross-reference:** §5 already recommends vendoring `geist`'s static font
files instead of depending on the npm package, which would have prevented
this class of incident entirely (no npm dependency → no peerDependency → no
transitive pull). That remains the stronger long-term fix; `geist` was kept
as a dependency for now — this section's fix is scoped to the mechanism, not
the underlying dependency choice.

### 8. Mandatory Pre-Install/Update Workflow

Before running `npm install`/`npm update` for any reason (fixing a broken
environment, adding a dependency, bumping a version) — not just before
*adding* a new dependency (§4 already covered that case):

1. `npm install --package-lock-only` — resolves the full dependency tree and
   updates `package-lock.json` *without* writing `node_modules` or running
   any install scripts. This is the inspection point.
2. `npm audit` against the resulting lockfile. Zero tolerance for
   high/critical (§4 point 5); moderate should be fixed if a non-major fix
   is available (`overrides` if the vulnerable package is transitive).
3. Only once the resolved lockfile audits clean: run the real `npm install`
   (or `npm ci` in CI/Docker) to sync `node_modules`.
4. Re-run `npm audit` after the real install as a final confirmation
   (`node_modules` state should match the already-audited lockfile, but this
   catches any drift).

This is the exact sequence used in the §7 fix and should be the standing
procedure, not a one-off.

### 9. Sigstore-Based Package Verification — Adopted 2026-07-21

npm supports Sigstore-based verification via `npm audit signatures`, which
checks two independent things against every package in the tree: the
registry's own signature (npm's long-standing PGP-style signing) and, for
packages published with `--provenance` (common for packages built in public
CI, e.g. GitHub Actions), a Sigstore attestation linking the published
artifact back to the exact source commit and build workflow that produced
it — verified against the public Sigstore transparency log, not a key this
project has to manage.

**Required npm ≥9.5.0 for provenance verification.** This machine's system
npm was `9.2.0` — too old, and `npm audit signatures` failed outright with
`EEXPIREDSIGNATUREKEY` (the locally cached registry public key had expired
2025-01-29, predating that npm version's key rotation). Upgraded via
`sudo npm install -g npm@10.9.8` (npm's own registry, engine-checked against
this machine's Node `v20.19.2`; `12.0.1`/latest was rejected as a target —
it requires Node ≥22.22.2, which would have forced an unrelated Node
upgrade). Verified working: `npm audit signatures` now reports **98/98
packages with verified registry signatures, 10 packages with verified
Sigstore attestations**, 0 failures.

**Tool version is now pinned, matching §1's "pin everything exactly"
principle applied to npm/Node themselves, not just packages:**
`package.json`: `"engines": { "npm": ">=10.9.8", "node": ">=20.5.0" }`;
`.npmrc`: `engine-strict=true`. Verified enforced (not just declared) by
deliberately setting an unsatisfiable `engines.npm` and confirming
`npm install` fails with `EBADENGINE`, then reverting.

**Adopted into §8's workflow** as the final step after the post-install
`npm audit` (not yet gating — no CI pipeline runs this yet, so "adopted"
means "run manually every time," not "blocks a merge automatically"). No
packages in this project's tree currently fail the registry-signature check;
the un-attested 88/98 packages are pre-provenance packages (the norm for
most of npm's registry, not a finding) rather than failures.

**`ignore-scripts`, `save-exact`, `legacy-peer-deps` are now also set at the
npm user-config level** (`~/.npmrc`, this user account, no root needed) —
every npm project this user touches gets the same hardened defaults, not
just `register`. `engine-strict` stays project-scoped only: it depends on
each project's own `engines` field, which most unrelated projects won't
declare, so there's nothing to gain from forcing it user-wide.

### 10. Cooldown Period Before Version Updates — Adopted 2026-07-24

A newly published dependency version must not be adopted until it has been
publicly available for **14 days**. This applies to updating an existing
dependency and to the initial version chosen for a new dependency.

**Rationale:** the dominant modern attack is account takeover of a legitimate
maintainer followed by a malicious release under a trusted name (ua-parser-js
2021, Shai-Hulud 2025). Such releases are typically discovered, reported, and
pulled by the ecosystem within days. The cooldown lets that discovery window
pass before the version reaches this project.

**Rules:**

- If the latest version is younger than 14 days, pin the newest version that
  is older than 14 days (provided it has no known vulnerabilities).
- **Waiver — security fixes:** a version that fixes a disclosed vulnerability
  affecting this project is adopted immediately; waiting extends the exposure
  window. The standard audit workflow (§8 for npm, §4 for others) still runs.
- **Waiver — first-party artifacts:** versions of `com.risquanter` artifacts
  published by this organisation (`metalog-distribution`, `vql-engine`,
  `hdr-rng`) are exempt from the cooldown — the account-takeover threat model
  targets third-party upstreams, not artifacts we publish ourselves. The
  pinning rule (§1) still applies. (User-approved 2026-08-09.)
- Checking a version's publish date:
  - npm: `npm view <pkg>@<version> time` (or `npm view <pkg> time --json`)
  - Maven Central: artifact page on `central.sonatype.com` shows the publish date
  - opam: commit date of the version's entry in `ocaml/opam-repository`
  - Docker images / GitHub releases: release/tag date on the registry or repo

### 11. Dependency Trust Policy — Adopted 2026-07-24

- **Prefer dependencies from well-known, trustworthy organisations** — the
  vendors already in the stack (SoftwareMill, dev.zio, the OCaml/Mirage
  organisations, Alpine mainline packages) and comparable established
  publishers with an organisational track record.
- A dependency from an individual account or an unestablished organisation
  requires **case-by-case user approval before it is added**.
- Every approved exception is **documented at the pin site** — a comment in
  the build file where the version is pinned (`build.sbt`, `package.json`,
  the Dockerfile) stating the date, that the user approved it, and why the
  dependency is needed. An exception without a pin-site comment is not
  approved.

### 12. Signature Verification — Status per Ecosystem (2026-07-24)

**Policy:** verify artifact signatures wherever the ecosystem supports it.
Where a choice of mechanism exists, prefer **Sigstore/cosign** (keyless,
transparency-logged) over PGP.

| Ecosystem | Built-in verification | Status |
|---|---|---|
| npm | `npm audit signatures` — registry signatures + Sigstore provenance | **Adopted** (§9) |
| sbt / Maven Central | None usable out of the box — coursier verifies checksums (integrity only); Central's PGP signatures are not checked by sbt | **Gap** — flagged to user 2026-07-24; fallback plan (Sigstore-based) to be commissioned separately |
| opam | None — package signing (conex) never shipped; integrity relies on checksums in `ocaml/opam-repository` fetched over HTTPS | **Gap** — flagged to user 2026-07-24; a Sigstore fallback can only cover artifacts we build (builder images), not upstream packages. Note: `irmin-graphql` is carried with a local source patch under §11 (pin site: `Dockerfile.irmin-builder`; policy: `VERSION-UPGRADE-PROTOCOL.md`, opam section) |
| Docker base images | Digest pinning (§1); `cosign verify` where the publisher signs | **Partial** — every external base image is digest-pinned; publisher signature verification is not yet wired in. Check publisher cosign support at each base-image bump and record the result at the pin site |
| OS packages (apk, microdnf) | Distribution signing keys baked into the base image verify the signed package index and each package's checksum; exact version pins (§1) supply determinism | **Partial** — signature checking is automatic, but it does not survive an upstream account compromise, and no per-package hash pin exists in either tool |
| Fetched binaries (sbt launcher) | SHA-256 checksum (§2); upstream publishes no signatures | **Partial** — checksum only |

The two hard gaps (sbt/Maven, opam) cannot be closed by configuration; they
need their own plan (likely: Sigstore signing/verification wrapped around our
own build artifacts, plus registry-independent attestation where available).
That plan is user-commissioned work, out of scope for this ADR revision.

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
| `modules/app/.npmrc` | npm | `ignore-scripts=true`, `save-exact=true`, `legacy-peer-deps=true` (§7) |
| `modules/app/package.json` | npm | Exact version pins (no `^` or `~`); `"overrides"` to force-pin vulnerable transitive deps (§7) |
| `modules/app/package-lock.json` | npm | Committed; must actually be installed *from* (`npm ci`), not just present (§7) |
| `containers/builders/Dockerfile.irmin-builder` | opam | `opam install pkg.3.11.0` (version-pinned) |
| `containers/builders/Dockerfile.graalvm-builder` | wget | `SBT_SHA256` arg + `sha256sum -c` |
| `containers/prod/Dockerfile.frontend-prod` | wget, npm | `SBT_SHA256` arg + `sha256sum -c`; `npm ci --ignore-scripts` against the copied lockfile (§7) |
| `project/build.properties` | sbt | `sbt.version=<exact>` |
| All `FROM` lines | Docker | Pin to specific semver tags; use digest for releases |
| `zed` CLI (CI runner) | SpiceDB | Pin version + checksum verify (same pattern as `opa`, `conftest`) |
| Pre-install checklist | All | socket.dev / opam-show / apk audit review; §8's resolve-then-audit-then-install workflow for npm |
| `npm audit signatures` | npm | Sigstore provenance + registry signature verification — adopted (§9), manual step in §8's workflow |
| `~/.npmrc` (user config, this machine) | npm | `ignore-scripts=true`, `save-exact=true`, `legacy-peer-deps=true` — same hardening, all npm projects for this user (§9) |
| 14-day cooldown | All | New versions adopted only after 14 public days; CVE fixes waived (§10) |
| Pin-site exception comments | All | User-approved trust exceptions documented where the version is pinned (§11) |
| `docs/dev/VERSION-UPGRADE-PROTOCOL.md` | All | Per-ecosystem map for dependency updates and version bumps |

---

## References

- [socket.dev](https://socket.dev) — npm/PyPI package supply chain analysis
- [SLSA Framework](https://slsa.dev) — supply chain levels for software artifacts
- [opam-repository](https://github.com/ocaml/opam-repository) — reviewed OCaml package registry
- [SIL Open Font License 1.1](https://openfontlicense.org) — permits bundling font files with software
- [npm ignore-scripts](https://docs.npmjs.com/cli/v10/using-npm/config#ignore-scripts) — official npm docs

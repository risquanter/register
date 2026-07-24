# Version & Dependency Upgrade Protocol

A map for version bumps and dependency changes: what is pinned where, what a
change triggers downstream, and which document holds the actual commands.
This file does **not** repeat build instructions — those live in the
register-dev skill, `docs/user/IMAGE-BUILD-REFERENCE.md`, and
`docs/user/DOCKER-DEVELOPMENT.md`. Policy (pinning, cooldown, trust,
signatures) lives in `docs/dev/ADR-020-supply-chain-security.md`.

---

## 1. Our own version

**Source of truth:** `build.sbt` → `ThisBuild / version`.
Mirrored to **two** env files (both must be updated on every bump):

- `.env` — read by plain `docker compose` runs to tag images (`APP_VERSION`)
- `.env.irmin` — used with `--env-file`, which *replaces* `.env`, so it
  carries its own `APP_VERSION` line

### Bump scheme

| Change | Bump |
|---|---|
| Task/step completed in an approved plan; bug fix; security fix — when shipped code changed | PATCH `x.y.Z` |
| Plan closed (feature level); external API change (while < 1.0.0) | MINOR `x.Y.0` |
| External API change (once ≥ 1.0.0); user-declared case or pre-declared milestone | MAJOR `X.0.0` |
| Docs-only, test-only, skill/tooling-only changes (no shipped code) | No bump |

### Ownership

- **PATCH and MINOR: autonomous** — applied as part of landing the
  qualifying work, no approval needed.
- **MAJOR: user-owned.** Never bumped autonomously. The user performs it or
  pre-declares it (for example, a note at the end of a plan document naming
  the milestone — milestone-2b completion → 1.0.0 is such a declaration).

### Publishing a new version of our artifacts

1. Edit `build.sbt` version; sync `.env` and `.env.irmin` (commands:
   register-dev skill, "Versioning").
2. `docker compose up` rebuilds and tags application images
   (`local/register-server:<v>`, `local/frontend:<v>`) via
   `pull_policy: build`.
3. Release validation: BATS suites A + B + C (register-dev skill).

The SPA shows the version in the sidebar footer via `/config.json` — a bump
without the env-file sync ships images tagged with the old version.

---

## 2. What is compiled vs consumed — pipeline characteristics

This determines where a fix or override can be applied.

| Artifact | How it is built | Consequence for upgrades |
|---|---|---|
| Irmin server binary | **Compiled from source** inside `local/irmin-builder` (opam downloads OCaml sources and builds them) | We can patch upstream source locally via `opam pin` in the builder Dockerfile — no upstream release needed. The project adopts such a patch (chosen 2026-07-24, implementation pending), so source compilation is **required** (cannot switch to a prebuilt Irmin), and every bump must re-apply + re-validate the patch — see the opam section below |
| register-server | Our Scala compiled to a GraalVM native image; JVM dependencies consumed as **prebuilt jars** from Maven Central | Jar content cannot be patched locally; overriding means a version bump or a forked artifact |
| Frontend bundle | Our Scala.js compiled + Vite bundle; npm packages consumed **prebuilt** from the registry | Transitive npm versions can be forced via `package.json` `"overrides"`; direct packages only by version bump |
| vql-engine / hdr-rng | Sibling repos compiled into the GraalVM builder image | Upgrading them = rebuild `local/graalvm-builder` |
| apk packages | Prebuilt from the Alpine release repo; effectively pinned by the Alpine base version | Bumped implicitly with the base image tag |

---

## 3. Ecosystem maps

Each entry: where pinned → how to add/update → what to rebuild.
Verification and cooldown rules: ADR-020 §8–§12 (via the supply-chain skill).

### npm (`modules/app/`)

- **Pinned in:** `package.json` (exact versions, `"overrides"` for transitive
  pins) + committed `package-lock.json`. Hardening in `.npmrc`.
- **Add/update:** user authorization first (hard rule), then ADR-020 §8:
  resolve-only → audit → install → audit → `npm audit signatures`.
  Cooldown check: `npm view <pkg>@<version> time`.
- **Rebuild:** local dev picks it up on next `npm run dev`;
  `Dockerfile.frontend-prod` runs `npm ci` against the committed lockfile —
  the lockfile commit is what actually changes production.

### sbt / JVM (`build.sbt`)

- **Pinned in:** version `val`s at the top of `build.sbt`; sbt launcher in
  `project/build.properties` **and** as `ARG SBT_VERSION`/`ARG SBT_SHA256`
  pairs in `Dockerfile.graalvm-builder` and `Dockerfile.frontend-prod`.
- **Add/update:** edit the version val (note: `build.sbt` edits are
  hook-gated — user approval token required). No signature verification
  exists for Maven Central (ADR-020 §12 gap) — the pre-add checklist
  (ADR-020 §4) and cooldown are the only controls.
- **Rebuild:** `sbt compile` locally; `docker compose up -d register-server`
  rebuilds the native image. A launcher bump touches both Dockerfiles
  (version + checksum together) and requires rebuilding the builder images.

### Scala / sbt toolchain itself

- **Pinned in:** `scalaVersion` in `build.sbt`; `sbt.version` in
  `project/build.properties`.
- **Caution:** the Scala version is embedded in output paths
  (`modules/app/target/scala-3.7.4/`) — a Scala bump must be grepped for
  hardcoded path references (Vite config, Dockerfiles, docs) before rebuild.

### opam / Irmin (`containers/builders/Dockerfile.irmin-builder`)

- **Pinned in:** `opam install pkg.<version>` lines; the Irmin version also
  appears in the image **tags** (`local/irmin-builder:3.11`,
  `local/irmin-prod:3.11`), in `docker-compose*.yml` references, and in
  register-dev skill text — all move together on a bump.
- **Add/update:** edit the pinned versions; source-level patches go through
  `opam pin add <pkg> <patched-source>` before the install step.
- **Rebuild:** irmin-builder (15–40 min) → irmin-prod (~10 s) → run
  `serverIt` + BATS suite B then A.

**Source compilation is mandatory here, not incidental.** Irmin is built from
OCaml source inside the builder image specifically so the project can carry
its own local patches to upstream packages via `opam pin` (the concrete case:
a patch to `irmin-graphql`'s `merge_with_branch` resolver to surface merge
conflicts that upstream silently swallows — see ADR-032 / the phase-D merge
work; approach chosen 2026-07-24, implementation pending). Consequences for the update
policy that do **not** apply to prebuilt-consumed dependencies:

- **The build cannot be switched to a prebuilt Irmin distribution** (a
  published binary, a distro package) while any internal patch is carried —
  doing so drops the patch silently. Source-from-opam is a hard requirement,
  not an optimisation to remove.
- **Every Irmin version bump must re-apply and re-validate the patch against
  the new upstream source.** The patched resolver may have moved or changed;
  a bump that compiles clean is not proof the patch still applies as intended.
  Re-run the integration test that pins the patched behaviour (the
  merge-conflict-surfacing spec) after any Irmin bump, not just a smoke build.
- **The patch is a documented trust exception (ADR-020 §11):** the pinned
  version plus a comment at the `opam pin` site must record that a local
  source patch is applied and why, so a future bump does not treat the pin as
  a plain upstream version.

### Docker base images (all Dockerfiles)

- **Pinned in:** `FROM` lines — specific tags today; digest pinning for
  release builds and `cosign verify` where the publisher signs
  (ADR-020 §1, §12); record the cosign check result at the pin site.
- **Rebuild:** the image whose Dockerfile changed, plus its dependents
  (builder → prod chains).

### Fetched binaries (wget in Dockerfiles)

- **Pinned in:** `ARG <TOOL>_VERSION` + `ARG <TOOL>_SHA256` pairs; both are
  updated together, checksum taken from the official release page
  (ADR-020 §2).

---

## 4. Rebuild matrix

| What changed | Must rebuild |
|---|---|
| Our version bump only | Nothing manually — compose retags/rebuilds app images on next `up` |
| npm dependency | Frontend image (via lockfile commit + `npm ci`) |
| JVM dependency | register-server image (compose rebuild) |
| vql-engine / hdr-rng | graalvm-builder, then register-server |
| sbt launcher | graalvm-builder + frontend image (both carry the ARG pair) |
| Irmin/OCaml version | irmin-builder → irmin-prod → tags + compose refs + skill text |
| Alpine/base image tag | That image + dependents; apk package set implicitly changes |
| Scala version | Everything + grep for embedded `scala-<v>` paths |

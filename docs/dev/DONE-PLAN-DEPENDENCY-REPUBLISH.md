# PLAN — Dependency Republish: Maven Central Coordinates + Builder Simplification

Status: implemented (landed 0.10.15); §2.7 security-review follow-ups landed 2026-08-10.
Version bump on landing: PATCH → 0.10.15 (dependency change, shipped code changed).

## 1. Goal

Three first-party libraries are now published on Maven Central as binary
artifacts. Register switches to consuming them from Maven Central and stops
building them from sibling source checkouts inside Docker image builds. The
two-stage build-image architecture (builder base image → app image) stays;
only the "COPY sibling repo + `sbt publishLocal`" steps are removed.

Library-side facts (provided by the user, 2026-08-09):

1. `com.risquanter:simulation.util` (register pinned 0.8.0) is replaced by
   `com.risquanter:metalog-distribution:0.9.0` (plain Java artifact, `%`).
   Java package `com.risquanter.simulation.util.distribution.metalog.*`
   becomes `com.risquanter.metalog.*`; class names unchanged (`Metalog`,
   `QPFitter`, `QPUnboundedConstrainedFitter`).
2. The vague-quantifier-logic GitHub repo is now `risquanter/vql-engine`.
   Artifact coordinates unchanged: `"com.risquanter" %%% "vql-engine" % "0.10.2"`.
   The sibling checkout remains at `../vague-quantifier-logic`; path
   references to it stay as they are.
3. `hdr-rng` unchanged: `"com.risquanter" %%% "hdr-rng" % "0.1.0"` — now
   resolved from Maven Central instead of a `publishLocal` inside the image.

This executes the migration pre-planned in `docs/dev/TODO.md` item 5b
("Migration work required when `hdr-rng` and `fol-engine` are published"),
plus the coordinate/package renames layered on top.

User rulings already given (2026-08-09, chat):

- (a) `NodeProvenance.simulationUtilVersion` IS renamed — wire-format change
  accepted; everything is pre-prod, no real consumers.
- (b) Build-context simplification approved (narrow contexts, delete the
  per-Dockerfile dockerignore files, compose context changes).
- (c) ADR-020 §10 gains an explicit cooldown exception for these first-party
  packages; a new docs TODO item is added on CoSign signature verification in
  the current two-stage build workflow and a holistic migration of the image
  creation pipeline to GitHub Actions.

## 2. Exact changes

### 2.1 build.sbt

Dependency-version vals (top section, matching the existing `val xyzVersion`
convention) and the changed lines, verbatim:

```scala
val metalogVersion    = "0.9.0"   // com.risquanter:metalog-distribution (Java)
val vqlEngineVersion  = "0.10.2"  // com.risquanter:vql-engine (cross-compiled)
```

In `serverDependencies` (replaces the `simulation.util` line):

```scala
  "com.risquanter"                 % "metalog-distribution"              % metalogVersion,
```

In the `server` project settings (replaces the current key):

```scala
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      "metalogDistributionVersion" -> metalogVersion
    ),
```

In `common` (replaces the 0.10.1-SNAPSHOT pin; comment line above it kept):

```scala
    libraryDependencies += "com.risquanter" %%% "vql-engine" % vqlEngineVersion
```

`hdr-rng` stays `"com.risquanter" %% "hdr-rng" % "0.1.0"` — unchanged line;
only its resolution source changes (Maven Central, no image-side publish).

### 2.2 Domain field rename (wire format, pre-prod — ruling (a))

`modules/common/.../domain/data/Provenance.scala`:

```scala
case class NodeProvenance(
  entityId: Long,
  occurrenceVarId: Long,
  lossVarId: Long,
  globalSeed3: Long,
  globalSeed4: Long,
  distributionType: String,
  distributionParams: DistributionParams,
  timestamp: Instant,
  metalogDistributionVersion: String
)
```

The derived `JsonCodec` emits the new JSON field name; no codec code changes.
Scaladoc `@param` line updated to: "Version of the metalog-distribution
library (Metalog fit; HDR randomness comes from hdr-rng)".

Call-site/test updates (mechanical rename, no behaviour change):

- `modules/server/.../services/helper/Simulator.scala` —
  `simulationUtilVersion = BuildInfo.simulationUtilVersion` →
  `metalogDistributionVersion = BuildInfo.metalogDistributionVersion`.
- `modules/server/src/test/.../domain/data/ProvenanceSpec.scala` — same
  rename at the construction site and in the `nonEmpty` assertion.
- `modules/common/src/test/.../domain/data/LossDistributionSpec.scala` —
  rename at the two construction/copy sites.

### 2.3 Java package rename at the import site

`modules/server/.../simulation/MetalogDistribution.scala`:

```scala
import com.risquanter.metalog.{Metalog, QPFitter}
```

Scaladoc/comment sweep in the same file: `@see` lines to
`com.risquanter.metalog.Metalog` / `...QPFitter`; prose mentions of
"simulation-util" become "metalog-distribution".
`modules/common/.../domain/data/iron/OpaqueTypes.scala` comment
("simulation-util's fitting" note) updated the same way.

### 2.4 Docker build simplification (TODO 5b execution — ruling (b))

- `containers/builders/Dockerfile.graalvm-builder` — delete the entire
  "Local SNAPSHOT libraries" section (both COPY + `sbt publishLocal` blocks);
  header comment updated: context note removed, build command becomes
  `docker build -f containers/builders/Dockerfile.graalvm-builder -t local/graalvm-builder:21 containers/builders/`
  (same convention as the irmin-builder). Dependencies resolve from Maven
  Central during dependent images' `sbt update` layers.
- `containers/builders/Dockerfile.graalvm-builder.dockerignore` — deleted
  (nothing is copied from the context anymore).
- `containers/prod/Dockerfile.frontend-prod` — delete the two COPY +
  `publishLocal` blocks; strip the `register/` prefix from every COPY path
  (context becomes the register root); header comments updated (context
  note, build command `..` → `.`).
- `containers/prod/Dockerfile.frontend-prod.dockerignore` — deleted; the
  root `.dockerignore` allow-list governs the narrowed context.
- `.dockerignore` (root) — add `!modules/app/package-lock.json` to the
  frontend section (the Dockerfile COPYies it; the current allow-list would
  exclude it — latent build break under the new context).
- `docker-compose.yml` — frontend service: `context: ..` → `context: .`;
  `dockerfile: register/containers/prod/Dockerfile.frontend-prod` →
  `dockerfile: containers/prod/Dockerfile.frontend-prod`.

`containers/prod/Dockerfile.register-prod` is not modified — it inherits the
toolchain from `local/graalvm-builder:21` and already resolves register's
dependencies via `sbt update` (now from Maven Central). The builder image is
rebuilt as part of verification so it no longer carries baked-in local
artifacts.

### 2.5 ADR-020 amendment + new TODO (ruling (c))

- `docs/dev/ADR-020-supply-chain-security.md` §10 gains an explicit waiver:

  > **Waiver — first-party artifacts:** versions of `com.risquanter`
  > artifacts published by this organisation (`metalog-distribution`,
  > `vql-engine`, `hdr-rng`) are exempt from the cooldown — the account-
  > takeover threat model targets third-party upstreams, not artifacts we
  > publish ourselves. The pinning rule (§1) still applies.

- `docs/dev/TODO.md`: item 5b marked RESOLVED (migration executed here);
  stale name references in items 5a/8 and the item-5a follow-up list updated
  to current artifact names; NEW item added: evaluate CoSign signature
  verification for the two-stage build workflow (builder + app images), and
  holistically, migrating the whole image-creation pipeline to GitHub
  Actions (build, sign, attest, push).

### 2.5a BATS suite repair (required X-Branch header)

`tests/bats/suite-c-in-memory.bats` (C03/C10/C16) and
`tests/bats/suite-a-full-prod.bats` (A04/A05) predate Phase E's required
`X-Branch` header (E7, landed 0.10.0) and had not run green since — masked
by the sibling-build breakage this plan removes. The affected curl calls
gain `-H 'X-Branch: main'`; every assertion is unchanged.

### 2.6 Docs and skills sweep (current-state rule)

Old coordinates/package/repo-name/path references updated; sibling-checkout
build prerequisites removed (siblings are no longer needed to build images):

- `CLAUDE.md` — drop the "sibling repos must be checked out for Docker
  builder-image builds" sentence.
- `README.md` — clone-siblings prerequisite removed; the vague-quantifier
  repo URL becomes `https://github.com/risquanter/vql-engine.git` where the
  repo is referenced.
- `docs/user/IMAGE-BUILD-REFERENCE.md`, `docs/user/DOCKER-DEVELOPMENT.md`,
  `docs/user/DEVELOPMENT-SETUP.md`, `docs/user/PERSISTENT-SETUP.md` —
  context notes, build commands, prerequisite tables updated to the new
  contexts and to Maven Central resolution.
- `docs/dev/ADR-002.md`, `docs/dev/ADR-003.md` — `simulation.util` /
  `simulation-util 0.8.0` references updated to `metalog-distribution`.
- `docs/dev/ADR-028-vague-quantifier-query-pane.md` + appendix — library/repo
  name mentions updated to `vql-engine` (repo `risquanter/vql-engine`).
- `.github/skills/register-dev/SKILL.md` + byte-identical mirror
  `.claude/skills/register-dev/SKILL.md` — builder build commands (new
  contexts), removal of the "after vql-engine changes rebuild the builder"
  source-build flow (now: bump the pinned version instead), sibling mentions.
- `.github/skills/supply-chain/SKILL.md` + mirror
  `.claude/skills/supply-chain/SKILL.md` — cooldown rule gains the
  first-party waiver sentence mirroring the ADR-020 §10 amendment.

Not touched (historical records, deliberately):
`docs/dev/DONE_PLAN-DISTRIBUTION-PREVIEW.md`,
`docs/dev/DONE-PLAN-STRING-PARAM-SWEEP.md`, decision-log passages inside
`docs/dev/PLAN-RISKTRANSFORM.md`, `docs/scratch/MITIGATION-PRE-PLANNING.md`.
Path references to `../vague-quantifier-logic` stay valid per the user's
note and are not rewritten.

### 2.7 Security-review follow-ups (user rulings 2026-08-10)

- **npm vulnerability fix (F1, user-authorized):** `modules/app/package.json`
  `overrides` become `"postcss": "8.5.23"` (outside the §10 cooldown) and
  `"nanoid": "3.3.17"` (inside cooldown, security-fix waiver — fixes
  GHSA-2v37-7h3g-55p8). Full ADR-020 §8 workflow (resolve → audit →
  install → audit → `npm audit signatures`); regression gate: frontend
  production build + BATS suite C green.
- **Ivy-local retirement (F3):** the pre-Central `publishLocal` artifacts
  shadow Maven Central (ivy-local wins resolution). Migration step for every
  dev machine: `rm -rf ~/.ivy2/local/com.risquanter/` — all coordinates
  there are dead (`fol-engine`, `vague-quantifier-logic`) or served by
  Central at the same versions.
- **Builder-context hygiene (F7):** root `.dockerignore` gains
  `**/.bloop/`, `**/metals.sbt`, `**/.metals/`, `**/.bsp/` so
  `COPY project/ project/` no longer carries IDE/compile metadata into
  builder-stage layers.
- **Doc fix (F6):** `docs/user/IMAGE-BUILD-REFERENCE.md` rebuild-trigger
  section rewritten to the pin-bump flow (no builder rebuild on first-party
  releases).

## 3. ADR alignment

- **ADR-020** — pins stay exact (§1); §10 amended per user ruling (c);
  publisher is our own organisation (§11 satisfied, no pin-site exception
  comment needed); sbt/Maven signature gap (§12) unchanged — the new CoSign
  TODO item is the commissioned follow-up vehicle.
- **ADR-026** — the staged image build order is preserved; the builder base
  simply no longer bakes in local artifacts.
- **ADR-001/-017 (API shapes)** — `NodeProvenance` wire field rename is an
  API-shape change, explicitly approved by the user (ruling (a), pre-prod).
- **ADR-002/-003/-028** — reference updates only, no decision changes.

## 4. Open decisions

No open decisions. Specified in this plan (vetoable at approval): new field
and BuildInfo key name `metalogDistributionVersion`; graalvm-builder build
context `containers/builders/` (matching the irmin-builder convention rather
than TODO 5b's literal `.` — with no COPY steps left, the smaller context is
strictly better and the pattern already exists).

## 5. Verification plan

Precondition check before any edit: the three artifacts resolve from Maven
Central (`metalog-distribution:0.9.0` `%`; `vql-engine:0.10.2` and
`hdr-rng:0.1.0` for both `_3` and `_sjs1_3`).

All tiers, all must be green (pass/fail reporting only):

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
sbt "serverIt/test"

# Rebuild the simplified builder base, then the smoke tiers
docker build -f containers/builders/Dockerfile.graalvm-builder \
  -t local/graalvm-builder:21 containers/builders/

run_bats tests/bats/suite-c-in-memory.bats
run_bats tests/bats/suite-a-full-prod.bats
run_bats tests/bats/suite-b-irmin-prod.bats
```

Suite B is included although the Irmin image is untouched: this change
alters the build architecture, so it is treated as full release validation
(A + B + C per the register-dev matrix). Landing: version 0.10.15 in
`build.sbt`, mirrored to `.env` and `.env.irmin`; doc-consistency sweep is
§2.5/§2.6 of this plan.

## File inventory

- build.sbt
- modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/LossDistributionSpec.scala
- modules/server/src/main/scala/com/risquanter/register/simulation/MetalogDistribution.scala
- modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala
- modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala
- containers/builders/Dockerfile.graalvm-builder
- containers/builders/Dockerfile.graalvm-builder.dockerignore
- containers/prod/Dockerfile.frontend-prod
- containers/prod/Dockerfile.frontend-prod.dockerignore
- .dockerignore
- docker-compose.yml
- CLAUDE.md
- README.md
- docs/user/IMAGE-BUILD-REFERENCE.md
- docs/user/DOCKER-DEVELOPMENT.md
- docs/user/DEVELOPMENT-SETUP.md
- docs/user/PERSISTENT-SETUP.md
- docs/dev/ADR-002.md
- docs/dev/ADR-003.md
- docs/dev/ADR-020-supply-chain-security.md
- docs/dev/ADR-028-vague-quantifier-query-pane.md
- docs/dev/ADR-028-appendix-technical-design.md
- docs/dev/TODO.md
- docs/dev/SENSITIVITY-ANALYSIS-PLAN.md
- docs/dev/PLAN-PROVENANCE-ENDPOINT.md
- examples/demo-enterprise-curl.sh
- examples/demo-enterprise-httpie.sh
- tests/bats/suite-c-in-memory.bats
- tests/bats/suite-a-full-prod.bats
- modules/app/package.json
- modules/app/package-lock.json
- .github/skills/register-dev/SKILL.md
- .claude/skills/register-dev/SKILL.md
- .github/skills/supply-chain/SKILL.md
- .claude/skills/supply-chain/SKILL.md
- .env
- .env.irmin

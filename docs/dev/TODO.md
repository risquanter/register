# TODO

Open issues, observations, and investigation notes.
Items are descriptive — they document symptoms and current understanding,
not prescribed solutions.

---

## ✅ 1. "Will retry" banner text has no backing implementation — RESOLVED 2026-07-09

**Resolution:** `retryable: Boolean` removed from `GlobalError.NetworkError`
(ADR-012 §4 + ADR-031 — request-path retries are owned by Istio, not the SPA).
`ErrorBanner` pattern updated. Dead `isFetchNetworkError` guard, `IOException`
arm, and private method removed — without `retryable` they were functionally
identical to the catch-all. `DependencyError` doc updated to name the mesh as
the retry owner. Catch-all comment names browser Fetch errors as the primary
non-domain case.

~~**Observed:** When a network error occurs (e.g. `TypeError: NetworkError when
attempting to fetch resource.`), the global error banner appends " — will retry"
to the message. No retry ever happens — the message is misleading.~~

---

## 2. Dual error display with inconsistent formatting on the same exception

**Observed:** When a query (or any `loadInto`-based API call) fails, the same
exception surfaces in two places simultaneously:

1. **Global error banner** (top of page) — via `ErrorObserver.onError` in
   `forkProvided` → `GlobalError.fromThrowable` → `ErrorBanner`. The message
   has browser prefixes stripped (`"TypeError: "`, `"Error: "`) and, for
   retryable errors, `" — will retry"` appended.

2. **Per-view inline error** (e.g. `QueryResultCard`, `LECChartView`) — via
   `LoadState.Failed(e.safeMessage)` set in `loadInto`'s `tapError`. The
   message is the raw `safeMessage` with no stripping or hints.

This produces two visually different error messages for the same failure.

**Current understanding:** The duplication is structural — `loadInto` sets the
per-view `LoadState.Failed` in its `tapError`, and then `forkProvided` fires
the global `ErrorObserver` as a separate concern. Both channels process the
same `Throwable` independently with different formatting pipelines
(`safeMessage` vs `GlobalError.fromThrowable` → `msg()` + hint).

This may be a hint at latent code / functionality duplication between the
per-view error path and the global error path. The two formatting pipelines
(`safeMessage` in `ZJS.loadInto` vs `GlobalError.msg()` in `fromThrowable`)
diverge silently — the global path strips prefixes and adds context; the
per-view path does not. Whether these should converge, or whether one channel
should suppress when the other is active, is an open design question.

---

## 3. [DEFERRED] Reference-class anchoring for distribution preview

**Description:** When a user is fitting a distribution to a risk leaf, show a
reference band drawn from anonymised distributions of "similar" leaves across
other workspaces (or a curated reference library). This grounds the user's
expert estimate in empirical base rates, directly countering planning-fallacy
and inside-view bias (Kahneman / Flyvbjerg reference-class forecasting).

**Design sketch:**
- Requires a reference-distribution store (aggregated, anonymised percentile
  statistics per risk category or industry label).
- The distribution preview endpoint could accept an optional `referenceClass`
  parameter. The server fetches the reference band (p10/p50/p90 envelope) from
  the store and includes it in the `DistributionPreviewResponse`.
- In the Vega-Lite PDF/CDF spec, the reference band appears as a shaded region
  behind the user's fitted curve.

**Prerequisites:** The distribution preview panel (PLAN-DISTRIBUTION-PREVIEW.md)
must be live first. A reference-distribution data store (schema + seed data)
must be designed. User consent / opt-in mechanism for anonymised contribution.

**Related IMPLEMENTATION-PLAN.md items:** Not yet planned. This is Phase 3+
functionality (post-Tier 2). Nearest anchor is Phase 7 (Scenario Branching,
§2403) for the aggregation/comparison infrastructure, but reference-class
anchoring is a distinct feature requiring a separate data pipeline.

---

## 4. [DEFERRED] Sensitivity labelling (tornado chart) for risk leaves

**Description:** Once a tree is simulated, show which leaf nodes contribute the
most variance to the aggregate LEC. Varying each leaf ±1σ while holding others
at their mean produces a ranked bar chart (tornado) of sensitivity. This tells
the user where to invest modelling effort and where to invest risk controls.

**Design sketch:**
- Compute by perturbing each leaf's distribution (shift quantiles or scale
  parameters) and re-running a partial simulation (or analytically via variance
  decomposition if the independence assumption holds).
- Display as a horizontal ranked bar chart in a dedicated "Sensitivity" panel
  (separate from the distribution preview, tree-level not leaf-level).
- Per-leaf sensitivity could also be shown as a colour-coded heatmap overlay on
  the tree structure.

**Prerequisites:** The scenario comparison infrastructure (IMPLEMENTATION-PLAN.md
Phase 7: Scenario Branching, §2403) is directly related — the `ScenarioComparator`
planned there already diffs LECs between tree variants. Sensitivity analysis is
essentially automated scenario comparison with systematic single-leaf
perturbations.

**Related IMPLEMENTATION-PLAN.md items:**
- Phase 7: Scenario Branching (§2403) — `ScenarioComparator.compare(a, b)` diffs
  LECs at p50/p90/p95/p99; this infrastructure is reusable for sensitivity.
- IMPLEMENTATION-PLAN.md §2523 (post-Tier 3 items): "sensitivity analysis becomes
  higher-value once content-addressed caching and scenario branching are in place."

---

## 5. PostgreSQL workspace TTL/idle interval handling uses a DB-specific text round-trip

**Observed:** The PostgreSQL workspace store currently writes `ttl` and
`idle_timeout` via a Quill `infix` insert that casts lifted string parameters
to `::interval`, and reads them back by parsing PostgreSQL's textual interval
representation.

**Current understanding:** This is implemented in
`WorkspaceStorePostgres.create()` and `WorkspaceStorePostgres.DurationString`
under `modules/server/src/main/scala/com/risquanter/register/services/workspace/`.
The approach is secure because values are still parameterized with `lift(...)`,
but it is PostgreSQL-specific and less idiomatic than a first-class Quill/JDBC
mapping. This was introduced because Quill did not provide a working
encoder/decoder for `PGInterval` in the current setup.

Follow-up review should determine whether to replace this with a more idiomatic
representation, for example:
- a custom Quill/JDBC encoder-decoder for a dedicated interval type,
- or a schema change to store durations as numeric milliseconds/seconds,
- or another typed persistence representation that avoids textual interval parsing.

---

## 5b. SNAPSHOT dependency resolution strategy in Docker builds

**Decision:** Inline `COPY + sbt publishLocal` per Dockerfile (Option 1).

Each Dockerfile that needs `hdr-rng` or `fol-engine` and cannot inherit a
pre-warmed base image copies the sibling source trees into a temporary directory,
runs `sbt publishLocal`, then deletes the source. The compiled `.jar` artifacts
remain in the image's Ivy cache (`~/.ivy2/local/`) for the duration of the build.

Current state:
- `containers/builders/Dockerfile.graalvm-builder` — publishLocal inline; context `..`
- `containers/prod/Dockerfile.frontend-prod` — publishLocal inline; context `..`
- `containers/prod/Dockerfile.register-prod` — no publishLocal; inherits the JVM
  artifacts from `FROM local/graalvm-builder:21`

**Rebuild rule:** if `hdr-rng` or `vague-quantifier-logic` source changes, rebuild
`local/graalvm-builder:21` and `local/frontend:<version>` before rebuilding dependent images.

**Note on Docker context and sibling paths:**  
Docker's `COPY` instruction cannot reference paths outside the build context —
there is no `COPY ../sibling/` syntax. The `..` passed as the context *argument*
to `docker build` is the only valid mechanism when sibling source trees are
needed. Context `.` with sibling paths is not possible in a Dockerfile.

**Migration work required when `hdr-rng` and `fol-engine` are published:**

The following changes must be made atomically once both artifacts are available
from a Maven registry (GitHub Packages or Maven Central):

1. **`containers/builders/Dockerfile.graalvm-builder`**
   - Delete the two `COPY` + `RUN sbt publishLocal` blocks for `hdr-rng` and
     `vague-quantifier-logic`.
   - Update the build comment at the top: remove "Build context must be the
     parent of the register project" and change the example command to use `.`.

2. **`containers/prod/Dockerfile.frontend-prod`**
   - Delete the two `COPY` + `RUN sbt publishLocal` blocks for `hdr-rng` and
     `vague-quantifier-logic`.
   - Change all `COPY register/...` paths back to unprefixed `COPY ...` paths
     (i.e. revert to context `.`).
   - Update the build comment at the top: remove the `..` context note.

3. **`containers/builders/Dockerfile.graalvm-builder.dockerignore`**
   - Delete the file entirely (the new `.` context uses `register/.dockerignore`
     which already has the correct allow-list).

4. **`containers/prod/Dockerfile.frontend-prod.dockerignore`**
   - Delete the file entirely (same reason as above).

5. **`docs/DOCKER-DEVELOPMENT.md`** — update every occurrence of:
   - The graalvm-builder build command: `.. ` → `.`
   - The frontend-prod build command: `..` → `.`
   - The build context explanatory notes in the graalvm-builder and frontend
     sections.
   - The Quick Reference table entries for both images.

6. **`build.sbt`**
   - Add a `resolvers +=` entry if publishing to GitHub Packages (not needed
     for Maven Central which is already on the default resolver chain).
   - For GitHub Packages: pass registry credentials via
     `--secret id=MAVEN_TOKEN,...` in the `docker build` command and write
     `~/.sbt/1.0/credentials` in the Dockerfile builder stage.
   - The `libraryDependencies` coordinates (`com.risquanter %% "hdr-rng"`,
     `com.risquanter %%% "fol-engine"`) remain unchanged.

7. **`docker-compose.yml`**
   - Change `context: ..` back to `context: .` for the `frontend` service
     (widened to `..` solely because the Dockerfile needed sibling source trees;
     once those `COPY` blocks are removed, the context can be narrowed back).
   - Change `dockerfile: register/containers/prod/Dockerfile.frontend-prod` back
     to `dockerfile: containers/prod/Dockerfile.frontend-prod` (the `register/`
     prefix was added because the context root is `..`).
   - `register-server` and `irmin` both already use `context: .` and are unaffected.

---

## 5a. [LOW PRIO / EXTERNAL] `vague-quantifier-logic` — add monotonicity validation to metalog constructor

**Context assessment:**
`MetalogDistribution.fromPercentilesUnsafe` accepts non-monotone quantile arrays
and silently returns `Right(_)`. The metalog is not a general curve-fitter;
its mathematical foundation is that the quantile function $Q(p)$ **is** the
distribution. You supply $\{(p_i, q_i)\}$ pairs, solve the linear system
$\mathbf{q} = \mathbf{M}\mathbf{a}$ for coefficients $\mathbf{a}$, and the result
is a valid probability distribution if and only if $Q'(p) > 0$ for all
$p \in (0, 1)$. Non-monotone input quantile values (e.g. $q_{0.25} > q_{0.50}$)
violate the precondition of the function — the linear system can still be solved,
but the result has a negative PDF on some interval and is not a probability
distribution. Accepting such input and returning `Right(_)` is a contract error,
not a design feature.

**Correct action in `vague-quantifier-logic`:**
Add a `fromPercentiles` constructor that validates strict monotonicity of the
supplied quantile values before fitting and returns
`Either[MonotoneViolation, MetalogDistribution]`. The existing
`fromPercentilesUnsafe` can delegate to it (strip the `Right` branch) or be
documented to explicitly require monotone input as a precondition — whichever
is more consistent with the library's internal conventions.

The validation is a simple linear scan: for each adjacent pair
$(q_i, q_{i+1})$, assert $q_i < q_{i+1}$. The error value should carry the
violating pair and its indices so the caller can surface a meaningful message.

**Consequence for this repo (`register`):**
`DistributionPreviewRequest.validate()` currently enforces strict quantile
monotonicity at the request layer *because* the library does not. Once the
library validates its own preconditions, register's check becomes
redundant-but-harmless defence-in-depth. It should be kept as-is (it provides
a user-facing error message before any IO) but annotated with a comment
explaining the layering:

```scala
// Validates monotonicity before dispatching to the library.
// vague-quantifier-logic's fromPercentiles also validates — this check
// surfaces a structured request-layer error earlier.
```

**Follow-up steps:**

1. **`simulation-util` / `vague-quantifier-logic`**
   - Add `MonotoneViolation` error type (or reuse an existing validation error
     type if one exists) carrying the violating indices and values.
   - Implement `fromPercentiles(...): Either[MonotoneViolation, MetalogDistribution]`
     with the linear scan guard before the fitting call.
   - Add a unit test: `fromPercentiles` with non-monotone input returns `Left(_)`;
     `fromPercentiles` with strictly increasing input returns `Right(_)` and
     agrees with `fromPercentilesUnsafe` on a known fixture.
   - Update `fromPercentilesUnsafe` scaladoc to state the monotonicity precondition
     explicitly.

2. **`register`**
   - Update the call site in `DistributionPreviewService` (or wherever
     `fromPercentilesUnsafe` is called) to use `fromPercentiles` instead,
     removing the `Unsafe` call entirely.
   - Add the layering comment to `DistributionPreviewRequest.validate()` as above.
   - No behaviour change; the monotonicity error is already surfaced before this
     call site is reached.

**Status:** root cause confirmed, fix not yet started. Blocked on
`simulation-util` work.



---

## 8. fol-engine typed vs. untyped pipeline mismatch — equality predicate not reachable

**Observed (2026-05-01).** A motivating query for the post-fix demo
set of [docs/PLAN-QUERY-NODE-NAME-LITERALS.md](docs/PLAN-QUERY-NODE-NAME-LITERALS.md)
is single-leaf identity:

```
Q[>=]^{1} x (eq(x, "Cyber Breach"), gt_loss(p95(x), 5000000))
# "Does the Cyber Breach leaf have P95 loss above $5M?"
```

This cannot be expressed today even after the F1 + F2 + F3 fixes from
that plan land. Reason: no `eq` (or generic `=`) predicate is
registered in the typed FOL dispatcher used by `register`
([RiskTreeKnowledgeBase.scala:100](modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala#L100)
lists only `leaf`, `portfolio`, `child_of`, `descendant_of`,
`leaf_descendant_of`, `gt_loss`, `gt_prob`).

**The puzzling part.** The sibling `fol-engine` repo *does* implement
equality, but only on a different code path:

- `fol.bridge.ComparisonAugmenter` (untyped) registers `"="` for any
  `Ordering` instance: `"=" -> { case List(a, b) => ord.equiv(a, b) }`.
- `semantics.FOLSemantics:399` likewise registers `"="` in the untyped
  semantics map.

Neither is wired into `register`. `QueryServiceLive`
([QueryServiceLive.scala:18](modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala#L18))
imports only `fol.typed.FolModel`, and the dispatcher imports only
`fol.typed.{TypeCatalog, … RuntimeDispatcher, …}`. So `register` runs
entirely on the typed pipeline, where equality is absent.

**What needs investigating.**

1. **Why two pipelines exist at all.** Is `fol.bridge` /
   `semantics.FOLSemantics` an earlier OCaml-faithful port that
   `fol.typed` was meant to subsume? An intentional escape hatch for
   ad-hoc usage? A research sandbox? Get a code-archaeology answer
   from the fol-engine ADRs (especially ADR-007 "Preserve OCaml-Ported
   Parser Combinator Core") and any author commentary.
2. **Why typed pipeline has no equality.** Is sort-polymorphic equality
   a known omission, an explicit decision (e.g. equality is meaningful
   only for some sorts and the textbook treats it specially), or a
   simple oversight?
3. **Where equality should land.** Three plausible homes:
   - (a) **Sort-monomorphic register-side predicates** — register adds
     `eq_asset: Asset × Asset → Bool` (and any other sort-specific
     equalities it needs) to its own `RiskTreeKnowledgeBase` catalog.
     Tiny, ADR-compliant, follows the existing per-predicate pattern
     exactly. Doesn't address the broader question.
   - (b) **Generic equality in `fol.typed`** — fol-engine adds
     polymorphic `=` to the typed type-checker / dispatcher. Bigger
     change; needs the typed type-checker to admit a polymorphic
     predicate or to special-case `=`.
   - (c) **Promote / merge the untyped path** — clarify whether the
     two pipelines should converge, then implement.
4. **Long-term posture on the untyped path.** Should `fol.bridge` /
   the untyped semantics layer be deprecated, kept as a separate
   public API, or merged?

**Why this deserves its own investigation step rather than a quick fix.**
Adding `eq_asset` (option a) in 5 minutes would unblock the demo query
but bake in a divergence between two equality stories (sort-specific
predicates here, polymorphic `=` over there) without anyone
understanding why both exist. A short investigation answers the
"why" first; the implementation choice then follows.

**Outcome wanted.** Brief written answer to questions 1–4, then a
follow-up plan (or PR if trivial) that lands equality in the typed
pipeline so Q-E becomes executable. The eq query above is the concrete
test case.

---

## 9. Design view tree dropdown does not load existing tree structure

**Observed:** With the cluster running under
`docker compose --profile persistence --profile frontend up -d` and a
workspace pre-populated by one of the `examples/demo-*.sh` scripts:

- Loading the SPA at the workspace's capability URL on the **Design view**
  shows the tree's name in the tree-selection dropdown, but selecting it
  from the dropdown does **not** render the pre-populated structure in the
  editor. The dropdown lists the tree; the canvas/structure pane stays
  empty (or in its initial "no tree loaded" state).
- Loading the same workspace URL on the **Analyze view** and selecting the
  same tree from its dropdown **does** load and display the tree structure
  correctly.

So the two views' tree-selection dropdowns behave inconsistently for the
same workspace + tree.

**What needs investigating.**

1. Identify the source of the divergence between the Design-view and
   Analyze-view dropdown handlers — likely candidates: a missing
   "load selected tree into editor state" wiring on the Design side,
   a guard that only populates the editor on freshly-created trees,
   or a separate `Var`/`Signal` for "selected tree" vs "loaded tree
   into editor".
2. Confirm whether the issue reproduces under the in-memory profile
   (i.e. without `--profile persistence`) — if it does, the bug is
   purely client-side; if not, persistence-layer rehydration is
   implicated.
3. Decide the correct fix so that both dropdowns behave identically
   from the user's perspective: selecting an existing tree loads its
   full structure into the view.

**Expected behaviour.** Both Design and Analyze tree dropdowns load and
display the structure of an existing tree on selection. On the Design
view, this must additionally **preserve the ability to pick up editing
of an existing tree** (i.e. the loaded structure becomes the editable
working copy, not a read-only snapshot).

**Outcome wanted.** A short root-cause write-up identifying the
divergent code path, followed by a fix (or follow-up plan if the fix is
non-trivial) that aligns Design-view dropdown behaviour with
Analyze-view behaviour while keeping Design's edit-existing-tree
capability intact.

---

## 10. ✅ `--profile persistence` was a no-op for the server — RESOLVED 2026-07-12 (env-file)

**Was:** `docker compose --profile persistence --profile frontend up -d` started
the postgres and irmin containers but `register-server` connected to neither —
`REGISTER_REPOSITORY_TYPE` and `REGISTER_WORKSPACE_STORE_BACKEND` defaulted to
`in-memory`, and `REGISTER_WORKSPACE_TTL` / `REGISTER_WORKSPACE_IDLE_TIMEOUT`
were absent from the compose env block. A Compose profile only decides which
containers *start* — it cannot change another service's environment — so the
profile flag could never switch the server's backends.

**Resolution:** completed the existing `--env-file .env.irmin` mechanism rather
than adding a second one. An earlier note proposed a `docker-compose.persistence.yml`
override file; that was dropped because `.env.irmin` (already documented in
`PERSISTENT-SETUP.md`) achieves the same result with no new invocation pattern,
and the override file's only unique capability — compose-level `depends_on`
ordering — is already covered by the app's Irmin readiness gate (ADR-031) and,
for Postgres, by Flyway retrying at boot.

Changes made:
- `docker-compose.yml` — added `REGISTER_WORKSPACE_TTL` and
  `REGISTER_WORKSPACE_IDLE_TIMEOUT` to the `register-server` `environment:` block
  with `${VAR:-72h}` / `${VAR:-1h}` defaults (matching `application.conf`, so the
  base in-memory stack is unchanged). Without this an `--env-file` cannot reach
  them: `--env-file` only feeds `${VAR}` interpolation, which requires the var to
  appear in the `environment:` block. `REGISTER_REPOSITORY_TYPE`,
  `REGISTER_WORKSPACE_STORE_BACKEND` and `REGISTER_DB_*` were already plumbed.
- `.env.irmin.example` — extended from 2 vars to the full persistent tier:
  `REGISTER_REPOSITORY_TYPE=irmin` + `IRMIN_URL`,
  `REGISTER_WORKSPACE_STORE_BACKEND=postgres`, and `REGISTER_WORKSPACE_TTL` /
  `REGISTER_WORKSPACE_IDLE_TIMEOUT=120h`. Postgres credentials/Flyway use the
  `REGISTER_DB_*` compose defaults, which already point at the `postgres` service.
- Docs reconciled: `PERSISTENT-SETUP.md`, `DOCKER-DEVELOPMENT.md` (use case C),
  `DEVELOPMENT-SETUP.md` — all now use `cp .env.irmin.example .env.irmin` +
  `--env-file .env.irmin --profile persistence`, and explain why the profile
  alone is insufficient. Removed the stale manual `export
  REGISTER_WORKSPACE_STORE_BACKEND=postgres` step.

One-stop command for a fully persistent stack:

```bash
docker compose --profile persistence --profile frontend --env-file .env.irmin up -d
```

**Verified:** `docker compose --env-file .env.irmin.example config` resolves all
four vars into the container (`irmin` / `postgres` / `120h` / `120h`); the base
`docker compose config` still resolves `in-memory` / `in-memory` / `72h` / `1h`.
Not run: a live restart-persistence smoke test (would require bringing the
running stack down); the app-side `WorkspaceStorePostgres` is separately covered
by its own tests.

**Note (Kubernetes):** this fix is docker-compose only. The K3D/ArgoCD deployment
has the same workspace-store gap — the Helm chart wires Irmin but leaves the
workspace store in-memory. Tracked in `register-infra/docs/TODO.md` ("Deferred —
Blocked on App-Side Changes" → register-db / workspace-store-postgres wiring).

## 11. `WorkspaceReaperSpec` — cascade-deletion across multiple expired workspaces flakes under `TestClock`

**Observed:** The test
`"reaper cascade-deletes trees across multiple expired workspaces"` in
`modules/server/src/test/scala/.../WorkspaceReaperSpec.scala` fails
intermittently. The failure pre-dates the
`PLAN-QUERY-NODE-NAME-LITERALS.md` work — it surfaced again during the
`server/test` run that followed the F3 implementation but is unrelated
to any code touched by that plan (only `RiskTreeKnowledgeBase`,
`QueryServiceLive` and one new spec were modified).

**Current understanding:**
- The reaper is a ZIO scheduled fiber that fires on a `Schedule` driven
  by the live (or test) `Clock`.
- The spec uses `TestClock.adjust` to advance time past the workspace TTL
  and then asserts that all child trees of every expired workspace have
  been removed.
- Symptom is a count mismatch (some trees still present) rather than an
  exception, which is the classic shape of a `TestClock` race: the
  reaper fiber has not yet observed the clock advance and run the
  cascade before the assertion is taken.

**Reproduction / trigger:**
- Run `sbt 'server/testOnly *WorkspaceReaperSpec'` repeatedly (or as
  part of a full `server/test`); the failure is non-deterministic but
  reliably appears within a small number of runs.
- Likely triggers: ordering of fiber scheduling versus
  `TestClock.adjust`, missing `TestClock.adjust(...).fork` /
  `awaitAllDescendants`, or asserting before yielding to the reaper
  fiber.

**Suspected fixes to investigate (in order):**
1. After every `TestClock.adjust`, insert
   `TestClock.adjust(0.seconds)` or an explicit
   `ZIO.yieldNow` / `awaitAllDescendants` to let the reaper fiber run.
2. Switch the reaper handle the spec uses to one whose schedule is
   driven via an injected `Clock` and use
   `Live.live(...) *> TestClock.adjust(...)` to make the relationship
   between the test clock and the fiber explicit.
3. If neither helps, confirm the reaper itself reads the current time
   from the same `Clock` service the test is adjusting (not a captured
   `Instant.now()`).

**Status:** flagged, untouched. Not blocking any current plan.

---

## 12. Simulation outcomes are not reproducible across re-creations of the same tree

**Observed (2026-05-03).**
`DemoEnterpriseScriptSpec` produces different P95/P99 figures — and
different `satisfied` verdicts on boundary queries — depending on
whether `DemoSimpleScriptSpec` runs first in the same JVM process.
The effect is fully deterministic (same execution order → identical
values), but cannot be reproduced without knowing which ULID block the
tree's nodes will land in.

Affected queries in the demo suite (tolerance-boundary cases): Q1
(`AtLeast(1/4,0.1)` proportion swings 3/21 ↔ 4/21), Q7b
(`About(2/3,0.1)` proportion swings 6/11 ↔ 7/11), Q-D
(`AtMost(1/3,0.1)` proportion swings 9/21 ↔ 11/21). All three straddle
their quantifier's acceptance threshold when the seed shifts.

**Root cause:**
`Simulator.createSamplerFromLeaf`
([modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala](modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala))
derives the HDR entity seed from the leaf's ULID:

```scala
entitySeed = leaf.id.value.hashCode.toLong
```

ULIDs are monotonically time-ordered. When two trees are created
milliseconds apart in the same test run the second tree's nodes receive
ULIDs in a numerically higher time bucket, so `hashCode()` differs from
the first run → different `entitySeed` → different HDR sample stream →
different Monte Carlo outcomes for the same declared parameters
(`probability`, `distributionType`, `minLoss/maxLoss`, `percentiles/quantiles`).

The same instability occurs for any two real-world users who create
"the same" risk tree at different wall-clock times, or any single user
who deletes and recreates their tree. `defaultSeed3=0, defaultSeed4=0`
in `SimulationConfig` were intended to anchor reproducibility, but they
are overridden by the per-leaf `entitySeed` derived from the ULID
timestamp.

There is already a `// TODO: review seed generation (hashcode) to ensure
good distribution and avoid collisions` comment at that site, so the
provisionality was known.

**Consequence for end users:**
- Two runs with identical leaf parameters produce different P50/P90/P95/P99
  figures and potentially different query verdicts.
- Quoting P95 figures from a saved report and comparing them against a
  freshly-simulated tree is meaningless without also recording the
  `entitySeed` (i.e. the ULID).
- The `NodeProvenance.entityId` field in `RiskResult` does record the
  seed that was used, which means reproduction is possible _if_ the
  provenance is preserved — but this requires callers to explicitly
  replay from provenance rather than re-simulating from parameters.

**Direction (not yet decided):**
The correct fix is to derive `entitySeed` from stable content rather
than from the ULID allocation timestamp. Natural candidates:

- **Leaf name hash** — `leaf.name.value.hashCode.toLong`. Two leaves
  with the same name always get the same seed; rename = seed change
  (arguably correct: renamed leaf is a conceptually different entity).
- **Distribution-parameter hash** — hash of
  `(name, distributionType, probability, minLoss, maxLoss, percentiles, quantiles)`.
  Two leaves with identical spec always get identical outcomes.
- **Explicit caller-supplied seed** — add an optional `seed` field to
  `RiskLeafDefinitionRequest`; server uses it if present, falls back to
  a content hash otherwise. This is the most flexible option and
  cleanly separates "I want reproducibility" from "I want a fresh run".

Whichever approach is chosen, it must be reflected in `NodeProvenance`
(the `entityId` field already captures the seed used; the _derivation
rule_ should be documented there too) and the `README.md` "Counter-based
PRNG" bullet should be updated to state exactly what the seed is derived
from.

**Status:** root cause confirmed, fix direction not yet decided. Blocks
reliable `DemoEnterpriseScriptSpec` assertions in combined test runs.

---

## 13. 

---

## 14. DELETED ITEM, placeholder kept for consistent numbering only 

---

## 15. Authorization domain: compiler-enforced `check()` proof tokens

**Context:** The current `AuthorizationService` is structurally correct (fail-closed
`IO[AuthError, Unit]`, no `grant()`/`revoke()`, ADR-024 compliant). However, the
primary authorization failure mode — forgetting to call `check()` before a handler
executes — is invisible to the type system. A controller can call the business logic
directly without calling `authorizationService.check()`, and the compiler will not
object.

**Analysis:** The same "validate once at the boundary, carry the proof" principle that
Iron applies to input types (ADR-001) can be applied to authorization. The pattern:

```scala
// Opaque — only AuthorizationService can produce this; no external constructor
opaque type Checked[+P <: Permission] = Unit

trait AuthorizationService:
  def check[P <: Permission](
    user:       UserId,
    permission: P,
    resource:   ResourceRef
  ): IO[AuthError, Checked[P]]   // returns the proof, not Unit

// Handler MUST receive the proof; cannot be called without it
def createTree(req: CreateTreeRequest, auth: Checked[Permission.DesignWrite.type]): IO[AppError, Response]
```

`AuthorizationServiceNoOp` would still produce `Checked[P]` (all modes still compile);
the difference is that skipping `check()` is now a compile error rather than a runtime
gap. Every call site where auth was accidentally omitted becomes visible immediately.

**Two compiler invariants that would be added:**

1. **`Checked[P]` proof token** — proves `check()` was called and succeeded with the
   specific `Permission` before the handler body runs. Analogous to Iron's
   `String :| SafeNameConstraint` — the type carries the proof, cannot be forged.

2. **`Authorized[R <: ResourceType]` phantom on fetched resources** — proves the
   resource was fetched *after* authorization, not before. Guards against the
   TOCTOU pattern where a resource is loaded first and then the auth check happens
   against stale data.

**Relationship to the authorization plan:**
This is an implementation refinement to Wave 1 of AUTHORIZATION-PLAN.md's rollout,
not a new architectural concern. The `AuthorizationService` trait signature changes
from `IO[AuthError, Unit]` to `IO[AuthError, Checked[P]]`; all call sites gain a
`Checked[P]` parameter. `AuthorizationServiceNoOp` and `AuthorizationServiceStub`
return `ZIO.succeed(())` cast to `Checked[P]` — intentional bypass, explicitly
documented in type, not accidental omission.

**Why not implement immediately:** The rollout waves in the authorization plan have
not yet landed (Wave 0 infrastructure is the current baseline). The `Checked[P]`
pattern is cleanest to introduce alongside Wave 1 when all `serverLogic` signatures
are being updated anyway. Retrofitting after all waves are wired would require
touching every controller twice.

**Action:** Revisit at Wave 1 implementation time. Evaluate whether introducing
`Checked[P]` from the start is worth the signature surface change, or whether the
`RouteSecurityRegressionSpec` invariants (Wave 6, Invariant 3: "authz.check() called
exactly once per protected request") provide sufficient runtime coverage.

**Status:** design identified, not yet prioritised. Depends on authorization plan Wave 0+.

---

## 16. Authorization plan — compiler and infrastructure security recommendations

**Context:** Analysis of `AUTHORIZATION-PLAN.md` against the "types as proofs" principle
(Iron, ADR-001, ADR-029) and infrastructure hardening identified eight areas where the
current design may benefit from tighter enforcement. These are candidate improvements
worth investigating before or during rollout — not pre-approved tasks.

---

### 16a. `seed()` on `AuthorizationService` may violate ADR-024 at the interface level

Wave 6 proposes adding `seed(userId, workspaceId)` to `AuthorizationService` to write
the bootstrap ownership tuple to SpiceDB. The plan describes this as "the only justified
exception" to the PEP-only constraint. However, placing a write operation on the shared
service trait means all implementations must carry it, and nothing in the type system
prevents it from being called outside the bootstrap handler.

**Recommendation to investigate:** separate `seed()` onto a distinct `BootstrapProvisioner`
service whose ZIO environment type is available only to the bootstrap handler. If the
compiler can express "only this one handler has this service in scope," calling `seed()`
from any other site becomes a compile error rather than a convention. `AuthorizationService`
would remain a pure read interface, fully honouring ADR-024 at the type level. Worth
validating whether ZIO's environment model makes this straightforward or impractical.

---

### 16b. Anonymous sentinel `UserId` may bypass the type-level guarantee

**Status: Design decision taken — Wave 0B pending implementation (AUTHORIZATION-IMPLEMENTATION-PLAN.md §B)**

`UserContextExtractor.noOp` returns the sentinel `UserId("00000000-...")`. If the mode
selection logic in `AppLayer` has a bug and NoOp is wired in a production environment,
this value could reach a SpiceDB `check()` call. SpiceDB would deny it (no relationships),
but it would appear in audit logs as a real-looking UUID, and no compiler error would
surface the mistake.

**Wave 0B (pending):** `UserId` becomes a sum type with `Anonymous` and `Authenticated(raw: UuidStr)`
variants. `AuthorizationService.check()` accepts only `Authenticated`. Passing the sentinel
to `check()` is then a compile error. The `UserContextExtractor.scala` code comment has been
updated with the two-layer mitigation model. Full migration scope listed in
`AUTHORIZATION-IMPLEMENTATION-PLAN.md §B` — 14 files across `common`, auth, HTTP controllers,
Tapir codec, and tests.

**Wave 3 (still required after Wave 0B):** The SpiceDB CEL caveat / schema-level subject
constraint that rejects `user:00000000-...` remains necessary as an independent infrastructure
layer — it guards against bypasses that skip the application layer (direct SpiceDB API calls,
misconfigured service accounts, operator error). Must be in place before `fine-grained` mode
is activated in any non-development namespace.

---

### 16c. `AuthConfig.mode` string matching fails open on misconfiguration

The AppLayer ZLayer selection uses:
```scala
case AuthConfig("fine-grained", spicedb) => ...
case AuthConfig("identity", _)           => ...
case _ =>                                    // falls through to capability-only
```

A typo (e.g. `"fine_grained"` with underscore) silently activates `capability-only`
mode — the least restrictive option. This is a misconfiguration that fails open rather
than failing hard.

**Recommendation to investigate:** make `auth.mode` a validated sealed enum or an
Iron-constrained string (`Match["^(capability-only|identity|fine-grained)$"]`) whose
parsing fails the service at startup on an unknown value. The `case _ =>` fallback
should arguably be an error, not a default. Worth checking how the ZIO Config library
handles enum-typed config values to see if this is straightforward.

---

### 16d. `timeoutSeconds: Int` in `SpiceDbConfig` is unvalidated

A raw `Int` allows 0 or negative values, which would produce undefined HTTP client
behaviour. The Iron constraint precedent (`PositiveInt`, `NonNegativeInt`) already
exists in the codebase.

**Recommendation to investigate:** change `timeoutSeconds: Int` to `PositiveInt`. This
is a small, low-risk change that follows established patterns. The only question is
whether the ZIO Config integration handles refined types cleanly for this field.

---

### 16e. ~~`SafeUrl` for SpiceDB endpoint permits `http://`~~ — **Resolved 2026-07-04**

Resolved by introducing `MeshServiceUrl` opaque type in `OpaqueTypes.scala` and using it
for `SpiceDbConfig.url`. Transport security is the mesh's responsibility (Istio mTLS);
application-layer TLS would be redundant and would complicate cert rotation.
See `SpiceDbConfig.scala` and `OpaqueTypes.scala` `MeshServiceUrl` block.

---

### 16f. `Checked[P]` proof token — see TODO §15

Already captured in §15. Restated here for completeness as a companion to the auth
plan rollout.

---

### 16g. Header spoofing test is absent from Phase K.5 exit criteria

The entire Layer 1/2 identity model depends on the Istio waypoint stripping external
`x-user-*` headers before injecting claim headers derived from the validated JWT. If a
request bypassing the waypoint carries a spoofed `x-user-id` header, it presents as an
arbitrary authenticated identity with no JWT required.

The plan references ADR-012 for this requirement but does not include a test that
verifies the behaviour in a deployed cluster.

**Recommendation to investigate:** add a mandatory smoke test to the K.5 exit criteria
that sends a request carrying a forged `x-user-id` header directly to the backend and
verifies it is rejected (403) or that the header is overwritten by mesh injection. The
test must be run against a real Istio deployment — an in-process test cannot exercise
this. Consider whether this test should be part of the K.6 CI pipeline as a post-deploy
check on every deploy.

---

### 16h. SpiceDB provisioning job drift detection scope may be incomplete

The plan says the K.6 CI job should "fail pipeline on drift/validation errors." As written,
this likely catches write errors but may not catch relationships that exist in SpiceDB
but are absent from the config source (orphaned tuples from manual `zed` CLI operations
or earlier CI runs).

**Recommendation to investigate:** scope "drift detection" explicitly to mean a full
reconcile: the CI job should compare the *intended state* (config file) against the
*live SpiceDB relationship graph* and fail if they diverge in either direction —
missing tuples OR extra tuples not in config. Extra tuples represent privilege creep.
Worth reviewing whether the SpiceDB API surface (LookupResources, ReadRelationships)
makes this full-reconcile pattern feasible at reasonable cost.

---

**Status:** analysis only, no decisions made. All eight items are candidates for
investigation; none are committed work. Items 16a–16e concern the Scala implementation
and should be revisited at or before Wave 0. Items 16g–16h concern the K3s rollout
and should be reviewed at Phase K.5 / K.6 planning.

---

## 17. Stale simulation served after tree update (PUT) without explicit invalidation

**Observed (2026-07-11, `APP_VERSION` 0.3.0):** full docker-compose stack
(`localhost:18080`, default in-memory repository). After
`PUT /w/{key}/risk-trees/{treeId}` (full-replacement update reusing existing
node ids) that changes a leaf's `probability` and `distributionShape`, an
immediate `POST …/nodes/lec-multi` returns curves that do not reflect the
update.

**Evidence from the session:**
- Baseline tree (4 leaves, Cyber Breach p=0.20, CI 500k–8M) vs "mitigated"
  variant (Cyber Breach p=0.08, CI 300k–3M, all else identical): the
  *mitigated* fetch returned p90 = 1 745 730 vs baseline p90 = 1 479 526
  (mitigated strictly worse — impossible), and **byte-identical
  p99 = 8 389 797.0 in both responses**, suggesting reuse of a previous
  simulation.
- In the same run, the first lec-multi after morphing a 1-leaf seed tree into
  the 4-leaf baseline matched a hybrid state: the leaf's **new** distribution
  shape but its **old** occurrence probability (root exceedance ≈ 0.37 ≈
  1−(0.9·0.9·0.85·0.92), i.e. Cyber Breach still at the previous leaf's
  p=0.10 instead of 0.20).

**Workaround (confirmed):** `POST /w/{key}/risk-trees/{treeId}/invalidate/{rootId}`
after the PUT — subsequent lec-multi results are correct (root exceedance
0.4433 ≈ analytic 1−∏(1−pᵢ) = 0.437).

**ROOT CAUSE (confirmed by live repro, 2026-07-12):**
`InvalidationHandlerLive.computeAffectedNodes` handles "reparented" and
"parameter-changed" as mutually exclusive branches (`if oldParent != newParent
then … else if nodeData changed then Set(nid)`). A node that is **both
reparented and content-changed in the same PUT** takes the reparent branch:
its old and new parents' ancestor paths are invalidated, but the node's own
cache entry is **not**. Because `RiskResultResolverLive.simulateNode` composes
portfolio results from cached child entries (`cache.get(childId)`), the stale
leaf result is folded into every "fresh" ancestor re-simulation — and **no
amount of ancestor/root invalidation can fix it**; only invalidating the leaf
itself flushes it.

Live verification (fresh stack, in-memory repo, 4-leaf tree under root "Ops"):

| Mutation via PUT | Root exceedance observed | Analytic | Verdict |
|---|---|---|---|
| leaf param change only (p 0.20→0.08, CI shrunk) | 0.3575 | 0.352 | correct — diff fired, log shows leaf in invalidation set |
| leaf reparented under new portfolio **and** p 0.08→0.30, CI grown | 0.3575 (byte-identical quantiles to previous state) | 0.507 | **stale** — log shows only `[root]` + `[root → IT Systems]` invalidated, leaf absent |
| … then explicit `invalidate/{rootId}` | 0.3575 | 0.507 | **still stale** (root recomposed from stale cached leaf) |
| … then explicit `invalidate/{leafId}` | 0.5118 | 0.507 | correct |

So the TODO's original title understates it: the documented root-invalidate
workaround only *appeared* to work in the original session because subsequent
non-reparenting PUTs re-triggered leaf invalidation. The reliable workaround
is invalidating the **changed leaf**, not the root. Also verified NOT broken:
structural equality on `RiskLeaf` (param-only changes are detected),
`TreeIndex.ancestorPath` (includes the node itself, runs to the root,
root-first order in logs).

**Test gap:** `MutationInvalidationSpec` covers "parameter change" and "move
node (reparent)" as separate suites but has no combined
reparent-plus-param-change case — exactly the hole.

**Fix candidates (decision needed):**
1. Make the checks additive in `computeAffectedNodes`: compute reparent
   contribution and content-change contribution independently and union them.
   Note a reparented node's `parentId` is part of its node data, so checking
   content-change first (or always) naturally includes the node itself;
   invalidating a *purely* reparented leaf is semantically unnecessary (its
   result doesn't depend on the parent) but harmless — decide whether to keep
   that optimisation.
2. Longer-term (milestone-2b Phase A, designed but unimplemented): move to the
   content-addressed cache per `docs/scratch/milestone-2b-cache-and-decisions.md`,
   which makes this whole bug class structurally impossible — see that doc's
   Review Addendum (2026-07-12) for the audited status, a recommended
   leaf-only-caching lean-down, and why it is the required substrate for
   scenario branching. `ARCHITECTURE.md` correctly documents the current
   NodeId keying; top-level claims of a "Merkle-tree cache" (CLAUDE.md/README)
   describe the *planned* state.

**Outcome wanted:** failing test in `MutationInvalidationSpec`
(reparent + param change in one mutation → node itself must be invalidated),
then the fix, then re-verify with the API repro above.

---

## 18. Update `examples/demo-*.sh` to the current bootstrap wire format

**Observed (2026-07-11, `APP_VERSION` 0.3.0):** all four scripts
(`demo-simple-curl.sh`, `demo-simple-httpie.sh`, `demo-enterprise-curl.sh`,
`demo-enterprise-httpie.sh`) still send the old flat leaf format and fail with
400 `missing at 'leaves[0].distributionShape'`.

**Current format** (see `RiskTreeDefinitionRequest.scala`): leaf fields
`distributionType`, `minLoss`, `maxLoss`, `percentiles`, `quantiles` moved
into a nested `distributionShape` object (which also gained `terms`):

```json
{"name": "...", "parentName": "...", "probability": 0.2,
 "distributionShape": {"distributionType": "lognormal",
   "minLoss": 500000, "maxLoss": 8000000,
   "percentiles": null, "quantiles": null, "terms": null}}
```

**Scope note:** also grep `tests/` (BATS) for the old flat payload shape
before assuming only `examples/` is affected. Constraints relevant to the
rewrite: node names allow only letters/digits/spaces/hyphens/slashes;
`POST /workspaces` is rate-limited (`REGISTER_WORKSPACE_MAX_CREATES_PER_IP`,
default 5/h, counted per attempt including 400s); beware item 17 if the
scripts PUT then immediately fetch LECs.

---

## 19. Verify persistence end-to-end with a live restart test (follow-up to item 10)

Item 10 (`--profile persistence` no-op) was fixed on 2026-07-12 by completing
the `.env.irmin` env-file path. The fix was verified **statically only** —
`docker compose --env-file .env.irmin.example config` resolves all four vars
into the container (`irmin` / `postgres` / `120h` / `120h`) and the base
config still resolves the in-memory defaults. A **live restart-persistence
test was not run** (it would have required bringing the running dev stack
down), and `WorkspaceStorePostgres` correctness is only covered by its own
unit/integration tests, not by an end-to-end compose check.

**What to verify (needs a disposable/fresh stack, not the working dev one):**

1. Bring up the full persistent stack:
   ```bash
   cp .env.irmin.example .env.irmin
   docker compose --profile persistence --profile frontend --env-file .env.irmin up -d
   ```
2. Confirm the server actually selected the persistent backends — check startup
   logs for the Irmin repository + Postgres workspace store being wired and
   Flyway running its migration (not the in-memory fallbacks).
3. Create a workspace + a risk tree via the API (or the SPA at
   `http://localhost:18080`); note the workspace key / capability URL.
4. Restart **only the server**: `docker compose restart register-server`
   (leave postgres + irmin running). Confirm the workspace and its tree still
   resolve after restart — this is the exact failure item 10 was about
   (in-memory workspace store loses everything on restart even when trees
   persist to Irmin).
5. Optionally also confirm risk-tree data survives a full
   `docker compose --profile persistence --profile frontend down` (without
   `-v`) followed by `up` — Irmin/Postgres volumes should retain state.
6. Tear down with `down` (add `-v` only to discard the persistent volumes).

**Consider promoting to a test:** if this passes, a BATS case under `tests/`
that boots the persistent profile, restarts the server, and asserts workspace
survival would guard against a regression in the compose wiring or the
`WorkspaceStorePostgres` boot path. See also the K8s twin in
`register-infra/docs/TODO.md` ("Deferred — Blocked on App-Side Changes"),
which has an analogous pod-restart survival check as its step 5.

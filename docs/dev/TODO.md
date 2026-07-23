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

## ✅ 9. Design view tree dropdown does not load existing tree structure — RESOLVED (verified 2026-07-23)

**Resolution:** fixed as part of the tree-builder rework (edit-in-place /
FormMode sessions, 2026-07-2x). `DesignView.scala` wires the dropdown
selection to `TreeBuilderState.loadFromTree(tree)`, which populates the
editable working copy (name, portfolios, leaves) and takes the dirty-tracking
snapshot — Design and Analyze dropdowns now both load existing structure, and
Design keeps edit-existing-tree capability. Original report kept below.

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

## 12. Simulation outcomes are not reproducible across re-creations of the same tree — DONE (2026-07-16)

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

**DECIDED (2026-07-15, final after two same-day revisions):
boundary-assigned seed identities, stored on the node.**

Every leaf carries a **`seedVarId`** (server-assigned at the creation
boundary in sorted-name order, or optionally caller-provided; immutable
once assigned; unique **per tree**; never auto-reused after deletion via
a persisted high-water mark). Every workspace carries a
**`seedEntityId`** (global counter, or optionally provided; workspace =
organizational boundary = the HDR paper's Entity axis). Streams:
`occurrence = 2·seedVarId`, `loss = 2·seedVarId+1`, entity = the
workspace's, derived in exactly one place and shared with provenance.
Portfolios carry **no** seed ID (no stochastic behaviour — retracted).
**No hashing anywhere.** App identity (ULID) and stochastic identity are
deliberately separate types with separate lifecycles.

Implementation plan (full rationale, HDR paper findings, verified
arithmetic, decision log): [PLAN-SEED-IDENTITY.md](./PLAN-SEED-IDENTITY.md).
**Implemented in full — see the completion record below.**

**Decision history (do not re-litigate):**
1. *Name-only + 64-bit truncated SHA-256* (locked earlier 2026-07-15) —
   killed by the HDR paper's magnitude budget: IDs are 8 **decimal**
   digits (< 10⁸); 64-bit values are ~3.5×10¹⁰ over the dividend limit
   and silently wrap `Long`, voiding the Dieharder validation.
2. *8-digit name hash + boundary collision rejection* — killed by
   unrecoverable UX ("rename until it stops colliding" is not
   validation) and birthday risk (2% @ 1,000 leaves).
3. *Assigned IDs* (this decision) — the paper's own "chart of accounts"
   model; collision-free by construction; content-determined because the
   ID is stored **in** the node content.

**Findings that stand regardless (details in the plan):**
- **The current code violates HDR's magnitude budget today** —
  `String.hashCode` values are 21× the 10⁸ ID budget; lane dividends 8×
  over the 10¹⁵ limit; HDR's statistical guarantees do not currently
  apply. Fixed as a side effect.
- **Provenance var-IDs are wrong for ~half of all leaves**
  (`Simulator.scala:209-210` records `entitySeed.hashCode + 1000/2000`;
  [RiskSampler.scala:93](../../modules/server/src/main/scala/com/risquanter/register/simulation/RiskSampler.scala#L93)
  uses `riskHash + 1000/2000`; `Long.hashCode ≠` the value outside Int
  range). Provenance-based replay — this item's stated escape hatch —
  is broken today. No test covers it; the plan adds one. Fixed by
  construction (single derivation site).
- The seed derivation was duplicated at two sites
  (`Simulator.scala:194`, `RiskSampler.scala:93`); fixing only the one
  this item originally named would have left results flaky.

**Semantics locked with the decision:**
- Name uniqueness (`RiskTreeRequests.requireUniqueNames`, both write
  paths) remains enforced but now serves **reference semantics only** —
  the name influences no figure. Renames preserve figures *and* cache.
- Reproducibility scope **narrowed by user decision**: within-workspace
  recreation is reproducible; same spec in two workspaces differs **by
  design** (org isolation); cross-workspace/deployment reproduction goes
  via explicit seed-ID provision (export/import round-trip).
- `seedVarId` uniqueness is per-tree, **not** workspace-wide: scenario
  branches must share seed IDs so unedited nodes produce identical draws
  (common random numbers across scenarios — milestone 2b's premise).
- Content addressing (DD-15): seed IDs **in** the node content hash;
  name and ULID **out**; `seedEntityId` as cache scope.
- **API change acknowledged (trigger #1):** optional `seedVarId` /
  `seedEntityId` on requests; both included in responses and exports.

**Status: DONE (2026-07-16).** Plan locked 2026-07-15 (approved in full,
no open decisions); all twelve §10 work-breakdown steps implemented
2026-07-15, plan-§11 test pyramid completed 2026-07-16. Migration
resolved as moot (no data survives; wipe and recreate demo data).
Version bump: MINOR — `0.4.0` (user-decided, executed).

**Completion record (2026-07-16):**
- Domain types, single derivation site (`SeedDerivation.streams`),
  boundary assignment (`SeedVarIdAssigner`), per-tree uniqueness,
  high-water mark, workspace `seedEntityId` (query param on bootstrap),
  optional provided IDs on create / new-leaf update, §5.3 immutability
  rejection with actionable message — all live.
- Test plan §11 delivered at every layer: Layer 0–3 (Iron ranges, exact
  derivation + HDR magnitude budget in `SeedDerivationSpec`, assignment
  properties in `SeedVarIdAssignerSpec`, boundary/API rejections in the
  request specs), **Layer 4 `SeedStabilitySpec`** (recreate → byte-identical
  figures; CRN edit locality; rename changes nothing; entity isolation;
  export → import round trip), **Layer 5 `SeedStatisticalSanitySpec`**
  (Pearson adjacency on dense consecutive IDs, occurrence frequency,
  KS uniformity smoke — deterministic constants), and system level
  **`SeedReproducibilityItSpec`** (Irmin persist → fresh-stack reload →
  identical figures; export → import across servers with pinned entity;
  demo-simple/demo-enterprise **order-independence** — the original
  symptom, asserted directly).
- Demo suite re-baselined with explicit margin assertions; the Q-D
  knife-edge was resolved by moving the quantifier to `AtMost(1/4,0.1)`
  (spec + both demo scripts). BATS suites C and A green; demo data
  recreated; cross-environment reproduction verified live (native
  binary figures == JVM spec figures).

---

## 13. DELETED ITEM, placeholder kept for consistent numbering only

(The content — "[POST USER-DOCS] Document threshold-pie normalisation behaviour
in user documentation" — was removed in commit `413e042` together with item 14
but did not receive this marker at the time. Recoverable via
`git show 8d94ce8:docs/dev/TODO.md` if the doc task should be revived.)

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

## 17. Stale simulation served after tree update (PUT) without explicit invalidation — RESOLVED by Phase A (2026-07-18)

**RESOLVED 2026-07-18 — milestone-2b Phase A shipped.** The content-addressed
`ContentCache` retired the bug class as designed: leaf cache keys are
recomputed from stored content on every read, so no diff decides invalidation.
All three package-b deliverables landed: `Item17RegressionSpec` (service-level
combined reparent+param-change PUT → root exceedance pinned to the analytic
`1−∏(1−pᵢ)` for the new params, 10σ away from the stale signature),
the SSE-only `InvalidationHandler` rewrite with the ADDITIVE union in
`computeAffectedNodes` (+ dedicated additive-union test), and
`MutationInvalidationSpec` retired with `TreeCacheManager` (DD-20 executed in
the same change: `invalidateWorkspaceCache` endpoint + `InvalidationResponse`
DTO deleted). `CacheTransparencySpec` additionally pins cache-transparency:
real cache vs pass-through cache, byte-identical figures over an edit sequence
including this item's exact mutation shape. Original record below.

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

**Outcome wanted (superseded 2026-07-18 — see decision below):** failing test in
`MutationInvalidationSpec` (reparent + param change in one mutation → node
itself must be invalidated), then the fix, then re-verify with the API repro
above.

**DECIDED 2026-07-18 (user) — tactical fix will NOT be implemented ("package
b").** Milestone-2b Phase A retires the bug class structurally: on every read
the leaf cache key is recomputed from the leaf's stored content
(`sha256(LeafSimContent)`), so a param change *is* a different key — no
hand-written diff decides invalidation, hence nothing to get wrong.
Consequences, now explicit Phase A deliverables in
`docs/scratch/milestone-2b-cache-and-decisions.md` (Phase Outline):

1. **End-to-end regression test** replicating this item's live repro at service
   level (create tree → LEC → one update combining reparent + param change →
   LEC again → root exceedance matches the analytic `1−∏(1−pᵢ)` for the *new*
   params; the repro's stale-vs-correct gap is ≈0.3575 vs ≈0.507 — wide and
   deterministic under fixed seeds). It is the acceptance probe for Phase A's
   central claim and is cache-implementation-agnostic. It cannot land earlier:
   it fails against the current design, and a failing test is a merge blocker.
2. **`InvalidationHandler` SSE half**: when Phase A rewrites the handler
   (SSE-only), `computeAffectedNodes` must union the reparent and
   content-change contributions additively — the current exclusive `if/else if`
   is this bug and would otherwise survive into the SSE node list (currently
   harmless: `CacheInvalidated` has zero subscribers, verified 2026-07-18).
3. `MutationInvalidationSpec` asserts cache-entry survival inside
   `TreeCacheManager` and dies with it; the e2e test above replaces its role.

**Until Phase A ships this bug stays live.** Interim workaround (reliable,
verified): `POST …/invalidate/{leafId}` on the *changed leaf* after a combined
reparent+param PUT. The endpoint itself retires in Phase A — DD-20, closed
2026-07-18 → (a) in the milestone-2b doc — deleted in the same change that
retires `TreeCacheManager`.

---

## 18. Update `examples/demo-*.sh` to the current bootstrap wire format — DONE (2026-07-14)

**Resolved 2026-07-14:** all four scripts rewritten to nest `distributionShape`
(with `terms`); `demo-simple-curl.sh` and `demo-enterprise-curl.sh` verified
end-to-end against the live server (bootstrap + all queries green). Also
updated the four `docs/test/*.json` fixtures and `docs/user/API-TUTORIAL.md`
(the tutorial's enterprise payloads additionally had `&`/`()` names that
violate the `SafeName` pattern — normalised to the demo-script spellings, e.g.
`Technology and Cyber`, `Data Breach - PII`, `M and A Integration Failure`).
No BATS/`tests/` payloads were affected — the Scala test suites and frontend
already use the nested shape. ADRs describe the *domain* model (`RiskLeaf`
still stores flat fields) so they remain accurate and were left unchanged.
Not touched: `docs/test/TESTING.md` uses the entire pre-workspace `/api/risk-trees`
surface (broader rot — needs a separate reconciliation pass, see below).

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

## 19. Verify persistence end-to-end with a live restart test (follow-up to item 10) — DONE (2026-07-19)

**Resolved 2026-07-19 — the live test found and fixed a real boot defect, then
passed end-to-end.** First live boot of the native image with
`REGISTER_WORKSPACE_STORE_BACKEND=postgres` crash-looped: Flyway's
`ClassicConfiguration` reflectively copies every registered
`ConfigurationExtension` through a Jackson round trip
(`ConfigurationExtension.copy`), and the GraalVM reflection metadata for those
classes was missing — `MissingReflectionRegistrationError` on
`CleanModeConfigurationExtension.getNamespace()`. Invisible until now because
`WorkspaceStorePostgres`/Flyway only ever ran on the JVM; exactly the gap this
item predicted. Fix (in `modules/server/src/main/resources/META-INF/native-image/`):
`reflect-config.json` regenerated from the official GraalVM
reachability-metadata for flyway-core 11.x (converted to the old per-file
format with explicit `queryAll*` flags — the new combined format implies
query access that the old format must spell out; Jackson needs it to resolve
`@JsonIgnore` on `ConfigurationExtension.getNamespace()`, which lives on the
*interface* method), plus hand-written entries for
`PostgreSQLConfigurationExtension`/`TransactionalModel` (flyway-database-postgresql
has no official metadata) and `org.postgresql.Driver`; `resource-config.json`
gained `db/migration/*.sql` (migrations were not baked into the image at all)
and Flyway's service/version resources. The fix was validated in isolation
with a 12-second probe build inside `local/graalvm-builder:21` (same GraalVM,
`--static --libc=musl`) before the full server rebuild — use that loop for any
future native-reflection issue instead of 5-minute full rebuilds.

Live test then passed in full (disposable workspace, volumes untouched):
persistent stack up via `--env-file .env --env-file .env.irmin` (both files —
`.env.irmin` alone drops `APP_VERSION` and mis-tags the image `dev`); startup
logs confirmed Irmin repository + PostgreSQL workspace store + "Flyway
migrations complete"; workspace + tree created via API; `docker compose
restart register-server` → workspace and tree still resolve (the exact item-10
failure); full `down` (no `-v`) + `up` → still resolve. Test workspace deleted
(204) and probe DB dropped afterwards; stack restored to the in-memory dev
default. The BATS promotion idea below stays open — suite A does not cover the
postgres workspace store; a restart-survival case would have caught the
Flyway-metadata defect in CI.

<details><summary>Original item text (for the promoted-test follow-up)</summary>

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

</details>

---

## 20. `docs/test/TESTING.md` targeted the retired `/api/risk-trees` surface — DONE (2026-07-14)

**Resolved 2026-07-14 by purge (not reconciliation).** `TESTING.md` had driven
the whole pre-workspace unscoped API — `POST/GET/PUT /api/risk-trees`,
`PATCH .../nodes/{ulid}/distribution`, `.../nodes/{ulid}` (rename),
`DELETE .../nodes/{ulid}` — all of which 404 on the live workspace-scoped
surface (only `GET /health` still resolved), and three of those endpoints no
longer exist at all (node PATCH-distribution / rename / delete; edits now go
through full-tree PUT). Per Daniel: don't re-author to the new surface — the
manual curl walkthrough is already covered by `docs/user/API-TUTORIAL.md`,
`examples/demo-*.sh`, and the `server-it` specs. Removed: API-testing cases
2–12, the `/api/` Protocol Cheatsheet + Payload-Fixtures blurb, the two `/api/`
LEC response-time benchmarks, and the entire outdated "Automated Test Script"
section (plus its TOC entry). Also dropped the dated "Expected Counts (as of
2026-03-09)" table (stale + counts, against the pass/fail-only rule) and all
"New API/DTO" framing. Kept: health check, SBT/Container/BATS/Irmin/Performance
sections. No `/api/` or historical framing remains.

## 21. Automated guard for user input crossing the validation boundary (SAST-equivalent) — OPEN

**Origin (2026-07-19):** the DD-7 security review hand-verified that every
interpolation slot in the new `set_tree` query builder is either constant,
server-minted, or Iron-refined-then-escaped. That verification is manual and
repeats on every change to a query/command builder. Question: can it be
automated so that stringly-typed code introduced by convention-unaware
contributors fails CI instead of relying on review?

**Status: undecided.** Everything below is an assumption to investigate, not a
decision. No tooling has been added; no dependency approved.

### Assumptions to validate before deciding

- **A1 — Market gap (assumed, spot-checked only):** no mature taint-tracking
  SAST for Scala 3. CodeQL: no Scala. Semgrep: parses Scala, but cross-function
  taint analysis assumed too weak to rely on. SonarQube: taint engine excludes
  Scala. Fortify: legacy Scala plugin, Scala 3 support assumed dead. Each of
  these is a point-in-time claim — re-verify current state before ruling
  commercial/OSS SAST out.
- **A2 — Type system as taint tracker (assumed sound):** Iron already encodes
  taint labels (raw primitive = tainted, refined = sanitized, smart constructor
  = sanitizer), so "does user input cross unvalidated" reduces to two finite
  checks: (1) no raw-primitive signatures/fields past the boundary, (2) audit
  of the enumerable escape hatches (`refineUnsafe`, `unsafeFrom`, `assume`,
  `asInstanceOf`). Validate that this reduction has no fourth escape channel
  (e.g. pattern-match extraction, `.value` widening chains, macro-generated
  code).
- **A3 — Default-deny is the ignorance-proof shape (assumed):** package-scoped
  allowlist rules ("no raw String in these packages") fail against contributors
  who put new code in unlisted packages. The rule must be project-wide ban +
  explicit allowlist, so violations fail loudly and the allowlist diff line is
  itself review bait. Validate noise level: how many legitimate allowlist
  entries would the current codebase need?
- **A4 — Highest-leverage single rule (assumed):** "no raw primitives in any
  Tapir endpoint input type" — taint is only born at decode, so closing the
  decode surface contains stringly code downstream even when it exists.
  Validate against current `common` endpoint DTOs.
- **A5 — Tooling candidates (none chosen):** Scalafix semantic rules
  (SemanticDB, Scala 3-capable) for A3/A4 + escape-hatch zoning; Semgrep for
  syntactic seam policies (e.g. "interpolation inside `IrminQueries` must pass
  through `escapeGraphQLString`"); WartRemover as lighter-weight alternative to
  Scalafix. Each adds a build dependency — decision trigger, user-owned.
- **A6 — Known residual gaps (assumed irreducible by tooling):** semantic
  stringlyness stays with human review regardless of tooling — String-where-ADT
  -belongs, missing nominal distinctness between same-typed fields (ADR-018),
  vacuous refinements. The code-quality-review skill's Layer A₀ questions
  remain the guard for these.

### Exit criteria

Decide: adopt (which tool(s), which rules, allowlist policy), or reject with
reasoning recorded here. If adopted: rules live in the build, run in CI, and
the DD-7-style slot-by-slot injection audit becomes automated for every new
query/command builder.

---

## ✅ 22. `Option[BranchRef]` lets "main" be spelled two different ways — RESOLVED 2026-07-23 (BranchChoice consolidation)

**Resolution:** implemented in full, both halves, as the opening task of
Phase C (user decision 2026-07-23, "Option A"):

- New shared selector `BranchChoice { Main; Scenario(name) }` in
  `OpaqueTypes.scala` (cross-compiled; carries no workspace identity, so it
  is legal on the client — unlike `BranchRef`, which embeds `WorkspaceId`).
- The wire keeps DD-8's encoding (`X-Active-Branch` absent = main), decoded
  exactly once by the shared `activeBranchHeader` / `compareBranchQuery`
  inputs in `BaseEndpoint` — every endpoint tuple now carries `BranchChoice`,
  and the ten per-endpoint header definitions collapsed into one.
- `ActiveBranch.resolve` is total: `BranchChoice` in, definite `BranchRef`
  out. `RiskTreeRepository`, `IrminClient`, `RiskTreeService`,
  `QueryService`, `ScenarioDiffService` all take a definite
  `branch: BranchRef` — `Some(BranchRef.Main)`-vs-`None` is no longer
  expressible; `requireMain` is a single equality. `IrminClientLive` still
  omits the GraphQL branch argument for main at the lowest boundary (one
  place), preserving the previous wire behavior against Irmin.
- Frontend: `ScenarioState.activeBranch`, all `branchAccessor` chains,
  `TreeLoadPolicy`, and `BranchBar`'s helpers use `BranchChoice`;
  `CompareTarget` rebased to `{ NotChosen; Target(BranchChoice) }`. The one
  deliberately remaining internal `Option[ScenarioName]` is the scenario
  `forkOf` request field, where `None` = "fork main's head" is genuine (main
  is not a scenario, DD-11 — the case this item's origin already called fine).

Verified: commonJVM + server + app unit suites and the Irmin integration
suite all pass. Original investigation kept below for the record.

*(As originally written:)*

**Origin (2026-07-20):** surfaced while reviewing the signature chosen for the
new `ScenarioService.create`'s source parameter (`Option[ScenarioName]`,
milestone-2b Phase B). That specific parameter turned out fine — `ScenarioName`
has no value that means "main" (main is not a scenario, DD-11), so `None` is
the only way to say it. But the review found that the existing, unrelated
`Option[BranchRef]` parameter used across `IrminClient` and
`RiskTreeRepository` (`get`, `set`, `setTree`, `remove`, `getHistory`, `list`,
`create`, `update`, `delete`, `getById`, `getAllForWorkspace` — roughly 70 call
sites) does not have that property, and the gap is not hypothetical.

**Observed:** `BranchRef.Main` (`OpaqueTypes.scala:352`) is a constructible
value equal in meaning to `None` wherever `Option[BranchRef]` is used to mean
"target branch, default main." `RiskTreeRepositoryInMemory.requireMain`
(`RiskTreeRepositoryInMemory.scala:21-23`) already has to handle both:

```scala
branch match
  case None | Some(BranchRef.Main) => ZIO.unit
  case Some(other) => ZIO.fail(...)
```

Any future consumer of the same `Option[BranchRef]` parameter that branches on
"is this main?" and checks only `None` (forgetting `Some(BranchRef.Main)`, or
vice versa) would silently misclassify a main-targeted call — the "branch
switching silently returns wrong results" failure class the milestone-2b cache
doc's problem statement opens with. `IrminClientLive` currently avoids this
only because it forwards the raw `Option[BranchRef]` to Irmin's GraphQL query
without ever branching on it (`branch.map(_.toBranchRef)`,
`IrminClientLive.scala:36`), and "no branch argument" happens to behave the
same as "branch=main" on Irmin's side — a property of Irmin's API, not a
guarantee the Scala types provide.

**What needs investigating:** whether a dedicated two-case type — e.g. a
selector distinguishing "main" from "a named branch" — should replace
`Option[BranchRef]` at the layers where it represents a real business choice
(`IrminClient`, `RiskTreeRepository` and implementations). Under such a type,
"main" would have exactly one representable value; `Some(BranchRef.Main)` as
an alternate spelling of the default would become structurally impossible
rather than something each new consumer has to remember to normalize — the
same "not just documented against, actually unrepresentable" property the
project's correct-by-construction principle asks for elsewhere (Iron
refinements, `Checked[P]` proof tokens in item 15).

**Where `Option` stays correct and should not change:** the `X-Active-Branch`
HTTP header (DD-8, closed 2026-07-18, not yet implemented — TODO app item 4).
A header is genuinely either present or absent on the wire; `Option[BranchRef]`
there reflects that real optionality, not a business classification standing
in for it. The investigation is about what the value becomes *after* decoding,
not the decode step itself — i.e. whether the decoded `None` (header absent)
should be normalized into the dedicated type's `Main` case immediately at the
Tapir boundary, so nothing past that point ever carries a bare `Option` that
could be confused with wire-level absence again.

**Also relevant to scope:** `IrminConfig.scala:27` (`branch: BranchRef =
BranchRef.Main`) is a plain, required `BranchRef` field, not an `Option` — a
context where `BranchRef.Main` is the correct tool and must keep working
whatever the outcome here. A clean design keeps that usage valid while closing
the duplicate-spelling hole in the optional-parameter contexts.

**Why not implement immediately:** this changes public method signatures
across `IrminClient` and `RiskTreeRepository` (and both implementations,
`IrminClientLive` and `RiskTreeRepositoryInMemory`/`RiskTreeRepositoryIrmin`),
which is a hard Decision Trigger under the project's working protocol, and is
unrelated in scope to the `ScenarioService` work in progress when this was
found. Also worth deciding whether `ScenarioService`'s own use of `BranchRef`
(item 3/4, milestone-2b Phase B) should be designed against the new type from
the start or added as a plain consumer now and migrated later.

---

## 23. No periodic reconciliation for orphaned Irmin resources (trees, scenario branches) — verify need before designing

**Origin (2026-07-21):** surfaced during milestone-2b Phase B work extending
`WorkspaceReaper`'s cascade-delete to scenario branches, and while auditing
`WorkspaceLifecycleController.evictExpired`/`deleteWorkspace` for the same
cascade coverage. All cascade-delete paths in the codebase — the reaper's
TTL loop, the explicit `DELETE /w/{key}` endpoint, and the admin
`POST /admin/workspaces/expired` sweep — are purely reactive: each cleans up
a resource only when *that resource's own workspace* hits a specific
lifecycle event (expiry or explicit delete). None of them ever list actual
storage (Irmin branches under `scenarios.*`, tree paths under
`workspaces/*/risk-trees/*`) and reconcile it against live `WorkspaceStore`
records independent of any particular workspace's own lifecycle firing.

**Observed:** the only two "orphan" mentions in `IMPLEMENTATION-PLAN.md`
("Worst case (crash mid-delete): orphaned trees — reaper cascade-deletes on
next cycle"; "Reaper cascade-deletes trees from evicted workspaces (orphan
bug fix)") both describe the *reactive* mechanism catching a crash mid-delete
because the workspace record itself survives to be reaped normally later —
not an independent scan. If a workspace record is ever lost or removed
without its resources being fully cascaded first (a bug, a crash at exactly
the wrong instant, direct DB/Irmin intervention), nothing today would ever
find or clean up whatever it left behind. The only genuine periodic-sweep
concept anywhere in the codebase, `EvictionStrategy.sweep`
(`services/cache/EvictionStrategy.scala`), is unrelated: it targets the
ephemeral in-memory `ContentCache` (simulation results), not Irmin-persisted
trees or branches, and is currently wired only to `NoOpEvictionStrategy`
(always returns empty — no sweep actually runs).

**Concrete failure mode identified (2026-07-21):** `ScenarioService.cascadeDeleteScenarios`
treats a failed `list` call as best-effort (`.ignore`) — and in both
`WorkspaceLifecycleController.deleteWorkspace` and `.evictExpired`, the
workspace *record* is removed regardless of whether that cascade succeeded.
If Irmin is transiently unreachable at the exact moment cascade runs during
a workspace delete/eviction, that workspace's scenario branches are left
behind, and — because the workspace record is now gone — nothing will ever
retry cleaning them up; there is no future lifecycle event left to trigger
it. This is exactly the failure mode TODO item 23 exists to check for via
property-based testing, not a new problem.

**Step 1 — verify the behaviour is actually needed (user direction,
2026-07-21):** before designing anything, determine whether the reactive-only
design leaves real orphans in practice. Proposed approach: randomized,
property-based testing that simulates a long usage cycle — many workspaces
created / expired / explicitly deleted, scenarios created / deleted,
interleaved with simulated crashes and restarts at random points in the
sequence — then asserts an invariant such as "every Irmin branch or tree path
whose embedded workspace ID has no live `WorkspaceStore` record has already
been cleaned up (or is in-flight) — none are permanently unreachable." A
property test capable of generating long random operation sequences and
checking this invariant would give a concrete, evidence-based answer on
whether leftovers accumulate, and under exactly which conditions, rather than
reasoning about it from worst-case scenarios in the abstract.

**Step 2 — only if step 1 finds real leftovers:** design the reconciliation
solution from first principles, scoped to whatever failure modes the
property tests actually demonstrate (e.g., a specific crash window, a
specific operation ordering) — not a speculative general "scan everything,
delete anything unmatched" sweep built ahead of evidence that it's needed.

**Why not implement immediately:** this would be a new architectural
capability (a background reconciliation job scanning live storage), not a
bug fix to the existing reactive cascade paths — those are being brought to
full, consistent coverage (trees + scenarios, across all three call sites)
as a separate, already-scoped fix. Whether this item is needed at all, and
what shape it should take, depends entirely on step 1's findings.

**Status:** investigation only, no decision made, no code changed.

## 24. Examine browser automation options for testing and development

**Origin (2026-07-21):** surfaced while implementing BranchBar (milestone-2b
Phase B). The working agent had no way to interactively exercise the new
Laminar UI (open the scenario dropdown, click switch/create/delete, confirm
visual state) — verification stopped at compile, unit tests, and direct
`curl` round-trips against the live backend, which confirm the wire format
but not the actual click-through UX. The frontend has no automated
end-to-end coverage of interactive behaviour at all today (`sbt app/test`
only exercises pure state/logic classes, e.g. `TreeBuilderStateSpec`,
`TreePreviewSpec` — nothing renders and clicks through real DOM).

**To examine:** what browser automation is available or worth adding —
a Playwright/Puppeteer-based E2E suite runnable in CI and locally, an MCP
server exposing browser control to the assistant during development
sessions, or both (the two solve different problems: repeatable CI
regression coverage vs. an agent's ability to visually verify a change
before reporting it done). Questions to resolve before choosing:
- Does the assistant's own tool environment support adding an MCP browser
  tool, and what would setup/maintenance cost look like?
- For CI: Playwright vs. Puppeteer vs. something lighter — matched against
  what's already used for BATS smoke tests (Suite C could plausibly gain a
  browser-driven check alongside the existing curl-based ones).
- Where would UI test specs live (`modules/app/src/test`? a new top-level
  `tests/e2e/`?) and what's the minimum useful first spec — likely the
  create-switch-edit-switch-back scenario flow (TODO item, milestone-2b
  Phase B), once its blocking gap (branch-aware tree listing) is closed.

**Status:** investigation only, no tooling chosen, no code changed.

## 25. Scala.js test harness for network-stubbed frontend specs — deprioritized, prior attempt failed

**Origin (2026-07-21):** surfaced as one of three options for covering
milestone-2b Phase B's "create scenario, switch, edit, switch back"
end-to-end item (the other two: item 24's browser automation, or a live
manual round-trip — see `docs/scratch/milestone-2b-cache-and-decisions.md`,
Phase Outline, Phase B). Every current `app/test` spec is a pure state/logic
test (`TreeBuilderStateSpec`, `TreePreviewSpec`, etc.) — none exercise
`ScenarioState`/`TreeViewState` against a fake backend. The server module has
a working pattern for this (`SttpBackendStub` + `TapirStubInterpreter`, used
throughout `modules/server/src/test`), but nothing equivalent exists for the
Scala.js/sttp client side.

**Status: deprioritized.** A prior attempt at this failed (per the user,
2026-07-21) — no further detail captured here. Not worth re-investing in
right now. Revisit only if a concrete need for automated frontend network-flow
coverage comes up again; if picked up, check what actually failed last time
before repeating the same approach.

## ✅ 26. Compare branch picker (`AnalyzeView.renderBranchPicker`) — `<select>` desync between DOM and `compareState.compareBranch` — RESOLVED 2026-07-22

**Origin (2026-07-16 session, confirmed by user 2026-07-22):** the Compare
branch `<select>` (`AnalyzeView.scala`, `renderBranchPicker`) rebuilds its
`<option>` list via `children <-- scenarioState.scenarios.signal.combineWith(...)`,
constructing brand-new `option(...)` element instances on every emission.
`controlled(value <-- compareState.compareBranch.signal, onInput.mapToValue --> ...)`
only re-asserts the DOM's selected value when `compareBranch` itself changes —
not when the option list is rebuilt. Removing and recreating the currently
selected `<option>` node resets the browser's `<select>` selection state
(back to the first/blank option) independently of the `compareBranch` Var,
which still holds the user's actual choice — a visible desync between what
the picker displays and what the app believes is selected.

**User confirmed reproducing this 2026-07-22; needs fixing.** Decision
presented (see conversation, decision-guide format): (1) a corrective
re-assert subscription mirroring the existing corrective subscription already in
`FormInputs.parentSelect` (`selectionAndOptions --> {...}`), vs. (2) keyed
reconciliation of the `<option>` list (Airstream's `Signal[Seq[A]].split` by
scenario name) so `<option>` DOM nodes are reused instead of torn down and
recreated, removing the root cause rather than re-syncing after the fact.

**Decided (2026-07-22): Option 2.** Implemented in
`AnalyzeView.renderBranchPicker`, and the same mechanism was confirmed
present (traced, not separately reproduced) in `FormInputs.parentSelect`'s
"Parent Portfolio" dropdown (used on both the Portfolio and Leaf forms in
Design) — its own Option-1-style corrective subscription (`selectionAndOptions --> {...}`)
only corrects an *invalid* selection, not a still-valid one whose `<option>`
DOM node was torn down and recreated for an unrelated reason (e.g. another
portfolio being added while this form's parent field is already set).

Extracted the fix into a shared `FormInputs.splitOptions(options: Signal[List[(String, String)]])`
helper (keyed `.split` rendering of the `<option>` list) instead of
copy-pasting the `.split` call at both sites — both `renderBranchPicker` and
`parentSelect` now call it.

**Status:** fixed, both call sites.

## ✅ 27. `AnalyzeQueryState` stale-result reset — imperative patch in place, more robust alternative available — RESOLVED 2026-07-22

**Origin:** milestone-2b Phase C Comparison-view review (2026-07-16 session).
A stale query result from the previously-selected tree could leak into the
newly-selected tree's chart/Compare curve fetch. Fixed with
`AnalyzeQueryState.resetResult()`, called from an explicit subscription on
`treeViewState.selectedTreeId.signal.changes` in `AnalyzeView.scala`. This
works today, but depends on every future tree-switch path remembering to
call `resetResult()` — the same category of fragility as item 26 and the
dirty-tracking races fixed this session (state kept correct by convention at
each call site, not by construction).

~~**More robust alternative (not yet built):** tag each query result with the
`TreeId` it was computed for, and derive the "current" result as a pure
filter against `selectedTreeId` (stale results for a different tree simply
never display) instead of relying on an imperative reset call wired into one
particular transition path.~~

~~**Decided (2026-07-22):** tentatively Option B (tagged/derived approach).
**Scheduled after milestone-2b's implementation is complete** — the current
imperative fix is live and correct, so this is a deferred re-engineering
pass, not a live bug.~~

**Resolution (2026-07-22):** built sooner than scheduled above, using a
different mechanism than the tagged/derived approach originally sketched —
Airstream's own `flatMapSwitch` combinator, which is the framework's
established tool for "supersede whatever the previous trigger's in-flight
work was still doing," rather than a hand-rolled staleness guard or a
tag-and-filter scheme. `executeQuery()`/`resetResult()` now emit onto a
private `EventBus[Trigger]`; `triggerBus.events.flatMapSwitch(outcomeStream)`
drives `queryResult`/`queryServerError` as the one and only writer. A new
trigger makes Airstream drop the subscription to the previous trigger's
request stream outright, so a stale response can never land after a newer
query has started — not merely "arrive but get filtered," an actual
unsubscribe. Same mechanism applied to `ScenarioDiffState.loadDiff` /
`loadCompareCurves` (item 28's sibling races). New `ZJS.toOutcomeEventStream`
(additive, `toEventStream` itself unchanged) supports this by also emitting
on failure, which plain `toEventStream` doesn't.

**Known gap, tracked separately:** `flatMapSwitch` stops *observing* a
superseded request, it does not cancel the underlying ZIO fiber —
`forkProvided` (`ZJS.scala`) discards the fiber handle it gets back from
`Runtime.default.unsafe.fork`, so the abandoned network call still runs to
completion server-side. See item 29.

**Status:** resolved — `AnalyzeQueryState.scala`, `ScenarioDiffState.scala`,
`ZJS.scala`.

## 28. VQL queries spanning multiple trees / multiple scenarios — investigation only

**Origin (2026-07-22):** surfaced while discussing the Analyze Overlay
comparison view (milestone-2b Phase C). Today's vql-engine queries
(`AnalyzeQueryState.executeQuery`) run against exactly one tree on exactly
one branch — the tab's own selected tree and active branch. There is no
mechanism, and no design yet, for a query expression to draw nodes from more
than one tree, or from the same tree across more than one scenario branch
(e.g. "find leaves whose loss changed by more than X between main and
scenario Y").

**Scope:** this is a whole investigation/design task on its own, separate
from the Overlay comparison view's node-selection mechanism (which stays
scoped to one node id shared across exactly two branches). Not started —
no query-language grammar changes, no backend service changes, no UI
changes proposed yet.

**Status:** investigation only, no design started, no code changed.

## 29. `flatMapSwitch` supersedes stale UI state, not the underlying ZIO fiber — investigate whether that gap is worth closing

**Origin (2026-07-22):** surfaced while resolving item 27 with
`flatMapSwitch` (`AnalyzeQueryState`/`ScenarioDiffState`). `flatMapSwitch`
correctly stops a stale request's result from ever being displayed — it
unsubscribes from the previous trigger's inner stream the moment a new
trigger fires. It does **not** cancel the ZIO fiber running the actual
network call: `ZJS.forkProvided` calls `Runtime.default.unsafe.fork(...)`
and discards the returned `Fiber` handle, so a superseded request's HTTP
call still runs to completion server-side (and the response, once it
arrives, is simply never observed) — wasted backend work, not a correctness
problem.

**Scope of the investigation:** whether this is worth closing at all (how
often does a user actually re-trigger fast enough for it to matter?), and if
so, what closing it would take — `forkProvided` would need to retain the
`Fiber` handle per in-flight request and interrupt it when Airstream drops
the corresponding subscription (e.g. wiring `Fiber#interrupt` into an
`onStop` hook of a custom `EventStream`, rather than the current bare
`emitTo`/`toEventStream`/`toOutcomeEventStream` helpers). Not started — no
design proposed, no `ZJS.scala` changes.

**Status:** investigation only, no code changed.

## ✅ 30. LEC chart: per-curve percentile lines (P05/P50/P95) + toggle — RESOLVED 2026-07-22

**Origin (2026-07-22):** two related, previously-planned chart items.

1. **Percentile lines: dotted, toggle, per-curve colour, plus P05 — DONE.**
   Clarified with the user: "per-branch, not per-risk" did **not** mean the
   existing `ColorAssigner`/`CompareColorAssigner` node-colour scheme (left
   untouched, as instructed — including the sequential palette-reuse and the
   manual colour-picker override). It meant: when multiple curves are shown
   together (Compare/Overlay's two branches, or several nodes picked from one
   tree), each curve's own P05/P50/P95 vertical marker lines should be
   coloured to match *that curve's own already-assigned line colour*, not one
   shared colour taken from a single curve as before.
   - `LECGenerator.calculateQuantiles` (server) now also computes `"p05"`
     (`unconditionalQuantile(result, 0.05)`), alongside the existing p50/p90/
     p95/p99 — additive, `Map[String, Double]` needs no schema change, flows
     through `LECNodeCurve.quantiles` automatically.
   - `LECSpecBuilder.buildSpec` (frontend) now builds P05/P50/P95 annotation
     layers **per curve** (was: only the first curve's P50/P95, one shared
     colour) — each layer's `color` is that curve's own `HexColor`.
   - The lines were already dashed (`strokeDash: [4,4]`, pre-existing). Added
     a `showPercentiles` checkbox param (same mechanism the existing
     interpolation dropdown already uses — a top-level Vega-Lite param
     referenced via `expr` in the annotation marks' `opacity`) — no new Scala
     state needed.
   - Test coverage: `LECGeneratorSpec` extended to assert `p05` is present
     and `p05 <= p50` alongside the existing monotonicity checks.

2. **"P50" naming — checked, NOT a mistake, no rename.** The LEC chart's
   "P50"/"P95" come from `LECGenerator.scala` (`unconditionalQuantile(result,
   0.50)` / `0.95`) — a genuine, deliberately-computed median and 95th
   percentile of the *simulated loss-exceedance output*. The leaf-creation
   preview's "P05" (`DistributionSpecBuilder.scala`, `LognormalHelper.scala`)
   is the 5th percentile *input* bound (`minLoss`) used to parameterize the
   lognormal distribution before simulation — a completely different
   statistic from a completely different (pre- vs. post-simulation) dataset.
   The resemblance between the two labels was coincidental; renaming P50→P05
   in the LEC chart would have mislabeled a true median as a 5th percentile.
   No rename made — instead, P05 was *added* to the LEC chart as its own,
   correctly-computed line (see item 1).

**Status:** both items done, full test run green.

**Addendum (2026-07-23) — design superseded by the locked chart UI.** The
resolution text above describes the first implementation (P05/P50/P95 lines
behind one shared `showPercentiles` checkbox). The chart has since moved to
its final form (commits 53a575f, ca82acc, 8e03e00):

- The annotation lines are the **tail quantiles P90 / P95 / P99 / P99.5**
  plus **AAL** — P05/P50 are no longer drawn on the LEC chart.
- Each line has its **own independent toggle** (`showP90`/`showP95`/`showP99`/
  `showP995`/`showAAL`); the shared `showPercentiles` switch is gone. Default:
  only **P95** (and AAL) start checked.
- Labels render as **two stacked lines** ("P95" over the formatted value) to
  reduce collisions when several quantile rules sit close together.
- Server side (`LECGenerator`): curves now always include the x=0 tick with
  the strict y-intercept `1 − P(no loss)`, and `getTicks` clamps its step to
  ≥ 1 so narrow loss ranges (span < nEntries) no longer collapse to a
  single-point (invisible) curve.

# TODO

Open issues, observations, and investigation notes.
Items are descriptive — they document symptoms and current understanding,
not prescribed solutions.

---

## 1. "Will retry" banner text has no backing implementation

**Observed:** When a network error occurs (e.g. `TypeError: NetworkError when
attempting to fetch resource.`), the global error banner appends " — will retry"
to the message. No retry ever happens — the message is misleading.

**Current understanding:** `GlobalError.NetworkError` carries a `retryable: Boolean`
field. `GlobalError.fromThrowable` classifies Fetch API failures and
`java.io.IOException` as `retryable = true`. `ErrorBanner` renders the
" — will retry" hint when that flag is true. However, no code in `modules/app/`
acts on the flag — there is no `Schedule`, no exponential backoff, no automatic
re-dispatch. A search of the entire app source tree for `retry`, `Schedule.recurs`,
`Schedule.exponential`, and `retryN` found nothing relevant. The `TreeListView`
has a manual "Retry" button for its own `LoadState.Failed`, but that is a
separate, unrelated mechanism.

The `retryable` flag is currently a classification-only hint with no consumer.

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

## 3. PostgreSQL workspace TTL/idle interval handling uses a DB-specific text round-trip

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

## 4. Backend/mode selector config is still stringly typed

**Observed:** Selector-style config values such as repository backend and
workspace-store backend still deserialize to raw `String` values rather than a
typed enum / ADT.

**Current understanding:** Immediate inspection found at least these cases:
- `RepositoryConfig.repositoryType: String` in
   `modules/server/src/main/scala/com/risquanter/register/configs/RepositoryConfig.scala`
- `WorkspaceStoreConfig.backend: String` in
   `modules/server/src/main/scala/com/risquanter/register/configs/WorkspaceStoreConfig.scala`
- `AuthConfig.mode: String` in
   `modules/server/src/main/scala/com/risquanter/register/configs/AuthConfig.scala`

These values are normalized with `.trim.toLowerCase` and matched manually in
application wiring. That means invalid values are only caught indirectly at use
sites, and supported values are not encoded in the type system.

Follow-up review should determine whether these should deserialize directly to
a typed representation (e.g. Scala 3 `enum` / sealed trait with explicit
zio-config decoding), at least for closed sets such as:
- `in-memory` / `irmin`
- `in-memory` / `postgres`
- `capability-only` / `identity` / `fine-grained`

Free-form config such as URLs, branch names, hostnames, and service names may
remain strings where the domain is intentionally open; the concern here is with
closed-choice selector values.

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
`local/graalvm-builder:21` and `local/frontend:dev` before rebuilding dependent images.

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

## 6. Inconsistent Docker image naming convention

**Observed:** Locally-built images use inconsistent tag conventions:

| Image | Tag | Issue |
|---|---|---|
| `local/frontend:dev` | `dev` | Misleading — built from `Dockerfile.frontend-prod`; tag does not reflect content |
| `register-server:prod` | `prod` | No `local/` prefix; tag describes build type, not version |
| `local/graalvm-builder:21` | `21` | JDK version — semantically clear |
| `local/irmin-prod:3.11` | `3.11` | Irmin version — semantically clear |
| `local/irmin-builder:3.11` | `3.11` | Irmin version — semantically clear |

The `local/frontend:dev` and `register-server:prod` names pre-date a coherent
naming policy. The inconsistency creates confusion when reading `docker ps` or
build scripts.

**Decision context:**

Three options were evaluated:

| Option | Example | Assessment |
|---|---|---|
| `local/` + `:latest` | `local/register-server:latest` | Honest for a mutable local-only image ("always the last thing built"); zero version management overhead; matches `pull_policy: never` semantics in compose |
| `local/` + app version | `local/register-server:0.1.0-SNAPSHOT` | Aligns with sbt versioning; enables multi-version coexistence and rollback; requires keeping tag in sync with `build.sbt` |
| Keep `:dev` / `:prod` tags | — | No migration cost; but `:dev` is actively misleading (prod Dockerfile, prod content) and `register-server` lacks the `local/` prefix |

Version tagging (option 2) is the standard practice for images intended for
registry promotion, and will be the eventual target as the project matures
toward a release process. However, it requires a mechanism to keep the image
tag in sync with `build.sbt` (e.g. a shell script or sbt task that reads the
version and passes it as a `--build-arg`), which adds complexity not justified
at the current stage.

**Decided migration path (on hold):**

1. **Phase 1 — immediate (on hold):** Rename to `local/` + `:latest` for all
   locally-built app images. Specifically:
   - `local/frontend:dev` → `local/frontend:latest`
   - `register-server:prod` → `local/register-server:latest`
   - Update `docker-compose.yml`, all docs, and test references atomically.

2. **Phase 2 — subsequent:** Migrate to `local/` + app version tag
   (e.g. `local/register-server:0.1.0-SNAPSHOT`), driven by the sbt project
   version in `build.sbt`. Implement a helper (script or sbt task) to stamp
   the tag automatically so it stays in sync without manual edits.

The Irmin and GraalVM builder images are **not affected** — their version tags
pin to external software versions (`3.11`, `21`) and are already correct.

---

## 7. `SafeName` character class is permissive — consider hardening

**Observed:** `SafeName` (alias of `SafeShortStr`) is currently defined as
`String :| (Not[Blank] & MaxLength[50])` in
[modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala](modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala#L14).
Beyond non-blank and ≤50 characters, **no character-class restriction
applies**. A tree node may legally be named `foo"), gt_loss(p95(x)`,
`a,b,c`, `name with\nnewline`, or any other string containing FOL-grammar
or whitespace-meaningful characters.

**Current understanding:** This was surfaced while reviewing injection-safety
of the upcoming node-name-literal fix
([docs/PLAN-QUERY-NODE-NAME-LITERALS.md](docs/PLAN-QUERY-NODE-NAME-LITERALS.md)).
After that fix lands, such names will be **unreferencable** from a query
(the FOL lexer's `"`-terminator stops at the first embedded `"`, the
remainder fails to parse, and pure `Map.get` / `Set.contains` lookup in
`RiskTreeKnowledgeBase` rejects unmatched keys with `UNKNOWN_REFERENCE`).
So this is **not** an injection vector — it is a UX / data-quality issue:
a user can create a node that no one can query for.

**Open question:** there is no obvious legitimate need for `"`, `(`, `,`,
newlines, or other control characters in business-domain risk names.
Decide whether to:
- (a) Tighten the refinement, e.g.
  `Not[Blank] & MaxLength[50] & Match["[A-Za-z0-9 _&/.:'-]{1,50}"]`,
  with an accompanying migration / validation strategy for any pre-existing
  data; or
- (b) Leave as-is and accept that users may create unreferencable nodes; or
- (c) Apply a softer rule (e.g. forbid only ASCII control chars and the
  literal `"` character) as a middle ground.

Independent of the node-name-literal plan; out of that plan's scope.
Tracked there as follow-up F-R5.

### Extension: applies to ALL string-backed user-input types

The same hardening question applies beyond `SafeName`. Concrete observation
from the injection-safety analysis:

> Even with a fully typed `Token` ADT, a `StringLit("foo\"), gt_loss(p99(x), 0")`
> value — perfectly well-typed, perfectly well-lexed — would still inject if
> any downstream code concatenated it into a string that was later re-parsed.
> Strong typing of the first lexer would change nothing. **The injection
> lives in the second parse, not the first.** A type that wraps a `String`
> only protects against injection to the extent that its *content domain* is
> restricted at construction time; the wrapper alone is decorative.

**Proposed defensive principle (for review):** for every Iron-refined
`String`-backed type in the codebase
(`SafeName`, `SafeShortStr`, `SafeLongStr`, query strings, free-text fields,
…), the refinement should restrict the **content domain** as aggressively as
the business domain permits — not merely length and non-blankness.
"Restricting the domain from `String` as much as possible" means: even
though we represent the value as `String` for storage and transport, the
*set of strings that can ever inhabit the type* is bounded by an explicit
character class / regex / format check, chosen so that characters that
carry meaning to any downstream parser (FOL grammar, SQL, HOCON, JSON, HTML,
shell, log templates, regex, URL paths) are filtered out unless the field's
purpose explicitly requires them.

This is **defence in depth** layered on top of the "never re-parse user
content through a second interpreter" discipline already required by the
codebase. Even if a future contributor accidentally introduces a
re-parsing call site, an aggressively-restricted content domain prevents
the attack from having any payload to deliver.

**Audit task (when this TODO is picked up):** enumerate every Iron alias
under
[modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala](modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala)
that is `String`-backed, classify each by purpose
(identifier / display name / free-text description / opaque token / secret),
and decide a per-type character-class policy:

| Purpose | Suggested policy |
|---|---|
| Internal identifier (e.g. `SafeId`) | strict ASCII `[A-Za-z0-9_-]` |
| Business display name (e.g. `SafeName`) | letters, digits, common punctuation `[A-Za-z0-9 _&/.:'-]` (decide on Unicode allowance) |
| Free-text description / multi-line | forbid only ASCII control chars (`\x00-\x1F\x7F`) |
| Opaque token / secret | already separate types; verify content rule matches issuer's grammar |

Whether this principle is the right one (vs e.g. "rely on type-system
parsing-vs-validation discipline alone") is itself an open architectural
question. Capture the decision in an ADR if adopted.

### Related gap: "no-re-parse" discipline is NOT documented as an ADR

**Audit performed 2026-05-01.** Searched all ADRs (`docs/ADR-*.md`) for
any mention of injection attacks, re-parsing user content, parameterised
queries, prepared-statement-style discipline, or content-domain
restriction as an injection defence:

| Term searched | Hits | Where |
|---|---|---|
| `inject` | 0 in security context | (only `parameterized` in ADR-019 component prose) |
| `OWASP` / `CWE` | covered for **A10 / CWE-209** only | ADR-022 § Context + References |
| `re-parse`, `reparse`, `concat`, `interpolat` (in injection context) | 0 | — |
| `sanitise` / `escape` | once — ADR-022 §4 (error-message sanitisation only) | ADR-022 |
| `parameteri[sz]ed`, `prepared statement` | 0 | — |

**Finding.** The current ADR set covers **secret leakage out** (ADR-022
→ OWASP A10, CWE-209) but does **not** cover **untrusted input flowing
into a downstream interpreter** (OWASP A03 — Injection). The injection
safety we currently enjoy on the FOL query path
(see [docs/PLAN-QUERY-NODE-NAME-LITERALS.md §10](docs/PLAN-QUERY-NODE-NAME-LITERALS.md))
is **structural and accidental** — it falls out of the lexer's
`"`-terminator rule plus pure `Set.contains` / `Map.get` lookup in
`RiskTreeKnowledgeBase`. There is no ADR that:

- States the discipline as a project-wide invariant
  ("user-supplied strings MUST NOT be concatenated into any string that
  is subsequently parsed by FOL, SQL, HOCON, JSON, HTML, shell, regex,
  URL routing, or log templates").
- Identifies the threat model (OWASP A03 Injection, CWE-89 / CWE-94 /
  CWE-79 / CWE-77 family).
- Codifies the defensive layers: (1) restrict content domain at
  construction time via Iron refinements; (2) never re-parse user
  content via string concatenation; (3) prefer
  parameterised / structured / AST-level interfaces to any downstream
  interpreter.
- Provides a compile-time enforcement story analogous to ADR-022 §4's
  exhaustive `ErrorResponse.encode` sanitisation (e.g. a `Tainted[A]`
  phantom type, a lint rule on `s"…$x…"` interpolation into known
  parser entry points, or a code-review checklist item).

**Recommended action (TODO, not yet decided):** clarify whether to:

- (a) **Extend ADR-022** with a new section (e.g. "§8 — Input Injection
  Defence") covering the discipline alongside leakage. ADR-022's title
  is "Secret Handling & Error Leakage Prevention," which is narrower
  than "Information Security at HTTP boundaries" — widening the scope
  may dilute its current focus.
- (b) **Author a new ADR** (e.g. ADR-029 "Input Injection Defence —
  Parse, Don't Re-Parse") that explicitly:
  - Names OWASP A03 + CWE-89/94/79/77 as the threat model.
  - Mandates content-domain restriction at the Iron-refinement layer
    for every `String`-backed user-input type (cross-references the
    SafeName audit task above).
  - Mandates that no user-supplied string ever flow into a string that
    is subsequently parsed (positive form: only via parameterised,
    structured, or AST-level interfaces).
  - Lists every current parser/interpreter boundary in the codebase
    (FOL `Lexer.lex`, JDBC via the chosen library, JSON via zio-json,
    HOCON config, ZIO logging templates, frontend HTML rendering via
    Laminar) and records how each currently honours the discipline.
  - Proposes a compile-time or lint-time enforcement mechanism
    (e.g. a `@injectionSink` annotation on parser entry points, a
    Scalafix rule rejecting `s"…$untrusted…"` into annotated sinks,
    or a `Tainted[String]` opaque type produced by every Tapir input
    decoder and consumed only by sanitiser functions).
  - Cross-references ADR-001 (parse-don't-validate — the same
    intuition applied to inputs from the wire), ADR-022 (leakage —
    its outbound counterpart), and ADR-018 (nominal wrappers — a
    related compile-time-distinction tool).
- (c) **Both:** extend ADR-022 with a one-paragraph forward-reference
  ("§8 — Input injection: see ADR-029") and author ADR-029 with the
  full treatment.

**Independent observation — the discipline is currently a tribal /
implicit norm.** It happens to be honoured everywhere we've audited
(FOL dispatcher uses `Set.contains` / `Map.get`; JDBC uses
parameterised queries via the typed query DSL; JSON encoding goes
through zio-json codecs; logging uses `s"… ${treeId.value}…"` where
the interpolated values are already validated `Iron`/`case class`
wrappers). But "happens to be honoured" is not a defence — a future
contributor adding (e.g.) a query-echo log-template, a hand-rolled
SQL fragment, or a frontend `dangerouslySetInnerHTML` equivalent has
no ADR to cite when reviewers push back. The gap is documentation,
not (currently) code; closing it is cheap and prevents a category of
future regressions.

Decide between (a) / (b) / (c) when this TODO is picked up.

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

## 10. `--profile persistence` is a no-op for the server — fix via compose override file

**Observed:** Running `docker compose --profile persistence --profile frontend up -d`
starts the postgres and irmin containers but the `register-server` service connects
to neither — both `REGISTER_REPOSITORY_TYPE` and `REGISTER_WORKSPACE_STORE_BACKEND`
default to `in-memory` in the base compose file, and `REGISTER_WORKSPACE_TTL` /
`REGISTER_WORKSPACE_IDLE_TIMEOUT` are absent from the compose env block entirely.
Workspaces disappear after ~1 h (the in-code `idleTimeout` default) regardless of
whether persistence containers are running.

**Decision:** Option A — companion override file (`docker-compose.persistence.yml`).
The base `docker-compose.yml` stays in-memory/safe for plain `docker compose up`
(dev/CI use). A separate override file resets the four env vars to their
persistence-backend values and wires the TTL overrides, so that:

```bash
docker compose -f docker-compose.yml -f docker-compose.persistence.yml \
  --profile persistence --profile frontend up -d
```

is the single one-stop command for a fully persistent stack. The postgres and
irmin `profiles: [persistence]` stay on those services so they are not started
without the flag.

**Env vars that need setting in the override file:**

| Var | Value |
|---|---|
| `REGISTER_REPOSITORY_TYPE` | `irmin` |
| `REGISTER_WORKSPACE_STORE_BACKEND` | `postgres` |
| `REGISTER_WORKSPACE_TTL` | `120h` (5-day) |
| `REGISTER_WORKSPACE_IDLE_TIMEOUT` | `120h` (5-day) |

**Outcome wanted:**
1. `docker-compose.persistence.yml` created with the above overrides.
2. `docs/DOCKER-DEVELOPMENT.md` updated to document the two-file invocation,
   explain the base vs. override split, and call out that `--profile persistence`
   alone is not sufficient without the override file.
3. `README.md` quick-start section reviewed and updated so that any persistence
   instructions reflect the corrected invocation.
4. A brief pass over any other docs that reference `docker compose … up` to
   confirm no stale single-file examples remain.

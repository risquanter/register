# Implementation Plan: Vague Quantifier Query Pane

**Parent:** [ADR-028](ADR-028-vague-quantifier-query-pane.md) +
[ADR-028 Appendix](ADR-028-appendix-technical-design.md)  
**Scope:** All code changes to ship the query pane end-to-end.  
**Last updated:** 2026-03-16

---

## Task Groups

| Group | Name | Depends on | Gate |
|---|---|---|---|
| TG-1 | fol-engine integration build prep | — | ✅ Done — all tests + bats suites pass |
| TG-1b | fol-engine internal debt resolution | TG-1 | 🔄 In progress — all 768 fol-engine tests pass; evaluation path unification pending |
| TG-2 | `RiskTreeKnowledgeBase` + query service | TG-1b | curl returns valid JSON |
| TG-3 | Frontend query pane | TG-2 | end-to-end GUI flow |
| TG-4 | Integration tests | TG-2 | `sbt serverIt/test` passes |
| TG-5 | Polish + docs | TG-3, TG-4 | ready to merge |

---

## TG-1 — fol-engine Integration Build Prep ✅

**Status: Complete.** Actual approach differed from the original plan.

Rather than cross-building the library for Scala 3.6.4, the register project
was upgraded to Scala 3.7.4 to match the library. No cross-build is needed.

### T1.1 — Rename and re-org the library ✅

**Files:** `vague-quantifier-logic/build.sbt`

- `organization := "com.risquanter"` added
- `name := "fol-engine"` (was `scala-logic`)
- `sbt.version` aligned to `1.12.0-RC1` (matches register's graalvm-builder)

Published as `com.risquanter::fol-engine:0.1.0-SNAPSHOT`.

### T1.2 — Bump register to Scala 3.7.4 ✅

**File:** `register/build.sbt`

```scala
ThisBuild / scalaVersion := "3.7.4"
```

All 711 unit tests + 19 integration tests pass at 3.7.4.

### T1.3 — Bake fol-engine into graalvm-builder ✅

**File:** `containers/builders/Dockerfile.graalvm-builder`

fol-engine is built from source inside the graalvm-builder image via
`sbt publishLocal`, making it available in the builder's `~/.ivy2/local`
when `Dockerfile.register-prod` runs `sbt server/update`. Build context
changed from `containers/builders/` to `..` (project parent dir) so both
`register/` and `vague-quantifier-logic/` are accessible during the build.

All three bats suites (A, B, C — 26 tests total) pass against the rebuilt
native distroless image.

### T1.4 — Add dependency to register build ✅

**File:** `register/build.sbt` (serverDependencies)

```scala
"com.risquanter" %% "fol-engine" % "0.1.0-SNAPSHOT"
```

---

## TG-1b — fol-engine Internal Debt Resolution 🔄

**Status: In progress.** Work in `~/projects/vague-quantifier-logic`.

This task group resolves technical debt in fol-engine that must be
completed before register can consume the library correctly. The core
issue: fol-engine has two parallel evaluation paths (string-parsed and
typed-DSL) implementing the same paper semantics with different
infrastructure. The string-parser path (which register uses) had a
degraded sampling implementation using `scala.util.Random` instead of
the proper HDR + statistical sampling pipeline.

**Design document:** [`vague-quantifier-logic/docs/EVALUATION-PATH-UNIFICATION.md`](../../vague-quantifier-logic/docs/EVALUATION-PATH-UNIFICATION.md)

### Completed sub-tasks

| Task | What | Status |
|---|---|---|
| T1b.1 | `NormalApprox` — replace commons-math3 inverse-normal with pure-Scala Acklam + A&S | ✅ Done (17 tests) |
| T1b.2 | Split `SamplingParams` → `SamplingParams(epsilon, alpha)` + `HDRConfig(entityId, varId, seed3, seed4)` | ✅ Done |
| T1b.3 | Rewrite `HDRSampler` — Fisher-Yates shuffle with HDR PRNG, eliminate `scala.util.Random` | ✅ Done (41 tests) |
| T1b.4 | Remove `UniformSampler` / `StratifiedSampler` | ✅ Done |
| T1b.5 | Update all typed-DSL source + test files to new `SamplingParams` + `HDRConfig` API | ✅ Done (768 tests pass) |

### Remaining sub-tasks — Evaluation path unification (Approach 2)

These tasks unify the two evaluation paths into a single pipeline so that
string-parsed queries and typed-DSL queries use identical sampling,
tolerance, and result semantics. See the
[design document](../../vague-quantifier-logic/docs/EVALUATION-PATH-UNIFICATION.md)
for full rationale and implementation steps.

| Task | What | Status | Est. |
|---|---|---|---|
| T1b.6 | Unify quantifier types: `Quantifier` (ratio) ↔ `VagueQuantifier` (percentage) — keep ratio as canonical, percentage as builder | Not done | 1.5h |
| T1b.7 | Create unified `VagueQueryResult` (replaces both `vague.semantics.QueryResult` and `vague.query.QueryResult`; adds `satisfyingElements`, `confidenceInterval`, `marginOfError`) | Not done | 0.5h |
| T1b.8 | Bridge FOL scope formulas into typed predicates (`Formula → RelationValue => Boolean` closure over `Model[Any]`) — enables string-parsed queries to flow through the typed evaluation pipeline | Not done | 1.0h |
| T1b.9 | Rewrite `VagueSemantics` to delegate to unified pipeline (remove `selectSample`, `checkQuantifier`, `EvaluationParams`, `import scala.util.Random`) | Not done | 1.5h |
| T1b.10 | Update `VagueQuery[A].evaluate` to return `VagueQueryResult` (remove `vague.query.QueryResult` wrapper) | Not done | 1.0h |
| T1b.11 | Remove deprecated KB wrappers (`holdsOnKB`, `holdsExactOnKB`, `holdsWithSamplingOnKB`) | Not done | 0.5h |
| T1b.12 | Build cleanup: remove `commons-math3` and `simulation.util` from `build.sbt` | Not done | 0.5h |
| T1b.13 | fol-engine usage documentation: `README.md` examples covering three evaluation modes (exact, sampled with HDR + CI, exact with satisfying element set) with semantic context for new users | Not done | 1.0h |
| T1b.14 | Cross-compile fol-engine (JVM + JS) — enables `commonDependencies` in register | Not done | TBD |

**Gate:** All fol-engine tests pass. `VagueSemantics.holds()` returns
`VagueQueryResult` with `satisfyingElements`. No `scala.util.Random`
anywhere. `commons-math3` and `simulation.util` removed from build.

**Acceptance:** `sbt test` passes (expect ~770+ tests). `sbt publishLocal`
produces artifact consumable by register.

---

## TG-2 — Server: `RiskTreeKnowledgeBase` + Endpoint

Work in `~/projects/register`.

### T2.1 — Add library dependency ✅

**File:** `build.sbt` (serverDependencies)

Done as part of TG-1 (T1.4). Coordinate:

```scala
"com.risquanter" %% "fol-engine" % "0.1.0-SNAPSHOT"
```

**Acceptance met:** `sbt server/compile` succeeds; `sbt server/test` passes
(323 tests). VagueQuery and all library types are importable.

### T2.2 — Define request/response types in `common` ✅

**Status: Complete.** Both types exist and compile.

**Files:**
- `modules/common/src/main/scala/.../http/responses/QueryResponse.scala`
- `modules/common/src/main/scala/.../http/requests/QueryRequest.scala`

#### Response type

```scala
final case class QueryResponse(
  satisfied: Boolean,
  proportion: Double,
  rangeSize: Int,
  sampleSize: Int,
  satisfyingCount: Int,
  matchingNodeIds: List[NodeId],
  queryEcho: String
)

object QueryResponse:
  given JsonCodec[QueryResponse] = DeriveJsonCodec.gen

  /** Outbound validation boundary: projects library evaluation
    * results back into the typed register domain (ADR-001 §7, ADR-018).
    *
    * After TG-1b, this method receives a VagueQueryResult from the
    * library (which includes satisfyingElements). No direct access to
    * ScopeEvaluator or RangeExtractor is needed.
    *
    * NOTE: QueryResponseBuilder.scala already exists in
    * modules/server/src/main/scala/.../foladapter/ with the old
    * Set[RelationValue] signature. It will be refactored in T2.5b
    * to accept VagueQueryResult instead.
    */
  def from(
    satisfyingElements: Set[Any],
    rangeElements: Set[Any],
    nodeIdLookup: Map[String, NodeId],
    queryEcho: String,
    thresholdSatisfied: Boolean
  ): QueryResponse =
    val matchingIds = satisfyingElements.toList.flatMap { elem =>
      nodeIdLookup.get(elem.toString)
    }
    QueryResponse(
      satisfied       = thresholdSatisfied,
      proportion      = if rangeElements.isEmpty then 0.0
                        else satisfyingElements.size.toDouble / rangeElements.size,
      rangeSize       = rangeElements.size,
      sampleSize      = rangeElements.size,
      satisfyingCount = satisfyingElements.size,
      matchingNodeIds = matchingIds,
      queryEcho       = queryEcho
    )
```

No explicit `Schema` derivation — auto-derived via `tapir.generic.auto.*`
(imported in `BaseEndpoint`).

`matchingNodeIds` uses `List[NodeId]` (not `List[String]`) per ADR-001 §7
and ADR-018. The `from` factory method is the **outbound validation
boundary** where untyped `Any` elements from the fol-engine evaluator
re-enter register's typed domain. This follows the same architectural
principle as `SimulationResponse.fromRiskTree` on the response side and
`RiskTreeDefinitionRequest.resolve` on the request side.

#### Request type

```scala
final case class QueryRequest(query: String)

object QueryRequest:
  given JsonCodec[QueryRequest] = DeriveJsonCodec.gen
```

**Acceptance:** `sbt common/compile` succeeds on JVM and JS.

### T2.3 — Define endpoint in `WorkspaceEndpoints` ✅

**Status: Complete.** Endpoint defined at line 182.

**File:** `modules/common/src/main/scala/.../http/endpoints/WorkspaceEndpoints.scala`

Added `queryWorkspaceTreeEndpoint`:
```
POST /w/{key}/risk-trees/{treeId}/query
  in: jsonBody[QueryRequest]
  out: jsonBody[QueryResponse]
```

**Acceptance:** ✅ Endpoint compiles; Swagger docs show it.

### T2.4 — Implement `RiskTreeKnowledgeBase`

**File:** `modules/server/src/main/scala/.../foladapter/RiskTreeKnowledgeBase.scala`

Responsibilities:
1. Build `KnowledgeBase` with structural facts (`leaf`, `portfolio`,
   `child_of`, `descendant_of`, `leaf_descendant_of`)
2. Build `Model[Any]` by augmenting `KnowledgeSourceModel.toModel()`
   with simulation-backed functions (`p50`, `p90`, `p95`, `p99`, `lec`)
   and comparison predicates (`>`, `<`, `>=`, `<=`)
3. Override `getFunction` for numeric literal parsing

**TREE-OPS applicability (Pattern 3 — Catamorphism):**
The `allDescendants` helper that materialises `descendant_of` and
`leaf_descendant_of` is a textbook catamorphism (bottom-up fold) over the
tree structure. `TreeIndex` already provides `children(nodeId)` which is
the one-level functor step; `allDescendants` composes it recursively.
While the codebase does not use a formal `Fix[F]` / recursion-scheme
library, the *shape* of the computation is a cata:

```
algebra: TreeF[Set[NodeId]] => Set[NodeId]
  LeafF(id)              => Set(id)
  PortfolioF(id, merged) => merged + id   // merged = union of children's sets
```

This insight is recorded for design clarity but does **not** warrant
introducing a recursion-scheme library — direct recursive implementation
via `TreeIndex.children` is idiomatic and sufficient at current scale.
If future tree operations proliferate, factoring the recursion via
TREE-OPS Pattern 3 would reduce duplication across folds.

Public API:
```scala
class RiskTreeKnowledgeBase(tree: RiskTree, results: Map[NodeId, LossDistribution]):
  def toModel(): Model[Any]
  def source: KnowledgeSource     // needed by RangeExtractor
```

**Acceptance:** Unit test — given a hand-built tree + results, assert:
- `source.contains("leaf", RelationTuple.fromConstants("cyber"))` is true
- `source.contains("descendant_of", RelationTuple.fromConstants("cyber", "root"))` is true
- `toModel().interpretation.getFunction("p95")(List("cyber"))` returns expected Long

### T2.5 — Implement `QueryService`

**File:** `modules/server/src/main/scala/.../services/QueryService.scala`

ZIO service layer. Steps:
1. Parse query → `VagueQuery` (fail 400 on parse error)
2. Validate symbols against known schema (fail 400 with available list)
3. Load tree + ensure simulations cached (fail 409 if not)
4. Build `RiskTreeKnowledgeBase`
5. Call `VagueSemantics.holds(query, source, answerTuple, params, config)`
   — returns `VagueQueryResult` including `satisfyingElements`
6. Map to `QueryResponse` via `QueryResponseBuilder.from(result, lookup)`

**Design change (2026-03-16):** The original plan called for bypassing
`VagueSemantics.holds()` and calling `RangeExtractor` +
`ScopeEvaluator.evaluateSample()` directly, with "trivial arithmetic"
for the quantifier check. This was a shortcut that discarded the
statistical sampling infrastructure (no `SampleSizeCalculator`, no
confidence intervals, no HDR determinism) and re-implemented tolerance
logic outside the library.

After TG-1b (evaluation path unification), `VagueSemantics.holds()`
returns `VagueQueryResult` which includes `satisfyingElements`. The
bypass is no longer needed. The register calls a single library method
and gets everything: satisfaction verdict (with tolerance), proportion,
CI, and the actual matching element set.

Three evaluation modes are available:
- **Exact** (default): All range elements evaluated. No `SamplingParams` needed.
- **Sampled**: Pass `SamplingParams(epsilon, alpha)` + `HDRConfig`.
  Library computes sample size statistically. For small trees the
  calculator may return n ≥ N, falling back to exact evaluation.
- **Exact with elements**: Default exact mode already includes
  `satisfyingElements` in the result.

**Acceptance:** Unit test with mocked resolver + tree.

### T2.5b — Refactor `QueryResponseBuilder`

**File:** `modules/server/src/main/scala/.../foladapter/QueryResponseBuilder.scala`

**Status:** File already exists with `from(satisfyingElements: Set[RelationValue], ...)` signature
(written before the TG-1b design change).

After TG-1b, refactor to accept `VagueQueryResult` directly:

```scala
object QueryResponseBuilder:
  def from(
    result: VagueQueryResult,
    nodeIdLookup: Map[String, NodeId],
    queryEcho: String
  ): QueryResponse
```

The builder extracts `satisfyingElements` from the `VagueQueryResult`,
maps `RelationValue.Const(name)` → `NodeId` via `nodeIdLookup`, and
populates all `QueryResponse` fields from the library result.

**Acceptance:** Compiles; unit test verifies NodeId mapping.

### T2.6 — Implement `QueryController`

**File:** `modules/server/src/main/scala/.../http/controllers/QueryController.scala`

Wire endpoint to service. Add auth check. Register in `HttpApi`.

**Acceptance:** Server starts; Swagger shows endpoint.

### T2.7 — OTel instrumentation

**File:** `QueryService.scala`

Wrap `evaluate()` in `ZIO.serviceWithZIO[Tracing]` span with attributes
(query.text, range_size, proportion, satisfied, duration_ms).

**Acceptance:** Trace visible in OTel collector on query execution.

---

## TG-3 — Frontend: Query Pane

Work in `~/projects/register/modules/app`.

### T3.1 — Extend `AnalyzeQueryState`

**File:** `src/main/scala/.../state/AnalyzeQueryState.scala`

Add `queryResult: Var[LoadState[QueryResponse]]`, derived signals
`matchingNodeIds` and `isExecuting`. Add `executeQuery()` method that
fires POST via `ZJS.loadInto`.

**Acceptance:** State compiles; `executeQuery` callable.

### T3.2 — Add `QueryResultCard`

**File:** `src/main/scala/.../views/QueryResultCard.scala` (new)

Composable function: `Signal[LoadState[QueryResponse]] => HtmlElement`.
Shows satisfied badge, proportion bar, count, matching IDs, query echo.
States: Idle, Loading, Failed, Loaded.

**Acceptance:** Renders all states correctly.

### T3.3 — Wire query input + Run button

**File:** `src/main/scala/.../views/AnalyzeView.scala`

Replace text input with monospace `<textarea>`. Add Run button +
Ctrl+Enter shortcut. Button calls `queryState.executeQuery(key, treeId)`.

**Acceptance:** Pressing Run sends POST, result card populates.

### T3.4 — Wire tree node highlighting

**Files:**
- `src/main/scala/.../views/TreeDetailView.scala`
- `styles/app.css`

Add `node-query-matched` CSS class to matching nodes based on
`matchingNodeIds` signal.

**Acceptance:** Matching nodes visually distinct after query.

### T3.5 — Wire "View LEC" cross-link

**File:** `src/main/scala/.../views/QueryResultCard.scala`

"View LEC for N matching nodes" button sets `lecChartState.chartNodeIds`
and triggers chart load.

**Acceptance:** Clicking loads LEC overlay for matching nodes.

---

## TG-4 — Integration Tests

### T4.1 — KB builder tests

**File:** `modules/server/src/test/scala/.../foladapter/RiskTreeKnowledgeBaseSpec.scala` (new)

Test cases:
1. Structural facts: leaf/portfolio/child_of populated correctly
2. Transitive closure: descendant_of/leaf_descendant_of correct for
   3-level tree
3. Simulation functions: p95, lec return expected values
4. Numeric literal: `getFunction("5000000")` parses correctly
5. Empty tree: KB has only schema, no facts

### T4.2 — Endpoint integration tests

**File:** `modules/server-it/src/test/scala/.../QueryEndpointSpec.scala` (new)

Test cases:
1. **Happy path:** Tree with known distributions. POST query. Assert
   satisfied, proportion, matchingNodeIds.
2. **Parse error:** Malformed query → 400 with position
3. **Unknown symbol:** `p96(x)` → 400 with available list
4. **Empty range:** `portfolio(x)` on leaf-only tree → 200,
   rangeSize = 0, satisfied = false
5. **Unary query:** Answer variable `(y)` → matchingNodeIds has
   portfolio IDs
6. **Simulation not cached:** 409 with detail message

**Acceptance:** `sbt serverIt/test` passes all 6.

---

## TG-5 — Polish + Docs

### T5.1 — Predefined query templates

Add dropdown in AnalyzeView with example queries from appendix §8.
Selecting populates textarea.

### T5.2 — Syntax reference panel

Collapsible accordion showing:
- Available functions: `p50`, `p90`, `p95`, `p99`, `lec`
- Available predicates: `leaf`, `portfolio`, `child_of`,
  `descendant_of`, `leaf_descendant_of`, `>`, `<`, `>=`, `<=`
- Operator table: `~`, `>=`, `<=`, tolerance `[ε]`
- Two example queries with explanation

### T5.3 — Update ADR-028 status

Change to `Status: Accepted` after merge.

### T5.4 — WORKING-INSTRUCTIONS update

Add "Query Pane" section to `docs/WORKING-INSTRUCTIONS.md`:
- How to run a query
- Available functions and predicates
- Range predicate guidance (leaf vs descendant_of)
- Double-counting warning

### T5.5 — Register domain usage documentation

Add query-pane usage documentation covering how fol-engine capabilities
are exposed within the risk register domain:
- How queries map to risk tree structures (range = leaf/portfolio/descendant_of)
- How simulation results are exposed as functions (p50, p90, p95, p99, lec)
- API examples: exact evaluation, sampled evaluation for large trees
- Configuration: SamplingParams + HDRConfig (what they control, when to tune)
- End-to-end example: POST query → JSON response → tree highlighting
- Error cases: parse error, unknown symbol, no simulation data

---

## Sequencing

```
Week 1:  TG-1 (library build prep)                            ✅ DONE
         T1.1 → T1.2 → T1.3 → T1.4

Week 2:  TG-1b (fol-engine internal debt)                     🔄 IN PROGRESS
         T1b.1–T1b.5 (sampling infrastructure)                ✅ DONE
         T1b.6–T1b.12 (evaluation path unification)           ← CURRENT
         T1b.13 (fol-engine usage docs)
         T1b.14 (cross-compilation)

Week 3:  TG-2 (server components)
         T2.2 ✅ + T2.3 ✅ (already done)
         T2.4 → T2.5 → T2.6 → T2.7

Week 4:  TG-4 (tests, can overlap with TG-2 tail)
         T4.1 (once T2.4 done) → T4.2 (once T2.6 done)

Week 5:  TG-3 (frontend)
         T3.1 → T3.2 → T3.3 → T3.4 → T3.5

Week 6:  TG-5 (polish + docs)
         T5.1 → T5.2 → T5.3 → T5.4 → T5.5
```

---

## Gate Criteria

| Gate | After | Criteria |
|---|---|---|
| G1 | TG-1 | ✅ 711 unit + 19 IT tests pass at Scala 3.7.4; bats A+B+C pass on rebuilt distroless image |
| G1b | TG-1b | fol-engine: all tests pass (~770+); `VagueSemantics.holds()` returns `VagueQueryResult` with `satisfyingElements`; no `scala.util.Random`; commons-math3 removed; `sbt publishLocal` succeeds |
| G2 | TG-2 | `curl -X POST .../query -d '{"query":"Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))"}'` returns valid JSON |
| G3 | TG-4 | `sbt serverIt/test` passes all 6 integration tests |
| G4 | TG-3 | Type query → result card → tree highlights → LEC overlay |
| G5 | TG-5 | ADR-028 accepted; WORKING-INSTRUCTIONS updated; register domain usage docs complete |

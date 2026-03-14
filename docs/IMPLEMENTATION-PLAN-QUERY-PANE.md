# Implementation Plan: Vague Quantifier Query Pane

**Parent:** [ADR-028](ADR-028-vague-quantifier-query-pane.md) +
[ADR-028 Appendix](ADR-028-appendix-technical-design.md)  
**Scope:** All code changes to ship the query pane end-to-end.

---

## Task Groups

| Group | Name | Depends on | Gate |
|---|---|---|---|
| TG-1 | fol-engine integration build prep | — | ✅ Done — all tests + bats suites pass |
| TG-2 | `RiskTreeKnowledgeBase` + query service | TG-1 | curl returns valid JSON |
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

### T2.2 — Define DTOs in `common`

**Files:**
- `modules/common/src/main/scala/.../http/responses/QueryResultResponse.scala`

**Type:**
```scala
case class QueryResultDTO(
  satisfied: Boolean,
  actualProportion: Double,
  rangeSize: Int,
  sampleSize: Int,
  satisfyingCount: Int,
  matchingNodeIds: List[String],
  queryEcho: String
)
```

With `JsonCodec` (zio-json) and `Schema` (Tapir) derivations.

**Acceptance:** `sbt common/compile` succeeds on JVM and JS.

### T2.3 — Define endpoint in `WorkspaceEndpoints`

**File:** `modules/common/src/main/scala/.../http/endpoints/WorkspaceEndpoints.scala`

Add `queryWorkspaceTreeEndpoint`:
```
POST /w/{key}/risk-trees/{treeId}/query
  in: jsonBody[QueryRequest]   // { "query": String }
  out: jsonBody[QueryResultDTO]
```

**Acceptance:** Endpoint compiles; Swagger docs show it.

### T2.4 — Implement `RiskTreeKnowledgeBase`

**File:** `modules/server/src/main/scala/.../query/RiskTreeKnowledgeBase.scala`

Responsibilities:
1. Build `KnowledgeBase` with structural facts (`leaf`, `portfolio`,
   `child_of`, `descendant_of`, `leaf_descendant_of`)
2. Build `Model[Any]` by augmenting `KnowledgeSourceModel.toModel()`
   with simulation-backed functions (`p50`, `p90`, `p95`, `p99`, `lec`)
   and comparison predicates (`>`, `<`, `>=`, `<=`)
3. Override `getFunction` for numeric literal parsing

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
5. Call `RangeExtractor.extractRange` + `ScopeEvaluator.evaluateSample`
6. Apply quantifier check (trivial arithmetic)
7. Map to `QueryResultDTO`

Uses `evaluateSample` (not `VagueSemantics.holds`) to retain the
satisfying element set for `matchingNodeIds`.

**Acceptance:** Unit test with mocked resolver + tree.

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

Add `queryResult: Var[LoadState[QueryResultDTO]]`, derived signals
`matchingNodeIds` and `isExecuting`. Add `executeQuery()` method that
fires POST via `ZJS.loadInto`.

**Acceptance:** State compiles; `executeQuery` callable.

### T3.2 — Add `QueryResultCard`

**File:** `src/main/scala/.../views/QueryResultCard.scala` (new)

Composable function: `Signal[LoadState[QueryResultDTO]] => HtmlElement`.
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

**File:** `modules/server/src/test/scala/.../query/RiskTreeKnowledgeBaseSpec.scala` (new)

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

---

## Sequencing

```
Week 1:  TG-1 (library build prep)
         T1.1 → T1.2

Week 2:  TG-2 (server components)
         T2.1 → T2.2 + T2.3 (parallel) → T2.4 → T2.5 → T2.6 → T2.7

Week 3:  TG-4 (tests, can overlap with TG-2 tail)
         T4.1 (once T2.4 done) → T4.2 (once T2.6 done)

Week 4:  TG-3 (frontend)
         T3.1 → T3.2 → T3.3 → T3.4 → T3.5

Week 5:  TG-5 (polish)
         T5.1 → T5.2 → T5.3 → T5.4
```

---

## Gate Criteria

| Gate | After | Criteria |
|---|---|---|
| G1 | TG-1 | ✅ 711 unit + 19 IT tests pass at Scala 3.7.4; bats A+B+C pass on rebuilt distroless image |
| G2 | TG-2 | `curl -X POST .../query -d '{"query":"Q[>=]^{2/3} x (leaf(x), >(p95(x), 5000000))"}'` returns valid JSON |
| G3 | TG-4 | `sbt serverIt/test` passes all 6 integration tests |
| G4 | TG-3 | Type query → result card → tree highlights → LEC overlay |
| G5 | TG-5 | ADR-028 accepted; WORKING-INSTRUCTIONS updated |

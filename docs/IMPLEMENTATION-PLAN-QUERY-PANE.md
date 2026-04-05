# Implementation Plan: Vague Quantifier Query Pane

**Parent:** [ADR-028](ADR-028-vague-quantifier-query-pane.md) +
[ADR-028 Appendix](ADR-028-appendix-technical-design.md)  
**Scope:** All code changes to ship the query pane end-to-end.  
**Last updated:** 2026-04-04 (fol-engine 0.9.0-SNAPSHOT adaptation — TypeDecl,
FolModel, LiteralValue, BindError/ModelValidationError/DomainNotFoundError)

---

## Task Groups

| Group | Name | Depends on | Gate |
|---|---|---|---|
| TG-1 | fol-engine integration build prep | — | ✅ Done — all tests + bats suites pass |
| TG-1b | fol-engine internal debt resolution | TG-1 | ✅ Complete (854 tests) — includes generic KB, DSL removal, cross-compilation |
| TG-1c | fol-engine model augmentation API | TG-1b | ✅ Complete — `ModelAugmenter[D]` on legacy `evaluate()`/`holds()`; **superseded by `fol.typed` many-sorted pipeline in 0.3.0** |
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

**File:** `register/build.sbt`

Originally added to `serverDependencies` as `%% "fol-engine" % "0.1.0-SNAPSHOT"`.
After TG-1b cross-compilation, moved to `common` crossProject `.settings()`.
Bumped to `0.9.0-SNAPSHOT` for the `fol.typed` many-sorted type system,
`TypeDecl` ADT, `FolModel` smart constructor, and `LiteralValue` pipeline:

```scala
"com.risquanter" %%% "fol-engine" % "0.9.0-SNAPSHOT"
```

Available on both JVM and JS. Server gets it transitively through `common`.

---

## TG-1b — fol-engine Internal Debt Resolution ✅

**Status: Complete.** Work in `~/projects/vague-quantifier-logic`.
854 tests passing. Published as `0.2.0-SNAPSHOT` (JVM + JS).
Remaining: T1b.13 (README docs) — non-blocking.

This task group resolved technical debt in fol-engine so that
register can consume the library correctly. The 0.2.0 release
included several changes beyond the original plan scope:

1. **Sampling degradation (resolved T1b.1–T1b.5):** The string-parser
   path used `scala.util.Random` instead of the HDR + statistical
   pipeline.
2. **Evaluation path unification (resolved T1b.15–T1b.22):** String
   path compiles `ParsedQuery` → `ResolvedQuery[D]` via
   `VagueSemantics.toResolved()`. Package rename `vague.*` → `fol.*`
   complete.
3. **Generic `KnowledgeBase[D]` (0.2.0):** Replaced stringly-typed
   `DomainValue` with a fully generic datastore/eval pipeline. New
   types: `DomainElement[D]`, `DomainCodec[D]`, `RelationTuple[D]`.
   `RelationValue` remains as a built-in domain type with pre-existing
   given instances. See fol-engine ADR-007, ADR-008.
4. **Bridge decomposition (0.2.0):** `NumericAugmenter` decomposed into
   `ArithmeticAugmenter`, `ComparisonAugmenter`, `LiteralResolver`.
   `NumericAugmenter.augmenter` remains as a composed convenience for
   `RelationValue`. See fol-engine ADR-009.
5. **DSL removal (0.2.0):** Typed Query DSL (`UnresolvedQuery`, etc.)
   deleted. `ResolvedQuery.fromRelation` is the sole programmatic entry
   point. See fol-engine ADR-011.
6. **`RelationName` opaque type (0.2.0):** Relation names are now
   `RelationName` (opaque over `String`), constructed via
   `RelationName("name")`, extracted via `.value`. See fol-engine ADR-010.
7. **Cross-compilation (0.2.0):** JVM + JS via `crossProject`. All
   `scala.util.Random` usages removed (dead `sampleDomain`/
   `sampleActiveDomain` methods + demo code). T1b.14 complete.

**Design document:** [`vague-quantifier-logic/docs/ADR-001.md`](../../vague-quantifier-logic/docs/ADR-001.md)
(ADR-001: Evaluation Path Unification — architecture, trace diagram, gap analysis)

### Completed sub-tasks

| Task | What | Status |
|---|---|---|
| T1b.1 | `NormalApprox` — replace commons-math3 inverse-normal with pure-Scala Acklam + A&S | ✅ Done (17 tests) |
| T1b.2 | Split `SamplingParams` → `SamplingParams(epsilon, alpha)` + `HDRConfig(entityId, varId, seed3, seed4)` | ✅ Done |
| T1b.3 | Rewrite `HDRSampler` — Fisher-Yates shuffle with HDR PRNG, eliminate `scala.util.Random` | ✅ Done (41 tests) |
| T1b.4 | Remove `UniformSampler` / `StratifiedSampler` | ✅ Done |
| T1b.5 | Update all typed-DSL source + test files to new `SamplingParams` + `HDRConfig` API | ✅ Done (768 tests pass) |

### Completed sub-tasks — Evaluation path unification (Approach 2)

These tasks unify the two evaluation paths into a single pipeline so that
string-parsed queries and typed-DSL queries use identical sampling,
tolerance, and result semantics. See the
[design document](../../vague-quantifier-logic/docs/ADR-001.md)
for full rationale and implementation steps.

| Task | What | Status | Est. |
|---|---|---|---|
| T1b.6 | Unify quantifier types: `Quantifier` (ratio) ↔ `VagueQuantifier` (percentage) — `toQuantifier`/`fromQuantifier` bridge, `tolerance` param | ✅ Done | — |
| T1b.7 | Create unified `VagueQueryResult` with `fromEstimate` factory (replaces 3 prior result types) | ✅ Done | — |
| T1b.8 | Bridge FOL scope formulas into typed predicates (`FOLBridge` — `Formula → RelationValue => Boolean` closure over `Model[Any]`) | ✅ Done | — |
| T1b.9 | Rewrite `VagueSemantics` as thin facade (no `selectSample`, `checkQuantifier`, `EvaluationParams`, `scala.util.Random`) | ✅ Done | — |
| T1b.10 | Update `VagueQuery[A].evaluate` to return `VagueQueryResult` directly | ✅ Done | — |
| T1b.11 | Remove deprecated KB wrappers (`holdsOnKB`, `holdsExactOnKB`, `holdsWithSamplingOnKB`) | ✅ Done | — |
| T1b.12 | Build cleanup: remove `commons-math3` and `simulation.util` from `build.sbt` | ✅ Done | — |
| T1b.13 | fol-engine usage documentation: `README.md` examples covering three evaluation modes (exact, sampled with HDR + CI, exact with satisfying element set) with semantic context for new users | Not done | 1.0h |
| T1b.14 | Cross-compile fol-engine (JVM + JS) — enables `common` crossProject dependency in register | ✅ Done | — |

### Completed sub-tasks — True unification (string path → shared IL)

These tasks closed the gap identified during architecture review
(2026-03-18): the string path must compile `ParsedQuery` into
a `ResolvedQuery`, and the typed DSL must resolve
`UnresolvedQuery` into a `ResolvedQuery`, so that both paths converge on
one concrete shared IL with one evaluator. See the
[design document](../../vague-quantifier-logic/docs/ADR-001.md)
§ Gap Analysis for the full comparison of plan vs achieved state.

**Key design decisions:**
- **D5:** `ResolvedQuery` is an explicit concrete shared IL type (always
  `RelationValue`), not a `DomainSpec.Resolved` variant. Phase separation
  is in the type system.
- **D6:** Phase-based query naming: `ParsedQuery` / `UnresolvedQuery` /
  `ResolvedQuery`. The adjective describes the pipeline phase, not the
  query kind (all three are vague quantifier queries).
- **D7:** "Vague" reserved for types modelling the paper's constructs
  (`VagueQuantifier`, `VagueQueryResult`, `VagueQueryParser`, `VagueSemantics`).
  Not used for query phase types, error/result-monad types, or namespace.
- **D8:** Phase 1 renames done: `ParsedQuery`, `QueryError`, `QueryException`,
  `QueryResult`. Phase 2 rename (`UnresolvedQuery`) ships with T1b.15.
  Package rename (`vague.*` → `fol.*`) deferred to T1b.22.
- **D9:** ~~Drop type parameter `[A]`.~~ **Superseded by 0.3.0:** The
  `fol.typed` many-sorted type system replaces the generic `D`-parameterised
  pipeline for register's use case. Register no longer uses
  `KnowledgeBase[D]`, `KnowledgeSource[D]`, `DomainElement[D]`, or
  `DomainCodec[D]`. Instead: `TypeCatalog` (sort declarations + function/
  predicate signatures), `RuntimeModel` (sort-tagged `Value` domains +
  `RuntimeDispatcher`), and `VagueSemantics.evaluateTyped()` returning
  `EvaluationOutput[Value]`. The legacy `[D]` API is preserved in the
  library for backwards compatibility but register uses the typed path.
- **D10:** Simulation data (LEC curves, p95, etc.) flows through the
  `RuntimeDispatcher` function/predicate dispatch, not through a
  `ModelAugmenter` or `Model[D]` function table.
  `RiskTreeKnowledgeBase` provides a `RuntimeDispatcher` that implements
  `evalFunction` for `p95`, `lec`, etc. (backed by `LossDistribution`
  results) and `evalPredicate` for `gt_loss`, `gt_prob` (native
  `Long`/`Double` comparisons) and structural predicates (`leaf`,
  `portfolio`, `child_of`). The many-sorted type system uses **sort-
  specific predicate names** (`gt_loss`, `gt_prob`) instead of the
  overloaded `>` — `TypeCatalog.predicates` maps each `SymbolName` to
  a single `PredicateSig(paramSorts)`, so a single `>` cannot serve
  two different sort signatures. The engine evaluates
  `gt_loss(p95(x), 5000000)` by dispatching to the `RuntimeDispatcher`.
  Literal validation (`"5000000"` as Loss, `"0.05"` as Probability)
  is handled by `TypeCatalog.literalValidators` with sort inference
  from argument position.
- **D11:** No `useSampling: Boolean` toggle on `ResolvedQuery.evaluate()`
  or `VagueSemantics.holds()`. One code path — `SamplingParams` controls
  whether evaluation is exact or sampled. `SamplingParams.exact`
  (ε = 1e-6) forces n = N via the sample size formula for any population
  under ~10¹². The "exact mode" toggle is a **service-level** concern
  in register: `if exactMode then SamplingParams.exact else SamplingParams.default`.
  T1b.16 is **ELIMINATED** — merged into params behavior. See
  `ADR-001.md` §Decision for the full rationale.

| Task | What | Status | Est. |
|---|---|---|---|
| T1b.15 | Create concrete `ResolvedQuery` in `fol.query` — shared IL with `evaluate()` and `evaluateWithOutput()`. Fields: `quantifier: VagueQuantifier`, `elements: Set[RelationValue]`, `predicate: RelationValue => Boolean`, `params: SamplingParams`, `hdrConfig: HDRConfig`. No `KnowledgeSource` dependency. No type parameter. No `useSampling` boolean (D11). Rename `VagueQuery[A]` → `UnresolvedQuery` (concrete, `RelationValue`). Remove `toDomainSetTyped` unwrap — DSL keeps `RelationValue` end-to-end. Add `.whereConst` convenience on builder. | ✅ Done | — |
| ~~T1b.15b~~ | ~~Rename `vague.logic.VagueQuery` → `ParsedQuery`~~ | ✅ Done | — |
| ~~T1b.16~~ | ~~Add exact/sampled mode toggle~~ | **ELIMINATED** (D11) — `SamplingParams` controls mode; `SamplingParams.exact` forces n=N | — |
| T1b.18 | Rewrite `VagueSemantics`: add private `toResolved()` that constructs `ResolvedQuery` from `ParsedQuery` via Steps A1-A5. `holds()` calls `toResolved(...).evaluate()`. True delegation — no direct `VagueQuantifier` calls. | ✅ Done | — |
| T1b.19 | Add `ProportionEstimator.estimateFromCount(successes: Int, sampleSize: Int, params)` — builds estimate from pre-counted integers without re-iterating | ✅ Done | — |
| T1b.20 | Create concrete `EvaluationOutput` in `fol.result` — `result: VagueQueryResult` + `rangeElements: Set[RelationValue]` + `satisfyingElements: Set[RelationValue]`. No type parameter. Add `evaluateWithOutput()` to `ResolvedQuery`. In sampled mode, `satisfyingElements` contains sample-only elements (D2B). | ✅ Done | — |
| T1b.21 | Add `VagueSemantics.evaluate()` returning `EvaluationOutput` — the element-aware API that register will call. `holds()` remains as statistics-only convenience. | ✅ Done | — |
| T1b.22 | Bulk package rename `vague.*` → `fol.*`. Type renames (`QueryError`, `QueryException`, `QueryResult`) already done. Mechanical — all references + tests. | ✅ Done | — |

**Note:** T1b.17 (handle `Resolved` in `UnresolvedQuery.evaluate` match)
was **eliminated** — there is no `DomainSpec.Resolved` variant. The
resolution step produces `ResolvedQuery`, a separate type. The match
arm is not needed.

**Gate G1b:** ✅ Met. fol-engine: 868 tests pass. Legacy
`VagueSemantics.evaluate[D]()` returns `EvaluationOutput[D]` with
`satisfyingElements`. New `VagueSemantics.evaluateTyped()` returns
`EvaluationOutput[Value]` via the `fol.typed` many-sorted pipeline.
String path compiles `ParsedQuery` → `ResolvedQuery[D]` via
`toResolved()` (legacy) or `ParsedQuery` → `BoundQuery` via
`QueryBinder.bind()` (typed). Typed DSL removed (ADR-011) —
`ResolvedQuery.fromRelation` is the sole programmatic entry point for
the legacy path. Generic `KnowledgeBase[D]` with `DomainElement`/
`DomainCodec` type classes preserved for backwards compatibility.
`RelationName` opaque type. Cross-compiled JVM + JS. No
`scala.util.Random` anywhere. `commons-math3` and `simulation.util`
removed from build.

**Acceptance:** ✅ `sbt test` passes (868 tests). `sbt publishLocal`
produces artifact consumable by register (JVM + JS). `0.3.0-SNAPSHOT`
integrated in register `common` crossProject — all register tests pass.

---

## TG-1c — fol-engine Model Augmentation API ✅

**Status: Complete.** Delivered as part of fol-engine 0.2.0-SNAPSHOT.
See fol-engine ADR-005 (Model Augmentation) and ADR-009 (Bridge
Decomposition).

**Note (2026-03-27):** TG-1c solved the augmentation problem for the
**legacy** `[D]`-parameterised pipeline. The `fol.typed` many-sorted
type system (0.3.0) supersedes this approach for register's use case:
instead of `ModelAugmenter[D]`, register now provides a
`RuntimeDispatcher` that handles function evaluation (`p95`, `lec`) and
predicate evaluation (`gt_loss`, `gt_prob`, `leaf`, `child_of`, etc.)
natively. The legacy `ModelAugmenter` API is preserved in the library
for backwards compatibility but is **not used** by register.

**Original problem:** Register needs to inject simulation-backed functions
(`p50`, `p90`, `p95`, `p99`, `lec`) and comparison predicates into the
evaluation model — these are backed by register's `LossDistribution`
results, not by KB facts.

**Solution in 0.2.0 (legacy):** `ModelAugmenter[D]` — a composable case
class wrapping `Model[D] => Model[D]`.

**Solution in 0.3.0 (current):** `RuntimeDispatcher` trait with
`evalFunction(name: SymbolName, args: List[Value]): Either[String, Value]`
and `evalPredicate(name: SymbolName, args: List[Value]): Either[String, Boolean]`.
Register implements this trait to back `p95`/`lec` with
`LossDistribution` data and `gt_loss`/`gt_prob` with native
`Long`/`Double` comparisons. Sort-specific predicate names (`gt_loss`
instead of `>`) are required because `TypeCatalog.predicates` maps each
`SymbolName` to a single `PredicateSig(paramSorts)`.

**Original prompt:** [`docs/PROMPT-FOL-ENGINE-MODEL-AUGMENTATION.md`](PROMPT-FOL-ENGINE-MODEL-AUGMENTATION.md)
(written pre-0.2.0 — the library agent implemented `ModelAugmenter[D]`,
then evolved to the `fol.typed` many-sorted system)

**Acceptance:** ✅ Legacy: `ModelAugmenter[D]` works (868 tests).
Current: `RuntimeDispatcher` + `TypeCatalog` + `VagueSemantics.evaluateTyped()`
works (868 tests including 9 typed-pipeline tests).

---

## TG-2 — Server: `RiskTreeKnowledgeBase` + Endpoint

Work in `~/projects/register`.

### T2.1 — Add library dependency ✅

**File:** `build.sbt` (`common` crossProject settings)

Originally added to `serverDependencies` (TG-1, T1.4). After fol-engine
0.2.0 cross-compilation, moved to `common` crossProject `.settings()`.
Bumped to `0.9.0-SNAPSHOT` for the `fol.typed` many-sorted type system,
`TypeDecl` ADT, `FolModel` smart constructor, and `LiteralValue` pipeline:

```scala
"com.risquanter" %%% "fol-engine" % "0.9.0-SNAPSHOT"
```

Available on both JVM and JS (same pattern as `zio-ulid`). Server gets
it transitively through `common`.

**Acceptance met:** `sbt server/compile` and `sbt commonJS/compile` succeed;
`sbt server/test` passes (386 tests); `sbt commonJVM/test` passes (391
tests). `VagueQueryParser`, `ParsedQuery`, `TypeCatalog`, `RuntimeModel`,
`Value`, `FolModel`, `TypeDecl`, `LiteralValue`, and all library types
are importable on both platforms.

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
    * After TG-1b, this method receives an EvaluationOutput from the
    * library (which includes satisfyingElements via VagueQueryResult). No direct access to
    * ScopeEvaluator or RangeExtractor is needed.
    *
    * NOTE: QueryResponseBuilder.scala already exists in
    * modules/server/src/main/scala/.../foladapter/ with the old
    * Set[RelationValue] signature. It will be refactored in T2.5b
    * to accept EvaluationOutput instead.
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

  /** Inbound parse boundary (ADR-001 §1 — parse-don't-validate).
    *
    * Wraps `VagueQueryParser.parse()` — the library's syntactic parser.
    * Returns the library's `fol.error.QueryError` on failure (not register's
    * `FolQueryFailure`); the controller maps the error type at the wiring
    * layer, keeping this DTO free of `AppError` dependencies.
    *
    * Analogous to `RiskTreeDefinitionRequest.resolve()` returning
    * `Validation[ValidationError, ResolvedCreate]` — the DTO owns the
    * parse step, the controller owns the error mapping.
    */
  def resolve(req: QueryRequest): Either[fol.error.QueryError, ParsedQuery] =
    VagueQueryParser.parse(req.query)
```

**Design decision D12 (2026-03-26):** `resolve()` returns the **library
type** `Either[fol.error.QueryError, ParsedQuery]`, not register's
`FolQueryFailure`. This mirrors how `RiskTreeDefinitionRequest.resolve()`
returns `Validation[ValidationError, ...]` — a domain-specific error, not
`AppError` — with the controller doing the final mapping. Keeping
`resolve()` free of `AppError` avoids a circular dependency from the DTO
companion into the error hierarchy.

**Acceptance:** `sbt common/compile` succeeds on JVM and JS;
`QueryRequest.resolve(QueryRequest("Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x), 5000000))"))` returns `Right(ParsedQuery(...))`;
`QueryRequest.resolve(QueryRequest("garbage"))` returns `Left(ParseError(...))`.

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

### T2.3b — Add `FolQueryFailure` to error hierarchy

**Files:**
- `modules/common/src/main/scala/.../errors/AppError.scala`
- `modules/common/src/main/scala/.../errors/ErrorResponse.scala`

Add a new `FolQueryFailure` branch to the sealed `AppError` hierarchy
(alongside `SimError`, `IrminError`, `AuthError`). The `Fol` prefix
identifies errors originating from fol-engine interaction; the `Failure`
suffix follows the existing `RepositoryFailure` / `SimulationFailure`
naming convention (D13).

```scala
sealed trait FolQueryFailure extends AppError

object FolQueryFailure:
  /** Maps from fol.error.QueryError.ParseError / LexicalError.
    * Syntactic parse failure — query string is not well-formed.
    */
  final case class FolParseFailure(message: String, position: Option[Int])
    extends FolQueryFailure:
    override def getMessage: String =
      position.fold(message)(p => s"$message (at position $p)")

  /** Maps from fol.error.QueryError.RelationNotFoundError /
    * UninterpretedSymbolError / SchemaError.
    * Well-formed query references unknown predicates or functions.
    */
  final case class FolUnknownSymbol(symbol: String, available: List[String])
    extends FolQueryFailure:
    override def getMessage: String =
      s"Unknown symbol '$symbol'. Available: ${available.mkString(", ")}"

  /** Maps from fol.error.QueryError.EvaluationError /
    * ScopeEvaluationError / TypeMismatchError / TimeoutError / etc.
    * Catch-all for unexpected evaluation-phase failures.
    */
  final case class FolEvaluationFailure(message: String, phase: String)
    extends FolQueryFailure:
    override def getMessage: String = s"Evaluation failed in $phase: $message"

  /** Register precondition — no Fol prefix.
    * Simulation results not yet computed for the requested tree.
    */
  final case class SimulationNotCached(treeId: TreeId)
    extends FolQueryFailure:
    override def getMessage: String =
      s"Simulation not cached for tree ${treeId.value}"
```

In `ErrorResponse.scala`, add `encodeFolQueryFailure` exhaustive matcher:
- `FolParseFailure` → 400 (`{ "error": "parse_error", "detail": ..., "position": ... }`)
- `FolUnknownSymbol` → 400 (`{ "error": "unknown_symbol", "symbol": ..., "available": [...] }`)
- `FolEvaluationFailure` → 500 (`{ "error": "evaluation_failed", "detail": ..., "phase": ... }`)
- `SimulationNotCached` → 409 (`{ "error": "simulation_required", "detail": ... }`)

Update the `encode` dispatch to include
`case e: FolQueryFailure => encodeFolQueryFailure(e)`.
Compiler-enforced exhaustive matching per ADR-022.

**Design decision (2026-03-23):** Query errors do NOT belong under
`SimError` — queries are a separate domain concern. A dedicated sealed
trait gives compiler-enforced exhaustive matching and clean HTTP mapping
without polluting the simulation error namespace.

**Design decision D13 (2026-03-26) — naming convention:** The `Fol`
prefix identifies all error types that map from `fol.error.QueryError`
variants (the library's error algebra). This avoids a naming collision:
`fol.error.QueryError` is the library type (used in `Either` returns
from `VagueQueryParser.parse()` and `VagueSemantics.evaluate()`), while
`FolQueryFailure` is register's HTTP error dispatch type. The `Failure`
suffix follows the existing `RepositoryFailure` / `SimulationFailure`
pattern in `AppError.scala`. `SimulationNotCached` has no `Fol` prefix
because it is a register precondition — no fol-engine involvement.
The `Auth` prefix pattern (`AuthForbidden`, `AuthServiceUnavailable`
under `AuthError`) and `Irmin` prefix pattern (`IrminUnavailable`,
`IrminHttpError` under `IrminError`) confirm this convention.

**Design decision (2026-03-23) — empty range:** A query that resolves to
an empty range (e.g., `child_of(x, cyber)` where `cyber` is a leaf node
with no children) is **not an error**. It returns HTTP 200 with
`rangeSize: 0`, `satisfied: false`, `proportion: 0.0`. This is valid
data — the query executed correctly; there are simply no elements in the
range. No error type is needed for this case.

**fol.error.QueryError → FolQueryFailure mapping table:**

| Library type (`fol.error.QueryError.*`) | Register type (`FolQueryFailure.*`) | HTTP | `ValidationErrorCode` |
|---|---|---|---|
| `ParseError`, `LexicalError` | `FolParseFailure` | 400 | `PARSE_ERROR` |
| `RelationNotFoundError`, `UninterpretedSymbolError`, `SchemaError` | `FolUnknownSymbol` | 400 | `UNKNOWN_SYMBOL` |
| `BindError` | `FolBindFailure` | 400 | `BIND_FAILED` |
| `DomainNotFoundError` *(defensive fallback — D14)* | `FolDomainNotQuantifiable` | 400 | `DOMAIN_NOT_QUANTIFIABLE` |
| `ModelValidationError` | `FolModelValidationFailure` | 500 | `MODEL_VALIDATION_FAILED` |
| `EvaluationError`, `ScopeEvaluationError`, `TypeMismatchError`, `TimeoutError`, `QuantifierError` | `FolEvaluationFailure` | 500 | `EVALUATION_FAILED` |
| *(register precondition)* | `SimulationNotCached` | 409 | `SIMULATION_REQUIRED` |

**Design decision D14 (2026-03-27, updated 2026-04-04) — domain-only
quantification:** FOL queries can only quantify over *entity types* — types
that represent finite, enumerable collections of domain objects. In
register's risk-tree schema the only entity type is `Asset` (the set of
tree node names). Types such as `Loss` (Long) and `Probability` (Double)
are *output types*: they appear as function return values (e.g.
`p95: Asset → Loss`) and are computed, not enumerated. A query like
`∀x: Loss. …` is semantically meaningless because there is no finite
domain to iterate.

**Resolved (0.9.0-SNAPSHOT):** fol-engine now enforces this at **two**
levels:

1. **Bind phase (Phase 2):** `TypeCheckError.TypeNotQuantifiable` rejects
   quantification over `ValueType` sorts at bind time, surfacing as
   `QueryError.BindError` → `FolBindFailure` → HTTP 400. This is the
   primary enforcement per ADR-001 (parse-don't-validate).
2. **Evaluation phase (Phase 1 — defensive fallback):**
   `QueryError.DomainNotFoundError` catches the case where evaluation
   reaches a type with no registered domain. In a correctly wired system
   this is unreachable — the bind phase rejects it first. Mapped to
   `FolDomainNotQuantifiable` → HTTP 400.

The `TypeDecl` ADT (`DomainType` / `ValueType`) in `TypeCatalog.types`
declares which types are quantifiable. `FolModel` smart constructor
validates catalog+model pairing once at construction. See fol-engine
ADR-014 and ADR-015.

**Acceptance:** `sbt common/compile` succeeds; exhaustive match on
`FolQueryFailure` is compiler-checked.

### T2.4 — Implement `RiskTreeKnowledgeBase`

**File:** `modules/server/src/main/scala/.../foladapter/RiskTreeKnowledgeBase.scala`

**Updated 2026-04-04** for fol-engine `0.9.0-SNAPSHOT` (`TypeDecl` ADT,
`FolModel` smart constructor, `LiteralValue` pipeline).

Responsibilities:
1. Build a `TypeCatalog` declaring register's many-sorted schema:
   - **Types:** `DomainType(Asset)`, `ValueType(Loss)`,
     `ValueType(Probability)`, `ValueType(Bool)` — `TypeDecl` ADT
     distinguishes quantifiable domain types from computed value types
   - **Functions:** `p95: Asset → Loss`, `p99: Asset → Loss`,
     `lec: (Asset, Loss) → Probability`
   - **Predicates:** `leaf: (Asset)`, `portfolio: (Asset)`,
     `child_of: (Asset, Asset)`, `descendant_of: (Asset, Asset)`,
     `leaf_descendant_of: (Asset, Asset)`,
     `gt_loss: (Loss, Loss)`, `gt_prob: (Probability, Probability)`
   - **Literal validators:** Loss → `Some(IntLiteral(s.toLong))`,
     Probability → `Some(FloatLiteral(s.toDouble))` — returns
     `Option[LiteralValue]` (parsed typed literal), not `Boolean`
2. Build a `RuntimeModel` containing:
   - **Domains:** `Asset → Set[Value]` (one `Value(asset, nodeName)` per
     tree node) — only `DomainType` sorts have registered domains
   - **Dispatcher:** A `RuntimeDispatcher` that implements:
     - `evalFunction("p95", [assetVal])` → returns
       `LiteralValue.IntLiteral(p95Long)` (not `Value`)
     - `evalFunction("lec", [assetVal, lossVal])` → returns
       `LiteralValue.FloatLiteral(probOfExceedance)`
     - `evalPredicate("leaf", [assetVal])` → true if asset is a leaf
     - `evalPredicate("gt_loss", [a, b])` → extracts `LiteralValue.IntLiteral`
       from `Value.raw`, compares
     - `evalPredicate("gt_prob", [a, b])` → extracts `LiteralValue.FloatLiteral`
       from `Value.raw`, compares
     - `evalPredicate("child_of", [a, b])` → structural lookup
     - etc. for `descendant_of`, `leaf_descendant_of`, `portfolio`

**Sort-specific predicates:** The many-sorted type system uses
sort-specific predicate names (`gt_loss`, `gt_prob`) instead of the
overloaded `>`. This is required because `TypeCatalog.predicates` maps
each `SymbolName` to a single `PredicateSig(paramSorts)` — a single name
cannot serve two different sort signatures. User-facing query syntax
is `gt_loss(p95(x), 5000000)` not `>(p95(x), 5000000)`.

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
import fol.typed.{TypeCatalog, RuntimeModel, RuntimeDispatcher, TypeId, SymbolName, Value}

class RiskTreeKnowledgeBase(tree: RiskTree, results: Map[NodeId, LossDistribution]):
  def catalog: TypeCatalog
  def model: RuntimeModel
```

**Acceptance:** Unit test — given a hand-built tree + results, assert:
- `model.domains(TypeId("Asset"))` contains `Value(asset, "cyber")`
- `model.dispatcher.evalPredicate(SymbolName("leaf"), List(Value(asset, "cyber")))` returns `Right(true)`
- `model.dispatcher.evalFunction(SymbolName("p95"), List(Value(asset, "cyber")))` returns expected `Value(loss, ...)`
- `catalog.predicates.contains(SymbolName("gt_loss"))` is true

### T2.5 — Implement `QueryService`

**File:** `modules/server/src/main/scala/.../services/QueryService.scala`

ZIO service layer. The service signature accepts `ParsedQuery` (already
parsed at the HTTP boundary via `QueryRequest.resolve()` in the
controller — see D12). **No parsing or raw-string validation happens
in the service layer** (ADR-001 §4: "No validation in service methods").

Service signature:
```scala
trait QueryService:
  def evaluate(treeId: TreeId, parsed: ParsedQuery): Task[QueryResponse]
```

Steps:
1. Load tree + ensure simulations cached (fail with `SimulationNotCached` → 409)
2. Build `RiskTreeKnowledgeBase` → get `catalog: TypeCatalog` + `model: RuntimeModel`
3. Construct `FolModel(kb.catalog, kb.model)` — validated pairing (smart
   constructor). Returns `Left(ModelValidationError)` if dispatcher
   coverage or domain registration gaps exist → `FolModelValidationFailure`
   → 500.
4. Call `VagueSemantics.evaluateTyped(parsed, folModel, answerTuple, params, hdrConfig)`
   — `evaluateTyped` internally: `bindTyped(parsed, folModel.catalog)` →
     sort-checks the query (returns `BindError` → `FolBindFailure` → 400
     if type-check fails, e.g. `TypeNotQuantifiable` for non-domain
     quantification) → `TypedSemantics.evaluate(bound, folModel.model, ...)`
     → returns `EvaluationOutput[Value]` containing
     `result: VagueQueryResult` + `rangeElements: Set[Value]` +
     `satisfyingElements: Set[Value]`
   — `Value(sort: TypeId, raw: Any)` — sort-tagged runtime values;
     for domain elements `raw` is the domain type (e.g. `String`), for
     computed values `raw` is a `LiteralValue` (e.g. `IntLiteral`,
     `FloatLiteral`)
   — on `Left(fol.error.QueryError)`, map via `FolQueryFailure.fromQueryError`
     to the appropriate `FolQueryFailure` subtype
5. Map to `QueryResponse` via `QueryResponseBuilder.from(output, lookup)`

**Design change (2026-03-16):** The original plan called for bypassing
`VagueSemantics.holds()` and calling `RangeExtractor` +
`ScopeEvaluator.evaluateSample()` directly. This is no longer needed.

**Architecture note (2026-03-27, updated 2026-04-04):** ~~The 0.2.0
approach used `VagueSemantics.evaluate[RelationValue]()` with
`ModelAugmenter[D]`.~~ **Superseded by 0.9.0:** Register now uses the
`fol.typed` many-sorted pipeline via `VagueSemantics.evaluateTyped()`.
This takes a `FolModel` (validated pairing of `TypeCatalog` + `RuntimeModel`,
constructed via smart constructor) and returns `EvaluationOutput[Value]`.
No `DomainElement[D]`, `DomainCodec[D]`, `KnowledgeSource[D]`, or
`ModelAugmenter[D]` are needed. Key 0.9.0 changes:
- `TypeCatalog.types` uses `Set[TypeDecl]` (`DomainType`/`ValueType`)
  instead of `Set[TypeId]`
- `literalValidators` returns `Option[LiteralValue]` instead of `Boolean`
- `evalFunction` returns `LiteralValue` instead of `Value`
- `FolModel` smart constructor validates catalog+model once at construction
- New error types: `BindError`, `ModelValidationError`, `DomainNotFoundError`

**Architecture clarification (2026-03-18, updated 2026-03-27):** The
library exposes three facade methods:
- `VagueSemantics.holds[D]()` → `Either[QueryError, VagueQueryResult]`
  (legacy — statistics only)
- `VagueSemantics.evaluate[D]()` → `Either[QueryError, EvaluationOutput[D]]`
  (legacy — statistics + element sets)
- `VagueSemantics.evaluateTyped()` → `Either[QueryError, EvaluationOutput[Value]]`
  (**current** — many-sorted typed pipeline)

Register uses `evaluateTyped()` because it provides both the element sets
(for tree highlighting) and sort-safe evaluation (no `Int` truncation
for `Long` losses or `Double` probabilities).

Two evaluation modes:
- **Exact** (default): All range elements checked. Deterministic.
  `satisfyingElements` = complete set of matches.
- **Sampled**: `SampleSizeCalculator` computes n, `HDRSampler` draws
  via Fisher-Yates. `satisfyingElements` = matches from the **sample
  only** (not the full range). User switches to exact mode for
  exhaustive results.

**Acceptance:** Unit test with mocked resolver + tree.

### T2.5b — Refactor `QueryResponseBuilder`

**File:** `modules/server/src/main/scala/.../foladapter/QueryResponseBuilder.scala`

**Status:** File already exists with `from(satisfyingElements: Set[RelationValue], ...)` signature
(written before the TG-1b design change).

**Updated 2026-03-27** for `fol.typed` many-sorted type system.

Refactor to accept `EvaluationOutput[Value]`:

```scala
import fol.result.EvaluationOutput
import fol.typed.{Value, TypeId, TypeRepr}

object QueryResponseBuilder:

  private val assetSort = TypeId("Asset")

  given TypeRepr[String] with
    val typeId = assetSort

  def from(
    output: EvaluationOutput[Value],
    nodeIdLookup: Map[String, NodeId],
    queryEcho: String
  ): QueryResponse
```

The builder extracts `satisfyingElements` from `EvaluationOutput`,
uses `Value.as[String]` (with `TypeRepr[String]` targeting the `Asset`
sort) to project node name strings, then maps via `nodeIdLookup` →
`NodeId`. Populates `QueryResponse` fields from `output.result` (the
`VagueQueryResult` with statistics).

Additionally, **remove the `Set[Any]` factory** from
`QueryResponse` in `common`. Once the typed
`QueryResponseBuilder.from(EvaluationOutput[Value], ...)` is the
sole construction site, the untyped `QueryResponse.from(Set[Any], ...)`
becomes dead code. `QueryResponse` itself (the DTO) stays in `common`
for JS access — only the untyped factory goes away.

**Acceptance:** Compiles; unit test verifies NodeId mapping via
`Value.as[String]` projection; `QueryResponse.from(Set[Any], ...)` no
longer exists.

### T2.6 — Implement `QueryController`

**File:** `modules/server/src/main/scala/.../http/controllers/QueryController.scala`

The controller is "dumb" per ADR-001 — it calls `QueryRequest.resolve()`
at the HTTP boundary, maps the library error to `FolQueryFailure`, then
forwards the typed `ParsedQuery` to the service:

```scala
val queryTree = queryWorkspaceTreeEndpoint.serverLogic { case (authCtx, (key, treeId, req)) =>
  QueryRequest.resolve(req) match
    case Left(parseErr) =>
      ZIO.fail(FolQueryFailure.mapParseError(parseErr))
    case Right(parsed) =>
      queryService.evaluate(treeId, parsed)
}
```

The `FolQueryFailure.mapParseError` helper centralises the
`fol.error.QueryError → FolParseFailure` mapping (see T2.3b mapping
table). The controller never inspects query content — it wires
`resolve()` → service, nothing more.

Add auth check. Register in `HttpApi`.

**Acceptance:** Server starts; Swagger shows endpoint;
malformed query returns 400 before reaching `QueryService`.

### T2.7 — OTel instrumentation

**File:** `QueryService.scala`

Wrap `evaluate()` in `ZIO.serviceWithZIO[Tracing]` span with attributes
(query.text, range_size, proportion, satisfied, duration_ms).

**Acceptance:** Trace visible in OTel collector on query execution.

---

## TG-3 — Frontend: Query Pane

Work in `~/projects/register/modules/app`.

**Data flow clarification (2026-03-23):** The query field in the Analyze
view accepts fol-engine's **string path syntax** (the `ParsedQuery` entry
point). Queries run against the **tree selected in the Analyze view's
dropdown** — that tree selection, combined with its cached simulation
results, becomes the `TypeCatalog` + `RuntimeModel` for typed evaluation.
The execution flow is:

1. User selects tree in Analyze view dropdown (`TreeViewState.selectedTreeId`)
2. User types query in textarea
3. **Client-side parse validation:** `VagueQueryParser.parse()` runs
   in-browser (fol-engine is cross-compiled to JS via `common`) giving
   instant syntax error feedback — red underline, error position, error
   message — without a server round-trip
4. User presses Run (or Ctrl+Enter)
5. Frontend POSTs `QueryRequest { query }` to
   `/w/{key}/risk-trees/{treeId}/query`
6. Server loads tree + simulation results → `RiskTreeKnowledgeBase`
   → `VagueSemantics.evaluateTyped(...)` → `QueryResponse`
7. Frontend renders result card + highlights matching nodes in tree view

**Client-side parsing rationale (2026-03-26):** fol-engine is cross-compiled
(JVM + JS) and declared as a `common` dependency with `%%%`. This was a
specific goal of the cross-compilation initiative (T1b.14): enable
`VagueQueryParser.parse()` on the ScalaJS side so the frontend can
validate query syntax at the input boundary per ADR-001 (parse-don't-validate)
without requiring a server round-trip. The parser is pure Scala with no
JVM-only dependencies — it runs identically on both platforms.
`VagueQueryParser.parse(s): Either[fol.error.QueryError, ParsedQuery]` is the
client-side validation boundary; server-side `QueryRequest.resolve()` (called
in the controller) re-parses authoritatively before evaluation.

**Note:** ADR-028 §1 ruled out client-side *evaluation* (data transfer,
cache locality), but client-side *parsing* is lightweight and was unblocked
once the JVM-only dependency (`commons-math3`) was removed in TG-1b.

### T3.1 — Extend `AnalyzeQueryState`

**File:** `src/main/scala/.../state/AnalyzeQueryState.scala`

Add `queryResult: Var[LoadState[QueryResponse]]`, derived signals
`matchingNodeIds` and `isExecuting`. Add `executeQuery()` method that
fires POST via `ZJS.loadInto`.

**Acceptance:** State compiles; `executeQuery` callable.

### T3.1b — Client-side parse validation

**File:** `src/main/scala/.../state/AnalyzeQueryState.scala`

Add a derived signal that calls `VagueQueryParser.parse()` on the current
`queryInput` value (debounced ~300ms). Produces
`Signal[Option[Either[fol.error.QueryError, ParsedQuery]]]` — `None` when
input is empty, `Left(err)` on syntax error (with error position),
`Right(parsed)` on valid parse. Note: the client uses the **library
error type** (`fol.error.QueryError`) directly for UI display — no
mapping to `FolQueryFailure` is needed on the JS side (that type
exists only for HTTP dispatch on the server).

The `AnalyzeView` uses this signal to:
- Show/hide inline syntax error message with position indicator
- Disable the Run button when parse fails
- Optionally highlight the error position in the textarea

This is the **client-side validation boundary** per ADR-001 §3 — parsing
at the input edge gives instant feedback. The server re-parses
authoritatively via `QueryRequest.resolve()` in the controller.

**Acceptance:** Typing a malformed query shows inline error instantly;
typing a valid query clears the error and enables Run.

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
1. Structural predicates: `dispatcher.evalPredicate(SymbolName("leaf"), List(Value(asset, "cyber")))` returns `Right(true)`
2. Transitive closure: `descendant_of`/`leaf_descendant_of` correct for
   3-level tree (via dispatcher predicate evaluation)
3. Simulation functions: `dispatcher.evalFunction(SymbolName("p95"), List(Value(asset, "cyber")))` returns expected `Value(loss, ...)`
4. TypeCatalog completeness: all declared sorts, functions, predicates,
   and literal validators present
5. RuntimeModel validation: `model.validateAgainst(catalog)` returns `Right(())`
6. Empty tree: model has only schema, no domain elements

### T4.2 — Endpoint integration tests

**File:** `modules/server-it/src/test/scala/.../QueryEndpointSpec.scala` (new)

Test cases:
1. **Happy path:** Tree with known distributions. POST query. Assert
   satisfied, proportion, matchingNodeIds.
2. **Parse error:** Malformed query → 400 with `FolParseFailure` position
3. **Unknown symbol:** `p96(x)` → 400 with `FolUnknownSymbol` available list
4. **Empty range:** `portfolio(x)` on leaf-only tree → 200,
   rangeSize = 0, satisfied = false
5. **Unary query:** Answer variable `(y)` → matchingNodeIds has
   portfolio IDs
6. **Simulation not cached:** 409 with `SimulationNotCached` detail message
7. **Evaluation failure:** Catch-all → 500 with `FolEvaluationFailure`

**Acceptance:** `sbt serverIt/test` passes all 7.

---

## TG-5 — Polish + Docs

### T5.1 — Predefined query templates

Add dropdown in AnalyzeView with example queries from appendix §8.
Selecting populates textarea.

### T5.2 — Syntax reference panel

Collapsible accordion showing:
- Available functions: `p50`, `p90`, `p95`, `p99`, `lec`
- Available predicates: `leaf`, `portfolio`, `child_of`,
  `descendant_of`, `leaf_descendant_of`, `gt_loss`, `gt_prob`
- Operator table: `~`, `>=`, `<=`, tolerance `[ε]`
- Two example queries with explanation
- Note: sort-specific predicates (`gt_loss`, `gt_prob`) replace the
  overloaded `>` from earlier designs

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

Week 2:  TG-1b (fol-engine internal debt)                     ✅ DONE
         T1b.1–T1b.5 (sampling infrastructure)                ✅ DONE
         T1b.6–T1b.12 (partial unification + code quality)    ✅ DONE
         T1b.15–T1b.21 (ResolvedQuery IL + EvaluationOutput)  ✅ DONE
         T1b.15b (ParsedQuery rename)                          ✅ DONE
         T1b.22 (fol.* package migration)                      ✅ DONE
         T1b.13 (fol-engine usage docs)                        ⬜ optional
         T1b.14 (cross-compilation)                            ✅ DONE
         0.2.0-SNAPSHOT: generic KB, DSL removal, bridge       ✅ DONE
           decomposition, RelationName opaque, ModelAugmenter
         0.3.0-SNAPSHOT: fol.typed many-sorted type system      ✅ DONE
         0.9.0-SNAPSHOT: TypeDecl, FolModel, LiteralValue,     ✅ DONE
           BindError, ModelValidationError, DomainNotFoundError

Week 3:  TG-1c (model augmentation — delivered in 0.2.0,       ✅ DONE
                superseded by fol.typed in 0.3.0)
         TG-2 (server components)                               ✅ DONE
         T2.1 ✅ + T2.2 ✅ + T2.3 ✅ + T2.3b ✅ + T2.4 ✅
         + T2.5 ✅ + T2.5b ✅ + T2.6 ✅ + T2.7 ✅
         0.9.0-SNAPSHOT adaptation (2026-04-04):                ✅ DONE
           TypeDecl ADT, FolModel smart constructor,
           LiteralValue pipeline, new error hierarchy
           777 tests passing (391 common + 386 server)

Week 4:  TG-4 (tests, can overlap with TG-2 tail)
         T4.1 (once T2.4 done) → T4.2 (once T2.6 done)

Week 5:  TG-3 (frontend)
         T3.1 → T3.1b (client-side parse) → T3.2 → T3.3
         → T3.4 → T3.5

Week 6:  TG-5 (polish + docs)
         T5.1 → T5.2 → T5.3 → T5.4 → T5.5
```

---

## Gate Criteria

| Gate | After | Criteria |
|---|---|---|
| G1 | TG-1 | ✅ 711 unit + 19 IT tests pass at Scala 3.7.4; bats A+B+C pass on rebuilt distroless image |
| G1b | TG-1b | ✅ fol-engine: 868 tests pass; `VagueSemantics.evaluateTyped()` returns `EvaluationOutput[Value]` via `fol.typed` many-sorted pipeline; legacy `evaluate[D]()` preserved; typed DSL removed (ADR-011); `RelationName` opaque type; cross-compiled JVM+JS; `0.3.0-SNAPSHOT` integrated in register `common` crossProject; no `scala.util.Random`; `commons-math3` removed |
| G1c | TG-1c | ✅ Legacy: `ModelAugmenter[D]` on `evaluate[D]()`/`holds[D]()`. Current: `RuntimeDispatcher` + `TypeCatalog` + `VagueSemantics.evaluateTyped()` (868 tests including 9 typed-pipeline tests) |
| G2 | TG-2 | `curl -X POST .../query -d '{"query":"Q[>=]^{2/3} x (leaf(x), gt_loss(p95(x), 5000000))"}'` returns valid JSON |
| G3 | TG-4 | `sbt serverIt/test` passes all 7 integration tests |
| G4 | TG-3 | Type query → result card → tree highlights → LEC overlay |
| G5 | TG-5 | ADR-028 accepted; WORKING-INSTRUCTIONS updated; register domain usage docs complete |

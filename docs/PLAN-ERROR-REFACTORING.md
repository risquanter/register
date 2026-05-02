# PLAN — Error Reporting Refactoring (ErrorResponse / FolQueryFailure / GlobalError)

> **Status**: Proposed — not started.
> **Triggered by**: Phase 5a §5.6 code-quality review uncovered SF-4
> (`FolUnknownReference` decode pattern) which led to a deeper analysis of
> `ErrorResponse` semantics and revealed multiple latent bugs and a
> fundamental abstraction mismatch between JSON-validation and VQL error
> reporting.
> **Owner decision pending**: choice of solution path (A / B / C below) and
> scoping of fixes vs. abstraction work.

---

## 1. Background — Original design intent (verified against code)

`ErrorDetail` (`modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorDetail.scala`)
was modeled for **request-body validation errors**:

```scala
final case class ErrorDetail(
  domain:    String,                  // "simulations", "risk-trees", "users"
  field:     String,                  // JSON path: "name", "root.children[0].minLoss"
  code:      ValidationErrorCode,
  message:   String,
  requestId: Option[String] = None
)
```

The doc comment explicitly says `field` is a **JSON path to the problematic
field**. The mental model is *"client sent JSON, here is the path to the
broken field, here is why."*

`ErrorResponse.decode` (the inverse function, used by the Tapir-mapped Scala
clients via `BaseEndpoint`) exploits `field` as a **routing discriminator**:

```scala
case 409 => firstField match
  case "version"    => VersionConflict(...)
  case "branch"     => MergeConflict(...)
  case _            => DataConflict(...)
case 500 => firstField match
  case "simulation" => SimulationFailure(...)
  case _            => RepositoryFailure(...)
```

This works for the JSON-validation use-case because the JSON-path string
happens to also encode the error category (the `version` field is on the
resource being mutated; the `branch` field is on a scenario; etc.). One
slot, two roles.

---

## 2. Why VQL breaks the model

A VQL query is **not a JSON document**. It is an opaque text string
submitted as `QueryRequest("Q[>=]^{1/2} x ...")`. Two semantic mismatches:

1. **No JSON-path concept.** There is no `"name"` or `"root.children[0]"`
   to point at. The whole query lives inside the value of a single field.
2. **The "what's wrong" is inside the body's text, not the body's
   structure.** Symbol name, line/column, malformed token — these are
   concepts of a query language, not concepts of a request schema.

All FOL builders in `ErrorResponse.scala` (`makeFolUnknownSymbolResponse`,
`makeFolUnknownReferenceResponse`, `makeFolParseFailureResponse`, etc.)
hard-code `field = "query"` because there's no meaningful JSON path to
put there. The actual data (symbol name, parse position, reference name,
error message) lives only in `ErrorDetail.message`.

The `decode` function continues to read `firstField` as if it were a
routing key, producing typed sentinel values that contain the literal
string `"query"` instead of the actual symbol/reference name.

---

## 3. Identified Bugs (MUST not lose these)

### Bug 1 — `decode` produces garbage `getMessage` for symbol/reference errors
**Location**: `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala`
lines ~46–53 (the 400 arm of `decode`).

**Encode path:**
```
FolUnknownSymbol("p96", List("p95", "p99", "lec"))
  → ErrorDetail(domain, "query", UNKNOWN_SYMBOL,
       message = "Unknown symbol 'p96'. Available: p95, p99, lec")
```

**Decode path:**
```
firstField = "query"
  → FolUnknownSymbol("query", Nil)
  → getMessage() = "Unknown symbol 'query'. Available: "
```

The reconstructed object's `getMessage` is **factually wrong**, not "lossy".
A user or log reader sees `Unknown symbol 'query'`. This affects:
- `FolUnknownSymbol` (pre-existing defect, since whenever 400-arm was added)
- `FolUnknownReference` (new in current uncommitted change — see Bug 4)
- Likely also `FolDomainNotQuantifiable` which uses the same `firstField`
  pattern (needs verification).

The docstring on `decode` calls this *"not perfectly lossless"* — that
description is misleading; the output is actively wrong.

---

### Bug 2 — `FolQueryFailure` falls through to `NetworkError` in `GlobalError`
**Location**: `modules/app/src/main/scala/app/state/GlobalError.scala`,
`fromThrowable` method.

`FolQueryFailure extends AppError` — **not** `SimError`, **not** `IrminError`.

`GlobalError.fromThrowable` arms (in order):
- `isFetchNetworkError` → `NetworkError`
- `IOException` → `NetworkError`
- `ValidationFailed` → `ValidationFailed`
- `DataConflict | VersionConflict | MergeConflict` → `Conflict`
- `RepositoryFailure` (workspace sentinel) → `WorkspaceExpired`
- `IrminError` → `DependencyError`
- `SimError` → `ServerError`
- catch-all → `NetworkError(retryable = false)`

`FolQueryFailure` matches **none** of these → falls into the catch-all and
is reported as a network error. A user submitting a bad VQL query sees
the wrong banner and a misleading message string.

**Independent of the wire-format choice** — must be fixed regardless.

---

### Bug 3 — Roundtrip tests assert type only, never payload
**Location**: `modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala`
lines ~401–443 (`suite("FolQueryFailure roundtrip")`).

```scala
test("FolUnknownSymbol roundtrip preserves type") {
  val original = FolQueryFailure.FolUnknownSymbol("p96", List("p95","p99","lec"))
  val decoded  = ErrorResponse.decode(ErrorResponse.encode(original))
  assertTrue(decoded.isInstanceOf[FolQueryFailure.FolUnknownSymbol])
  // ^^^ type only — does not assert .symbol == "p96" nor .available == [...]
}
```

This test passes while Bug 1 is silently broken. Adding
`decoded.asInstanceOf[FolUnknownSymbol].symbol == "p96"` would fail
loudly. **No roundtrip test exists for `FolUnknownReference` at all.**

---

### Bug 4 — SF-4 fix propagates the same defective pattern
**Location**: `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala`
line ~52 (currently uncommitted in working tree).

The fix applied during Phase 5a §5.6 review changed the decode for
`UNKNOWN_REFERENCE` from `details.headOption.map(_.message).getOrElse("")`
to `firstField`, **for symmetry with `FolUnknownSymbol`**. Both now
produce `"query"` as the reconstructed name. This is "consistently broken"
— neither is correct.

> **Specifically flagged**: SF-4 fix (`FolUnknownReference(firstField)`)
> is in the working tree. It's "consistently broken" with `FolUnknownSymbol`.
> Two unsatisfactory choices exist:
>
> - **Keep `firstField`** for symmetry with the existing pattern. Wrong-by-pattern;
>   reconstruction yields `FolUnknownReference("query")`. Easy to repair atomically
>   when Bug 1 is fixed (single decode change covers both arms).
>
> - **Revert to `details.headOption.map(_.message).getOrElse("")`.** Wrong-but-readable;
>   reconstruction yields `FolUnknownReference("Unknown constant or literal reference: 'foo'")`
>   — the full English sentence is stuffed into the `name` field. Inconsistent with
>   `FolUnknownSymbol`'s behavior. At least a human reading the message would see the
>   real reference name embedded in the string.
>
> Either way it's wrong. Decision deferred to this plan; default is to ship the
> current `firstField` variant (consistent with `FolUnknownSymbol`) and fix both
> together when Bug 1 is repaired.

---

## 4. Abstraction Analysis

### 4.1 What `ErrorDetail` was actually doing

Strip implementation, abstract properties:

| Property        | Purpose                                              | JSON example                  |
|-----------------|------------------------------------------------------|-------------------------------|
| **Locator**     | Point at the exact piece of input that's wrong       | `"root.children[0].minLoss"`  |
| **Category**    | Machine-readable error category                      | `REQUIRED_FIELD`              |
| **Subject**     | What entity is being complained about                | (implicit in path)            |
| **Message**     | Human prose                                          | "Loss must be ≥ 0"            |
| **Domain**      | Which subsystem owns the error                       | `"simulations"`               |
| **Correlation** | Trace back through logs                              | request UUID                  |
| **Multiplicity**| Many errors at once (accumulation, not first-fail)   | `List[ErrorDetail]`           |

The current `ErrorDetail` collapses **three abstract roles** (locator +
subject + decode-time route key) into one `field: String` slot. This
works for JSON validation because a JSON path can simultaneously serve
all three. It breaks for VQL because Span (locator) and SymbolRef
(subject) are different shapes that can't both fit in one string.

### 4.2 What VQL/compiler practice provides

Compiler engineering (rustc, GHC, Scala 3, Roslyn, TypeScript) has
converged on a structured-diagnostic vocabulary:

- **Source spans**: `(start, end, line, col, snippet)` into the original text
- **Symbolic locators**: `Symbol(name, kind)` with optional `declaredAt: Span`
- **AST paths**: `quantifier.body.predicate[0].arg[1]` — JSON-path analog for parse trees
- **Phase**: `parse | lex | bind | typecheck | evaluate`
- **Suggestions**: did-you-mean (`p96 → p95`), fix-its (concrete replacements)
- **Available alternatives**: full enumeration when small (`["p95","p99","lec"]`)
- **Notes / secondary spans**: "scope opened here", "type declared there"
- **Severity**: `error | warning | hint | note`

Each is a structured field with a name that says what it is. No string
overloading.

### 4.3 The common abstraction

```scala
final case class Diagnostic[L, C, S](
  primary:      L,                    // locator: where in the input
  secondary:    List[(L, String)],    // additional labelled spans/paths
  code:         C,                    // category enum, switchable by client
  phase:        Option[String],       // optional pipeline phase
  subject:      Option[S],            // entity being complained about
  message:      String,               // human prose
  suggestions:  List[Suggestion[L]],  // optional repair hints
  alternatives: Option[List[String]], // small enumeration of valid choices
  severity:     Severity,
  requestId:    Option[String]
)

final case class DiagnosticBundle[L, C, S](
  domain: String,
  errors: List[Diagnostic[L, C, S]]
)
```

Two concrete worlds:

```
JSON validation : Diagnostic[JsonPath, ValidationErrorCode, FieldName]
VQL query       : Diagnostic[Span,     VqlErrorCode,        SymbolRef]
```

`L` is the **locator** type and varies the most across input media
(JSON path, text span, AST path, future SQL fragment range, future
protobuf field path). The abstraction: *something the consumer can use
to navigate back to the offending bit of the original input.*

`C` is the per-subsystem category enum.

`S` is the entity-level subject identity. Optional — parse errors don't
always have a subject (an unbalanced brace has a span but no entity).

Routing should be on **typed `code`** or an explicit `routeKey: Option[String]`,
**not** on `field` (which would stop lying about being a JSON path).

---

## 5. Solution Options

### Option A — Stop pretending typed roundtrip

Decode produces an opaque envelope:
```scala
case class FolQueryEnvelope(code: VqlErrorCode, message: String, position: Option[Int])
```

The frontend renders `message` verbatim. No typed reconstruction of
symbol name / available list / phase. Wire format unchanged.

**Pros**: smallest change, removes the lie that decode is meaningfully
reconstructing typed errors. Frontend already does no logic on
`FolUnknownSymbol.symbol` etc.
**Cons**: gives up on the typed-roundtrip ambition entirely. Existing
`FolUnknownSymbol` etc. case classes become dead at the decode boundary.

---

### Option B — Redesign the wire format properly (full abstraction)

Implement `Diagnostic[L, C, S]` per §4.3. New JSON contract for FOL
errors. Old `ErrorDetail` retained for JSON-validation; FOL gets its own
detail type carried in a parallel field on `JsonHttpError` (e.g.
`queryDiagnostics: Option[List[QueryDiagnostic]]`).

**Pros**: solves the abstraction debt for good. Frontend can highlight
parse positions, render did-you-mean suggestions, navigate to symbol
declarations. Future SQL/protobuf consumers can reuse the locator
abstraction.
**Cons**: largest change — wire format, builders, decode, frontend
renderer, Tapir error handling. Coordinate with frontend work.

---

### Option C — Local hack: stuff symbol name into `field`

Change FOL builders to pass the symbol/reference name as `field` instead
of `"query"`. Decode reads `firstField` and gets the actual name. Then
update the docstring on `ErrorDetail.field` to admit the dual semantics.

**Pros**: minimal code change. Decode roundtrip becomes lossless for
symbol/name. Tests can be tightened.
**Cons**: violates the documented "JSON path" semantics of
`ErrorDetail.field`. Couples FOL behavior to a slot meant for something
else. Pays interest forever — the next non-JSON error producer (SQL?
GraphQL?) will repeat the same hack.

---

## 6. Recommended Path Forward

1. **Immediately**: ship Phase 5a §5.6 with the current `firstField`
   variant of SF-4. The wire format is correct; only `decode` and
   `GlobalError` are buggy, and both bugs predate this commit (apply to
   `FolUnknownSymbol` since it was introduced).
2. **Soon (separate commit)**: fix Bug 2 (`GlobalError` arm for
   `FolQueryFailure`). Independent of wire-format choice.
3. **Soon (separate commit)**: tighten Bug 3 — add payload assertions to
   roundtrip tests, including a roundtrip test for `FolUnknownReference`.
   These tests will then **fail**, exposing Bugs 1 and 4 as red CI.
4. **Decide A/B/C** for Bugs 1 + 4 with full information. Recommend B
   for long-term code health, C for short-term unblock if frontend
   work is gated.

---

## 7. Files in scope when work begins

### Bug 1 / Bug 4 (wire format + decode):
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala`
  — `decode`, `encodeFolQueryFailure`, all `makeFol*Response` builders
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorDetail.scala`
  — possibly extend, or leave alone if Option B adds parallel field
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala`
  — `FolQueryFailure` case classes; possibly enrich with structured locator
- `modules/common/src/main/scala/com/risquanter/register/domain/errors/JsonHttpError.scala`
  — possibly add `queryDiagnostics` parallel field (Option B)
- Frontend renderer for query errors (Option B only)

### Bug 2 (GlobalError):
- `modules/app/src/main/scala/app/state/GlobalError.scala`
  — add `case _: FolQueryFailure => …` arm with appropriate routing
  (probably `ValidationFailed`-style or a new `QueryError` variant)

### Bug 3 (tests):
- `modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala`
  — `suite("FolQueryFailure roundtrip")` — tighten every test to assert
  payload preservation; add `FolUnknownReference` roundtrip test
- Possibly add a `GlobalError` spec covering `FolQueryFailure` arm

---

## 8. Test Strategy

For any chosen solution path:

1. **Encode roundtrip property tests** — for every `FolQueryFailure`
   case class, generate arbitrary instances and assert
   `decode(encode(x)) == x` (or the documented projection).
2. **Wire-format snapshot tests** — pin the JSON shape so frontend
   contract changes are visible.
3. **`GlobalError.fromThrowable` exhaustive arm tests** — one test per
   `AppError` subtype (this would have caught Bug 2 immediately).
4. **End-to-end IT tests** (already exist for FOL: H2/H3 in
   `QueryEndpointSpec`) — assert `errorBody.errors.exists(_.code == ...)`
   and that the `message` payload includes the actual symbol/reference
   name (currently NOT asserted).

---

## 9. Memory location

Working notes for this analysis are in session memory at
`/memories/session/error-response-vql-mismatch.md`. Promote to repo
memory if/when work begins so the abstraction analysis survives.

---

## 10. Decision Log (to be filled in by owner)

- [ ] Solution path chosen: **A / B / C**
- [ ] SF-4 disposition: **keep `firstField`** / **revert to `.message`**
- [ ] Bug 2 scope: same commit as wire fix / separate commit
- [ ] Bug 3 scope: precede wire fix (tests fail red) / accompany wire fix
- [ ] Frontend coordination needed: yes / no (Option B only)

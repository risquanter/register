# PLAN — Error Reporting Refactoring (ErrorResponse / FolQueryFailure / GlobalError)

> **Status**: OPEN — Option D ruled (2026-08-30); §0–§11 implementation pending
> in §13. The original analysis of the `decode`-routes-on-`field` defect holds;
> the class naming and option framing in §3–§5 predate the vql-engine 0.16.0
> error-API changes and are stale where the revalidation note below corrects them.
> Current status of each identified bug, verified against the code:
>
> - **Bug 1 (garbage `getMessage` on decode).** Closed for the `FolUnknownReference`
>   and `FolBindFailure` arms — both read the per-detail message slot and round-trip
>   losslessly (§11). Open for the two sibling arms: `decode` still reconstructs
>   `FolUnknownSymbol(firstField, Nil)` (`ErrorResponse.scala` line 49) and
>   `FolDomainNotQuantifiable(firstField, Set.empty)` (line 55), both yielding the
>   literal `"query"`. Option D closes both — see §13.
> - **Bug 2 (`FolQueryFailure` → `NetworkError` fall-through).** Closed.
>   `GlobalError.fromThrowable` classifies `FolQueryFailure` (→ `ServerError`); the
>   §12 continuation makes that classifier's completeness compiler-enforced over
>   the sealed `AppError`.
> - **Bug 3 (roundtrip tests assert type only).** Closed for `FolUnknownReference`
>   and `FolBindFailure` (payload asserted). Open for the two sibling arms, whose
>   roundtrip tests still assert `isInstanceOf` only. Option D tightens/removes them.
> - **Bug 4 (SF-4 `firstField` variant on `FolUnknownReference`).** Closed together
>   with Bug 1 for that arm (§11); disposition was the message slot, not `firstField`.
>
> **vql-engine revalidation (0.16.0, 2026-08-30).** register pins vql-engine 0.16.0.
> Its 0.14.0 release pruned the unknown-symbol error variant, so `fromQueryError`
> has no arm that produces `FolUnknownSymbol` — the type is dead end to end (no
> producer; only `decode`/`encode`/a unit test reach it). `DomainNotFoundError`
> survives, but the engine documents it as a defensive fallback that
> `RuntimeModel.validateAgainst` prevents from firing on a served query, so
> `FolDomainNotQuantifiable` is produced only on an unreachable misconfiguration.
> Neither sibling arm is reachable by live traffic; both are latent-correctness /
> code-honesty items, not live user-facing defects. The engine consolidated its
> error API on a rendered `message` plus a `context: Map[String,String]` (not typed
> spans/locators), which is why §5 Option B (the `Diagnostic[L,C,S]` redesign, §4.3)
> is over-scoped and misaligned, and Option D is the ruled path.
>
> **Ruled 2026-08-30 — Option D.** Retire `FolUnknownSymbol` entirely; fold
> `FolDomainNotQuantifiable` onto a single rendered-message slot (the Option A
> mechanism, mirroring the `FolUnknownReference` repair in §11). §5 Options B and C
> rejected (B over-scoped vs. the engine's rendered-message direction; C re-abuses
> the `field` slot the plan set out to stop overloading). Exact signatures: §13.

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

- [x] Solution path chosen: **D** (2026-08-30) — retire `FolUnknownSymbol` (dead
      in vql-engine 0.16.0); fold `FolDomainNotQuantifiable` onto the message slot.
      §5 Options B/C rejected. The `FolUnknownReference` arm took the message-slot
      repair earlier via §8.11. Signatures: §13.
- [x] SF-4 disposition: **message slot** — decided for the `FolUnknownReference`
      arm via PLAN-RISKTRANSFORM §8.11 (its `decode` arm reads
      `details.map(_.message)`); the sibling arms follow the same slot in §13.
- [x] Bug 2 scope: separate commit — landed (`GlobalError` `FolQueryFailure` arm).
- [x] Bug 3 scope: accompanies the wire fix — sibling-arm roundtrip tests are
      tightened (or removed with the retired type) in Option D (§13).

---

## 11. Partial resolution via PLAN-RISKTRANSFORM §8.11 (Option A) — 2026-08-19

M2's bind-error classification (PLAN-RISKTRANSFORM §8.11) widens
`FolUnknownReference` to carry the engine's rendered messages and routes its
`decode` arm through the **message** slot, mirroring `FolBindFailure`. That
reuses the existing wire mechanism for one arm; it does **not** adopt any §5
option (no opaque envelope, no `Diagnostic` type, no name-in-`field` encoding).
State as of §8.11 landing (2026-08-21):

**Fixed for the `FolUnknownReference` arm (leaves this plan's scope):**
- **Bug 1 / Bug 4** — `decode` no longer reconstructs `firstField == "query"`;
  the `UNKNOWN_REFERENCE` arm reads `details.map(_.message)` and round-trips
  losslessly. `makeFolUnknownReferenceResponse` emits one detail per message.
- **Bug 3** — a `FolUnknownReference roundtrip preserves list losslessly` test
  is added to `ErrorResponseSpec`, asserting `f.messages == messages`.

**Still open here (unchanged — this plan's remaining scope):**
- **Bug 1 / Bug 3 for the two sibling arms** that still route on `firstField`:
  - `decode` `UNKNOWN_SYMBOL` → `FolUnknownSymbol(firstField, Nil)` —
    reconstructs `"query"` as the symbol and drops `available`
    (`ErrorResponse.scala`, ~line 49).
  - `decode` `DOMAIN_NOT_QUANTIFIABLE` → `FolDomainNotQuantifiable(firstField,
    Set.empty)` — reconstructs `"query"` as the type name (~line 55).
  - Neither has a payload-asserting roundtrip test.
- The **§5 abstraction decision (A / B / C)** for the full wire redesign.

**Resulting decode inconsistency to close here:** after §8.11,
`UNKNOWN_REFERENCE` and `BIND_FAILED` read the message slot (correct);
`UNKNOWN_SYMBOL` and `DOMAIN_NOT_QUANTIFIABLE` still read `firstField` (broken).

**Closed by §13 (Option D, ruled 2026-08-30):** the two sibling arms are brought
onto the message slot — `FolUnknownSymbol` by retirement (dead in vql-engine
0.16.0, so no arm can be wrong), `FolDomainNotQuantifiable` by folding its two
structured fields onto a single rendered `message`. That makes every FOL decode
arm read the message slot; the §5 `Diagnostic` redesign is not adopted.
- [x] Frontend coordination needed: no (Option D; Option B not chosen).

---

## 12. Continuation (2026-08-30): AppError encode/classify exhaustiveness

**Summary.** The `AppError` sealed hierarchy has four direct sub-traits —
`SimError`, `IrminError`, `AuthError`, `FolQueryFailure` (`AppError.scala`
lines 8–10, 198, 235). Two dispatch sites classify an incoming error by walking
those sub-traits, and both match on `Throwable` rather than on the sealed
`AppError`, so neither is compiler-checked for completeness: a fifth sub-trait
added later would compile clean and fall silently to a catch-all. That is the
same class of gap as Bug 2 (a sibling sub-trait missed by a classifier), here
present structurally rather than as a live bug. This continuation closes it at
both sites by routing through an intermediate matcher whose scrutinee is the
sealed `AppError`, which makes a missing sub-trait a compile error
(`-Wconf:msg=match may not be exhaustive:error`, `build.sbt`). Both changes are
behaviour-preserving for every error that exists today; the only new mapping is
a defensive `AuthError` arm on the frontend that no current input reaches. Doc
corrections bring ADR-010 and ADR-035 from "two sub-traits" to the actual four,
and make ADR-035's described `encode`/`encodeAppError` structure match the code
it describes. Ruling: **Option 1** (strengthen the code) plus the frontend
`GlobalError` narrowing, decided 2026-08-30.

### 12.1 Backend — `ErrorResponse.encode`

`encode(error: Throwable)` currently lists the four sub-traits directly plus a
`case _`, so the compiler sees a total match on `Throwable` and checks nothing
about `AppError` coverage. Extract an intermediate matched on the sealed
`AppError`; the four existing per-family sub-matchers
(`encodeSimError`/`encodeAuthError`/`encodeIrminError`/`encodeFolQueryFailure`)
are unchanged.

Before (`ErrorResponse.scala`):

```scala
def encode(error: Throwable): (StatusCode, ErrorResponse) = error match {
  case e: SimError          => encodeSimError(e)
  case e: AuthError         => encodeAuthError(e)
  case e: IrminError        => encodeIrminError(e)
  case e: FolQueryFailure   => encodeFolQueryFailure(e)
  // Genuine unknown — already logged at service layer (ADR-002 Decision 5)
  case _ => makeGeneralResponse()
}
```

After:

```scala
def encode(error: Throwable): (StatusCode, ErrorResponse) = error match {
  case e: AppError => encodeAppError(e)
  // Genuine unknown — already logged at service layer (ADR-002 Decision 5)
  case _ => makeGeneralResponse()
}

/** Exhaustive match on the sealed `AppError` hierarchy: the compiler enforces a
  * branch for every direct sub-trait (`SimError`, `AuthError`, `IrminError`,
  * `FolQueryFailure`). A new sub-trait added without a branch is a compile error
  * (`-Wconf:msg=match may not be exhaustive:error`), so no error family can
  * silently fall through to the generic response (ADR-035 Decision 1).
  */
private def encodeAppError(error: AppError): (StatusCode, ErrorResponse) = error match {
  case e: SimError          => encodeSimError(e)
  case e: AuthError         => encodeAuthError(e)
  case e: IrminError        => encodeIrminError(e)
  case e: FolQueryFailure   => encodeFolQueryFailure(e)
}
```

Behaviour: every `AppError` routes to the same sub-matcher as before; every
non-`AppError` `Throwable` still reaches `makeGeneralResponse()`. Only the
compile-time guarantee changes.

New signature: `private def encodeAppError(error: AppError): (StatusCode, ErrorResponse)`.

### 12.2 Frontend — `GlobalError.fromThrowable`

`fromThrowable(e: Throwable)` mixes `SimError` leaf arms (`ValidationFailed`,
`DataConflict`, `VersionConflict`, `MergeConflict`, the workspace-sentinel
`RepositoryFailure`) with sub-trait arms (`IrminError`, `SimError`,
`FolQueryFailure`) and a `case _ => NetworkError` catch-all. `AuthError` has no
arm today and falls to that catch-all. Split the domain classification into an
intermediate matched on the sealed `AppError`; the non-domain catch-all
(browser Fetch/`TypeError`, `IOException`) stays on `fromThrowable`.

Before (`GlobalError.scala`):

```scala
def fromThrowable(e: Throwable): GlobalError = e match
  case vf: com.risquanter.register.domain.errors.ValidationFailed =>
    ValidationFailed(vf.errors)
  case _: DataConflict    => Conflict(msg(e))
  case _: VersionConflict => Conflict(msg(e))
  case _: MergeConflict   => Conflict(msg(e))
  case rf: RepositoryFailure if RepositoryFailure.isWorkspaceSentinel(rf) =>
    WorkspaceExpired(
      "Your previous workspace has expired and its data is no longer available. " +
      "Creating a new tree will start a fresh workspace.")
  case _: IrminError      => DependencyError(msg(e))
  case _: SimError        => ServerError(msg(e))
  case _: FolQueryFailure => ServerError(msg(e))
  case _ => NetworkError(msg(e))
```

After:

```scala
def fromThrowable(e: Throwable): GlobalError = e match
  case appErr: AppError => fromAppError(appErr)
  // Browser Fetch failures (TypeError), IOExceptions, and any other non-domain
  // throwable. Request-path retries are owned by Istio (ADR-012 §4 + ADR-031);
  // the SPA fails fast.
  case _ => NetworkError(msg(e))

/** Exhaustive classification of the sealed `AppError` hierarchy. The compiler
  * enforces a branch for every direct sub-trait (`SimError`, `IrminError`,
  * `AuthError`, `FolQueryFailure`); a new sub-trait without a branch is a
  * compile error, so no error family can silently be reported as a
  * `NetworkError`.
  */
private def fromAppError(e: AppError): GlobalError = e match
  case vf: com.risquanter.register.domain.errors.ValidationFailed =>
    ValidationFailed(vf.errors)
  case _: DataConflict    => Conflict(msg(e))
  case _: VersionConflict => Conflict(msg(e))
  case _: MergeConflict   => Conflict(msg(e))
  case rf: RepositoryFailure if RepositoryFailure.isWorkspaceSentinel(rf) =>
    WorkspaceExpired(
      "Your previous workspace has expired and its data is no longer available. " +
      "Creating a new tree will start a fresh workspace.")
  case _: SimError        => ServerError(msg(e))
  case _: IrminError      => DependencyError(msg(e))
  case _: AuthError       => ServerError(msg(e))
  case _: FolQueryFailure => ServerError(msg(e))
```

Behaviour: the guarded `RepositoryFailure` arm precedes `case _: SimError`, so
non-sentinel `RepositoryFailure` and every other `SimError` reach
`ServerError` exactly as before; `IrminError`, `FolQueryFailure`, and the
non-domain catch-all are unchanged. The one new mapping is `AuthError →
ServerError`. `ErrorResponse.decode` reconstructs HTTP 403 as `AccessDenied`
(a `SimError`, `ErrorResponse.scala` line 61), never as an `AuthError`, so no
decoded error reaches the `AuthError` arm; it exists to satisfy compile-time
exhaustiveness and to force a deliberate mapping if a future `AuthError` ever
reaches the client. `ServerError` is the neutral default (a generic 5xx-style
banner); adding a dedicated forbidden `GlobalError` variant for an unreachable
path is out of scope. No open decision.

New signature: `private def fromAppError(e: AppError): GlobalError`.

### 12.3 Documentation corrections (docs-as-current-state, same pass)

- `ADR-010.md` Decision 1 (lines 28–41): "split into two sub-traits" and the
  code block listing only `SimError`/`IrminError` → the four current
  sub-traits, with origin notes (`AuthError` — authorization, ADR-024;
  `FolQueryFailure` — FOL queries, ADR-028). Decision 5's simplified snippet
  (lines 95–101) stays as the non-exhaustive baseline it explicitly frames,
  with its existing hand-off to ADR-035 (lines 104–105).
- `ADR-035-...md`: the sub-trait tree (lines 25–29) → four sub-traits; the
  `encodeAppError` code block (lines 40–43) → four branches; the Implementation
  table entry (line 128) → `encode` + `encodeAppError` + all four per-family
  matchers. After 12.1 lands, this described structure is exactly the code.
- `ErrorResponse.scala` `encode` doc comment (lines 137–138): "Dispatches to
  exhaustive sub-matchers for SimError and IrminError" → dispatches via
  `encodeAppError` over all four families.
- `GlobalError.scala` `fromThrowable` doc comment (lines 65–74): keep, and note
  the completeness is now compiler-enforced over `AppError`.

### 12.4 ADR alignment

- **ADR-010** — hierarchy description corrected to match the code it governs;
  no behavioural change. Compliant.
- **ADR-035** — Decision 1's compile-time guarantee becomes real at the
  top-level dispatch (previously only per-family); the doc's described
  `encode`/`encodeAppError` structure becomes the actual code. Realized.
- **ADR-024** — `AuthError` is the authorization error family; the new frontend
  arm maps it to a neutral banner and reveals nothing about the authorization
  backend (consistent with the backend collapsing it to an opaque 403). Compliant.
- **ADR-036** — no identifier or message content changes. Unaffected.

### 12.5 Open decisions

None. The `AuthError → ServerError` frontend mapping is a defensive default for
a decode-unreachable path, not a value judgement.

### 12.6 Version bump

Shipped code changes (`ErrorResponse.scala`, `GlobalError.scala`) → PATCH.
Option D (§13) lands first and takes `0.10.27`, so this continuation bumps from
the then-current version (`0.10.27` → `0.10.28`). Mirror `APP_VERSION` into both
`.env` and `.env.irmin`.

### 12.7 Verification plan

- `sbt commonJVM/test` — `ErrorResponseSpec` stays green (backend encode
  behaviour unchanged; no spec edit needed).
- `sbt app/test` — new `GlobalErrorSpec` green: one case per `AppError`
  sub-trait asserting the mapping (`SimError`/`ValidationFailed` →
  `ValidationFailed`; `DataConflict` → `Conflict`; sentinel `RepositoryFailure`
  → `WorkspaceExpired`; non-sentinel `RepositoryFailure` → `ServerError`;
  `IrminError` → `DependencyError`; `AuthError` → `ServerError`;
  `FolQueryFailure` → `ServerError`) plus a non-`AppError` throwable →
  `NetworkError`. This is the per-sub-trait test §8 item 3 names.
- `sbt server/test` — green (no server-module change; guards against ripple).
- `sbt serverIt/test` — green (clear leaked `register_it_` networks first).
- Compilation is the exhaustiveness proof: `encodeAppError`/`fromAppError`
  match on the sealed `AppError`; deleting any sub-trait branch fails the build.

---

## 13. Option D — retire `FolUnknownSymbol`; fold `FolDomainNotQuantifiable` (ruled 2026-08-30)

Closes Bugs 1 and 3 for the two sibling FOL decode arms, the last §0–§11 code
work. Two independent moves:

- **Retire `FolUnknownSymbol`.** vql-engine 0.16.0 has no producer for it
  (`fromQueryError` never constructs it), so the type and its `UNKNOWN_SYMBOL`
  wire code are dead. Deleting them makes the broken decode arm impossible rather
  than reconstructing it correctly.
- **Fold `FolDomainNotQuantifiable` onto the message slot.** Replace its two
  structured fields (`typeName`, `availableTypes`) with a single rendered
  `message`, built at the `fromQueryError` boundary and read back by `decode` —
  the same mechanism `FolUnknownReference` uses (§11). The rendered sentence is
  the existing user-facing `getMessage` text, now produced at one site.

Behaviour: `FolUnknownSymbol` had no producer, so its removal changes no served
response. For `FolDomainNotQuantifiable`, the domain error's `getMessage` text is
preserved verbatim and the wire message becomes that same text (previously the
builder emitted a second, divergent sentence); the error is engine-unreachable,
so no live response text changes. Both `decode` arms stop reconstructing
`"query"`.

### 13.1 `AppError.scala`

**Delete** `FolUnknownSymbol` (case class + docstring):

```scala
final case class FolUnknownSymbol(symbol: String, available: List[String])
  extends FolQueryFailure:
  override def getMessage: String =
    s"Unknown symbol '$symbol'. Available: ${available.mkString(", ")}"
```

**Change** `FolDomainNotQuantifiable` to carry the rendered message:

```scala
// after
final case class FolDomainNotQuantifiable(message: String)
  extends FolQueryFailure:
  override def getMessage: String = message
```

**Change** the `fromQueryError` arm to render at the boundary (same sentence the
old `getMessage` produced):

```scala
// after
case e: QE.DomainNotFoundError      =>
  FolDomainNotQuantifiable(
    s"Queries can only range over tree nodes (type 'Asset'). " +
    s"The type '${e.typeName}' cannot be enumerated. " +
    s"Available quantifiable types: ${e.availableTypes.mkString(", ")}")
```

### 13.2 `ValidationErrorCode.scala`

**Delete** the now-unproduced code:

```scala
case UNKNOWN_SYMBOL extends ValidationErrorCode("UNKNOWN_SYMBOL", "Query references an unknown predicate or function")
```

### 13.3 `ErrorResponse.scala`

**`decode` 400 arm** — delete the `UNKNOWN_SYMBOL` case; change the
`DOMAIN_NOT_QUANTIFIABLE` case to read the message slot:

```scala
// deleted
case ValidationErrorCode.UNKNOWN_SYMBOL =>
  FolUnknownSymbol(firstField, Nil)
// after
case ValidationErrorCode.DOMAIN_NOT_QUANTIFIABLE =>
  FolDomainNotQuantifiable(message)
```

**`encodeFolQueryFailure`** — delete the `FolUnknownSymbol` arm; change the
`FolDomainNotQuantifiable` arm:

```scala
// after
case FolDomainNotQuantifiable(message)            => makeFolDomainNotQuantifiableResponse(message)
```

**Builders** — delete `makeFolUnknownSymbolResponse`; change
`makeFolDomainNotQuantifiableResponse` to a single-message builder:

```scala
// after
def makeFolDomainNotQuantifiableResponse(message: String, domain: String = "query", requestId: Option[String] = None): (StatusCode, ErrorResponse) =
  response(StatusCode.BadRequest, "query", ValidationErrorCode.DOMAIN_NOT_QUANTIFIABLE, message, domain, requestId)
```

### 13.4 `AnalyzeQueryState.scala`

**Delete** the `FolUnknownSymbol` arm from `isQueryDomainError` (the match stays
exhaustive over the remaining `FolQueryFailure` subtypes):

```scala
case _: FolUnknownSymbol         => true
```

### 13.5 Tests

- `ErrorResponseSpec.scala` — **delete** the `FolUnknownSymbol roundtrip` test;
  **tighten** the `FolDomainNotQuantifiable` roundtrip test to assert the message
  round-trips losslessly (Bug 3):

  ```scala
  test("FolDomainNotQuantifiable roundtrip preserves message losslessly") {
    val original = FolQueryFailure.FolDomainNotQuantifiable(
      "Queries can only range over tree nodes (type 'Asset'). The type 'Loss' cannot be enumerated. Available quantifiable types: Asset")
    ErrorResponse.decode(ErrorResponse.encode(original)) match
      case f: FolQueryFailure.FolDomainNotQuantifiable =>
        assertTrue(f.message == original.message)
      case other =>
        assertTrue(other.isInstanceOf[FolQueryFailure.FolDomainNotQuantifiable])
  }
  ```

- `FolQueryFailureFromQueryErrorSpec.scala` — **change** the destructuring test to
  the single-field shape and assert the message carries the type and `Asset`:

  ```scala
  test("DomainNotFoundError renders type and Asset into the message") {
    val err = QE.DomainNotFoundError("Loss", Set("Asset"))
    FolQueryFailure.fromQueryError(err) match
      case FolQueryFailure.FolDomainNotQuantifiable(message) =>
        assertTrue(message.contains("Loss"), message.contains("Asset"))
      case other => throw MatchError(other)
  }
  ```

  The adjacent `DomainNotFoundError getMessage mentions type and Asset` test is
  unchanged (already asserts `getMessage` content).

### 13.6 Documentation sweep (docs-as-current-state, same pass)

- `PLAN-RISKTRANSFORM.md` line ~1823 — the note that `FolUnknownSymbol` is
  retained is superseded; mark it retired per Option D.
- No ADR references `FolUnknownSymbol` / `UNKNOWN_SYMBOL` / `FolDomainNotQuantifiable`
  (grep-verified), so no ADR edits are required.

### 13.7 ADR alignment

- **ADR-028** (FOL query errors) — the FOL error family loses a dead member and
  unifies `FolDomainNotQuantifiable` on a rendered message; the wire codes for
  live errors are unchanged. Compliant.
- **ADR-010 / ADR-035** (typed error channel, no leakage) — messages remain
  safe rendered prose; no secrets/PII/paths introduced. Compliant.
- **ADR-018** — no new IDs. Unaffected.

### 13.8 Open decisions

None. Approach is ruled (Option D); the retire-vs-keep and message-slot choices
are settled above.

### 13.9 Version bump

Shipped code changes across `common` and `app` → PATCH: `0.10.26` → `0.10.27`.
Mirror `APP_VERSION` into both `.env` and `.env.irmin`.

### 13.10 Verification plan

- `sbt commonJVM/test` — `ErrorResponseSpec` green with the tightened
  `FolDomainNotQuantifiable` payload assertion and the retired-type test removed.
- `sbt server/test` — `FolQueryFailureFromQueryErrorSpec` green with the
  single-field destructuring.
- `sbt app/test` — green (`AnalyzeQueryState` still compiles; `isQueryDomainError`
  match stays exhaustive).
- `sbt serverIt/test` — green (clear leaked `register_it_` networks first).
- Compilation is the completeness proof: `encodeFolQueryFailure`,
  `isQueryDomainError`, and `fromQueryError` all match on the sealed
  `FolQueryFailure`; removing a subtype and its arms keeps them exhaustive, and a
  missed reference is a compile error.

---

## 14. vql-engine 0.17.0 adoption — `BindErrorDetail` reshape (2026-08-31)

Ships with §13; same landing, same version (`0.10.27`).

### 14.1 Trigger

vql-engine 0.17.0 (ADR-021 in the vql project) reshapes `BindErrorDetail`, the
per-error detail carried inside `QueryError.BindError.details`. Before (0.15.0–
0.16.0) it had two cases: `UnparseableConstant(name, sortName, sourceText,
rendered)` and a catch-all `Other(rendered)`. After (0.17.0) it is an `enum`
with one case per `TypeCheckError` variant (11 total) and **no `Other`**. The
motive is exhaustiveness: a new type-check variant becomes a compile error in
the engine's own facade fold rather than silently flattening into `Other`.

`UnparseableConstant` and every `rendered` value are unchanged;
`BindError.messages` is still `details.map(_.rendered)`.

### 14.2 Impact on register

The production mapping `FolQueryFailure.fromQueryError` in
`AppError.scala` matches only `UnparseableConstant` and folds everything else
through `case _ => false`. That wildcard is correct under 0.17.0: register's
classifier asks only "is every detail an unresolved node reference?", and any
non-`UnparseableConstant` variant — old or new — is correctly *not* a node
reference, so it stays `BIND_FAILED`. No production change is warranted, and the
runtime behaviour is identical. The wildcard is kept deliberately: enumerating
all eleven cases would list ten `=> false` arms that couple register to the
engine's internal variant set for zero behavioural gain.

The only compile break is fixture construction in
`FolQueryFailureFromQueryErrorSpec.scala`, which built `BindErrorDetail.Other`:
- the mixed node-plus-type-error case now builds
  `ArityMismatch(symbol = "leaf", expected = 1, actual = 2, rendered = …)` —
  the real variant the old `Other` string described; still a
  non-`UnparseableConstant` detail, so still classifies `FolBindFailure`;
- the message-join case builds `UnknownPredicate("A", rendered = "error A")` and
  `UnknownFunction("B", rendered = "error B")` — two generic bind errors whose
  `rendered` strings the test joins with `; `.

No assertion is changed; only the fixture constructors follow the dependency.

### 14.3 Pin and version

`build.sbt` `vqlEngineVersion` `0.16.0` → `0.17.0`, first-party-waiver comment
updated to the 0.17.0 shape. First-party (`com.risquanter`), cooldown-exempt
per ADR-020 §10. This rides on §13's `0.10.27`; no further bump, `.env` /
`.env.irmin` already at `0.10.27`.

### 14.4 Build coordination — publishLocal only

0.17.0 is published to `~/.ivy2/local` (`publishLocal`), not yet to Maven
Central. sbt resolves local ivy first, so all four sbt tiers compile and test
against 0.17.0 now. The Docker/GraalVM image builds resolve from Maven Central
and cannot build until vql-engine 0.17.0 is on Central — a coordination
dependency on the vql-engine project, not a register code gap. It does not
block the sbt whole-suite-green bar; it does gate image/BATS release validation.

### 14.5 Verification plan

`sbt commonJVM/test`, `server/test`, `app/test`, `serverIt/test` all green
against 0.17.0 (clear leaked `register_it_` networks first). Compilation of the
spec against the 0.17.0 `enum` is itself the proof the fixtures match the new
shape.

---

## File inventory

- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ValidationErrorCode.scala
- modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala
- modules/server/src/test/scala/com/risquanter/register/domain/errors/FolQueryFailureFromQueryErrorSpec.scala
- modules/app/src/main/scala/app/state/AnalyzeQueryState.scala
- modules/app/src/main/scala/app/state/GlobalError.scala
- modules/app/src/test/scala/app/state/GlobalErrorSpec.scala
- build.sbt
- .env
- .env.irmin
- docs/dev/decision-records/ADR-010.md
- docs/dev/decision-records/ADR-035-error-leakage-prevention.md
- docs/dev/plans/PLAN-ERROR-REFACTORING.md
- docs/dev/plans/PLAN-RISKTRANSFORM.md

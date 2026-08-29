# PLAN — `RiskTreeKnowledgeBase` VQL Canonical Style (Extract[A] + MapDispatcher)

> **Status**: Proposed — not started.
> **Triggered by**: VQL codebase review (2026-06-21) identified two deviations
> from the ADR-015 canonical dispatcher style in
> `register/modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`.
> Both are style/convention gaps, not correctness bugs. The migration is safe
> and fully reversible.
> **Owner decision pending**: none — both changes are unambiguous improvements
> with no trade-off surface. The plan may be executed sequentially without
> user option-selection.

---

## 0. Background — What changed in the VQL library (for a returning reader)

`RiskTreeKnowledgeBase` was written when the VQL library's `RuntimeDispatcher`
API was new. Since then, two canonical patterns were added and documented in
`vql-engine` ADR-015 (Accepted 2026-05-02):

### Gap 1 — Manual extract helpers vs `value.extract[A]`

The dispatcher currently contains three private helpers:

```scala
private def extractString(args: List[Value], idx: Int, ctx: String): Either[String, String] =
  args.lift(idx).toRight(s"$ctx: missing argument at index $idx").flatMap { v =>
    v.raw match
      case s: String => Right(s)
      case other     => Left(s"$ctx: expected String at index $idx, got ${other.getClass.getSimpleName}")
  }

private def extractLong(args: List[Value], idx: Int, ctx: String): Either[String, Long] = ...
private def extractDouble(args: List[Value], idx: Int, ctx: String): Either[String, Double] = ...
```

ADR-015 § Code Smells names this exact pattern as the anti-pattern to avoid:

> ❌ *Per-sort hand-written extract helper — same shape repeated per sort in
> every consumer.*

The library's `Extract[A]` typeclass and `Value.extract[A]` extension method
are the canonical replacement. They provide the same behaviour, with the
error messages provided by the library:

```scala
// Before
extractString(args, 0, "leaf")
// After
args(0).extract[String]
```

The helpers also have a latent index-out-of-bounds gap: `args.lift(idx)` returns
`None` if the dispatcher is called with the wrong arity, which produces a
`Left("missing argument")` instead of surfacing the arity contract as a
precondition. `FolModel` construction validates arity at startup, so this
cannot happen for a well-formed `FolModel`, but the helpers mask the
assumption. `args(i).extract[A]` states the precondition more directly.

### Gap 2 — Manual `RuntimeDispatcher` vs `MapDispatcher`

The dispatcher is declared as:

```scala
val dispatcher: RuntimeDispatcher = new RuntimeDispatcher:
  override def functionSymbols: Set[SymbolName]  = Set(SymbolName("p95"), ...)
  override def predicateSymbols: Set[SymbolName] = Set(SymbolName("leaf"), ...)
  override def evalFunction(...)  = name.value match { ... }
  override def evalPredicate(...) = name.value match { ... }
```

This requires `functionSymbols` / `predicateSymbols` to be kept in sync with
the `match` arms by convention. If a new symbol is added to the `match` but
not to the `Set`, `FolModel.validateAgainst` will catch it at startup — but
only then.

`MapDispatcher` (added in VQL Phase 4) derives the symbol sets automatically
from the map keys, eliminating the gap:

```scala
val dispatcher = MapDispatcher(
  functions = Map(
    SymbolName("p95") -> { args => ... }
  ),
  predicates = Map(
    SymbolName("leaf") -> { args => ... }
  )
)
// dispatcher.functionSymbols  == Set(SymbolName("p95")) — derived, cannot diverge
// dispatcher.predicateSymbols == Set(SymbolName("leaf")) — derived, cannot diverge
```

---

## 1. Scope and constraints

**In scope:**
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/BinderIntegrationSpec.scala`

**Out of scope:**
- `QueryServiceLive` — no changes needed; it uses `kb.catalog`, `kb.model`, and
  `VagueSemantics.evaluateTyped`, all of which are unaffected.
- `QueryResponseBuilder` — unaffected.
- Any register ADR changes — these are implementation-level style updates, not
  architectural decisions.

**Hard constraints:**
- Both phases end with `sbt test` green in register (server module).
- `BinderIntegrationSpec` B1/B2/B3 remain green throughout.
- `RiskTreeKnowledgeBaseSpec` remains green throughout.
- No behaviour change in `evalFunction` / `evalPredicate` — outputs must be
  identical for all valid inputs.
- The public `val catalog: TypeCatalog`, `val model: RuntimeModel`,
  `val nameToNodeId`, and `val nameCollisions` signatures are unchanged.

---

## 2. Phase 1 — Replace manual extract helpers with `value.extract[A]`

**Goal:** Remove `extractString`, `extractLong`, `extractDouble` from the
anonymous `RuntimeDispatcher` implementation. Replace all call sites with
`args(i).extract[A]` using the `Extract[A]` typeclass from `fol.typed`.

**Import change required:**
```scala
// Add to RiskTreeKnowledgeBase.scala imports
import fol.typed.Extract  // brings value.extract[A] into scope
```

Actually `Extract` is brought into scope via the `value.extract[A]` extension,
which is defined in the `fol.typed` package alongside `Value`. The extension
is available automatically once `fol.typed.Value` is imported — no extra
import required. Verify at compile time.

**Call-site mapping:**

| Before | After |
|---|---|
| `extractString(args, 0, "p95")` | `args(0).extract[String]` |
| `extractLong(args, 1, "lec")` | `args(1).extract[Long]` |
| `extractDouble(args, 0, "gt_prob")` | `args(0).extract[Double]` |

Every `for`-comprehension that currently opens with `extractXxx(args, i, ctx)`
becomes `args(i).extract[A]`. The `ctx: String` argument disappears; the
library's `Extract[A]` error messages include the sort name and the actual
carrier type, which is sufficient diagnostic information.

**Error message change (acceptable):** The `Left` messages will change from
`"p95: expected String at index 0, got Long"` to
`"Extract[String]: expected String carrier for sort 'Asset', got Long(…)"`.
This is a log-level diagnostic only; no register logic branches on the content
of `Either.Left` from dispatcher lambdas.

**Deletion:** Once all call sites are replaced, delete the three private
`extractString`, `extractLong`, `extractDouble` methods entirely.

**Pass criterion:**
- `sbt "server/test"` green.
- `grep "extractString\|extractLong\|extractDouble" RiskTreeKnowledgeBase.scala`
  returns no matches.
- `BinderIntegrationSpec` B1/B2/B3 green.

**HARD STOP** — await user confirmation before Phase 2.

---

## 3. Phase 2 — Replace `new RuntimeDispatcher` with `MapDispatcher`

**Goal:** Replace the anonymous `new RuntimeDispatcher:` implementation and the
manually declared `functionSymbols` / `predicateSymbols` sets with a
`MapDispatcher` constructed from explicit `Map` literals.

**Import change required:**
```scala
// Add to RiskTreeKnowledgeBase.scala imports
import fol.typed.MapDispatcher
```

**Structural change:**

The existing structure:
```scala
val dispatcher: RuntimeDispatcher = new RuntimeDispatcher:
  override def functionSymbols: Set[SymbolName]  = Set(SymbolName("p95"), SymbolName("p99"), SymbolName("lec"))
  override def predicateSymbols: Set[SymbolName] = Set(SymbolName("leaf"), ..., SymbolName("gt_prob"))
  override def evalFunction(name: SymbolName, args: List[Value]): Either[String, Any]    = name.value match { ... }
  override def evalPredicate(name: SymbolName, args: List[Value]): Either[String, Boolean] = name.value match { ... }
```

Becomes:
```scala
val dispatcher: MapDispatcher = MapDispatcher(
  functions = Map(
    SymbolName("p95") -> { args => ... },
    SymbolName("p99") -> { args => ... },
    SymbolName("lec") -> { args => ... }
  ),
  predicates = Map(
    SymbolName("leaf")               -> { args => ... },
    SymbolName("portfolio")          -> { args => ... },
    SymbolName("child_of")           -> { args => ... },
    SymbolName("descendant_of")      -> { args => ... },
    SymbolName("leaf_descendant_of") -> { args => ... },
    SymbolName("gt_loss")            -> { args => ... },
    SymbolName("gt_prob")            -> { args => ... }
  )
)
```

The lambda bodies are identical to the current `match` arms, just relocated
into map entries. The `name.value match` dispatch is replaced by the map keys.

**`val model` type change:** `RuntimeModel.dispatcher` expects a
`RuntimeDispatcher`. `MapDispatcher extends RuntimeDispatcher`, so the existing
`RuntimeModel(domains = ..., dispatcher = dispatcher)` line compiles unchanged.
The declared type of `val dispatcher` may be widened to `MapDispatcher` or left
as `RuntimeDispatcher` — either compiles. Prefer the more specific
`MapDispatcher` to allow the `functionSymbols` / `predicateSymbols` derivation
guarantee to be visible at the declaration site.

**Symbol-set declarations deleted:** The two `override def functionSymbols` and
`override def predicateSymbols` declarations are removed. Symbol sets are now
derived from the map keys by `MapDispatcher` — they cannot diverge from the
lambdas.

**Pass criterion:**
- `sbt "server/test"` green.
- `grep "functionSymbols\|predicateSymbols" RiskTreeKnowledgeBase.scala`
  returns no matches (the overrides are gone; the derived properties exist on
  `MapDispatcher` but are not re-declared here).
- `grep "new RuntimeDispatcher" RiskTreeKnowledgeBase.scala` returns no
  matches.
- `BinderIntegrationSpec` B1/B2/B3 green.
- `RiskTreeKnowledgeBaseSpec` green.

**HARD STOP** — await user confirmation.

---

## 4. Verification checklist (after both phases)

```bash
# From register root
sbt "server/test"

# Structural checks
grep -n "extractString\|extractLong\|extractDouble" \
  modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala
# → no output

grep -n "new RuntimeDispatcher\|functionSymbols\|predicateSymbols" \
  modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala
# → no output
```

---

## 5. Context for the executing agent

**File to edit:** `register/modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`

**VQL library surface used:**
- `fol.typed.Value` — sort-tagged runtime value; `value.raw: Any` is the JVM carrier
- `fol.typed.Extract[A]` — extraction typeclass; `value.extract[A]` is the extension method
  (ADR-015 §2; library givens for `Long`, `Double`, `String`)
- `fol.typed.MapDispatcher` — `RuntimeDispatcher` implementation that derives
  `functionSymbols` / `predicateSymbols` from map keys (VQL Phase 4)
- `fol.typed.TypedFunctionImpl.of[A]` — optional combinator for documenting the
  native return type at the registration site; not required here since the
  lambdas already return `Either[String, Any]` directly

**Key guarantee to preserve:** `RiskTreeKnowledgeBase.model: RuntimeModel` is
consumed by `QueryServiceLive` via `FolModel(kb.catalog, kb.model)`. `FolModel`
validates dispatcher symbol coverage against the catalog at construction time.
This validation is the correctness gate — the changes in this plan must not
reduce its coverage.

**Tests to keep green throughout:**
- `BinderIntegrationSpec` — B1 (end-to-end evaluate), B2 (unknown literal), B3 (injection safety)
- `RiskTreeKnowledgeBaseSpec` — catalog construction, domain elements, dispatcher correctness

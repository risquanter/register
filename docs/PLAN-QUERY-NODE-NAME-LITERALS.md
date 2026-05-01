# Implementation Plan: Node-Name Literals in Vague-Quantifier Queries

**Status:** Draft — awaiting user review (per `docs/WORKING-INSTRUCTIONS.md` §
"Mandatory Review Halt").
**Date:** 2026-04-30
**Parent ADRs:** [ADR-028](ADR-028-vague-quantifier-query-pane.md),
[ADR-028 Appendix](ADR-028-appendix-technical-design.md)
**Related:** [ADR-001](ADR-001.md), [ADR-010](ADR-010.md),
[ADR-011](ADR-011.md), [ADR-014](ADR-014.md), [ADR-015](ADR-015.md),
[ADR-018](ADR-018-nominal-wrappers.md)
**Repos affected:**
1. `register` (this workspace)
2. `vague-quantifier-logic` (sibling repo, published as
   `com.risquanter::fol-engine:0.9.0-SNAPSHOT`)

---

## 1. Executive Summary

The query language documented in ADR-028 promises that an analyst can write
queries like:

```
Q[>=]^{2/3} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 5000000))
```

These queries currently **fail with `400 PARSE_ERROR`** at the very first
multi-word node-name reference. Investigation (see §2) shows three independent
defects across two repositories that combine to make the binary-relation
constructs (`child_of`, `descendant_of`, `leaf_descendant_of`) effectively
unusable from the HTTP query string, even though the dispatcher backing them
in `RiskTreeKnowledgeBase` works correctly when invoked directly (verified by
existing tests in `RiskTreeKnowledgeBaseSpec`).

The defects:

| # | Layer | Repo | Symptom |
|---|---|---|---|
| D1 | Lexer | fol-engine | `"IT Risk"` tokenises as `"`, `IT`, `Risk`, `"` (no string-literal support) |
| D2 | TermParser | fol-engine | A bare alphanumeric token like `Cyber` is always classified as a variable, never a constant |
| D3 | TypeCatalog | register | `RiskTreeKnowledgeBase.catalog.constants` is empty — node names are not registered as `Asset`-sorted constants |

This plan describes each defect, a proposed fix, the test cases that would
demonstrate the fix works, and an explicit list of **open decisions that
require user input** before implementation begins.

> ⚠️ **Per WORKING-INSTRUCTIONS, I am NOT making the final calls on the open
> decisions in §6.** The agent will halt after this plan is presented and wait
> for explicit continuation signals.

---

## 2. Defect Catalogue

### D1 — Lexer has no quoted-string support

**Repo:** `vague-quantifier-logic`
**File:** `core/src/main/scala/lexer/Lexer.scala`
**File:** `core/src/main/scala/util/StringUtil.scala`

The lexer follows OCaml's character-class tokeniser:

```scala
val prop: Char => Boolean =
  if alphanumeric(c) then alphanumeric
  else if symbolic(c) then symbolic
  else _ => false  // single character token
```

`"` is neither alphanumeric nor symbolic, so it always becomes a single-char
token. The string `"IT Risk"` therefore lexes to:

```
List(""", "IT", "Risk", """)   // 4 tokens, not 1
```

The downstream parser cannot recover — it sees `"` then expects an atom and
encounters `IT`, producing the observed `Closing bracket ')' expected, but
got: IT` error.

**Direct evidence:**

```bash
$ http POST .../query query='Q[>=]^{2/3} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 1000000))'
{ "error": { "code": 400, "errors": [{ "code": "PARSE_ERROR",
  "message": "Unexpected error during parsing: Closing bracket ')' expected, but got: IT" }] } }
```

### D2 — `isConstName` only recognises numeric / `nil` / decimal

**Repo:** `vague-quantifier-logic`
**File:** `core/src/main/scala/parser/TermParser.scala` (lines ~28-40)

Even if D1 were fixed and `IT_Risk` (single alphanumeric token, no space) were
emitted, the term parser would classify it as a `Var`, not a `Const`:

```scala
private def isConstName(s: String): Boolean =
  s.forall(numeric) || s == "nil" || StringUtil.isDecimalLiteral(s)
```

`Cyber` → `false` → falls through to `Var(a)` rather than `Term.Const(a)`.

The downstream `QueryBinder` then takes the `Term.Var` path
(`QueryBinder.scala` lines 120–128), which silently inserts the name into the
type environment as a free variable, **producing a quantifier over the entire
domain instead of pinning the argument to a specific node**. The query
`leaf_descendant_of(x, Cyber)` becomes equivalent to
`∃y. leaf_descendant_of(x, y)` — wrong semantics, not just wrong syntax.

This means **no node name reference works through the HTTP API**, not even
single-word ones, regardless of D1.

### D3 — `TypeCatalog.constants` is empty

**Repo:** `register`
**File:** `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala` (lines ~88-114)

The current catalog construction:

```scala
val catalog: TypeCatalog = TypeCatalog.unsafe(
  types = Set(...),
  functions = Map(...),
  predicates = Map(...),
  literalValidators = Map(...)
  // constants is implicitly Map.empty (default param)
)
```

`QueryBinder.bindTermExpected` line 131 looks up `Term.Const(name)` in
`catalog.constants`. With an empty map, **no node name can ever bind as an
`Asset`-sorted constant**, even if D1 + D2 were fixed and the parser produced
a `Term.Const(name)`. The bind step would fall through to
`literalValidators.get(assetSort)` (none registered), then
`Left(UnknownConstantOrLiteral(name))`.

Notably, this is the **only** fix in `register`. D1 and D2 are upstream.

---

## 3. Architectural Constraints — Systematic ADR Review

Per WORKING-INSTRUCTIONS § ADR Compliance, **every** accepted/proposed ADR
was reviewed for impact on the proposals — not just the obviously related
ones. The matrix below records the verdict for each.

| ADR | Topic | Impact on this plan |
|---|---|---|
| ADR-001 | Iron / smart constructors / parse-don't-validate | Constants populated from already-validated `RiskNode.name` (Iron `SafeName`). The new lexer token branch is a *parse* step (ADR-001 §1) returning `Either`, not throwing. ✅ No deviation. |
| ADR-002 | Logging strategy | Any new diagnostic in the binder/parser must use `ZIO.logDebug`/`ZIO.logError` — not `System.err.println`. Currently no logging change is needed; D-7 below asks whether parse failures should be logged. ⚠️ See D-7. |
| ADR-003 | Provenance / reproducibility | Not affected — query evaluation is deterministic over an already-cached `RiskResult` set. ✅ |
| ADR-008 (proposed) / ADR-010 | Error handling — sealed hierarchy, accumulating Validation, typed codes, no string matching | A new parse/bind error code is the cleanest path (D-5). Even if reusing an existing code, the new error must flow through `AppError`/`SimError` not as a bare exception, and must accumulate with other validation errors where applicable. ✅ Compatible — extends existing pattern. |
| ADR-009 | Compositional risk aggregation | Not affected. ✅ |
| ADR-011 | Import conventions | Top-level imports, no FQNs in signatures, group order. Applies mechanically to all new code. ✅ |
| ADR-012 | Service mesh | Not affected — query endpoint already sits behind the same mesh layer as the rest of `/w/{key}`. ✅ |
| ADR-013 (proposed, **not accepted**) | Testing strategy — Unit / Integration / Wiring / Manual | ADR-013 proposes a separate `*WiringSpec.scala` category. **The convention has not been adopted in this codebase** (verified: zero `*WiringSpec*` files exist). The repo's actual pattern uses one `HttpApiIntegrationSpec` per HTTP surface, booted via `HttpTestHarness` with the full app layer — that *is* the wiring check, just folded into the integration tier. The real gap is that no integration test today exercises `POST /w/{key}/query`; §5.6 closes it. |
| ADR-014 / ADR-015 | RiskResult cache, `RiskResultResolver` | `RiskTreeKnowledgeBase` is constructed per query off `ensureCachedAll`. The new `nodeNameConstants` map lives at the same scope as the existing `nameToNodeId` map directly above it — same lifetime, same source. ✅ |
| ADR-016 | Configuration via ZIO Config | Lexer/parser limits (e.g. max query length, max quoted-string length) — if introduced — must live in `application.conf` and be loaded via `Configs.makeLayer`, with `ConfigTestLoader` in tests. ⚠️ Currently no resource limit exists on the parser; D-8 below asks whether to add one (DoS hardening). |
| ADR-017 | Tree API design — separate Create/Update DTOs, name-based topology on Create | **Cross-impact:** `parentName` on Create DTOs is a string. If D-2.b lands as "reject reserved names at create-tree time", the validator on `RiskLeafDefinitionRequest` / `RiskPortfolioDefinitionRequest` must reject names that would collide with FOL reserved tokens. That couples the tree DTO to the FOL symbol set — a noticeable architectural addition. ⚠️ See D-2.b. |
| ADR-018 | Nominal wrappers (`TreeId`/`NodeId` over `SafeId`) | Node *names* are `String` at the FOL boundary by design (the `Asset` sort is `String`-backed in the dispatcher today). No new wrapper proposed; the wrapper line stays where it is. ✅ |
| ADR-019 | Frontend Laminar architecture | Not affected — backend-only fix. ✅ |
| ADR-020 | Supply chain security — pin versions exactly | The fol-engine bump (D-6) keeps the `0.9.x-SNAPSHOT` line during development, which is the existing dev pattern. ADR-020 §1 forbids floating ranges and snapshots **in production builds** — a release-version bump is needed before any prod build. ⚠️ Pre-existing concern, surfaced by D-6. |
| ADR-021 | Capability URLs | Query endpoint already scoped under `/w/{key}/...`. No change. ✅ |
| **ADR-022** | **Secret handling & error leakage prevention** | **Multiple direct impacts — see §3.1 below.** |
| ADR-023 | Local dev TLS | Not affected. ✅ |
| ADR-024 | Externalized authorization (PEP) | Query endpoint is workspace-key-gated (Layer 0). No new authorization surface. ✅ |
| ADR-025 | SPA routing | Frontend-only, not affected. ✅ |
| ADR-026 | Container image strategy (builder/dev/prod) | The fol-engine bump propagates through `containers/builders/Dockerfile.graalvm-builder` (publishLocal step). Already part of the existing build pipeline. ⚠️ Mentioned for D-6 sequencing. |
| ADR-027 | Frontend nginx serving | Not affected. ✅ |
| ADR-028 + appendix | Vague-quantifier query pane (parent ADR) | The ADR still documents the older `>(p95(x), N)` syntax and lists `p50`/`p90`. Pre-existing drift. ⚠️ See D-3. |
| ADR-INFRA-006 | DB credentials per-namespace SOPS | Not affected. ✅ |

### 3.1 ADR-022 (Secret handling) — direct impacts on this plan

Three clauses of ADR-022 apply concretely:

1. **§4 — exhaustive error sanitisation.** ADR-022 enables
   `-Wconf:msg=match may not be exhaustive:error` so adding a new
   `AppError`/`SimError` subtype without a corresponding `ErrorResponse.encode`
   branch is a **compile error**. If D-5 is resolved by adding
   `UnknownReference` (or similar), the change set MUST include the matching
   `encode` arm. The plan now records this as a hard requirement.
2. **§6 — `getMessage` discipline.** Parser/binder errors include user-supplied
   query text. Node names are not secrets *within* the workspace context
   (the user already holds the `WorkspaceKeySecret` for that workspace), but
   error messages MUST NOT echo the `WorkspaceKeySecret` or any internal
   `TreeId`/`NodeId` — only the offending token from the query. Concretely:
   the existing `QueryError → AppError` mapping in `QueryController` must be
   reviewed to confirm it does not pull workspace context into the message.
3. **R8 — opt-in serialisation.** If D-1 introduces a new token ADT in
   fol-engine, no inherited JSON/Schema codec should be derived for it; that
   ADT lives entirely inside fol-engine and never reaches the wire.

### 3.2 ADR Deviation Flags

None of the proposed *fixes* deviate from any accepted ADR. Two
*pre-existing* drift items are surfaced for the user to decide on:

- **ADR-028 documentation drift** (D-3): old syntax + nonexistent functions
  still listed.
- **Integration-test gap** for `POST /w/{key}/query`: no spec exercises this
  endpoint today (verified — only `HttpApiIntegrationSpec` exists in
  `modules/server-it/`, and it does not cover `/query`). Pre-existing; this
  plan closes it via §5.6.

---

## 4. Proposed Fixes

Each fix is described as a signature/behaviour echo (no implementation
bodies — per WORKING-INSTRUCTIONS § Signature Echo Protocol). Implementation
will only proceed after explicit user approval.

### F1 — Typed `Token` ADT + quoted-string lexer branch (addresses D1)

**Repo:** `vague-quantifier-logic`
**File:** `core/src/main/scala/lexer/Lexer.scala`

Replaces the stringly-typed `List[String]` lexer output with a sealed
`Token` ADT (per D-1, settled). Adds a fourth lexer branch for `"` that
consumes characters up to the next `"` (no escaping; per D-4) and emits a
`Token.StringLit(content)` carrying the **inner** content only — the
surrounding quote characters are not part of the payload.

**ADT shape (D-1 Option α — settled 2026-05-01; encoding refined
2026-05-01 to `enum` per fol-engine ADR-006 §1: pure-data sum type with
no per-variant behaviour):**

```scala
enum Token:
  /** OCaml `string` (alphanumeric run). */
  case Word(name: String)
  /** New (D-1/D-4): content of a `"…"`-delimited literal, quotes stripped. */
  case StringLit(content: String)
  /** OCaml `string` (symbolic operator run, e.g. `">="`, `"/\\"`, `"==>"`). */
  case OpSym(sym: String)
  /** Single-character punctuation. One case per terminal.
    *  OCaml originals (verbatim, per fol-engine ADR-007 C11):
    *  LParen="(", RParen=")", LBracket="[", RBracket="]",
    *  LBrace="{", RBrace="}", Comma=",", Dot="."
    */
  case LParen, RParen, LBracket, RBracket, LBrace, RBrace, Comma, Dot
```

**Signature change:**

```scala
// before
def lex(inp: List[Char]): List[String]
// after
def lex(inp: List[Char]): List[Token]
```

**Lexer behaviour additions (no implementation body yet):**

- `"` opens a string literal; characters consumed verbatim until the next
  `"` (no backslash escapes; newline inside ⇒ `LexerError`); emits one
  `Token.StringLit(content)`.
- Single-character punctuation is matched by exact char and emitted as the
  corresponding object case.
- Existing alphanumeric run → `Token.Word(...)`; symbolic run → `Token.OpSym(...)`.
- `LexerError` for unterminated string literals (existing exception-based
  backtracking pattern, per fol-engine ADR-002 / ADR-007 C2).

**Downstream impact (per C13 of the proposed ADR-007 amendment).** Every
downstream `case "(" :: rest =>` becomes `case Token.LParen :: rest =>`;
every `case "," :: rest =>` becomes `case Token.Comma :: rest =>`; every
`case op :: rest if isOp(op) =>` becomes `case Token.OpSym(op) :: rest =>`;
bare-name detection becomes `case Token.Word(a) :: rest =>`. Same number of
arms, same control flow, no new abstraction — covered by C13(c).

### F2 — Const detection for `Token.StringLit` (addresses D2)

**Repo:** `vague-quantifier-logic`
**File:** `core/src/main/scala/parser/TermParser.scala`

With the typed `Token` ADT (F1), the stringly-typed `isConstName`
predicate is no longer needed for the quoted-string path — classification
becomes a direct pattern match on the ADT case. The existing
`isConstName: String => Boolean` is retained only to recognise the legacy
numeric / `nil` / decimal-literal forms inside a `Token.Word`.

**Atomic-term branch shape:**

```scala
// in the atomic-term parser, replacing the current `case a :: rest`:
parserCases match
  case Token.StringLit(content) :: rest =>
    // Quoted literal — always a constant whose value is the inner content.
    (Term.Const(content), rest)
  case Token.Word(a) :: rest if isConstName(a) =>
    // Numeric / `nil` / decimal — preserved unchanged from current behaviour.
    (Term.Const(a), rest)
  case Token.Word(a) :: rest =>
    // Bare alphanumeric — variable, preserved unchanged.
    (Term.Var(a), rest)
```

No `unquote` helper is needed: the lexer already strips the surrounding
quotes when it constructs `Token.StringLit(content)`.

`isConstName` itself is unchanged:

```scala
private def isConstName(s: String): Boolean =
  s.forall(numeric) || s == "nil" || StringUtil.isDecimalLiteral(s)
```

### F3 — Populate `catalog.constants` from tree node names (addresses D3)

**Repo:** `register`
**File:** `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`

**Call site:** the existing `val catalog` definition (one site).

**Signature:**

```scala
// Boundary: register code → fol-engine library API.
//
// fol-engine's `TypeCatalog.constants` is `Map[String, TypeId]` —
// fol-engine has no knowledge of Iron / `SafeName`. The `SafeName`
// refinement (`Not[Blank] & MaxLength[50]`) is a construction-time
// invariant established at `RiskNode.parse`; the property travels with
// the immutable `String` value, so stripping the wrapper at this
// boundary discards the type-level proof but not the property. The
// dispatcher uses `Set.contains` / `Map.get` only — no interpolation,
// no concatenation — so an unwrapped `String` cannot widen the attack
// surface. See §10 for the full injection-safety analysis.
private val nodeNameConstants: Map[String, TypeId] =
  tree.index.nodes.values.iterator.map(_.name -> assetSort).toMap

val catalog: TypeCatalog = TypeCatalog.unsafe(
  types = Set(...),
  constants = nodeNameConstants,        // ← new line; D-2.b safety net (skip + log)
                                        //   on reserved-name collisions applied here
  functions = Map(...),
  predicates = Map(...),
  literalValidators = Map(...)
)
```

The map is built once per `RiskTreeKnowledgeBase` instance (per query — same
lifetime as the existing `nameToNodeId` map directly above). Cost is O(n) over
nodes; trees we're targeting have ≤ low thousands of nodes.

**D-2.a / D-2.b are settled (see §6).** The KB-level handling of duplicate or
reserved-symbol-colliding names is **alarm-on-bypass diagnostics**, not
normal-flow validation: the DTO validators (`requireUniqueNames`,
`requireNoReservedNames`) are the authoritative gate, and any malformed
tree reaching the KB indicates an upstream bypass (direct repo write,
migration, Irmin merge). Behaviour: skip the offending entry, emit
`ZIO.logWarning` so the bypass is observable.

---

## 5. Test Plan

Tests follow the existing layout & style:

- fol-engine: munit specs in `core/src/test/scala/...`
- register unit: ZIO Test specs in
  `modules/server/src/test/scala/com/risquanter/register/foladapter/`
- register integration: ZIO Test specs in
  `modules/server-it/src/test/scala/com/risquanter/register/http/`

### 5.1 Lexer tests (F1) — `core/src/test/scala/lexer/LexerSpec.scala`

| ID | Input | Expected tokens |
|---|---|---|
| L1 | `Q[>=]^{1/2} x ("IT Risk")` | `..., "(", «"IT Risk"», ")"` (single token for the quoted part, exact spelling depends on the option chosen in §6 D-1) |
| L2 | `"single"` | one token |
| L3 | `""` | empty-content token (must not crash) |
| L4 | `"a" "b"` | two tokens |
| L5 | unterminated `"oops` | clear `LexerError` (or controlled exception with position info) — **see §6 D-4 for whether escaping is in scope** |
| L6 | mixed `foo("bar baz", 42)` | `foo`, `(`, «"bar baz"», `,`, `42`, `)` |

### 5.2 TermParser tests (F2) — `core/src/test/scala/parser/TermParserSpec.scala`

| ID | Input (token list) | Expected `Term` |
|---|---|---|
| T1 | `«"IT Risk"»` | `Term.Const("IT Risk")` (note: content, not raw token) |
| T2 | `«"42"»` | `Term.Const("42")` (string-typed; not the integer literal) |
| T3 | `Cyber` (bare alphanum) | `Term.Var("Cyber")` (unchanged behaviour — preserves backwards compatibility) |
| T4 | `42` | `Term.Const("42")` (numeric path, unchanged) |

### 5.3 VagueQueryParser end-to-end tests (F1 + F2) — `core/src/test/scala/fol/parser/VagueQueryParserSpec.scala`

| ID | Query string | Expected outcome |
|---|---|---|
| P1 | `Q[>=]^{2/3} x (leaf_descendant_of(x, "IT Risk"), large(x))` | `Right(ParsedQuery(...))` with the second range arg as `Term.Const("IT Risk")` |
| P2 | `Q[~]^{1/2} x (child_of(x, "Operations"), gt_loss(p95(x), 5000000))` | `Right(...)`; verify Const content `Operations` (no surrounding quotes) |
| P3 | mixed bare + quoted: `Q[>=]^{1/2} x (capital(x, "Vienna"), large(x))(y)` | `Right(...)`; bare `x` stays Var, `"Vienna"` becomes Const |

### 5.4 register `RiskTreeKnowledgeBase` tests (F3) — `RiskTreeKnowledgeBaseSpec.scala`

The existing `catalogSuite` already covers structure. Add a `constantsSuite`.

**Note on C2/C3.** These are **bypass-path diagnostics tests**, not
normal-flow tests. In the supported flow, `requireUniqueNames` and
`requireNoReservedNames` reject malformed trees at the DTO boundary —
`RiskTreeRequestsSpec` covers that. C2/C3 simulate the unsupported case
(direct repo write / migration / Irmin merge) where a malformed tree
reaches the KB constructor, and assert that the KB does not crash, behaves
deterministically, and emits the expected `ZIO.logWarning` diagnostic. The
log-capture assertion is the contract being tested.

| ID | Setup | Assertion |
|---|---|---|
| C1 | KB built from the existing 4-node fixture (`Root`, `IT Risk`, `Cyber`, `Hardware`) | `kb.catalog.constants` contains exactly those 4 keys, all → `assetSort`; no warnings logged |
| C2 | KB built directly (bypassing DTO) from a tree fixture containing two distinct nodes both named `"Cyber"` | KB constructs without exception; `kb.catalog.constants` contains key `"Cyber"` exactly once (deterministic last-write-wins); exactly one `ZIO.logWarning` containing the duplicate name fires (captured via ZIO Test log harness) |
| C3 | KB built directly (bypassing DTO) from a tree fixture containing a node named `"leaf"` (collides with the `leaf` predicate symbol) | KB constructs without exception; `kb.catalog.constants` does NOT contain key `"leaf"` (skipped); `kb.catalog.predicates` still contains `"leaf"` (predicate wins); exactly one `ZIO.logWarning` containing the colliding name fires |
| C4 | KB built from any non-empty fixture | `RiskTreeKnowledgeBase.reservedFolNames` equals `kb.catalog.predicates.keySet ++ kb.catalog.functions.keySet`, and contains the documented baseline (`leaf`, `portfolio`, `child_of`, `descendant_of`, `leaf_descendant_of`, `gt_loss`, `gt_prob`, `p95`, `p99`, `lec`) |

### 5.5 register `QueryBinder` integration tests — new file `BinderIntegrationSpec.scala`

| ID | Query | Expected |
|---|---|---|
| B1 | `Q[>=]^{1/2} x (leaf_descendant_of(x, "IT Risk"), gt_loss(p95(x), 1000))` against the 4-node fixture | parses + binds without error; range evaluates to `{Cyber, Hardware}` |
| B2 | Same query but referencing a non-existent node (`"Nonexistent"`) | `Left(UnknownConstantOrLiteral)` mapped to a structured `ValidationError` (per ADR-010) |
| B3 | Tree fixture containing a node literally named `foo")` (grammar-meaningful payload simulating an injection attempt). Query attempts to reference it: `Q[>=]^{1/2} x (leaf_descendant_of(x, "foo"), …)` | KB constructs without exception; query parses (lexer's `\"`-terminator stops at the embedded `\"` — see §10); binder rejects with `UNKNOWN_REFERENCE`; the structured error message echoes only the offending token, never re-feeds the embedded payload into any parser. **Locks the §10 verdict in code** — fails if anyone later swaps `Map.get` for string interpolation. |

### 5.6 HTTP integration test — new file `QueryEndpointSpec.scala` in `server-it`

Currently **no integration test exercises `POST .../query`**. Adding one is in
scope for the post-fix verification step.

| ID | Scenario | Assertion |
|---|---|---|
| H1 | Bootstrap a workspace with the same tree as the simple demo script; POST a query using `leaf_descendant_of(x, "IT Risk")` | HTTP 200; `satisfyingCount` is non-null and reflects the actual leaves under "IT Risk" |
| H2 | POST a query with a malformed quoted string (`"unterminated`) | HTTP 400, `code: PARSE_ERROR` with position info; **error body MUST NOT contain the `WorkspaceKeySecret` or any `TreeId`/`NodeId`** (ADR-022 §4/§6 cross-check) |
| H3 | POST a query referencing an unknown node name | HTTP 400, structured error indicating the unbound constant (exact `ValidationErrorCode` per D-5) |

*(Earlier draft proposed a separate `QueryControllerWiringSpec.scala`. Removed —
ADR-013's `*WiringSpec` convention is not adopted in this codebase, and the
integration spec in §5.6 already covers the same "endpoint reachable through
the full layer stack" check via `HttpTestHarness`.)*

---

## 6. Open Decisions Requiring User Input

Per WORKING-INSTRUCTIONS § Decision Triggers, every item below is a
trigger-class issue. **The agent will not pick a default.** Each one is
phrased as prose so you can simply type a freeform reply.

### D-1 — How should the lexer signal that a token originated from a quoted string?

**FULLY SETTLED — typed `Token` ADT, with fol-engine ADR-007 amendment
(user 2026-05-01 chat: "I opt for doing D-1 as described above with the
amendments").**

*Why typed.* `Lexer.lex: List[Char] => List[String]` is stringly typed
solely because the original OCaml impl returned `string list`. The token
class (word / symbolic / single-char punctuation / now string-literal)
is already computed inside `lex` to drive `lexwhile`'s predicate, then
immediately discarded. A sealed `Token` ADT (`Token.Word` /
`Token.StringLit` / `Token.Symbol`) recovers information the lexer
already has and removes the need for any `"foo"` / `STR:foo` /
surrounding-character convention in the token string itself.

*Blast radius.* All call sites are in the sibling fol-engine repo:
`VagueQueryParser`, `FOLParser`, `FOLAtomParser`, `TermParser`, plus
`LexerSpec` tests. The register-side change is just the version bump
(D-6).

#### fol-engine ADR review outcome (read 2026-05-01)

Two fol-engine ADRs apply to the lexer/parser layer:

- **fol-engine ADR-002 — Parser-Combinator Style.** Codifies
  `ParseResult[A] = (A, List[String])` plus exception-based backtracking.
- **fol-engine ADR-007 — Preserve OCaml-Ported Parser Combinator Core.**
  Codifies twelve characteristics (C1–C12) protecting Harrison's textbook
  traceability. C1 (parser signature `List[String] => (A, List[String])`),
  C10 (lexer output is flat `List[String]`), and C11 (verbatim OCaml in
  scaladoc) are directly impacted by a typed `Token` ADT.

**Conflict assessment & user verdict (accepted 2026-05-01):**

- **C1 (parser signature element type).** Acceptable as long as the
  Harrison → Scala mapping is preserved. The parser *shape* (signature
  arity, threading, exception-based backtracking) stays identical —
  only the element type changes from `String` to `Token`. Future
  Harrison ports remain mechanical.
- **C10 (flat string output).** Mostly a wording conflict, with one
  mechanical ripple: every downstream `case "(" :: rest =>` becomes
  `case Token.LParen :: rest =>`. Same number of arms, same control
  flow, no new abstraction — just an ADT match instead of a string
  match. No architectural cost beyond a one-time rewrite.
- **C11 (OCaml in scaladoc).** Preserved by annotating each new ADT
  case with the OCaml `string` value it replaces. Existing OCaml
  fragments stay verbatim; only the new ADT introduces a Scala
  extension note.

**Required amendment to fol-engine ADR-007 (to be authored in fol-engine
alongside the F1 implementation; user-confirmed scope 2026-05-01):**

1. **Update C1 wording** — replace the literal `List[String]` in the C1
   heading and body with `List[Token]`, retaining the explicit note
   that the *shape* (tuple-threaded `(A, Remaining)`, exception
   backtracking, mutual recursion) is preserved unchanged.
2. **Add C13 — Element-type evolution.** Element types within the parser
   pipeline (e.g. lexer token type, intermediate parse result
   components) may be replaced by Scala-side ADTs **provided that:**
   (a) the surrounding combinator shape — signature arity, token
   threading, exception-based backtracking, mutual recursion structure
   — is unchanged; (b) each new ADT case carries a scaladoc note
   identifying the OCaml `string` (or other primitive) it replaces;
   (c) downstream pattern matches expand 1:1 from string-match arms to
   ADT-match arms with no change in control flow. C13 documents that
   Scala-side type refinements that improve compile-time guarantees
   without altering Harrison's algorithmic structure are explicitly
   permitted.

*Trade-off acknowledged.* Choosing D-1 (typed ADT) over D-4-only is a
quality-of-life and future-proofing investment: parser arms become
self-documenting, future enhancements (position info, source spans,
escape sequences, multi-line literals) can extend the ADT without
breaking string conventions, and the type checker prevents accidental
confusion between `Token.OpSym(",")` and `Token.StringLit(",")`.
D-4-only would have shipped the bug fix with minimum fol-engine churn
but would have nailed the parser to "everything is a string forever."

#### D-1 punctuation granularity (settled 2026-05-01: Option α)

The `Token` ADT uses **a dedicated `enum` case per single-character
punctuation terminal** (`LParen`, `RParen`, `LBracket`, `RBracket`,
`LBrace`, `RBrace`, `Comma`, `Dot`), with `OpSym(String)` retained as a
string carrier *only* for the open-ended symbolic-operator class
(`">="`, `"/\\"`, `"==>"`, etc.).

*Rationale.* (a) The punctuation alphabet is closed and finite — it
deserves the named cases so the type checker can enforce exhaustiveness
and prevent confusion between, e.g., `Comma` and `Dot`. (b) The
symbolic-operator set is open-ended and varies per parser dialect (FOL
vs vague-quantifier extensions); those tokens already need string
content in downstream pattern matches, so a string carrier is the right
shape there. (c) The dominant convention in functional
compiler/interpreter codebases (GHC `Lexeme`, scala3 `Tokens`,
Lean 4, Roc, Unison) is one ADT case per terminal. (d) The C13(c)
"1:1 expansion" claim is preserved: `case "(" :: rest =>` →
`case Token.LParen :: rest =>` is a literal substitution.

### D-2.a — How should duplicate node names be handled when building `catalog.constants`?

**SETTLED — already enforced at create/update via the existing
Validation pattern; (c) added as defense-in-depth (user 2026-04-30:
"what I don't get [is] whether D is in line with the ADR validation
spirit AND existing validation patterns that implement it or are we
introducing a new mechanism? research the code for this now…").**

*Code research outcome.* Duplicate-name uniqueness is **already a
first-class invariant of the create AND update DTOs.**
[modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala](modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala#L121)
defines:

```scala
private[requests] def requireUniqueNames(allNames: Seq[SafeName.SafeName])
    : Validation[ValidationError, Set[SafeName.SafeName]] =
  val duplicates = allNames.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }
  if duplicates.nonEmpty then
    Validation.fail(ValidationError("request.names", ValidationErrorCode.AMBIGUOUS_REFERENCE,
      s"Duplicate names: ${duplicates.map(_.value).mkString(", ")}"))
  else Validation.succeed(allNames.toSet)
```

It is wired into both `validateTopologyCreate` (line 189) and
`validateTopologyUpdate` (line 217). It uses the same
`Validation[ValidationError, _]` accumulating channel as every other
create-DTO validator (see `TreeBuilderLogic.preValidateTopology`,
`requireSingleRoot`, `requirePortfolioParents`, `requireLeafParents`,
`requireNoCycles`, `requireNonEmptyPortfolios`). It produces a
structured `ValidationError` with a typed `ValidationErrorCode`. It is
fully ADR-001 / ADR-010 compliant and follows the established pattern
exactly. **No new mechanism is being introduced.**

*Verdict.* Option (d) is **already in place** for the normal create /
update path — the agent does not need to add anything for ordinary
duplicates. The remaining residual risk is duplicate names slipping in
via non-DTO code paths (direct repo writes, future migration scripts,
Irmin merges, etc.). To cover that:

- **Plan adds Option (c) only as defense-in-depth** — in
  `RiskTreeKnowledgeBase` constructor, detect duplicate names in
  `index.nodes` and either log a warning + last-write-wins (current
  silent behaviour, made visible) or fail the KB build with a typed
  error. Choose log-and-continue to preserve current operational
  behaviour while adding observability; the create-DTO validator is the
  authoritative gate.
- **No new tree-create validator code is needed.** The plan's earlier
  framing of "option (d) is a bigger effort" was based on incomplete
  research — corrected here.

### D-2.b — How should node names that collide with reserved FOL symbols be handled?

**SETTLED — combined A + B + C preferred (user 2026-04-30: "I agree
just document this in the plan as the combined being the preferred
method").**

*Combined design.* Three coordinated layers, each tiny:

- **Pattern A — register-internal constant set (single source of truth).**
  Expose, alongside `RiskTreeKnowledgeBase.catalog`, a public
  `val reservedFolNames: Set[String]` derived from the catalog itself
  (the keys of `catalog.predicates ++ catalog.functions`). The DTO
  validator imports this `Set[String]`. **No DTO → fol-engine coupling**
  — the constant lives in register and just happens to mirror what the
  catalog declares.

- **Pattern B — new validator following the existing pattern.** Add
  `requireNoReservedNames` next to `requireUniqueNames` in
  [`RiskTreeRequests.scala`](modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala),
  shaped identically:

  ```scala
  private[requests] def requireNoReservedNames(
      allNames: Seq[SafeName.SafeName]
  ): Validation[ValidationError, Unit] =
    val collisions = allNames.filter(n => RiskTreeKnowledgeBase.reservedFolNames.contains(n.value))
    if collisions.nonEmpty then
      Validation.fail(ValidationError(
        "request.names",
        ValidationErrorCode.RESERVED_NAME,
        s"Reserved query symbols cannot be used as node names: ${collisions.map(_.value).mkString(", ")}"))
    else Validation.succeed(())
  ```

  Plug it into `validateTopologyCreate` and `validateTopologyUpdate`
  alongside `requireUniqueNames`. Add `ValidationErrorCode.RESERVED_NAME`
  (one enum line + `ValidationErrorCodeSpec` round-trip case + ADR-022
  §4 exhaustive `ErrorResponse.encode` arm). Same `Validation`
  accumulating channel — errors aggregate with all other field errors
  in the same response.

- **Pattern C — KB-construction safety net.** In
  `RiskTreeKnowledgeBase` constructor, if a node name collides with
  `reservedFolNames`, skip registering it as a constant and log
  `ZIO.logWarning` (covers names that bypassed the DTO via direct
  repo writes / migration / Irmin merges). The user simply cannot
  reference such a node from a query — the predicate/function symbol
  wins. This matches the existing
  `.tapError(e => ZIO.logWarning(…))` pattern in
  `QueryServiceLive`.

*Why this beats the original "couple DTO to FOL symbol set" framing.*
The coupling goes in the opposite direction now — the DTO depends on a
register-internal constant, not on fol-engine. The constant happens to
be derived from the catalog, but exposing it is a one-liner and there
is no DTO-side knowledge of "FOL". Independent of D-2.a.

### D-3 — Should this plan also update ADR-028, or defer that to a separate change?

**SETTLED — done independently of this plan, on user instruction "update
the ADR NOW, irrespectively of the plans other decisions" (2026-04-30).**
ADR-028 main and ADR-028-appendix have been updated to current
implementation syntax: `gt_loss`/`gt_prob` typed comparison predicates
replace untyped `>`/`<`/`>=`/`<=`; available functions narrowed to
`p95`/`p99`/`lec` (the unimplemented `p50`/`p90` removed); status notes
added explaining the prior listings. The three node-name-literal bugs
remain open and tracked in this plan; the ADR cross-links here.

### D-4 — How rich should the new quoted-string syntax be?

**SETTLED — minimal scope (user 2026-04-30: "keep it minimal").**
Grammar: literal `"`, any character except `"` or newline, closing `"`.
No backslash escapes, no single-quoted form, no Unicode escapes. Lexer
emits the raw inner text as the token payload. Parser/binder treat such
tokens as `Term.Const` with sort `Asset` and look them up in
`catalog.constants`.

### D-5 — Which `ValidationErrorCode` (or new code) should an unbound query reference map to?

**SETTLED — add new code (user 2026-04-30: "add a new code and other new
error structures as needed as long as they are following existing
patterns and ADR compliant").** Add `ValidationErrorCode.UNKNOWN_REFERENCE`.
The `QueryController` (or its error-mapping layer) pattern-matches
`TypeCheckError.UnknownConstantOrLiteral(name) => ValidationError(field =
"query", code = UNKNOWN_REFERENCE, message = s"Unknown reference: $name")`.
ADR-022 §4 requires the new arm in `ErrorResponse.encode`'s exhaustive
match — `-Wconf` will fail compilation if it's missing. New
`ValidationErrorCodeSpec` case verifies round-trip.

### D-6 — How should the cross-repo change be sequenced?

**SETTLED — version bump within SNAPSHOT line (user 2026-04-30: "I want a
version bump on the plan content this a significant change").**
`vague-quantifier-logic` bumps from `0.9.0-SNAPSHOT` to e.g.
`0.10.0-SNAPSHOT` carrying F1+F2. Sequencing:
  1. Tests for F1+F2 in fol-engine (TDD)
  2. F1+F2 implementation in fol-engine; `sbt test` green
  3. fol-engine `version := "0.10.0-SNAPSHOT"`; `sbt publishLocal`
  4. register `build.sbt` bumped to `0.10.0-SNAPSHOT`
  5. F3 implementation in register; `sbt test` green
  6. register integration test; `sbt serverIt/test` green
The library stays SNAPSHOT for now per separate user direction ("keep
the fol-engine snapshot we will address this separately"). ADR-020
SNAPSHOT-in-prod resolution remains a deferred follow-up.

### D-7 — Should parse/bind failures be logged on the server side?

**SETTLED — yes, follow established pattern (user 2026-04-30: "yes,
introduce and ADR compliant logging following established patterns.
Verify current logging patterns…Separately verify that WorkspaceSecret
is structurally unloggable").**

*Verification done.* `QueryServiceLive` already uses
`.tapError(e => ZIO.logWarning(s"… for tree ${treeId.value}: ${e.formatted}"))`
at three sites. The codebase has ~30 `ZIO.logInfo`/`logDebug`/`logWarning`/
`logErrorCause` calls with this consistent shape. `WorkspaceKeySecret` is
defined `final class WorkspaceKeySecret private (private val raw:
WorkspaceKeyStr)` with `override def toString: String =
"WorkspaceKeySecret(***)"` at OpaqueTypes.scala:258 (and `WorkspaceKeyHash`
likewise at 301). Confirmed structurally unloggable per ADR-022 R1–R8.

*Implementation.* New `tapError` arms in `QueryServiceLive` for the new
parse-failure path:
```scala
.tapError(e => ZIO.logWarning(s"Query parse failed for tree ${treeId.value}: ${e.getMessage}"))
.tapError(e => ZIO.logWarning(s"Query bind failed for tree ${treeId.value}: ${e.formatted}"))
```
No `wsKey` interpolation. Level: `warning` to match the existing parse-
adjacent failures (FolModel validation, FOL evaluation).

### D-8 — Should the parser gain resource limits (DoS hardening)?

**SETTLED — yes, parametrisable, server-enforced, integrated into existing
config + validation patterns (user 2026-04-30: "yes and I want it
parametrizable enforced as appropriate both on client (if it is used on
the client already - do not change the existing pattern) and on the
server side as a must. integrate this into the existing validation
patterns and configuration patterns. do not invent new approaches base
everything on reviewing the existing code").**

*Verification done.* Config pattern in this codebase is uniform:
`Configs.makeLayer[X]("register.<path>")` where `X` is a case class read
from `application.conf` HOCON, with env-var overrides via the
`foo = default; foo = ${?REGISTER_FOO}` idiom. Eleven existing config
classes follow this shape (`ServerConfig`, `SimulationConfig`,
`WorkspaceConfig`, `TelemetryConfig`, …).

*Implementation.* New `QueryConfig` case class:
```scala
final case class QueryConfig(
  maxQueryLength: PositiveInt,
  maxQuotedStringLength: PositiveInt,
  maxQuantifierDepth: PositiveInt
)
object QueryConfig:
  val layer: ZLayer[Any, Config.Error, QueryConfig] =
    Configs.makeLayer[QueryConfig]("register.query")
```
HOCON in `application.conf`:
```hocon
register.query {
  maxQueryLength        = 4096
  maxQueryLength        = ${?REGISTER_QUERY_MAX_LENGTH}
  maxQuotedStringLength = 256
  maxQuotedStringLength = ${?REGISTER_QUERY_MAX_QUOTED_STRING_LENGTH}
  maxQuantifierDepth    = 8
  maxQuantifierDepth    = ${?REGISTER_QUERY_MAX_QUANTIFIER_DEPTH}
}
```

*Enforcement sites (split — corrected 2026-05-01).* Length limits are
cheap, depth requires structure:

- `maxQueryLength` and `maxQuotedStringLength` — enforced **pre-lex** in
  `QueryService.evaluate`, before any tokenisation work. Returns
  `ValidationFailed(ValidationError("query", INVALID_LENGTH, "…"))`
  (existing code, used by `ValidationUtil` for the same kind of bound
  check; semantics: "String length constraint violated").
- `maxQuantifierDepth` — enforced **post-parse**, before binding /
  evaluation, by traversing the `ParsedQuery` AST. Returns
  `ValidationFailed(ValidationError("query", QUANTIFIER_DEPTH_EXCEEDED,
  "…"))`. **New code added to `ValidationErrorCode`**, placed in the
  existing `── FOL query codes (ADR-028) ─────…` group, following the
  established naming convention (`UPPER_SNAKE_CASE`, descriptive,
  scoped) and the established enum-case shape (one line, with
  human-readable description). No new error-handling **logic** —
  reuses the existing `ValidationFailed` envelope, accumulating
  `Validation` channel, and `ErrorResponse.encode` exhaustive arm
  (ADR-022 §4 — `-Wconf` enforces the new arm).

*No new mechanism.* Both sites use the same `ValidationFailed →
ErrorResponse.encode` pipeline as every other validator in the
codebase; the only additions are one new `ValidationErrorCode` enum
case, its `ValidationErrorCodeSpec` round-trip case, and the matching
`ErrorResponse.encode` arm.

*Client side.* **Investigation deferred to a mandatory follow-up task**
(see §9 F-R2). The instruction "do not change the existing pattern"
means: if `AnalyzeView` already validates query length via a
`Var[String]` length signal, extend it to read from a config-derived
value; if it does no length validation today, mirror the server limit
as a constant at the client boundary without adding a new pattern.

---

## 6.1 Decisions Closed in chat (2026-04-30 round 2)

D-1, D-2.a, D-2.b have been settled in §6 above. ADR-018 was confirmed
satisfied at the register-side `NodeId` boundary; no `AssetName` wrapper
is needed because the register-side validation gate is upstream of FOL
and the dispatcher uses pure `Set`/`Map` lookup (no string interpolation
or eval). See §11 below for the injection-safety analysis that supports
this verdict.

---

## 7. Implementation Order (Conditional on §6 Approvals)

Once §6 is settled:

0. **Phase 0 — DONE (2026-04-30): Demo scripts rewritten** to avoid the
   three bugs. All four scripts (`demo-simple-{httpie,curl}.sh`,
   `demo-enterprise-{httpie,curl}.sh`) now exercise the full query
   surface (`gt_loss`, `gt_prob`, `p95`, `p99`, `lec`, `leaf`,
   `portfolio`, all four quantifier shapes) **without** quoted node-name
   literals. Each script carries a NOTE banner pointing back to this
   plan; the dropped sub-portfolio queries (e.g.
   `leaf_descendant_of(x, "Technology & Cyber")`) will be re-added once
   F1+F2+F3 land. ADR-028 + ADR-028-appendix syntax drift fixed in the
   same commit.
1. **Phase 1 — fol-engine: F1 (lexer)** — write tests in §5.1 first (TDD); implement; `sbt test` green.
2. **Phase 2 — fol-engine: F2 (term parser)** — tests §5.2 + §5.3; implement; `sbt test` green.
3. **Phase 3 — fol-engine release & register bump** — `version := "0.10.0-SNAPSHOT"`; `sbt publishLocal`; bump register's `build.sbt`.
4. **Phase 4 — register: F3 (catalog constants)** — tests §5.4 + §5.5; implement; `sbt test` green.
5. **Phase 5 — register integration test** — §5.6; `sbt serverIt/test` green.
6. **Phase 6 — re-enable quoted-name queries in demo scripts** — restore the dropped sub-portfolio queries.
7. **Phase 7 — Post-implementation ADR review** (per WORKING-INSTRUCTIONS).

Each phase ends with the WORKING-INSTRUCTIONS § Approval Checkpoint and a
**hard halt** awaiting explicit user continuation.

---

## 8. Out of Scope

- Any change to the `fol.typed` semantic layer beyond `TypeCatalog.constants`
  population.
- Tree-name uniqueness enforcement at the create-tree DTO (subject to D-2.a).
- New comparison predicates (e.g. `lt_loss`, `eq_loss`, generic `=`).
- Frontend changes to query suggestion / autocomplete.
- ADR-013 `Proposed → Accepted` resolution (separate; see §9).
- ADR-020 SNAPSHOT-in-prod resolution (deferred per user).

---

## 9. Mandatory Follow-up Tasks (added per user 2026-04-30)

The user prefers code reviews not be done inline during planning but
captured as named follow-ups.

- **F-R1 — Code review: `QueryController` + `QueryServiceLive` for ADR-022
  §6 secret-leakage discipline.** Verify all error-path messages are
  free of `WorkspaceKeySecret` and that `getMessage`/`formatted`
  invocations on `AppError` subtypes never widen to include workspace
  context. Run before merge of F3.
- **F-R2 — Code review: client-side query input length validation.**
  Inspect `AnalyzeView` (or whichever component owns the query
  `Var[String]`) for any existing length-validation pattern. If one
  exists, extend it to honour the new `register.query.maxQueryLength`
  (mirrored to the client at build time or via `/config` endpoint —
  whichever the codebase already uses). If none exists, add a constant
  matching the server limit at the input boundary; do NOT introduce a
  new validation pattern. Run alongside D-8 implementation.
- **F-R3 — ADR-013 status resolution.** ADR-013 is Proposed (not
  Accepted) and uses non-standard "wiring test" terminology with zero
  `*WiringSpec*` files in the codebase. Decide whether to (a) accept it
  in its current spirit but rename the terminology to
  "integration-tier", (b) reject and remove, or (c) leave Proposed and
  defer further. Independent of this plan.
- **F-R4 — ADR-020 SNAPSHOT-in-prod resolution.** fol-engine remains on
  `0.X.0-SNAPSHOT` per user direction. Track resolution separately.
- **F-R5 — Decide whether to tighten `SafeName`'s character class.**
  `SafeName = String :| (Not[Blank] & MaxLength[50])` (see
  [OpaqueTypes.scala:101](modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala#L101))
  imposes no content restriction beyond non-blank + ≤50 chars. After the
  bug fix lands, a node named `foo,bar)` will be storable and
  unreferencable from a query (the comma terminates the FOL term, the
  paren mismatches). Not a security issue (see §11) but a UX one. Decide
  whether to add a refinement like `Match["[A-Za-z0-9 _-]{1,50}"]` and
  whether to migrate existing names. Out of scope here.

---

## 10. Injection-Safety Analysis (added 2026-04-30 round 2)

The user asked: "*Note that strong typing is also a security feature
against injection attacks. Does your assessment still hold? If we are
protected against injection good, I am just checking whether this aspect
was considered and whether we are practically protected in this
specific case with this type information.*"

**Verdict: yes, the system is practically protected against query
injection via node names — but the protection comes from the
`Map.get` / `Set.contains` lookup discipline, not from the type system
per se. A typed lexer (D-1) does not change the answer.**

### Threat model

A user with workspace-key auth creates a node whose name contains
characters meaningful to the FOL grammar (e.g.
`foo"), gt_loss(p95(x), 1000000000), leaf(x`). They then issue a query
that quotes the name as a string literal. The question is whether the
attacker can smuggle additional FOL syntax through the name.

### What enforces safety today

1. **`SafeName` content.** `SafeName = String :| (Not[Blank] &
   MaxLength[50])` forbids only all-whitespace and >50 chars. **It does
   NOT restrict characters.** So a name *can* contain `"`, `(`, `,`,
   newlines, etc.

2. **Minimal quoted-string lexer (D-4).** Grammar: `"`, any character
   except `"` or newline, `"`. **The first `"` ends the string.** A
   user-supplied name containing `"` cannot be referenced from a query
   — the lexer terminates the literal at the embedded quote, and the
   trailing characters become separate tokens that the parser will
   reject as malformed. Zero injection.

3. **`Map.get` / `Set.contains` lookup discipline in the dispatcher.**
   Verified by reading
   [RiskTreeKnowledgeBase.scala:117–230](modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala#L117).
   Every dispatcher arm uses pure equality lookup:
   `leafNames.contains(s)`, `portfolioNames.contains(s)`,
   `childrenByName.getOrElse(parent, Set.empty).contains(child)`,
   `descendantsByName.getOrElse(ancestor, Set.empty)`,
   `nameToResult.get(assetName)`. **No string interpolation, no
   concatenation, no dynamic eval, no SQL/HOCON/template generation.**
   A name like `foo"), …` would just be a key that doesn't match any
   real node → `Right(false)` for predicates, `Left("p95: not found")`
   for functions → mapped to `UNKNOWN_REFERENCE` at the boundary.

4. **`catalog.constants` registration (the F3 fix).** Each node name
   becomes a `Value(Asset, name)` in the constant map. The catalog
   resolves a quoted literal `"foo"` by `constants.get("foo")` — exact
   string equality. No partial match, no regex.

### Where the type system actually contributes

The many-sorted FOL type checker (`fol.typed`) prevents *cross-sort*
mistakes: e.g. `gt_loss(lec(x, 1000000), 0.05)` is rejected at type
check (Loss vs Probability). That is a correctness guarantee, not an
injection guarantee. It does not, by itself, prevent string-content
attacks — those are blocked by the lookup discipline in (3).

### Would a typed lexer (D-1) add anything?

No, not for injection. A typed `Token.StringLit("foo")` vs
`Token.Word("foo")` distinguishes intent (user wrote a literal vs an
identifier) but the resulting *value* still flows into `constants.get`
and `Set.contains`. The injection surface is "what string can reach the
dispatcher's lookup", and that's bounded by the lexer's `"`-terminator
rule + the lookup's equality semantics — both unchanged by typing.

### What ADR-018 does and doesn't apply to

ADR-018 ("Nominal Wrappers") prescribes wrapping Iron-validated values
in `case class` to make compile-time-distinct domain concepts
distinguishable to the compiler. The register codebase satisfies it at
the `NodeId` (and `TreeId`) wrapper boundary: both are `case class`es
around the same Iron `SafeId`, and the compiler cannot pass one for
the other. By the time a node name reaches the FOL dispatcher, it has
been resolved against `nameToNodeId: Map[String, NodeId]` to a real
`NodeId` — so the wrapper line is at the resolution point, not deeper.

Inside fol-engine, the `Asset` sort is backed by `String`. Wrapping
that as `case class AssetName(value: String)` would force codecs and
lookup-key conversions at every dispatcher call site without
preventing any concrete bug — `extractString` already validates
`v.raw` is a `String` at the dispatcher boundary, the many-sorted type
checker enforces sort-correctness upstream, and the register-side
wrapping is at `NodeId`. The wrapper would be ceremony.

### Verdict & residual risk

- ✅ ADR-018 is **satisfied in practice** at the `NodeId` boundary.
- ✅ Injection via node-name literals is **structurally prevented** by
  the lexer's `"`-terminator + the dispatcher's equality lookup.
- ⚠️ Residual: a malicious tree creator can create node names that are
  *unreferencable* from a query (e.g. names with commas or
  unbalanced parens). This is a UX / data-quality issue, not an
  injection one. Tracked as **F-R5** in §9.
- ⚠️ Residual: any future change that interpolates a node name into a
  string that is later parsed (e.g. for logging templates, query
  echoing into a different parser, error messages re-fed to the
  evaluator) re-opens the surface. **Mitigation discipline:** any
  log/echo path MUST pass the name through unaltered to the rendering
  sink, never concatenate it into a parser-bound string. F-R1 (controller
  + service code review) covers verification of this.

---

## 11. Post-Fix Acceptance Set (added 2026-05-01)

The business value of F1 + F2 + F3 is the ability to scope vague-quantifier
queries to a named sub-portfolio. The four queries below are the
minimum demonstration set: each MUST execute successfully against the
enterprise demo tree (`examples/demo-enterprise-{httpie,curl}.sh`) and
MUST return a Boolean answer consistent with the underlying simulation
data. Re-adding them to the demo scripts is Phase 6.

| ID | Query | Exercises |
|---|---|---|
| Q-A | `Q[>=]^{2/3} x (leaf_descendant_of(x, "Technology & Cyber"), gt_loss(p95(x), 5000000))` | sub-portfolio descendant scoping + P95 |
| Q-B | `Q[>=]^{1/2} x (child_of(x, "Operational Risk"), gt_prob(lec(x, 10000000), 0.05))` | direct-child scoping + LEC + Probability |
| Q-C | `Q[>=]^{2/3} x (leaf_descendant_of(x, "Financial Risk"), gt_loss(p99(x), 20000000))` and the same shape with `"Operational Risk"` | cross-branch comparison |
| Q-D | `Q[<=]^{1/3} x (leaf(x), ~descendant_of(x, "Technology & Cyber"), gt_loss(p95(x), 1000000))` | exclusion via negation |

A fifth motivating query (single-leaf identity via `eq`) exists in the
README's "coming soon" block but is **out of scope here** — no `eq`
predicate is registered in the typed dispatcher used by `register`, and
there is a deeper typed-vs-untyped fol-engine pipeline question to
resolve before promising it. Tracked separately in
[../TODO.md](../TODO.md) §8.

Acceptance criteria added to Phase 5 (§7) — the integration spec
(§5.6) covers Q-A as scenario H1; Q-B / Q-C / Q-D each get an analogous
scenario in the same spec. Acceptance criteria added to Phase 6 — each
of Q-A..Q-D restored as an executable line in both
`demo-enterprise-httpie.sh` and `demo-enterprise-curl.sh` with its
plain-English caption, replacing the corresponding line dropped in
Phase 0.

---

## 12. Halt Marker

> 🛑 **Per WORKING-INSTRUCTIONS § Mandatory Review Halt, the agent now stops.**
>
> No code changes, no further file edits, no commands until the user provides
> an explicit continuation signal AND resolves the open decisions in §6.

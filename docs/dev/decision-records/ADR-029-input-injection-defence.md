# ADR-029: Input Injection Defence — Parse, Don't Re-Parse

**Status:** Accepted  
**Date:** 2026-06-22  
**Tags:** security, injection, iron, validation, boundary

---

## Context

- User-supplied strings can carry payloads that change semantics when
  interpreted by a downstream parser (FOL, SQL, HOCON, HTML, URL, CSS,
  log templates).
- A type that wraps a `String` only blocks injection to the extent that
  its **content domain is restricted at construction time**; the wrapper
  alone is decorative.
- Every parser boundary is a potential injection sink: the same string
  that is safe in JSON becomes dangerous when concatenated into a FOL
  formula or a log template that is later re-parsed.
- Iron refinement at the type boundary and structural output-layer
  guarantees (e.g. Laminar's typed DOM API) are complementary and both
  required; neither is sufficient alone.

---

## Decision

### 1. Restrict content domains at the Iron boundary

Every `String`-backed user-input type must carry a `Match[...]`
constraint that reflects the narrowest character set the business domain
permits — not merely `Not[Blank] & MaxLength[N]`.

```scala
// Correct: whitelist-only constraint
type SafeNameConstraint = Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9 /\\-]+$"]
type SafeNameStr = String :| SafeNameConstraint

// Wrong: only length/blank; any string content enters the system
type SafeShortStr = String :| (Not[Blank] & MaxLength[50])  // general use only
```

The character set must exclude any character that carries special meaning
in downstream parsers (e.g. `"`, `(`, `)`, `&`, `<`, `>` for
display-name fields).

### 2. Never concatenate user strings into parser input

User-supplied values reach a downstream interpreter by one of two routes, in
this order of preference. A boundary using the second records that choice as a
row in §3. Any third route is rejected.

**Preferred — a parameterised, structured, or AST-level interface.** Values
travel beside the text rather than inside it, so the interpreter never lexes
them and no character they contain can become syntax. The typed Quill DSL,
zio-json codecs, Laminar's `textContent`, and a GraphQL document's `variables`
member are all this route.

**Approved alternative — interpolation of values whose type excludes the
interpreter's syntax.** Where the target offers no parameterised interface for
what is being built, a value may be interpolated if its Iron refinement admits
no character carrying meaning in that interpreter. The guarantee is then held by
the type at compile time rather than by the protocol at run time, which is why
it is approved rather than merely tolerated — but it holds only for values that
carry such a type. A raw `String` interpolated into parser input is never this
case, whatever its provenance.

**Rejected — everything else**, and in particular escaping a raw value at the
interpolation site as the primary guard. Escaping defends one rendering and
depends on the escaper being complete and on every site remembering to call it;
neither property is checked by the compiler. Escaping remains correct as a
second layer over free text that has no narrower type available, which is the
only role §3 records it in.

```scala
// Wrong: string interpolation into a FOL query that will be re-parsed
val query = s"""leaf(x) /\\ gt_loss(p95(x, "inherent"), ${userInput})"""

// Correct: user string resolved via a per-sort literal validator — never re-parsed
val result = riskNameToId.get(userInput)  // Set.contains / Map.get only
```

### 3. Parser boundaries in this codebase

| Boundary | Current guard |
|---|---|
| FOL `VagueQueryParser.parse` | Query text is user-typed; node references resolve at bind time through per-sort literal validators — `riskNameToId.get` for a name (`Node` / `NodeNameLiteral` sorts), `NodeId.fromString` for an id (`NodeIdLiteral`) — whitelist-constrained, never interpolated |
| FOL `TargetingPredicate.create` | Mitigation targeting text is user-typed; length-bounded (1–256) then parsed via `FOLParser`, then restricted to the targeting fragment (no quantifiers, no function terms), a single free variable, and no mitigation-state predicate. `decode == create`, so no `TargetingPredicate` exists whose source was not validated; the parsed formula is derived state, never re-serialised or interpolated |
| JDBC / Quill | Parameterised queries via typed DSL; no hand-rolled SQL |
| Irmin GraphQL (`IrminQueries`) | **Approved alternative (§2).** Documents are built by interpolation, and every value interpolated into one is derived from an Iron-refined type and nothing else. Branch names (`BranchRefStr`, `MergeStagingRefStr`), paths (`IrminPath`) and commit hashes (`CommitHash`) carry refinements whose character classes exclude `"` and `\`. A stored node is zio-json output whose every string-bearing field is refined — `SafeId`, `SafeName`, `NodeId`, `DistributionType` — so neither `RiskLeaf` nor `RiskPortfolio` has a free-text field, and no user-supplied character that carries meaning in GraphQL can enter the document. Commit message and author are server-constructed, not user-supplied. `escapeGraphQLString` is the second layer over those free-text values and emits every character below U+0020. One interpolation in the branch **selector** is structure rather than a value and so could not move to `variables` even if the rest did: a read of a named branch aliases it as `main` so that every response decodes identically, and the choice between that aliased call and the bare `main` selector is a choice between two document shapes. The branch name inside it is an ordinary argument value |
| zio-json encode/decode | Codecs handle escaping; no manual string construction |
| Laminar DOM | `textContent` / typed setters; `innerHTML` is never called |
| ZIO logging | `s"…${treeId.value}…"` — interpolated values are Iron-validated wrappers, not raw user input |
| HOCON config | Server-side only; not user-supplied |
| HTTP request body | zio-http `RequestStreaming.Disabled(maxRequestBytes)` — bodies over the configured cap (default 8 MiB, env `REGISTER_MAX_REQUEST_BYTES`) are rejected with 413 before any handler runs, bounding request memory (DoS) and the largest accepted tree payload. Resource-limit table: ADR-017 §6 |

If a new code path introduces a parser boundary not in this table,
document it here and verify it honours the no-re-parse discipline.

### 4. Iron whitelisting is defence-in-depth, not the primary XSS guard

For Laminar-rendered output, the primary XSS guard is Laminar's typed
DOM API (writes via `textContent`, structurally cannot inject HTML).
Iron whitelisting is a backup layer — it limits payloads if the
structural layer is ever bypassed or a new non-Laminar output path is
added.

Any new output path that uses a user-input type outside Laminar must
apply context-aware encoding at the rendering site:

```
HTML text   →  htmlEncode(value)    (or use a typed Html wrapper)
URL param   →  urlEncode(value)
CSS         →  cssEncode(value)
```

---

## Code Smells

### ❌ Raw string in downstream parser position

```scala
// BAD: user string concatenated into a FOL expression
val formula = s"""leaf(x) /\\ gt_loss(p95(x, "inherent"), "$threshold")"""

// GOOD: threshold is a typed constant; no re-parse
val thresholdVal: Long = threshold.value   // Long, not String
```

### ❌ Unwhitelisted user-input type

```scala
// BAD: accepts any printable character — & ( ) " etc. can enter
type SafeShortStr = String :| (Not[Blank] & MaxLength[50])
opaque type SafeName = SafeShortStr   // no content restriction

// GOOD: content domain matches business need
type SafeNameConstraint = Not[Blank] & MaxLength[50] & Match["^[A-Za-z0-9 /\\-]+$"]
opaque type SafeName = String :| SafeNameConstraint
```

### ❌ Trusting Iron at a new non-Laminar output path

```scala
// BAD: assumes Iron whitelist means no encoding needed in HTML email
val body = s"<p>Node: ${node.name.value}</p>"  // must HTML-encode even if whitelist is tight

// GOOD: encode at the rendering site
val body = s"<p>Node: ${HtmlEncoder.encode(node.name.value)}</p>"
```

---

## Implementation

| Location | Pattern |
|---|---|
| `OpaqueTypes.scala` — `SafeNameConstraint` | Whitelist refinement for display names |
| `OpaqueTypes.scala` — `ValidEmail` | Whitelist regex for email |
| `RiskTreeKnowledgeBase` dispatcher | `Map.get` / `Set.contains` — node names never interpolated into FOL |
| `BinderIntegrationSpec` B3 | Injection-shaped name rejected at construction with `INVALID_PATTERN` |
| `BinderIntegrationSpec` B4 | Malformed query string rejected at parse/bind level |
| `code-quality-review` §6 | XSS two-layer model; MUST-FIX for new output paths without encoding |

---

## References

- OWASP Top 10 A03:2021 — Injection
- CWE-89 (SQL injection), CWE-79 (XSS), CWE-94 (code injection), CWE-77 (command injection)
- ADR-001: parse-don't-validate (the same intuition applied to inbound data)
- ADR-035: Error Leakage Prevention (outbound counterpart)
- ADR-018: nominal wrappers (compile-time distinction between semantically different strings)

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

User-supplied values must flow into downstream interpreters only via
parameterised, structured, or AST-level interfaces — never via string
concatenation followed by a second parse.

```scala
// Wrong: string interpolation into a FOL query that will be re-parsed
val query = s"""leaf(x) /\\ gt_loss(p95(x), ${userInput})"""

// Correct: user string looked up via Map.get — never re-parsed
val result = catalog.constants.get(userInput)  // Set.contains / Map.get only
```

### 3. Parser boundaries in this codebase

| Boundary | Current guard |
|---|---|
| FOL `VagueQueryParser.parse` | Query text is user-typed; node names enter via `catalog.constants` lookup (`Map.get`), never interpolated |
| JDBC / Quill | Parameterised queries via typed DSL; no hand-rolled SQL |
| zio-json encode/decode | Codecs handle escaping; no manual string construction |
| Laminar DOM | `textContent` / typed setters; `innerHTML` is never called |
| ZIO logging | `s"…${treeId.value}…"` — interpolated values are Iron-validated wrappers, not raw user input |
| HOCON config | Server-side only; not user-supplied |

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
val formula = s"""leaf(x) /\\ gt_loss(p95(x), "$threshold")"""

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
- ADR-022: Secret Handling & Error Leakage Prevention (outbound counterpart)
- ADR-018: nominal wrappers (compile-time distinction between semantically different strings)

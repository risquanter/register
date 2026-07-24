---
name: code-quality-review
description: "Critical code quality review for Scala 3 / ZIO / Laminar codebase. Load when performing a pre-commit review, reviewing a diff, or auditing completed implementation work. Covers: algebraic design-first pass, ADR compliance, type safety, functional design, API surface, duplication, security, compiler hygiene, test quality, plan fidelity."
user-invokable: true
argument-hint: "files or diff to review (attach changed files, or describe the scope)"
---

# Code Quality Review — Register

Use this skill to review changed files or a diff before committing.
For each criterion report one of:

- **PASS** — no issues found
- **FINDING (severity)** — issue, location, concrete fix

Severities: **MUST-FIX** (blocks commit) · **SHOULD-FIX** (quality debt) · **NOTE**

Do not rubber-stamp. Report PASS when clean. Report FINDING when not. Do not propose
options without flagging which decision the user must make — **all decisions are the
user's to resolve**.

Two binding rules on the report itself (working-protocol G6):

- Presenting the review report is a presentation — halt after it. No fixes,
  no commits, no further tool calls until the user rules on the findings.
- Never self-classify a finding as "non-blocking", "note-only-so-proceeding",
  or "acceptable" on the user's behalf. Severity is yours to assess; the
  disposition of every finding is the user's.

The criteria below are not exhaustive. Use general principles and industry best
practices to catch issues the checklist has not yet anticipated.

---

## Pass 0a — Domain primitive typing (Layer A₀, run first)

For every **new field, parameter, case class member, or function parameter** in the
diff, apply the Layer A₀ questions from `scala-algebraic-design.instructions.md`:

- Is the type `String`, `Int`, `Long`, or `Double` for a concept that has a natural
  valid-value subset? → Missing Iron refinement. **MUST-FIX.**
  Signal words: `name`, `label`, `slug`, `id`, `email`, `url`, `probability`,
  `count`, `weight`, `rate`, `score`, `description`, `title`, `path`.
- Is there a maximum/minimum length implied by a DB column, protocol, or UI limit
  that is not expressed in the type? **MUST-FIX.**
- Is there a character-set restriction (HTML rendering, path, URL segment) that is
  not expressed in the type? **MUST-FIX** — missing restriction is an injection
  surface before any processing runs.
- Could this field be confused with another field of the same raw type? (Two
  `String` IDs for distinct concepts.) → Missing nominal wrapper per ADR-018.
  **MUST-FIX.**
- Does the field originate from user input and lack an Iron refinement? **MUST-FIX.**

**Within-domain adhesion:**
- Is `.value` called on an Iron type outside of: (a) a Tapir codec or JSON
  encoder, (b) a repository query, (c) a third-party library bridge?
  Every other `.value` call is a within-domain widening. **SHOULD-FIX.**
- Is there a helper function / private method that accepts `String` where the
  caller is passing a refined type's raw value? The function signature should
  accept the refined type. **SHOULD-FIX.**

---

## Pass 0b — Algebraic structure (Layers A/B, run after 0a)

Ask: did the implementation reach for algebraic structures proactively, or did it
invent ad-hoc logic that a Monoid / Functor / Validation would have eliminated?

- Is there a manual aggregation loop where `Identity[A].combine` + `ZIO.foreach`
  would suffice? **SHOULD-FIX.**
- Is there manual per-field error accumulation where `Validation.validateWith` would
  collect all errors? **MUST-FIX** — fail-fast changes user-visible error behaviour.
- Is there a manual match that threads non-success cases unchanged where `map` would
  collapse to a single line? **SHOULD-FIX** — structural gap, not style.
- Is there an inline multi-line lambda where a named companion function would make
  the operation testable and the call site a one-liner? **SHOULD-FIX.**
- Does the new type have the algebraic instances its usage implies? A type that is
  folded over a collection but has no `Identity` instance is incomplete. **SHOULD-FIX.**

### `if/else` checklist — run on every `if` or `if/else` encountered

Every `if/else` in new or changed code must pass all of the following before being
accepted as clean:

1. **Single value produced?** If the branches compute *multiple* related values
   (e.g. `code` and `message` both depend on the same condition), the values are
   semantically coupled and must travel together. Replace parallel `if/else` chains
   or parallel `val` bindings with a single `match` on a tuple or sealed ADT that
   produces all derived values in one expression. **SHOULD-FIX.**

2. **Condition repeated?** If the same boolean predicate appears in more than one
   `if/else` arm (including hidden repetition via `!flag && otherFlag`-style
   guards), the classification is being done twice. Extract the classification into
   one `match` with exhaustive cases. **SHOULD-FIX.**

3. **Exhaustive?** If branches don't cover all inputs the type system permits,
   the `if/else` is partial. Prefer `match` with a wildcard arm that makes the
   default explicit. **SHOULD-FIX** — silent defaults are correctness risks.

4. **Can a `map`/`fold`/`Option` replace it?** `if (x.isEmpty) None else Some(f(x))`
   → `x.map(f)`. `if (cond) Left(e) else Right(v)` → `Either.cond(cond, v, e)`.
   Every such substitution removes a branch and makes intent clearer. **SHOULD-FIX.**

5. **Is it a guard on a domain value?** `if (name.length > 50)` in service/handler
   code means a constraint that should have been enforced at the Iron type boundary
   has leaked inward. **MUST-FIX** — route through the smart constructor instead.

---

## 1. ADR Compliance

- Do changes align with all applicable ADRs in `docs/dev/`?
- Are ADR constraints violated or silently weakened?
- If change scope exceeds what an ADR anticipated, is the deviation justified and
  documented?

---

## 2. Type Safety & Algebraic Soundness

- Are type class instances lawful? (Identity, associativity, commutativity where
  required.) **MUST-FIX** if a provided instance is not lawful.
- Is `asInstanceOf` / `isInstanceOf` used? Is it genuinely unavoidable? **MUST-FIX**
  if avoidable.
- Are variance annotations (`+A`, `-A`) correct and intentional?
- Does new generic code introduce unchecked type erasure at runtime?
- Are service / repository method parameters Iron types, never raw `String` or `Int`?
  (If not, this is also a Pass 0a MUST-FIX.)

---

## 3. Functional Design

- Pure functions where possible. Side effects at the edges.
- Total functions preferred (`Option`, `Either`, sealed error type) over partials.
- Pattern matching exhaustive. `MatchError` risks eliminated.
- **Functor smell:** Manual match threading non-success variants unchanged. **SHOULD-FIX.**
- **Inline logic smell:** Lambda > 3 lines inside `.map`, `.combineWith`, signal
  combinator. Extract as named pure companion function. **SHOULD-FIX.**
- **Orchestration smell:** Code manually unwraps, transforms, re-wraps. That is `map`
  written longhand. **SHOULD-FIX.**

---

## 4. API Surface & Design Integrity

- New public types / methods not required by the task? **MUST-FIX** — unused API is a liability.
- Every new public method has at least one call site? Zero-caller method is dead code. **MUST-FIX.**
- Existing public APIs removed or signature-changed without migrating all call sites? **MUST-FIX.**
- Default parameter values unchanged unless task explicitly requires it?

---

## 5. Code Duplication & DRY

- Logic duplicated across files or within a file?
- Repeated patterns extractable into a shared helper, type class, or combinator?
- Near-identical blocks differing only in a type parameter or constant?

---

## 6. Secure Design

- Exceptions only for truly unrecoverable situations? Prefer typed errors.
- Error messages free of secrets, PII, internal paths?
- Input validation at the boundary (codec / smart constructor), not in service?
- No `catch` blocks that silently swallow exceptions?
- New credential types satisfy R1–R8 from ADR-022?

### XSS defence-in-depth — two layers, both required

When Iron type constraints exclude HTML-meaningful characters (`<`, `>`, `&`, `"`):

- **That is defence-in-depth, not the primary XSS guard.**
  The primary guard for Laminar-rendered output is Laminar’s typed DOM API, which
  writes via `textContent` / typed setters, never `innerHTML`. Structural prevention
  cannot be bypassed regardless of string content.

- **Iron whitelisting is the backup layer.** Its value is: if the structural layer
  is ever bypassed (e.g. a future contributor calls `.innerHTML` or renders into a
  non-Laminar context), the input domain is already restricted.

- **New output paths require explicit output encoding.**
  If a value typed as `SafeName` (or any Iron-refined user-input type) is used in:
  - Email template bodies
  - PDF export content
  - Server-rendered HTML (Twirl, Scalatags, etc.)
  - Log lines rendered as HTML in a dashboard
  - URL path or query parameters
  - CSS selectors or `style` attribute values

  ...then context-aware encoding must be applied at the rendering site. The Iron
  whitelist is insufficient in those contexts because allowed characters (`/`, `-`,
  letters) can form payloads depending on the output language. Flag any new output
  path that consumes a user-input type without visible encoding. **MUST-FIX.**

### OWASP-grounded checklist (run on every diff touching an endpoint, service signature, or container config)

Derived 2026-07-20 from the actual content (not headlines) of the OWASP API Security
Top 10 (2023), the OWASP Top 10 (2021) → Cheat Sheet Series mapping, and the
Authorization / IDOR Prevention / REST Security / Java Security / Secrets Management
cheat sheets. Register's attack-surface shape (capability-URL + optional JWT +
SpiceDB layered auth, Tapir/ZIO REST API, Quill/Postgres, Docker Compose/K8s
deployment, no browser session cookies) is stable, so this list is meant to be
reused as-is, not re-derived per review — update it only when the architecture
itself changes (e.g. a new auth mechanism, a new external API integration).

**Hard rule — `WorkspaceId` never crosses the client boundary, either direction
(2026-07-20).** Full reasoning in `security.instructions.md`'s "Internal
identifiers" section; check both halves on every diff:
- **Output:** no response field, header, error message, or log line returned
  to a client may contain a raw `WorkspaceId`. **MUST-FIX.**
- **Input:** no endpoint may accept a bare `WorkspaceId` as a path segment,
  query parameter, header, or body field — not even alongside a capability
  check. Every endpoint must derive `WorkspaceId` server-side from a resolved
  `WorkspaceKeySecret`, never accept it directly. This holds even if the value
  is never echoed back: an endpoint that behaves observably differently for a
  valid vs. invalid ID (status code, error shape, timing) is an enumeration
  oracle on its own. **MUST-FIX.**

**API1:2023 Broken Object Level Authorization (BOLA) — the finding that produced
this checklist (2026-07-20 X-Active-Branch review).** For every new parameter
that is a *reference* (an ID, name, or key naming some other object) rather than
a value:
- Is it resolved into a scoped principal server-side (`workspaceStore.resolve(key)`
  → `WorkspaceId`), or is a client-supplied value used directly to select data?
  **MUST-FIX if used directly.**
- If it names a resource that could belong to a different tenant/workspace
  (a branch, another workspace's tree/scenario), is ownership checked explicitly
  — not merely relied upon because of how an unrelated code path (e.g. storage
  path construction) happens to be scoped today? Per the Authorization Cheat
  Sheet's "Authorization Bypass Through User-Controlled Key" and the IDOR
  Prevention Cheat Sheet: "just because a user has access to an object of a
  particular type does not mean they should have access to every object of that
  type" — restrict the *lookup itself* to the authenticated principal's own
  resources, don't rely on the result happening to come back empty.
  **MUST-FIX.**
- Does the rejection path for "belongs to someone else" return the exact same
  shape as "doesn't exist"? A distinct 403 vs 404 is itself an enumeration
  oracle (existence disclosure) — collapse both to one response. **SHOULD-FIX.**

**API2:2023 Broken Authentication.** Register's Layer 0 already uses
`SecureRandom` for capability tokens (ADR-021) and Layer 1 JWT validation is
delegated to the mesh, never parsed in-app (see `security.instructions.md`).
Check: does new code introduce any other credential/session mechanism, or
weaken `WorkspaceStore`'s dual-timeout (absolute + idle) expiry? **MUST-FIX**
if so — this is a Decision Trigger (auth boundary), not a routine change.

**API3:2023 Broken Object Property Level Authorization (mass assignment /
over-exposure).** For every new request DTO field: if a client set it to a
value belonging to another tenant, does the service layer re-derive the scope
from the authenticated principal instead of trusting the field (mirrors
`ScenarioServiceLive` building the branch string itself from the resolved
`wsId`, never from client input — DD-11)? For every new response DTO field:
does it leak data the caller shouldn't see (internal IDs, other branches'
state)? **MUST-FIX** on either direction. (`WorkspaceId` specifically is
covered by the standalone hard rule above, not just this general check.)

**API4:2023 Unrestricted Resource Consumption.** New endpoints accepting a
count/size/depth must be bounded at the type boundary (Iron refinement with a
max), matching `REGISTER_MAX_NTRIALS`/`REGISTER_MAX_PARALLELISM`/
`REGISTER_WORKSPACE_MAX_TREES`. Flag any new unbounded `List[_]`/`Set[_]`
request body (e.g. a node-ID list with no max-size constraint) as
**SHOULD-FIX** — note as a known existing gap on `getWorkspaceLECCurvesMultiEndpoint`'s
`jsonBody[List[NodeId]]`, worth a follow-up item, not blocking on its own.

**API5:2023 Broken Function Level Authorization.** Every new service method
that reads or mutates privileged data must require `using Checked[Permission]`
in its signature (ADR-024) — a method that can be called without that proof
type is a function-level authorization gap. **MUST-FIX.**

**API6:2023 Unrestricted Access to Sensitive Business Flows.** New
resource-creation endpoints should be evaluated for abuse potential the same
way workspace bootstrap already is (`REGISTER_WORKSPACE_MAX_CREATES_PER_IP`).
Note as a known gap: scenario creation has no per-workspace rate limit today
— flag if a diff touches scenario creation without addressing it.
**SHOULD-FIX** (not a regression, but worth closing opportunistically).

**API7:2023 Server-Side Request Forgery.** Not applicable today — no endpoint
fetches a client-supplied URL server-side (`IRMIN_URL` is operator-configured,
never client input). Any future feature that fetches-by-reference (import from
URL, webhook, remote export) must validate against an allowlist before
fetching. **MUST-FIX** if introduced without one.

**API8:2023 Security Misconfiguration.** New Docker services/config must
default to the most restrictive setting, matching the existing precedent
(`read_only: true`, `no-new-privileges:true`, CORS origin allowlist,
fail-closed `REGISTER_AUTH_MODE` — see the A-numbered items in
`docs/dev/IMPLEMENTATION-PLAN.md` and `docker-compose.yml`). Document new env vars
in `DOCKER-DEVELOPMENT.md`'s
Configuration table in the same diff that introduces them (this was the gap
found and fixed for `/config.json` on 2026-07-20). **MUST-FIX** on a missing
restrictive default; **SHOULD-FIX** on missing documentation.

**API9:2023 Improper Inventory Management.** Every new Tapir endpoint is
automatically inventoried via the generated OpenAPI/swagger-ui — no action
needed there. But dead-but-reachable-if-misused code (see `WorkspaceStore.resolveById`,
warned 2026-07-20) is the same root problem in a different shape: an
undocumented, unmonitored surface. Any method that bypasses the normal
key-resolution path must carry an explicit warning and zero controller call
sites, checked at review time. **MUST-FIX** if such a method gains a call
site without the ownership check being added alongside it.

**API10:2023 Unsafe Consumption of APIs.** Applies to the Irmin GraphQL client
— already handled via typed `IrminError` decoding and `StartupReadiness`
bounded-wait (ADR-031), not blind trust. Any future third-party API
integration (e.g. an EPSS feed, per the README's cyber-risk roadmap) must
follow the same typed-error, bounded-timeout discipline. **MUST-FIX** if a new
integration parses external responses without a typed error channel.

**OWASP Top 10 (2021, web) items not already covered above:**
- **A03 Injection — Postgres/Quill.** `WorkspaceStorePostgres` must use Quill's
  type-safe query DSL (`run(query[...].filter(...))`) exclusively — never a raw
  `sql"..."` string built from user input. Flag any raw SQL interpolation.
  **MUST-FIX.**
- **A01 CSRF.** Not applicable while auth stays capability-URL + header-based
  JWT (no ambient cookie authority) — classic CSRF targets cookie sessions.
  Re-open this line item the moment any browser-cookie-based session is
  introduced. **Note, not a current finding.**
- **A08 Insecure Deserialization.** No `java.io.Serializable`/`ObjectInputStream`
  anywhere in the codebase — all wire formats are typed zio-json codecs. Flag
  any new use of Java native serialization on untrusted input as **MUST-FIX**.

**Java/JVM-specific (OWASP Java Security Cheat Sheet — no separate "OWASP Java
guide" exists beyond this cheat sheet; SEI CERT's Oracle Coding Standard for
Java is the closest independent reference and largely overlaps, so not
duplicated here).** Register's actual JVM-adjacent surfaces:
- Logging: use parameterized/structured logging (`ZIO.logInfo(s"...")` with
  values, not raw string concatenation of untrusted input into a format that
  could contain control characters) — already the codebase's convention; flag
  any new log line built by directly concatenating unescaped user input.
  **SHOULD-FIX.**
- No `Runtime.exec`/`ProcessBuilder` invoked with user-controlled input
  anywhere in Scala code (shell scripts in Docker entrypoints are a separate,
  operator-controlled surface, not user input). **MUST-FIX** if introduced.
- Cryptography: never hand-roll — `SecureRandom` for tokens (ADR-021) is
  already the only crypto primitive in use; any new crypto need must go
  through a vetted library, not custom code. **MUST-FIX** on custom crypto.

---

## 7. Compiler Hygiene

- Compiles with zero warnings?
- All imports used? No wildcard imports that widen implicit scope?
- Deprecation warnings addressed or explicitly suppressed with documented reason?
- Scala 3: `infix` annotations present on methods designed for infix use?

---

## 8. Test Quality

- New/changed behaviour covered — happy path + all validation constraints + error branches?
- Removed tests justified by removed functionality (not by inconvenience)?
- Fixtures use realistic types (not `Any` / `asInstanceOf` casts)?
- Algebraic law tests present for new type class instances?
- No assertion weakened to make a failing test pass? **MUST-FIX.**

---

## 9. Documentation & Comments

- Scaladoc tags accurate after the change?
- Stale comments or TODOs removed or updated?
- No source code comments referencing plan phases, timelines, or migration history?

---

## 10. Plan Fidelity & Decision Discipline

This is a **MUST-FIX** criterion by default.

- Was the implementation plan followed faithfully?
- For every API shape, method signature, file placement, naming convention in the
  plan: does the implementation match?
- Were all Decision Triggers honoured (no silent deviations)?
- Were all Mandatory Review Halts observed?

---

## 11. Placement & Holistic Improvement

### Co-location

New Iron refinements, opaque types, nominal wrappers, and smart constructors must
be co-located with existing definitions of the same kind. Follow existing file and
naming conventions unless there is an explicit improvement reason:

| New definition | Where it lives |
|---|---|
| Iron type alias / opaque type | Alongside existing refined types in the relevant `Types.scala` or companion object |
| Smart constructor | Companion object of the domain type it validates |
| Tapir codec for an Iron type | `IronTapirCodecs.scala` or the relevant codec file |
| Validation utility / refiner | `ValidationUtil` alongside existing refiners |
| Nominal wrapper (`case class NodeId`) | Alongside other ID wrappers in the same file |

A new Iron type that is not co-located with its siblings creates a split source of
truth and makes the Layer A₀ recognition questions harder to answer in future.
**SHOULD-FIX** if misplaced; **MUST-FIX** if it duplicates an existing definition.

### Holistic improvement protocol

If a systemic issue is identified during review — e.g., multiple fields across
several files that should be Iron-refined, a pattern violation repeated in N places,
or a naming inconsistency in all existing wrappers —:

1. **Do not fix individual instances silently or piecemeal.** A partial fix creates
   inconsistency and will be harder to complete later.
2. **Surface the full scope:** count the occurrences, name the files and line ranges.
3. **Present a holistic fix plan** using the standard Decision Required format.
4. **Await user approval** before touching any instance.

Piecemeal fixes of systemic issues are themselves a **SHOULD-FIX** finding—
they introduce partial states that are worse than the original consistency.

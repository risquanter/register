---
name: adr-constraints
description: "Agent-efficient distillation of all accepted ADRs for the register project. Load during any planning or implementation phase that introduces new types, endpoints, services, or infrastructure changes. Use for: ADR compliance review, pre-implementation planning, architecture alignment checks, boundary ownership questions."
user-invokable: true
---

# ADR Constraints Reference — Register

**This skill owns which design constraints bind.** It is the distillation of the accepted ADRs, so that upholding them does not require reading all of `docs/dev/decision-records/`. It is lossy by design: it keeps each rule and drops its reasoning, so it cannot carry an interaction between two rules. Before writing code, check **When this skill is not enough** below — that section lists what you must be touching to need the full ADRs, and **Known interactions** records the traps that live between them.

It does not own the domain concepts (`docs/user/TERMINOLOGY.md`), how the system is built (`docs/dev/ARCHITECTURE.md`), or what is being built (`docs/dev/plans/`).

## Boundary ownership

| Concern | Owner | Never in |
|---|---|---|
| Input validation | Tapir codec / JSON decoder | Service, Repository |
| Iron type construction | Smart constructor (`create`, `fromString`) | Direct `apply` with primitives |
| Error accumulation | `Validation.validateWith` | `.flatMap` for independent fields |
| Error mapping to HTTP | `ErrorResponse.encode` at the HTTP edge | Service or Repository |
| Authorization check | `AuthorizationService.check()` | Domain model, Repository |
| JWT validation | Istio waypoint (mesh) | Application code |
| Credential lifecycle | Ops tooling (`zed` CLI, CI/CD) | Application code |
| Capability URL entropy | `SecureRandom` | `scala.util.Random` |

---

## Positive invariants — reach for these

| Pattern | ZIO Prelude / Iron type | When |
|---|---|---|
| Multi-field validation | `Validation.validateWith` | Any independent fields that can each fail |
| Aggregation of same type | `Identity[A].combine` / `Semigroup[A]` | Combining child results, merging partial outputs |
| Domain primitive with constraints | Iron refinement + smart constructor | Any new field with a valid-value subset |
| Semantically distinct IDs | `case class` wrapper over Iron opaque type (ADR-018) | Two concepts, same encoding |
| Credential type | `final class` + R1–R8 checklist (ADR-022) | Any type that wraps a secret |
| Sealed error hierarchy | `sealed trait AppError` subtype | Any new error condition |
| Effect sequencing | `for`-comprehension / `ZIO.foreach` | Sequential or parallel effects |
| Reactive state | `Signal` derivation from `Var` (Laminar ADR-019) | Any UI state |
| Combining trial data | `TrialOutcomes.combine` / its `Commutative` instance (ADR-009) | Aggregating a portfolio from its children |
| Reproducible draw | `SeedDerivation.streams` coordinates (ADR-003) | Any sampling |
| Startup dependency gate | `StartupReadiness.awaitReady` in `ZLayer` wiring (ADR-031) | Waiting on a dependency at boot |
| User-input string type | Iron `Match[...]` whitelist, narrowest the domain permits (ADR-029) | Any `String`-backed input type |

---

## Negative constraints

### Types & Validation

❌ NEVER accept raw `String` / `Int` / `Double` as a parameter that carries a
domain value — in ANY function, private helpers and internal plumbing
included, not only service/repository interface methods.
✅ INSTEAD: define or reuse an Iron-refined type; pass the already-validated
value through instead of re-deriving it from a raw primitive.
Exceptions (raw `String` is correct there): smart-constructor/parser inputs
(`from(s: String)` — the boundary where the raw value arrives), genuinely
free text (commit messages, descriptions, log/telemetry keys), and
serialization/escaping helpers.
*ADR-001; scope widened 2026-07-24 (user ruling after `readNodes(basePath: String)` passed earlier reviews)*

❌ NEVER call `DomainObject(rawPrimitive, rawPrimitive)` directly.
✅ INSTEAD: call the smart constructor `DomainObject.create(...)` which returns `Validation`.
*ADR-001*

❌ NEVER use `.flatMap` to accumulate independent validation errors.
✅ INSTEAD: `Validation.validateWith(fieldAV, fieldBV, fieldCV)(...)`; all errors surface.
*ADR-001, ADR-010*

❌ NEVER use a transparent `type TreeId = SafeId` alias when two IDs must be compile-time distinct.
✅ INSTEAD: `case class TreeId(toSafeId: SafeId)` — a nominal wrapper per ADR-018.
*ADR-018*

❌ NEVER write a `case class` credential type.
✅ INSTEAD: `final class` satisfying R1–R8 in ADR-022; `WorkspaceKeySecret` is the reference.
*ADR-022*

❌ NEVER throw exceptions for domain validation failures.
✅ INSTEAD: return `Validation[ValidationError, A]` or `ZIO` with a typed error channel.
*ADR-010*

### API & DTOs

❌ NEVER reuse the same DTO for create and update operations.
✅ INSTEAD: separate `*DefinitionRequest` (no `id` field, server-assigned) and `*UpdateRequest` (`id` required).
*ADR-017*

❌ NEVER validate input in a handler or service method.
✅ INSTEAD: Tapir codec or JSON decoder validates; handler receives already-validated types.
*ADR-001*

❌ NEVER change a Tapir endpoint signature without a Decision Trigger (Decision Trigger #1).
✅ INSTEAD: stop, present options, wait for explicit approval.
*copilot-instructions.md*

### Security & Authorization

❌ NEVER add `grant()` or `revoke()` to `AuthorizationService`.
✅ INSTEAD: tuple writes are ops-only (`zed` CLI, CI/CD provisioning job).
*ADR-024*

❌ NEVER validate JWTs in application code.
✅ INSTEAD: Istio waypoint handles JWT validation; app reads decoded `x-jwt-claims` header.
*ADR-012, ADR-024*

❌ NEVER use `scala.util.Random` for capability URL or token generation.
✅ INSTEAD: `SecureRandom` — cryptographically secure, required by ADR-021.
*ADR-021*

❌ NEVER include secrets, PII, or internal paths in error messages.
✅ INSTEAD: typed error codes (`ValidationErrorCode`) with safe human-readable messages.
*ADR-010, ADR-035*

❌ NEVER give a `String`-backed user-input type only `Not[Blank] & MaxLength[N]`.
✅ INSTEAD: add a `Match[...]` whitelist as narrow as the business domain permits,
excluding every character that carries special meaning in a downstream parser
(`"`, `(`, `)`, `&`, `<`, `>` for a display name).
*ADR-029*

❌ NEVER concatenate a user string into text that will be parsed again.
✅ INSTEAD: reach a downstream interpreter only through parameterised, structured or
AST-level interfaces — a per-sort literal validator (`riskNameToId.get`,
`NodeId.fromString`), the typed Quill DSL, zio-json codecs, Laminar `textContent`.
Never string interpolation followed by a second parse; `innerHTML` is never called.
*ADR-029*

❌ NEVER call a `WorkspaceStore` or `RiskTreeService` method from another service for
cross-cutting orchestration.
✅ INSTEAD: orchestrate in the HTTP handler's `serverLogic` for-comprehension or a
background orchestrator — those are the only valid call sites, because only there is
the authorization check visible and compiler-enforced.
*ADR-030*

❌ NEVER call a service method that reads or mutates workspace or tree data without
`authz.check()` bound as a `given` earlier in the same handler.
✅ INSTEAD: bind the check first; a call site that genuinely cannot carries an
`// exempt:` comment with an approved reason (`workspaceStore.resolve` is the Layer 0
capability gate).
*ADR-030*

❌ NEVER let a confidential internal identifier cross the client boundary in either
direction — not in a response body, header or error message, and not accepted as a
path segment, query parameter, header or body field, even on an endpoint that also
checks a capability.
✅ INSTEAD: accept `WorkspaceKeySecret` and derive `WorkspaceId` server-side via
`WorkspaceStore.resolve`. A value that embeds one is confined as if it were one
(`BranchRef` embeds `WorkspaceId`), made safe by construction rather than scrubbed
afterwards. The ADR-022 credential checklist does **not** apply: these may appear in
server logs, internal storage paths and merge commit messages — what closes them at
the boundary is enumeration-oracle / BOLA (Broken Object-Level Authorization) risk,
not secret leakage.
*ADR-036*

### Frontend

❌ NEVER let a child component create internal `Var`s for state that the parent coordinates.
✅ INSTEAD: parent owns all `Var`s; child receives `Signal`s and emits callbacks.
*ADR-019*

❌ NEVER call `.now()` in a rendering pipeline.
✅ INSTEAD: derive via `Signal`; `.now()` breaks reactivity and produces stale snapshots.
*ADR-019*

❌ NEVER write mutable cross-component state outside `FormState` or `BuilderState`.
✅ INSTEAD: assign new state to exactly one layer (field-level vs assembly-level) before writing.
*ADR-019*

❌ NEVER put SPA routing logic anywhere but the baked-in `nginx.conf`.
✅ INSTEAD: nginx serves the built assets and applies Accept-header discrimination on
`/w/*`; content-hashed static assets carry `Cache-Control: public, immutable,
max-age=31536000` and `X-Content-Type-Options: nosniff`.
*ADR-027*

### Exception catching

❌ NEVER catch `scala.util.control.NonFatal` — on Scala.js it silently misses `UndefinedBehaviorError`; it is never the narrowest sound catch.
✅ INSTEAD: catch the narrowest type guaranteed to cover every failure the call can raise — a named exception on the JVM; `Throwable` only at a Scala.js↔JS interop edge, via the shared `JsBoundary` helper.
*ADR-033*

❌ NEVER add a `catch` to code that calls no throwing API, and never wrap a total-fallback boundary in an error channel no caller reads.
✅ INSTEAD: throw-free code stays catch-free (errors are values, ADR-010); a boundary with no recovery path returns `Option`/`Unit`; typed errors are reserved for failures a caller branches on.
*ADR-033*

### Container & Infrastructure

❌ NEVER build container images manually outside the documented 5-step order.
✅ INSTEAD: follow the build-order in the `register-dev` skill (base → builder → app layers).
*ADR-026*

❌ NEVER commit a CA private key, or reference one from the repository.
✅ INSTEAD: only public certs and non-sensitive generated artifacts are referenced;
private keys are generated locally and stay outside git.
*ADR-023*

❌ NEVER expose a local cluster entry point over plain HTTP.
✅ INSTEAD: HTTPS first; HTTP is redirect-only or explicitly marked local-only.
*ADR-023*

### Supply chain (ADR-020)

❌ NEVER add or update a dependency with a floating version (`^`, `latest`, unpinned install).
✅ INSTEAD: pin exactly in every ecosystem (npm, sbt, opam, apk, Docker `FROM`, wget ARGs).
*ADR-020 §1*

❌ NEVER adopt a dependency version published less than 14 days ago.
✅ INSTEAD: take the newest version older than 14 days. Exception: a fix for a disclosed vulnerability affecting this project is adopted immediately.
*ADR-020 §10*

❌ NEVER add a dependency from an individual or unestablished publisher without user approval.
✅ INSTEAD: prefer well-known organisations; an approved exception gets a comment at the pin site (date, user-approved, reason).
*ADR-020 §11*

❌ NEVER run `npm install`/`npm update` without explicit prior user authorization.
✅ INSTEAD: ask first; then resolve → audit → install → audit → `npm audit signatures`.
*ADR-020 §8–§9; supply-chain + register-dev skills*

---

### Simulation, caching and equality

- The cache stores trial outcomes, never rendered curves. The value is
  `LeafSimResult` — the `TrialOutcomes` carrier plus a content-only
  `NodeProvenance` that carries no node identity (ADR-014).
- There is no cache invalidation. The key is the content hash of the leaf's
  `LeafSimContent` projection, recomputed on every read, so an edited leaf
  hashes to a new key and misses; stale entries become unreachable orphans
  (ADR-014, ADR-032).
- The algebra lives on `TrialOutcomes`, never on a result type.
  `LossDistribution` is the product `NodeId × TrialOutcomes`: the node id is a
  label from tree context, only the trial data combines. `combine` is an
  outer-join pointwise sum and enforces same-`nTrials` alignment (ADR-009).
- Two equality relations, chosen by the question asked, never conflated
  (ADR-032): the **domain content hash** (`ContentHashIndex`, the
  `LeafSimContent` projection for a leaf, Merkle over sorted child hashes for a
  portfolio) answers "does this change simulation results?"; the **storage
  hash** (Irmin blob hash over the full persisted node JSON) answers "did the
  stored artifact change, can a merge conflict here?". Semantic diff uses the
  domain relation, so a renamed or moved node reports `Identical`.
- Simulation writes the cache and reads query it. `CachedResultResolver` owns
  the `ensureCached` primitive; the cache stays pure storage and never
  simulates (ADR-015).
- Every draw is a pure function of the trial number and four seed coordinates.
  `SeedDerivation.streams` is the only place stream coordinates are computed.
  A leaf's occurrence stream is `2 * seedVarId` and its loss stream
  `2 * seedVarId + 1`, so the two are disjoint from each other and from every
  other leaf's (ADR-003).

### Mitigation valuation (ADR-034)

- Two valuations, computed separately. `raw` is the mitigation-free commutative
  fold: cached, and never altered by a mitigation. `mitigated` is a second fold
  computed at the read edge and never stored.
- Each node applies its own transforms to the combine of its children's
  **mitigated** values. Never apply a transform to a cached raw total — it
  cannot see a reduction already applied below it, and the result is wrong, not
  merely approximate.
- Transforms compose by position, never by authoring order. `mitigated` folds
  leaves-upward so a child's transform acts before its parent aggregates;
  within one mitigation `TransformPipeline` steps run in list order; across
  mitigations on one node `MitigationPrecedence` orders them.
- A mitigation may reduce a risk; none may eliminate it, and the rule has two
  enforcement points. A type at the boundary where the parameter decides it:
  the three scale operations take a `RetentionFactor` (above 0, at most 1),
  `LikelihoodTransform.Override` a `ResidualProbability` (same interval), and
  `CapLosses` a `PositiveLong`. A check in `LossDistribution.decorate` where the
  outcomes decide it: a deductible at or above every loss, a threshold above
  every loss, or a scale factor small enough that every loss rounds to zero,
  annihilate only against particular outcomes, so `decorate` refuses a layer
  that leaves no loss where there was one. A figure that
  increases is stated absolutely through an `Override`. A risk that no longer
  exists is removed from the tree, not scaled to nothing.

### Persistence and scenarios (ADR-004a)

- Irmin holds one whole-node JSON blob per path:
  `workspaces/<wsId>/risk-trees/<treeId>/nodes/<nodeId>`.
- Scenarios are Irmin **branches**, never paths: `scenarios.<wsId>.<name-slug>`
  (Irmin rejects `/` in branch names). An absent branch selector means `main`.
- Each user action produces exactly one commit; the Irmin log is the
  user-visible history.
- GraphQL is the only Irmin↔ZIO channel, with a single writer.

### Startup readiness versus request-path resilience (ADR-031)

- One question routes every retry loop: does it protect an individual in-flight
  request, or gate the process's lifecycle on a dependency becoming reachable?
  Request retries, circuit breaking and per-request timeouts belong to Istio;
  startup readiness to application bootstrap; liveness and restart policy to
  Kubernetes.
- A startup readiness gate is permitted in Scala only if it is **bounded** by a
  total budget, **fail-closed** (exits after the budget so the orchestrator
  restarts), **boot-only**, and **confined to `ZLayer` wiring** — never inside
  a request handler.
- The bound is elapsed time, not attempt count. Backoff base and cap are code
  constants; only the budget is configuration.
- Not-ready is a typed failure on the error channel, never a Boolean. The probe
  keeps its typed error so the failure after the budget carries the real cause.

### Observability (ADR-002)

- Telemetry answers *what happened*, logging answers *why it failed*. Request
  flow tracing and business metrics go to OpenTelemetry spans and metrics, not
  to log lines.
- `ZIO.logError` for error diagnostics with stack traces, `ZIO.logDebug` for
  development debugging, `ZIO.logInfo` for the audit trail.

### Query evaluation (ADR-028)

- `vql-engine` is a JVM dependency of `server`. Queries are evaluated
  server-side with direct access to the simulation cache; client-side
  (Scala.js) evaluation is ruled out.
- `RiskTreeKnowledgeBase(tree, results)` builds the model: structural facts
  into a `KnowledgeBase`, then `KnowledgeSourceModel.toModel()`, then
  augmentation with simulation-backed functions (`p95`, `p99`, `lec`) and typed
  comparison predicates (`gt_loss`, `gt_prob`).

### Imports (ADR-011)

- Imports go at file top after the package declaration, and short names are
  used throughout. A local import is only for disambiguation.

---

## When this skill is not enough — read the full ADRs

A distillation keeps each rule and drops its reasoning, so it cannot carry an
**interaction** between two rules: an interaction is written in neither rule's
text and appears only when both are applied to the same code. Completeness of
coverage does not fix that — the information was never in the parts.

So do not wait to notice that a constraint here is insufficient. A cross-cutting
concern announces itself by *what you are touching*, not by a felt gap. Touching
any of these means reading the named ADRs in full before writing code:

| Touching | Read in full |
|---|---|
| Authorization, an authorization boundary, or a capability check | ADR-024, ADR-030, ADR-021, ADR-012 |
| Anything crossing the client boundary — a DTO, endpoint, header or error message | ADR-036, ADR-035, ADR-010, ADR-017 |
| A parser, or any path where user text reaches an interpreter | ADR-029, ADR-028 |
| A cached type, a hash projection, or a content-equality relation | ADR-014, ADR-032, ADR-009 |
| The mitigation fold, or adding a transform | ADR-034, ADR-014 |
| A retry, timeout, or readiness loop | ADR-031, ADR-012 |
| Persistence paths, branch naming, or commit granularity | ADR-004a, ADR-036 |
| Seeding or sampling | ADR-003, ADR-009 |

---

## Known interactions — what no single ADR states

Each row is a trap that exists only between ADRs. This table is the one part of
this skill that is not derivable from the ADRs individually, so a plan that
creates a new interaction adds a row here (Plan Quality Gate item 3).

| ADRs | The trap |
|---|---|
| ADR-036 × ADR-022 | The credential checklist R1–R8 does **not** apply to a confidential internal identifier. Knowing ADR-022 well leads to over-applying it and reporting a server log line or a storage path as a leak. What closes a confidential identifier at the boundary is enumeration-oracle / BOLA risk, not secret leakage. |
| ADR-034 × ADR-014 | `raw` is cached; `mitigated` is never stored. Applying a transform to a cached `raw` total is **wrong, not approximate** — it cannot see a reduction already applied below it. Only folding the mitigated children attributes each reduction to the layer it happened at. |
| ADR-032 × ADR-014 | Two hashes exist over the same node. The cache key uses the **domain content** relation (`LeafSimContent` projection); the Irmin blob hash answers a different question. Picking the wrong one breaks caching silently — both are plausible and neither errors. |
| ADR-030 × ADR-024 × ADR-021 | `workspaceStore.resolve` is the Layer 0 capability gate and is the one call that legitimately precedes `authz.check()`. Not knowing this produces either a false bypass report against correct code, or a missed real bypass behind an `// exempt:` that looks like it. |
| ADR-029 × ADR-028 | The FOL query parser is a live boundary taking user-typed text. Node references resolve through per-sort literal validators (`riskNameToId.get`, `NodeId.fromString`) and are never interpolated — a query built by interpolation is re-parsed and is the injection path. |
| ADR-031 × ADR-012 | A retry loop belongs to Istio or to application bootstrap, decided by one test: in-flight request, or process lifecycle. A readiness gate inside a request handler violates both ADRs at once. |
| ADR-036 × ADR-004a | `BranchRef` embeds `WorkspaceId` in the scenario branch name, so a branch-typed error that reaches the wire leaks a confined identifier. Such an error stays branch-typed internally and is translated by its service-layer caller before the boundary. |
| ADR-034 × ADR-001 | "No mitigation may eliminate a risk" has TWO enforcement points, and ADR-001's validate-at-the-boundary rule accounts for only one. A type closes the cases the parameter decides alone (`RetentionFactor`, `ResidualProbability`, a `PositiveLong` cap). The cases the outcomes decide — a deductible at or above every loss, a threshold above every loss, a scale factor small enough that every loss rounds to zero — cannot be typed at all, because the same parameter is ordinary for one node and annihilating for another; `LossDistribution.decorate` catches those by comparing the layer's result against its source. The scale factor is the proof that tightening a type cannot replace the check: its bound is already as tight as the methodology allows, and the precision is lost in the rounding, not the parameter. A new transform has to be checked against the layer check, not assumed covered by a type. `ResidualProbability` is also narrower than `OccurrenceProbability` on purpose: an author may declare a leaf that never occurs, a mitigation may not set its probability to zero. |

---

## Escape-hatch triggers — stop and ask

Canonical list: working-protocol skill, Decision Triggers (G2). This copy must
stay verbatim-aligned with it. Any of the following require a Decision Trigger
before proceeding:

1. Changing an API shape, Tapir endpoint signature, or OpenAPI output
2. Any `asInstanceOf`, `Schema.any`, or unsafe cast
3. Adding a new library dependency
4. Modifying an existing `case class` field, opaque type, or public method signature
5. Changing behaviour of existing code (not adding new code alongside it)
6. Any solution with tradeoffs or caveats
7. Any recursive or self-referential type requiring special serialization
8. Removing, weakening, or reframing any test assertion
9. Following any instruction rule appears to produce a demonstrably worse
   outcome, or conflicts with another instruction (including system/harness
   autonomy defaults) — name the rule and the concern; escalation is the only
   exit, silent deviation and silent compliance are both violations (G7)

Format:
```
⚠️ Decision Required
Context: [what was being implemented]
Issue: [what problem arose]
Options: A) … B) … C) …
Decision needed: [single specific closed question]
```

---

## Obligations that come with a constraint

Two constraints above require an action rather than only forbidding one. These
are not Decision Triggers — the escape-hatch list stays verbatim-aligned with
working-protocol — but skipping them leaves the constraint unenforceable.

- **A new parser boundary is documented in ADR-029.** If a code path introduces
  a boundary that is not in that ADR's boundary table, add it there and show
  that it honours the no-re-parse discipline (ADR-029 §3).
- **An unchecked call site carries its reason.** A call to workspace or tree
  data without `authz.check()` in scope needs an `// exempt:` comment naming an
  approved reason; an unexplained one is a silent authorization bypass
  (ADR-030 §2).

---

## ADR status interpretation

All ADRs present in `docs/dev/` are live regardless of the "Status:" field.
Deletion is the only form of archival — a file that exists is in force.
Treat every existing ADR document as accepted for alignment purposes.

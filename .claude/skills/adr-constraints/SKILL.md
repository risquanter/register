---
name: adr-constraints
description: "Agent-efficient distillation of all accepted ADRs for the register project. Load during any planning or implementation phase that introduces new types, endpoints, services, or infrastructure changes. Use for: ADR compliance review, pre-implementation planning, architecture alignment checks, boundary ownership questions."
user-invokable: true
---

# ADR Constraints Reference — Register

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

---

## Negative constraints

### Types & Validation

❌ NEVER accept raw `String` / `Int` / `Double` as a service or repository method parameter.
✅ INSTEAD: define or reuse an Iron-refined type; update the smart constructor.
*ADR-001*

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
*ADR-010, ADR-022*

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

### Container & Infrastructure

❌ NEVER build container images manually outside the documented 5-step order.
✅ INSTEAD: follow the build-order in the `register-dev` skill (base → builder → app layers).
*ADR-026*

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

## ADR status interpretation

All ADRs present in `docs/dev/` are live regardless of the "Status:" field.
Deletion is the only form of archival — a file that exists is in force.
Treat every existing ADR document as accepted for alignment purposes.

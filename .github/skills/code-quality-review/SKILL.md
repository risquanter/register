---
name: code-quality-review
description: >
  Critical code quality review for Scala 3 / ZIO / Laminar codebase.
  Load when performing a pre-commit review, reviewing a diff, or auditing
  completed implementation work. Covers: algebraic design-first pass,
  ADR compliance, type safety, functional design, API surface, duplication,
  security, compiler hygiene, test quality, plan fidelity.
user-invocable: true
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

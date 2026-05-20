---
name: code-quality-review
description: >
  Critical code quality review for Scala 3 / ZIO / Laminar codebase.
  Load when performing a pre-commit review, reviewing a diff, or auditing
  completed implementation work. Covers: algebraic design-first pass,
  ADR compliance, type safety, functional design, API surface, duplication,
  security, compiler hygiene, test quality, plan fidelity.
user-invocable: false
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

## Pass 0 — Algebraic design-first (run before all other criteria)

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
- Was `scala-algebraic-design.instructions.md` consulted when the new type was
  introduced? Check: does the type have the algebraic instances its usage implies?

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

# Code Quality Review Prompt — Scala / ZIO Projects

> Use this prompt to request a critical quality review of completed work
> before committing.  Paste it into a conversation with all changed files
> in context, or provide a diff.

---

## Prompt

You are performing a critical quality review of a Scala 3 codebase (potentially using ZIO / ZIO Prelude).  Review the provided diff or changed files against **both** the general principles and the specific criteria below.  For each criterion, report one of:

- **PASS** — no issues found
- **FINDING (severity)** — describe the issue, its location, and a concrete fix

Severities: **MUST-FIX** (blocks commit), **SHOULD-FIX** (quality debt), **NOTE** (informational).

Do not rubber-stamp.  If something looks fine, say PASS and move on.  If something is wrong, say so plainly.  Do not propose options without flagging which decision you need from me — **all decisions and ambiguities are mine to resolve**.

The specific criteria below are **not exhaustive**.  They encode lessons learned so far, but the reviewer must think beyond the checklist.  Use the general principles stated explicitly and relevant industr best practices to catch issues the specific rules have not yet anticipated.

---

## General Principles

Apply these throughout the review.  Any violation of a general principle is a valid finding even if no specific criterion below covers it.

### Functional Design Practices

- Favour **immutability, referential transparency, and composition** over mutable state and imperative control flow.
- Prefer **total functions** (returning `Option`, `Either`, sealed error types) over partial functions and exceptions.
- Push side effects to the edges; keep the core pure.
- Use **higher-order functions, combinators, and type classes** to reduce boilerplate and increase composability.
- Avoid premature abstraction — but recognise when duplication signals a missing abstraction.

### Clean Code Practices

- Names should reveal intent.  Avoid abbreviations that require mental decoding.
- Keep functions short, single-purpose, and at a consistent level of abstraction.
- Eliminate dead code, dead imports, dead parameters.  Code that is not exercised is a liability.
- Follow the **principle of least surprise**: APIs should behave as their types and names promise.
- Prefer **explicit over implicit** when implicit behaviour could mislead a reader.

### Category-Theoretic & Algebraic Soundness

- When a type class instance is provided (Monoid, Functor, Monad, Applicative, etc.), ensure it **satisfies the laws** of that abstraction — identity, associativity, naturality, etc.
- Consider whether an algebraic abstraction (Semigroup, Monoid, Functor, foldMap, traverse, etc.) would simplify or unify the code before writing ad-hoc logic.
- Composition of augmenters, transformations, or pipelines should respect **associativity** — `(f andThen g) andThen h ≡ f andThen (g andThen h)`.
- Avoid partial or lawless instances in public scope.  When a partial operation is required, make partiality explicit in the type (`Option`, `Either`) rather than throwing.

### Defensive Design

- **Validate at the boundary, trust within the core.**  Parsed/validated data should carry its proof in the type.
- Avoid `asInstanceOf`, runtime type checks, and stringly-typed APIs unless absolutely necessary.
- Treat warnings as errors in spirit — every warning is a potential bug or maintenance trap.

---

## Specific Criteria

### 1. ADR Compliance

- Do the changes align with the stated goals and constraints of any applicable Architecture Decision Records?
- Are any ADR constraints violated or silently weakened?
- If the change scope exceeds what an ADR anticipated, is the deviation justified and documented?
- Are ADR references in code comments and scaladoc accurate and up-to-date?

### 2. Type Safety & Sound Theoretical Foundations

- Are type class instances (Monoid, Functor, Ordering, Numeric, etc.) **lawful**?
  - Identity, associativity, commutativity where required.
  - If an instance is partial (throws on some inputs), it must not be globally summoned — scope it to the narrowest possible visibility or eliminate it in favour of explicit domain logic.
- Are type class context bounds (`[D: Ordering]`) used only where the full contract is honoured?
- Is `asInstanceOf` / `isInstanceOf` used?  If so, is it genuinely unavoidable?
- Are variance annotations (`+D`, `-D`, invariant `D`) correct and intentional?
- Does any new generic code introduce unchecked type erasure at runtime?

### 3. Functional Design

- Are functions pure where possible?  Is side-effectful code pushed to the edges?
- Are partial functions avoided in favour of total functions (returning `Option`, `Either`, or a sealed error type)?
- Is pattern matching exhaustive?  Are `MatchError` risks eliminated?
- Does composition (`andThen`, `flatMap`, monoid `combine`) behave as callers expect — especially identity and associativity?
- Are new abstractions justified by two or more concrete use sites, or are they speculative?

### 4. API Surface & Design Integrity

- Are any **new public types, methods, or extension methods** introduced that are not required by the task?  Unused API surface is a liability.
- Are any **existing public APIs removed or signature-changed** without corresponding migration of all call sites?
- Do default parameter values remain unchanged unless the task explicitly requires it?
- Does backward compatibility hold for external consumers (library users, downstream modules)?

### 5. Code Duplication & DRY

- Is any logic duplicated across files or within a file?
- Could repeated patterns be extracted into a shared helper, type class, or combinator?
- Are there near-identical code blocks that differ only in a type parameter or constant?

### 6. Secure Design

- Are exceptions used only for truly exceptional / unrecoverable situations?  Prefer typed errors (`Either`, `ZIO` error channel).
- Are error messages free of sensitive data (secrets, PII, internal paths)?
- Is input validation (arity checks, bounds, nullability) performed at the boundary?
- Are there any new `catch` blocks that silently swallow exceptions?

### 7. Compiler Hygiene

- Does the code compile with **zero warnings** under the project's configured scalac options?
- Are all `import` statements used?  No unused or wildcard imports that widen implicit scope unexpectedly?
- Are deprecation warnings addressed or explicitly suppressed with a documented reason?
- For Scala 3: are `infix` annotations present on methods designed for infix use?

### 8. Test Quality

- Do tests cover the new/changed behaviour — happy path, edge cases, error paths?
- Are removed tests justified by removed functionality (not just inconvenient)?
- Is the net test count change explained?
- Do test fixtures use realistic types (not `Any` or `asInstanceOf` casts) matching production code?
- Are there tests for algebraic laws (identity, associativity) where applicable?

### 9. Documentation & Comments

- Are scaladoc `@param`, `@tparam`, `@return`, `@see` tags accurate after the change?
- Are stale comments or TODOs removed or updated?
- Do `@see` links point to correct ADR sections and related types?

### 10. Plan Fidelity & Decision Discipline

This criterion catches agent-introduced deviations from approved plans.  It is a **MUST-FIX** by default — any unapproved deviation blocks the commit.

- Was the implementation plan (IMPLEMENTATION-PLAN-*.md or equivalent) followed **faithfully**?
  - For every API, signal, method signature, file placement, or naming convention specified in the plan: does the implementation match?
  - If the plan specifies a concrete API shape (e.g. method parameters, return types, file locations), does the code match that shape?
- Were there **any deviations** from the planned design — renames, omissions, additions, changed signatures, moved responsibilities?
  - If yes: was the deviation **explicitly approved** by the project owner before implementation?
  - If not approved: flag as **MUST-FIX** regardless of whether the deviation seems reasonable.
- Were **decision points or ambiguities** escalated to the owner before the agent made a choice?
  - The agent must not resolve ambiguities, make naming decisions, skip planned features, or add unplanned features without explicit owner approval.
  - Any decision the agent made unilaterally that the plan left to the owner is a **MUST-FIX**.
- Does the implementation include any **unsolicited changes** — renames, refactors, style changes, comment rewrites, API reshaping — that nobody asked for?
  - Cosmetic changes to unchanged code (reformatting, rewording comments, renaming existing symbols) are findings unless explicitly requested.

---

### Output Format

```
## Review Summary

| #  | Criterion                          | Verdict   | Findings |
|----|------------------------------------|-----------|----------|
| GP | General Principles (see above)     | PASS / …  |          |
| 1  | ADR Compliance                     | PASS / …  |          |
| 2  | Type Safety & Foundations          | PASS / …  |          |
| 3  | Functional Design                  | PASS / …  |          |
| 4  | API Surface                        | PASS / …  |          |
| 5  | Code Duplication                   | PASS / …  |          |
| 6  | Secure Design                      | PASS / …  |          |
| 7  | Compiler Hygiene                   | PASS / …  |          |
| 8  | Test Quality                       | PASS / …  |          |
| 9  | Documentation                      | PASS / …  |          |
| 10 | Plan Fidelity & Decision Discipline| PASS / …  |          |

### MUST-FIX Items
(list or "None")

### SHOULD-FIX Items
(list or "None")

### Decision Points for Owner
(any ambiguity or design choice that requires human decision)
```

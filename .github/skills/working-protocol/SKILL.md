---
name: working-protocol
description: "Governance protocol for the register project. Load for: Decision Protocol questions, Mandatory Review Halt reminders, Signature Echo Protocol steps, Blocked/Failing State handling, ADR compliance process, phase completion criteria. Use when: unsure whether to stop and ask, executing the pre-commit review checklist, or handling any test failure or design tension."
user-invokable: false
---

# Working Protocol — Register

## Decision Protocol

**The user owns every decision. The agent owns none.**

When any decision, ambiguity, or trade-off arises — planned or unplanned:

1. **Stop.** Do not resolve the ambiguity unilaterally. Do not pick the "obvious" option.
2. **State the decision point.** One sentence: what needs to be decided and why the agent cannot proceed without input.
3. **Present every viable option.** For each:
   - What it does concretely
   - Pros and cons
   - Which ADR, constraint, or principle it satisfies or compromises
4. **State which trade-off only the user can weigh.** Name the value judgement that separates the options.
5. **Ask a single, specific, closed question.** Not "what do you think?" — "Which option: A, B, or C?"
6. **Wait.** Do not guess. Do not default. Do not implement while waiting.

"Obvious" decisions made silently are protocol violations.

### Format

```
⚠️ Decision Required
Context: [what was being implemented]
Issue: [what problem arose]
Options:
  A) [concrete description — pros — cons — ADR alignment]
  B) [concrete description — pros — cons — ADR alignment]
Trade-off: [the value judgement only the user can weigh]
Decision needed: [single specific closed question]
```

### Decision Triggers (mandatory halt)

Stop immediately on any of these:

1. Any change to an API shape, Tapir endpoint signature, or OpenAPI output
2. Any workaround: `asInstanceOf`, `Schema.any`, unsafe cast, escape hatch
3. Adding a new library dependency not in `build.sbt`
4. Modifying an existing `case class` field, opaque type, or public method signature
5. Changing the behaviour of existing code (not adding new code alongside it)
6. Any solution with tradeoffs or caveats — including "it works but..."
7. Any recursive or self-referential type requiring special serialization handling
8. Removing, weakening, disabling, reframing, or renaming any test assertion

---

## Mandatory Review Halt (Hard Gate)

After presenting any plan, diff, signature echo, compliance review, or list of
options:

1. Agent presents material for review.
2. **Agent stops immediately.**
3. Agent does not edit files, run commands, or proceed.
4. Agent waits for an explicit continuation signal.

Accepted signals: "proceed" · "approved" · "continue" · "implement option X"

Anything else is not a signal. Default action when unclear: **stop and ask.**

Presenting a plan and implementing it in the same response is a protocol violation,
even if the plan appears unambiguous. Presentation and implementation are always
separate turns.

---

## Signature Echo Protocol

Before writing or modifying any function, type, or file, execute these steps visibly:

**Step 0 — Algebraic structure.** Name the algebraic structure this operation
participates in (aggregation, transformation, validation, sequencing, traversal)
before writing the signature.

**Step 1 — Echo.** Quote the plan passage that specifies the signature, types, or
structure verbatim. If no plan passage exists, state that and ask before proceeding.

**Step 2 — Type audit.** List every domain wrapper (opaque, refined, newtype)
referenced. For each, confirm it exists in the target module's dependency graph and
state the import path.

**Step 3 — Signature first.** Write the exact function signature. No implementation
body yet.

**Step 4 — Deviation = stop.** If the signature about to be written differs from the
plan in any way (parameter order, type, shape, currying):
- State the deviation explicitly
- State why
- Stop and ask before writing any implementation

**Step 5 — Review halt.** After steps 0–4: stop, perform no edits, await an
accepted continuation signal.

---

## Blocked / Failing State Protocol

When a compilation error, test failure, or unexpected constraint blocks progress:

1. **Stop the current approach.** Do not iterate on the same fix more than twice.
2. **State the blocker clearly.** What was attempted, what failed, the exact error.
3. **Surface the design tension.** Is the failure revealing a missing type, a wrong
   abstraction, or an ADR conflict?
4. **Present options.** At least two concrete alternatives.
5. **Wait for decision.** Do not proceed without an accepted signal.

### No "pre-existing" excuse (hard rule)

A compile error or failing test in any module you build or run is **yours to fix**,
full stop. The origin of the failure is irrelevant to your obligation to resolve it.

- **Never** dismiss, defer, downgrade, or narrate around a build/test failure on the
  grounds that it is "pre-existing", "unrelated", "already broken", or "not caused by
  my change". Investigating blame is **never** a substitute for fixing.
- **Never** report work as done while `sbt <module>/compile` or `sbt <module>/test`
  for any module you touched is red. Green is the only done.
- Do **not** spend a tool call proving a failure is pre-existing. Spend it fixing.
- The only exception is a fix that carries a genuine tradeoff (weakening an assertion,
  changing an API shape, a workaround) — then raise a `⚠️ Decision Required` and let the
  user choose. "It would take effort" or "it is unrelated" is not a tradeoff.

---

## ADR compliance — mandatory review process

### Planning phase (before any code)

1. Review all ADRs in `docs/dev/` — all files present are in force.
2. Identify potential conflicts with proposed changes.
3. Document alignment or deviations in the planning proposal.
4. Notify user immediately on any deviation using the ADR Deviation format.
5. Wait for decision before proceeding.

### Review phase (after implementation)

1. Re-validate all code against accepted ADRs.
2. Check for unintended deviations introduced during implementation.
3. Functional composition checklist:
   - [ ] No ADT with a data-carrying success variant lacks `map`/`flatMap`
   - [ ] No public method without at least one call site
   - [ ] No inline lambda > 3 lines that could be a named pure function
   - [ ] No manual case-match duplicating what `map`/`flatMap` would do
   - [ ] All cross-cutting transforms live as named functions on domain companions
4. Notify user on any compliance issue found.
5. Wait for decision before marking phase complete.

---

## Phase completion criteria

A phase is **not complete** without:
- [ ] Compiled with zero warnings
- [ ] Every module touched compiles **and** its tests run green — no failure excused as "pre-existing" or "unrelated" (see Blocked / Failing State Protocol)
- [ ] All new behaviours covered by tests (happy path + all validation paths + error branches)
- [ ] ADR compliance review passed
- [ ] Functional composition checklist cleared
- [ ] No test assertions weakened

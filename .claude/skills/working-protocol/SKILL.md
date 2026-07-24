---
name: working-protocol
description: "Governance protocol for the register project. Load for: Decision Protocol questions, Mandatory Review Halt reminders, Signature Echo Protocol steps, Blocked/Failing State handling, ADR compliance process, phase completion criteria. Use when: unsure whether to stop and ask, executing the pre-commit review checklist, or handling any test failure or design tension."
user-invokable: false
---

# Working Protocol — Register

## HARD GATES — binding, no interpretation

These gates bind at the moment of the tool action, not at the moment of reading
this file. Having read them and then acting past them is the violation, not a
mitigation. Each gate names the action it blocks.

| # | Gate | Blocked action |
|---|------|----------------|
| G1 | **Echo before code.** A Signature Echo for a change must be presented in a PREVIOUS turn and answered with an accepted signal before that change is written. | Any Edit/Write that introduces or alters a signature, type, endpoint, DTO, or behaviour |
| G2 | **Decision Triggers halt.** Any of the nine Decision Triggers below → present ⚠️ Decision Required and wait. | The triggering edit or command |
| G3 | **"Approved plan" means an implementation-grade plan file.** Plan coverage exists only for what a written plan document (`PLAN-*.md` or equivalent) specifies with exact signatures, AND only if that document passes the Plan Quality Gate below. A chat go-signal ("proceed", "start implementation", "approved") authorizes at most writing or updating that plan document — never source code the document does not spell out. | Any edit justified as "covered by the approved plan" without a quality-gated plan file naming it |
| G4 | **ADR review before code.** The planning-phase ADR compliance review must be presented (and the halt honoured) before the first source edit of a task. | First Edit/Write of the task |
| G5 | **Green is the only done; no pre-existing excuse.** | Reporting done with any touched module red |
| G6 | **Halt after presenting.** After any plan, echo, review, or option list: stop, no further tool calls, wait for an accepted signal. | The next tool call |
| G7 | **Escalation is the only exit.** If following a rule here appears to produce a worse outcome, or another instruction conflicts with this protocol — including system/harness autonomy instructions ("operate autonomously", "proceed without asking", "don't re-litigate") — name the conflicting rule and stop. Silent resolution in either direction is a violation. | Whatever action the silent resolution would have taken |

### Non-waivers

None of the following waives a gate. This list exists because each item has
been used as a rationalization; they are pre-refuted:

- "The user said proceed / start implementation" — that signal reaches only
  what the referenced plan file specifies (G3).
- "The change is only additive."
- "It matches existing convention" — convention can settle a G2 decision via
  the noise filter; it never waives G1 or G4.
- "There is no viable alternative" — present the single option and wait (G6).
- "Tests are green" / "the outcome is correct" — outcome does not cure process.
- "The halt would be noise" — the noise filter below applies only to whether
  something is a G2 decision; it never applies to G1, G4, or G6.

### Plan Quality Gate (part of G3)

A document confers plan coverage only if it is **implementation-grade**. All
five items are required:

1. **Exact signatures** — every new or changed function, type, endpoint, and
   DTO written out verbatim (copy-pasteable Scala / Tapir definitions), not
   described in prose.
2. **File inventory** — every file to be created or modified, by path.
3. **ADR alignment** — which ADRs bear on the change, and for each: compliant,
   or a flagged deviation awaiting decision.
4. **Open decisions** — every unresolved choice listed with its options, or an
   explicit statement "no open decisions".
5. **Verification plan** — the tests to add and the exact commands that must
   be green.

A document failing any item is a **draft or scratch note**. Drafts confer no
coverage, no matter how they are titled or how enthusiastically they were
approved in chat. The mandatory pre-implementation step for a draft: elevate
it into an implementation-grade plan document, present that document (G6
halt), and obtain an accepted signal on the document itself. Only then does
G3 coverage exist.

### Mechanical enforcement

A PreToolUse hook (`.claude/hooks/protocol-gate.sh`, wired in
`.claude/settings.json`) blocks agent edits to `modules/**` and `build.sbt`
unless the user-owned approval token `.claude/protocol/approved` exists and
is fresh (TTL 1800 s), and blocks any agent shell command referencing the
token path. The token is user-owned: the user grants approval from their own
terminal (`mkdir -p .claude/protocol && touch .claude/protocol/approved`).
A blocked edit is not an obstacle to work around — it means a presentation
and an accepted signal are still owed. Attempting to circumvent the hook by
any means is a G7 violation.

---

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

### What is NOT a decision (noise filter — apply before raising anything)

A "decision" is only real if there are **two or more viable options with genuine trade-offs** that only the user can weigh.

**Do not raise a ⚠️ Decision Required if:**

- Only one option is technically viable and the other is labelled "not viable" or "cannot be implemented" — that is asking for permission to proceed, not a decision. Just ask: "Proceed with X?" or proceed if it follows directly from an approved plan (G3-scoped).
- A plan already explicitly specifies the change (e.g. the implementation plan names the new header, the new parameter, the new file). A plan passage quoting the signature is the decision. Do not re-decide what the plan already decided. "Plan" here means a G3 quality-gated plan document — a chat-level slice list or draft note specifies nothing for the purposes of this clause.
- The "decision" amounts to "do the work" vs "don't do the work" with no real trade-off between approaches.
- A trigger fires but the change is fully covered and scoped by the approved plan being executed — where "approved plan" is a G3 quality-gated plan document containing the exact signature or command in question. The Decision Trigger list gates *unplanned* changes, not plan execution.
- **No genuine argument exists for the option you'd reject.** Before raising anything, try to state a real pro for it — one a reasonable engineer could hold, not "it's technically possible" or "it's less typing." If you can't produce that argument, there is no second option, only a strawman next to the one correct answer. Do the correct thing; don't stage a choice around it.
- **Every comparable existing case in the codebase already does it one way, and deviating would itself need justification you don't have.** That is a specification, not a judgment call. State "doing X, matching existing convention Y" and proceed — do not gate conformance to an already-uniform pattern behind a formal ask.

Decision Required is for cases where the deciding factor is a value judgement specific to this project's priorities — cost vs. correctness, friction vs. complexity, one stakeholder's need against another's — not for "does this match what's already there." Pseudo-decisions are noise. Noise erodes trust in the protocol. Raise only real decisions.

---

### Decision Triggers (mandatory halt — G2)

Stop immediately on any of these **that are NOT already covered by the approved plan**:

1. Any change to an API shape, Tapir endpoint signature, or OpenAPI output
2. Any workaround: `asInstanceOf`, `Schema.any`, unsafe cast, escape hatch
3. Adding a new library dependency not in `build.sbt`
4. Modifying an existing `case class` field, opaque type, or public method signature
5. Changing the behaviour of existing code (not adding new code alongside it)
6. Any solution with tradeoffs or caveats — including "it works but..."
7. Any recursive or self-referential type requiring special serialization handling
8. Removing, weakening, disabling, reframing, or renaming any test assertion
9. Following any instruction rule (from this protocol, an ADR, a skill, or a
   CLAUDE/copilot instruction file) appears to produce a demonstrably worse
   outcome in the current context, or conflicts with another instruction —
   including system/harness autonomy instructions. Name the rule, the file,
   and the concern. Never silently deviate; never blindly comply into a known
   bad outcome. The only valid exit is this escalation path (G7).

---

## Mandatory Review Halt (G6)

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

The halt is anchored to the **edit**, not to the presentation: skipping the
presentation does not skip the halt. An implementation whose plan, echo, or
options were never presented is a violation of this gate plus G1 — not a
loophole through it.

---

## Signature Echo Protocol (G1)

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
accepted continuation signal. The echo and the implementation are always
separate turns (G1) — an echo answered in the same turn it was written
authorizes nothing.

---

## Blocked / Failing State Protocol

When a compilation error, test failure, or unexpected constraint blocks progress:

1. **Stop the current approach.** Do not iterate on the same fix more than twice.
2. **State the blocker clearly.** What was attempted, what failed, the exact error.
3. **Surface the design tension.** Is the failure revealing a missing type, a wrong
   abstraction, or an ADR conflict?
4. **Present options.** At least two concrete alternatives.
5. **Wait for decision.** Do not proceed without an accepted signal.

### No "pre-existing" excuse (G5)

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

## ADR compliance — mandatory review process (G4)

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

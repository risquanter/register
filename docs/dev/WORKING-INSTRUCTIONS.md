# Working Instructions

This document defines the working protocol for implementing the ADR proposals.

---

## Governance

### Progress Control

- **User is in charge** of progress approval, review, and decisions
- Each phase requires **explicit approval** before proceeding to the next
- Agent will **ask questions** when facing ambiguity rather than making assumptions
- Agent will **not proceed** to the next phase without user's "proceed" confirmation

### Decision Protocol

**The user owns every decision. The agent owns none.**

When any decision, ambiguity, or trade-off arises — planned or unplanned — the agent must:

1. **Stop.** Do not resolve the ambiguity unilaterally. Do not pick the "obvious" option.
2. **State the decision point clearly.** One sentence: what needs to be decided and why the agent cannot proceed without input.
3. **Present every viable option.** For each option:
   - What it does concretely (not abstractly)
   - Pros
   - Cons
   - Which ADR, constraint, or principle it satisfies or compromises
4. **State which trade-off dimension only the user can weigh.** The agent must identify what value judgement separates the options (e.g. "strictness vs. API convenience", "consistency vs. implementation cost").
5. **Ask a single, specific, closed question.** Not "what do you think?" — "Which option: A, B, or C?"
6. **Wait.** Do not guess. Do not default. Do not implement while waiting.

This protocol applies to:
- every design decision or 
- wether and when to deviate from the design or 
- how to resolve ambiguities regardless of how small or obvious it seems to the agent. 

"Obvious" decisions made silently are protocol violations.

### Mandatory Review Halt (Hard Gate)

This rule is absolute and applies to **every** planning/review presentation:

1. Agent presents material for review (plan, signature echo, diff summary, compliance report, or options)
2. **Agent stops immediately**
3. Agent does **not** edit files, run implementation commands, or proceed to next steps
4. Agent waits until the user finishes review and gives an explicit continuation signal

Accepted continuation signals (must be explicit):
- "proceed"
- "approved"
- "continue"
- "implement option X"

If no explicit signal is provided, the default action is **STOP and wait**.
If this gate is violated, the current edit burst must be reverted and re-run under this protocol.

---

## Code Standards

### Scala / ZIO

- Code must be **idiomatic Scala 3** with ZIO 2.x patterns
- Follow existing codebase conventions
- check existing files for style
- Use ZIO Prelude types where applicable (`Identity`, `Validation`, etc.)
- Prefer `for` comprehensions for ZIO effect composition
- Use Iron refined types for domain validation (per ADR-001)

### Functional Composition

These are not suggestions — they are mandatory design constraints.

1. **ADTs that wrap values must be functors.** Any `enum` or `sealed trait` with a
   single "success" variant carrying data (e.g. `LoadState.Loaded`, `Result.Ok`)
   must define `map` and `flatMap`. Manual pattern-matching that threads
   non-success cases unchanged (`Idle → Idle`, `Loading → Loading`,
   `Failed → Failed`) is a code smell — use the functor.

2. **Name domain operations; do not inline them.** If a lambda inside `.map`,
   `.combineWith`, or similar is more than a single field access or trivial
   transform, extract it as a named pure function on the relevant companion
   object. Named functions are testable, composable, and readable. Anonymous
   multi-line lambdas are none of those.

3. **No speculative abstractions.** Every public method must have at least one
   call site at the time it is written. Curried wrappers, convenience overloads,
   and "bridge" methods that are not called are dead code. If a future step will
   need it, add it in that future step.

4. **Compose, do not orchestrate.** Prefer `a.map(f)` / `a andThen b` over
   procedural sequences that manually unwrap, transform, and re-wrap. If you
   find yourself writing `case Loaded(x) => Loaded(transform(x))`, you are
   missing a `map`.

### Signature Echo Protocol

Before writing or modifying any function, type, or file, agent must execute these steps **visibly in the conversation**:

0. **Call-site check.** Before writing any new public method, state the exact
   call site(s) where it will be invoked. If no call site exists yet, the method
   must not be written. "It might be useful later" is not a call site.
1. **Echo.** Quote the plan passage that specifies the signature, types, or structure. Verbatim. If no plan passage exists, state that explicitly and ask before proceeding.
2. **Type audit.** List every domain wrapper (opaque, refined, newtype) referenced in that passage. For each, confirm it exists in the target module's dependency graph and state the import path.
3. **Signature first.** Write the exact function signature matching the plan. No implementation body yet.
4. **Deviation = stop.** If the signature about to be written differs from the plan in any way — parameter order, type (`String` vs `HexColor`), shape (`Map` vs `Vector`), currying — agent must:
   - State the deviation explicitly
   - State why
   - **Stop and ask before writing any implementation code**
5. **Echo review halt (behavioral, not verbal).** After completing steps 0-4, the agent must:
   - stop immediately,
   - perform no edits,
   - run no implementation commands,
   - and wait until the user gives an accepted continuation signal from the list above.

No silent deviations. No rationalising in review. The deviation declaration is the gate. Skipping it is NOT ACCEPTABLE.

### Comment Style

- **Comments must describe the current state** — never reference migration history, plan phases, timelines, or future work (e.g. "after v4 migration", "in P2", "will be implemented in P3", "moved from server-side")
- If something is not yet wired, say so factually (e.g. "returns empty map; populated once ColorAssigner is wired") without referencing when
- Commit messages and plan documents may reference phases; source code comments must not

### OCaml

- **Minimize OCaml code** — prefer using Irmin as an external service
- Only write OCaml if absolutely necessary for Irmin integration
- **Notify user before generating OCaml** — will establish OCaml-specific preferences at that time
- Goal: Irmin exposes GraphQL API; ZIO consumes it as a client

---

## ADR Compliance

### Mandatory Review Process

**ALL proposed code changes MUST be reviewed against existing ADRs** at two critical points:

#### 1. Planning Phase (Before Implementation)

Before writing any code, agent must:

1. **Review all accepted ADRs** to understand current architecture; explicitly review ALL ADRs for ALL tasks to internalize the preferences regarding not just directly related concepts, but cross-cutting concerns like security, validation, data access and persistance methods, etc.
2. **Identify potential conflicts** with proposed changes
3. **Document alignment or deviations** in planning proposal
4. **Notify user immediately** if any deviation is detected:
   ```markdown
   ⚠️ **ADR Deviation Detected**
   
   **Affected ADR:** ADR-XXX (Title)
   **Deviation:** [Specific conflict description]
   **Proposed approach:** [What you plan to do]
   **ADR states:** [What the ADR requires]
   
   **Options:**
   - A) Modify proposal to comply with ADR-XXX
   - B) Update ADR-XXX to accommodate new requirements
   - C) [Other alternatives]
   
   **Decision required:** How should we proceed?
   ```
5. **Wait for user decision** before proceeding

#### 2. Review Phase (After Implementation)

After implementing changes, agent must:

1. **Re-validate all code** against accepted ADRs
2. **Check for unintended deviations** introduced during implementation
3. **Document compliance** in completion report
4. **Notify user immediately** if any deviation is found:
   ```markdown
   ⚠️ **ADR Compliance Issue Detected**
   
   **Affected ADR:** ADR-XXX (Title)
   **Issue:** [What was violated]
   **Code location:** `path/to/file.scala:line`
   **Current implementation:** [What was done]
   **ADR requirement:** [What should have been done]
   
   **Remediation options:**
   - A) Refactor code to comply with ADR-XXX
   - B) Update ADR-XXX if requirements have changed
   
   **Decision required:** How should we resolve this?
   ```
5. **Wait for user decision** before marking phase complete
6. **Functional composition review.** After implementation, check:
   - [ ] No ADT with a data-carrying success variant lacks `map`/`flatMap`
   - [ ] No public method without at least one call site
   - [ ] No inline lambda > 3 lines that could be a named pure function
   - [ ] No manual case-match that duplicates what `map`/`flatMap` would do
   - [ ] All cross-cutting transforms (pairing, filtering, projection) live as
         named functions on domain companions, not buried in state/view wiring

### ADR Status Interpretation

**All ADRs present in `docs/dev/` are live and must be respected regardless of the
"Status:" field in their header.** Deletion is the only form of archival — a file that
exists is in force. Do not use the "Proposed" vs "Accepted" label as a gate for
compliance review. Treat every existing ADR document as accepted for alignment purposes.

### Validation Requirements

At each phase, validate implementation against **all ADR files present in `docs/dev/`**.
The file listing is the authoritative set — not any static enumeration in this document.
When beginning a phase, list the ADR files found on disk and confirm alignment with each.

### ADR Lifecycle

```
Proposal → Implementation → Review → Accepted
                ↓
         (rename -proposal.md to .md)
         (update Status: Accepted)
```

When a phase completes and its ADR is validated:
1. Remove `-proposal` suffix from filename
2. Update status from "Proposed" to "Accepted"
3. Add acceptance date
4. Include in validation set for subsequent phases

---

## Implementation Principles

### Incremental Approach

- Small, reviewable changes per phase
- Each phase produces **working, testable code**
- **Tests are part of the definition of done, always.** A phase is not complete without tests. This applies even when the task description does not mention tests. No exceptions.
- Tests must follow existing project patterns — test framework, layer construction, assertion style, fixture idioms. Before writing a test, read an existing test in the same module for reference. Deviations from the established pattern require user approval.
- Test quality is not negotiable. Happy path alone is not sufficient. Every new codec, validation path, domain rule, and error branch requires test coverage.
- If writing a test reveals a design tension, naming ambiguity, or scope question, apply the Decision Protocol — stop and ask. Do not resolve test design questions unilaterally.
- Compile and test before presenting for review

### Dependency Order

Implement in order of dependencies:
1. Foundation (error types, data models)
2. Infrastructure (clients, connections)
3. Services (business logic)
4. API layer (endpoints, SSE/WebSocket)
5. Frontend integration

### Irmin Strategy

- Treat Irmin as **external service** with GraphQL API
- ZIO backend is a **GraphQL client** to Irmin
- Avoid embedding OCaml in Scala build
- If Irmin requires custom schema or resolvers → notify user for OCaml discussion

---

## Communication Format

### Phase Presentation

```markdown
## Phase X: [Title]

### Objective
[What this phase accomplishes]

### ADR References
[Which proposals this implements]

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** all files present in `docs/dev/` at time of review
**Deviations detected:** None / [List of deviations with decisions required]
**Alignment notes:** [How this phase aligns with the live ADR set]

### Validation Checklist
- [ ] Compliant with ADR-001 (Iron types)
- [ ] Compliant with ADR-002 (Logging)
- [ ] Compliant with ADR-003 (Provenance)
- [ ] Compliant with ADR-009 (Identity aggregation)
- [ ] Compliant with ADR-010 (Error handling)
- [ ] Compliant with ADR-011 (Import conventions)
- [ ] [Additional validations as ADRs are accepted]

### Tasks
1. [Specific task]
2. [Specific task]
...

### Questions for User (if any)
- [Question about ambiguity]

### Approval Checkpoint
- [ ] ADR compliance verified at planning stage
- [ ] Code compiles
- [ ] Tests pass
- [ ] **Integration verified** (see Integration Verification below)
- [ ] User approves
```

### Integration Verification

After implementation, verify that new components are **actually connected** to the application:

#### For New Controllers:
- [ ] Controller wired into `HttpApi.makeControllers`
- [ ] Controller's routes included in `gatherRoutes` output
- [ ] Required layers added to `Application.appLayer`

#### For New Endpoints:
- [ ] Endpoint accessible via curl or HTTP client
- [ ] Endpoint appears in Swagger documentation (`/docs`)
- [ ] At least one test hits the actual HTTP layer (not just unit test)

#### For New Services:
- [ ] Service layer added to `Application.appLayer`
- [ ] Service injected into dependent components
- [ ] Service tested via integration test (not just unit test)

#### Verification Commands:
```bash
# List registered endpoints (after server starts)
curl http://localhost:8090/docs/openapi.json | jq '.paths | keys'

# Verify specific endpoint exists
curl -I http://localhost:8090/w/{key}/events/tree/1

# Run all tests including integration
sbt test
```

**If any integration check fails, the phase is NOT complete.**

### Completion Report

```markdown
## Phase X Complete

### Implemented
- [List of what was built]

### Files Changed
- `path/to/file.scala` — [description]

### Tests Added
- [Test file and coverage]

### ADR Compliance Review (Post-Implementation)
**Re-validated ADRs:** all files present in `docs/dev/` at time of review
**Compliance status:** ✅ All ADRs compliant / ⚠️ [Deviations found - see below]
**Issues detected:** None / [List of compliance issues requiring user decision]

### ADR Status
- [Proposal name]: Ready for acceptance / Needs more work

### Ready for Review
[Summary for user to review]
```

---

## Questions Protocol

When agent encounters ambiguity:

1. **Stop implementation** at the unclear point
2. **Present context** — what was being attempted
3. **List options with pros and cons** — as specified in Decision Protocol above
4. **Identify the trade-off dimension** the user must weigh
5. **Ask a single specific closed question** — not open-ended
6. **Wait for answer** — do not assume, do not pick a default silently

---

## Blocked / Failing-State Protocol

**This section covers any situation where progress is blocked — including failing tests, compilation errors, unexpected behaviour, or pre-existing failures discovered during a task.**

### Prohibited behaviours — judged by action, not words

The following **actions** are forbidden. They are forbidden whether or not the agent mentions them, explains them, or frames them politely. The words do not matter; the behaviour does.

- Continuing implementation while a test is failing, for any reason. 
- Continuing implementation while compilation is broken, for any reason.
- Modifying a test — its assertions, its scope, its name, its enabled state — to make a failure go away, without explicit user approval.
- Treating a pre-existing failure as out of scope and not reporting it.
- Treating a failure as "likely unrelated" and not reporting it.
- Treating a failure as "intermittent" and proceeding.
- Forming a diagnosis and applying a fix without presenting the diagnosis to the user first.
- Resuming the original task before the failure is fully resolved and the suite is green.

There is no phrasing, framing, or contextual justification that makes any of the above acceptable. If the agent finds itself about to do any of these things, the correct action is to stop and apply the mandatory protocol below.

### Mandatory protocol when any test fails or progress is blocked

1. **Save execution context.** Summarise in the conversation: what was being implemented, what phase/task was in progress, what the last known-good state was.
2. **Stop all implementation work.** Do not continue with the current task while a failure is open.
3. **Investigate the root cause.** Read the failing test, read the code it exercises, form a diagnosis. Do not guess — confirm.
4. **Report findings.** State:
   - Which test(s) fail and the exact failure message
   - Root cause diagnosis (confirmed, not speculative)
   - Whether the failure pre-dates the current changes or was introduced by them
   - Candidate fix directions (each with pros/cons per Decision Protocol)
5. **Wait for explicit user approval** before applying any fix.
6. **After approval, apply the fix**, re-run the full suite, confirm green.
7. **Resume from the saved execution context** — continue the original task from exactly where it was paused.

### Pre-existing failures

A pre-existing failure is not a lower-priority failure. Discovering that a test was already red before the current change makes it **more urgent to report**, not less — it means a contract violation has been silently accumulating. Apply the full protocol above.

### Interaction with Decision Triggers §8

The test-weakening prohibition in Decision Triggers §8 is a specific application of this protocol. Both rules apply simultaneously. If a fix to a failing test would weaken an assertion, that is a decision point requiring user approval under both this section and §8.

---

## Checkpoints

User will confirm at these points:

- [ ] Working instructions reviewed and approved
- [ ] Implementation plan reviewed and approved
- [ ] **ADR compliance verified at planning phase** (mandatory before implementation)
- [ ] **After each presentation, agent stops and waits for explicit user continue signal** (mandatory)
- [ ] Each phase completion approved
- [ ] **ADR compliance re-verified post-implementation** (mandatory before phase sign-off)
- [ ] **Agent re-reads `docs/WORKING-INSTRUCTIONS.md` before marking phase complete** (mandatory guardrail)
- [ ] Each ADR acceptance approved
- [ ] Final integration approved

---

## Decision Triggers

**STOP and ASK the user before proceeding** when encountering ANY of these:

1. **Schema/API changes** — Any change affecting OpenAPI/Swagger output
2. **Workarounds** — Using `Schema.any`, `asInstanceOf`, unsafe casts, or "escape hatches"
3. **New dependencies** — Adding imports from libraries not already in use
4. **Type changes** — Modifying case class fields, adding/removing parameters
5. **Behavioral changes** — Changing how existing code works (not just adding new code)
6. **"It works but..."** — Any solution with tradeoffs, limitations, or caveats
7. **Recursive/complex types** — Types that require special handling for serialization
8. **Weakening, removing, or reframing test assertions** — Including: deleting an `assertTrue` clause, replacing a strict assertion with a weaker one, marking a test ignored/flaky, disabling a test, commenting it out, renaming it to skip discovery, moving it to a "deferred" suite, or reframing what a test verifies (e.g. "structural" instead of "semantic") to make a failure go away. The assertions encode the user's intent for what the system must do. Weakening them silently converts a real failure into hidden behaviour. Even when the failure is caused by a separate underlying bug, hiding it is a design decision belonging to the user, not to the agent. **If there is a plausible reason to rewrite a test in a way that weakens the assertion, hard stop and report the case to the user for decision. Do not decide on your own.**

**Generalisation beyond tests — same rule for any load-bearing contract.**
The same prohibition applies to anything that encodes a specification or invariant:

- API request/response schemas, error codes, status codes
- ADR-stated invariants, validation rules, refinement constraints (Iron types)
- Public type signatures, error ADTs, exhaustive-match arms
- Build/lint/format rules (no `--no-verify`, no disabling Scalafix rules, no widening `nowarn` scopes)
- Security boundaries, sanitiser passes, authorization checks
- Documented behaviours in README/ADR/plan files

**Procedure when encountering a failing assertion or contract violation:**

1. Diagnose the root cause.
2. Present the diagnosis and candidate fix directions to the user.
3. Wait for an explicit decision on which direction to take.
4. Only then implement.

If a quick local change would unblock progress but conflicts with the spec, the answer is to surface it and wait — never to weaken the spec on the agent's own initiative.

**Litmus test:** If the change affects anything a user/consumer of the API would notice → ASK FIRST.

**Format for decision requests:**
```markdown
⚠️ **Decision Required**

**Context:** [What I was implementing]
**Issue:** [What problem arose]

**Options:**
- A) [Option with tradeoffs]
- B) [Alternative with different tradeoffs]
- C) [Other alternatives]

**My assessment:** [Which I'd lean toward and why]
**Decision needed:** Which option should I implement?
```

---

## Memory Enforcement

**Problem:** Agent context can lose track of this document mid-session.

**Mitigation:** User may issue these commands at any time:

- `"Re-read WORKING-INSTRUCTIONS.md"` — Agent must re-read and acknowledge
- `"Decision check"` — Agent must verify current action doesn't require a decision
- `"Protocol check"` — Agent must state which protocol section applies to current work

**Agent self-check:** Before ANY file edit, mentally verify:
1. Is this a decision trigger? → If yes, STOP and ask
2. Does this deviate from an ADR? → If yes, STOP and ask
3. Am I assuming user approval? → If yes, STOP and ask

---

## ADR Deviation Protocol Summary

**Agent must NEVER:**
- Implement code that deviates from accepted ADRs without user approval
- Assume deviation is acceptable without asking
- Proceed with implementation if deviation is detected at planning stage

**Agent must ALWAYS:**
- Review ALL accepted ADRs before proposing any code changes
- Notify user immediately when deviation is detected (planning OR review phase)
- Present clear options and wait for user decision
- Document all deviations and resolutions in phase reports

---

## CRITICAL STOP POINTS

Before ANY of these actions, STOP and ask for explicit approval:
- [ ] Deleting files
- [ ] Removing methods/functions
- [ ] Changing service interfaces
- [ ] Modifying layer wiring
- [ ] Removing tests
- [ ] Weakening, removing, or reframing any test assertion (see Decision Trigger #8)

Format: "I propose to [ACTION]. Approve? (Y/N)"

---

*Document created: 2026-01-17*  
*Last updated: 2026-01-17*  
*Status: Awaiting user approval*

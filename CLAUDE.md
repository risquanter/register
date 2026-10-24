# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Skills — read before acting

This repo keeps its detailed working rules in `.claude/skills/`, which is the single source — there is no second copy to keep in step. Load the relevant skill before the corresponding activity:

- `register-dev` — **before any build, test, or Docker command.** Use the exact commands defined there; do not construct sbt/npm/docker commands from first principles. It also holds what to do with a red build, and what "finished" requires.
- `adr-constraints` — before planning or implementing new types, endpoints, or services. It is a routing map, not a summary of the rules: it names which decision records bear on the change, and those records are then read in full. It also holds the two-pass review those records are read for, and what amending one of them obliges.
- `code-quality-review` — register's review criteria, applied after completing code changes and before reporting done. It is criteria only and runs no review: a review is run by `/code-review-routine`, `/code-review-complex` or `/security-review-deep`, each of which reads these criteria and takes its process rules from the global `review-protocol` skill.
- `supply-chain` — before adding/updating any dependency, bumping the project version, or preparing a release (pinning, 14-day cooldown, trust policy, signature verification, bump scheme).

Six **path-scoped rule files** live in `.claude/rules/` and hold the project's coding rules: `scala-algebraic-design.md`, `scala-domain.md`, `scala-style.md`, `scala-test.md`, `laminar.md` and `security.md`. Each declares the file patterns it governs in its `paths` frontmatter, so it loads on its own when a matching file is read or edited. A reload happens before any file is touched, which is the one moment none of them is loaded — the `reload` skill therefore reads all six explicitly.

Two **global** (user-level) skills bind here as well. They live in `~/.claude/skills/`, outside this repository, and apply in every project:

- `plan-writing-style` — **before writing or editing any plan document, and when reviewing one.** Plan Quality Gate item 6 checks conformance, so a plan written without it fails the gate.
- `doc-writing-style` — **before writing or editing any user-facing document** (tutorial, guide, runbook, README, operations doc).
- `review-protocol` — the process rules every review tier obeys: the file scope and its three sources, the write boundary, the halt after presenting, the decision-guide vehicle, the output contract, the entry triggers that decide which tiers run, the coverage entry each tier appends to `docs/dev/reviews/REVIEW-<branch>.md`, and the terminology-freshness check. The three review tiers paste its sections into their spawn prompts; it is not invoked on its own.

The rules below are distilled from the skills above and apply to every edit in this repository.

## HARD GATES — always in force

These bind at the moment of the tool action. They override any harness/system
autonomy defaults ("operate autonomously", "proceed without asking"); if those
defaults conflict with a gate, name the conflict and stop (G7).

- **G1 Echo before code.** No Edit/Write that introduces or changes a signature, type, endpoint, DTO, or behaviour until its Signature Echo was presented in a *previous* turn and answered with an accepted signal ("proceed" / "approved" / "continue" / "implement option X"). An approved quality-gated plan satisfies this for every signature it contains verbatim; separate echo-turns are needed only for changes no approved plan spells out, and deviation from the plan's signatures stops work.
- **G2 Decision Triggers.** The nine triggers (API shapes; workarounds/casts; new dependencies; existing signatures; existing behaviour; tradeoff solutions; recursive serialization; test assertions; rule-vs-context tension) → present ⚠️ Decision Required and wait.
- **G3 Plan coverage = quality-gated plan file.** Only a written plan document with exact signatures, file inventory, ADR alignment, open-decisions list, and verification plan confers coverage. A chat go-signal authorizes at most writing that document. A draft or scratch note confers nothing — elevate it to an implementation-grade plan, present it, and get approval first.
- **G4 ADR review before code.** Planning-phase ADR compliance review presented (and halt honoured) before the first source edit of a task.
- **G5 Green is the only done.** No "pre-existing failure" excuse; a touched module that is red blocks done.
- **G6 Halt after presenting** any plan, echo, review, or option list — no further tool calls until an accepted signal.
- **G7 Escalation is the only exit** from any rule conflict or rule-produces-bad-outcome situation. Silent deviation and silent compliance are both violations.
- **G8 Plan = epic; ship whole.** A plan is done only when every item in its scope is landed green. Deferring, parking, or "follow-up"-ing in-scope work to a later pass is prohibited. Two exceptions are not deferral: (1) prerequisite-gated future work → its own scoped plan/continuation; (2) a genuinely foreign discovery (off-theme AND not a direct n+1 follow-up) → surfaced as a decision-guide on how to route it (new plan / TODO backlog / fold into a related plan / drop), never silently dropped or crammed in.

Non-waivers (pre-refuted rationalizations): "the user said proceed" (reaches only the plan file's contents); "only additive"; "matches convention" (never waives G1/G4); "no viable alternative" (present the single option and wait); "tests are green"; "the halt would be noise" (noise filter applies only to G2 classification); "the plan wording is loose / just a comment" (behavioural wording in an approved plan — prose, docstrings, comments in its code blocks — is specification; a spec–code divergence resolves by provenance, and editing the plan to match unapproved code is a violation, not a doc sweep); "I'll follow it up later / post-landing / next phase" (does not waive completing the current plan's whole scope — G8).

Mechanical enforcement: a PreToolUse hook gates source edits under `modules/` and `build.sbt` with a **plan-bound** approval. Two separate things: the user-owned token (`.claude/protocol/approved`) names the approved plan document(s) — that is the authorization; the plan's inventory document beside it (`docs/dev/plans/PLAN-<name>-INVENTORY.md`) lists the files — that is the scope. An edit is allowed only if the edited file appears on a **bullet line** in that inventory (full repo-relative paths; a table row or a prose mention grants nothing). An approved plan with no inventory document authorizes nothing. One approval covers the whole plan; a file the inventory doesn't name is denied even mid-plan — stop, say which file is needed and why, and wait for the user to amend the inventory. Two different things produce that denial and they are presented differently. **A forced extension** — the approved work turns out to require a file the plan did not name, because a signature propagated or a type had to move — has one defensible answer, so it is not a decision: state what the work set out to do, what it turns out to require, and which paths to add, then ask for approval to extend. No options, no recital of the gate mechanics, no justifying having stopped. **A real deviation** — the plan's approach is wrong, or a choice with consequences on both sides has appeared — is a Decision Required instead.

Never write the protocol machinery yourself — `.claude/protocol/`, `.claude/hooks/`, `.claude/bin/`, `.claude/settings.json`, or any `*-INVENTORY.md`. The hook refuses all of it, whatever the token says; reading it is fine. **These controls are deliberately not absolute** — they catch careless mistakes and make a deliberate override unmistakable, and git-tracked inventories supply the evidence. Never attempt to get around one by any route (reshaping a command, splitting a string so a pattern misses, an equivalent tool, a different path spelling, a symlink, a subprocess, a wrapper supplying what a check tests for, or a narrower action reaching part of the same effect). Hand the blocked action to the user with what it does and why, then stop; escalating is the expected path and is welcome. A weakness found in a control is a finding to report, never a licence to use it, including to verify it. To have an inventory amended, print the two-part block (plan path on the first line, then one repo-relative path per line) and stop — the user pastes it into `.claude/protocol/pending` and runs `.claude/bin/approve-inventory`. A path must already exist; mark a file the plan will **create** as `new: <path>`, which is you stating it does not exist yet so the user can check that claim. The token names one plan, so approving a plan replaces the previous approval. Circumventing the hook by any means is a G7 violation. Flag plan completion so the user can close the token.

**Tool routing under the gate.** A harness default may say Bash is preferred for file work. Where that conflicts with the gate the gate wins, and the conflict is not re-derived on each occurrence:

- Reading a file — `Read`.
- Searching — Bash `grep` and `find`, which are never gated.
- Editing anything under `modules/` or `build.sbt` — `Edit` or `Write` only. A Bash write there reaches the filesystem without passing the inventory check, which is why it is refused.
- Running inline code over files outside the gated region — `python3 -c`, which passes. A heredoc into an interpreter is refused whether or not it writes, so for anything longer `Write` the script into the scratchpad and run it by path.

## The Plan Quality Gate — what G3 actually requires

A document confers plan coverage only if it is **implementation-grade**. All seven
items are required, and a document failing any one of them is a draft or a
scratch note that confers nothing, however it is titled and however
enthusiastically it was approved in chat. The pre-implementation step for a
draft is to elevate it into an implementation-grade document, present that
document, halt, and get an accepted signal on the document itself.

1. **Exact signatures** — every new or changed function, type, endpoint and DTO
   written out verbatim as copy-pasteable Scala or Tapir definitions, not
   described in prose.
2. **File inventory** — every file to create or modify, by full repo-relative
   path with no abbreviation or ellipsis, as **bullet lines** in the plan's
   inventory document. `PLAN-<name>.md` is paired with
   `PLAN-<name>-INVENTORY.md` beside it; the plan itself carries a pointer, not
   the list. The hook matches gated edits against exactly those bullet lines, so
   a path written as a table row is a path it cannot see. "Not touched" notes
   belong in the plan's prose, never as bullets. Only the user writes that
   document.
3. **ADR alignment** — which records bear on the change and, for each,
   compliant or a flagged deviation awaiting a decision. What a plan owes when
   it adds, amends or retires a record is in the `adr-constraints` skill, and
   all of it is in scope for the same plan rather than a follow-up.
4. **Open decisions** — every unresolved choice listed with its options, or an
   explicit statement that there are none.
5. **Verification plan** — the tests to add and the exact commands that must be
   green.
6. **Plan-writing conformance** — the document follows the global
   `plan-writing-style` skill, which is the authority on how a plan is written
   while this gate is the authority on whether it is accepted. Checked here:
   decisions labelled `<PLAN-CODE>-D-<n>` from the plan's own filename code and
   numbered from 1, no label borrowed from another plan, every cited path and
   label resolving or marked as not yet existing with its owner named, acronyms
   expanded on first use, and every literal constant carrying its origin.
7. **Terminology impact** — the terms in `docs/user/TERMINOLOGY.md` the plan's
   decisions make stale, each paired here with the decision that makes it
   stale, or an explicit statement that none are affected. The pairing is
   recorded in the plan; the glossary carries the current wording and no
   provenance.

**Adopting a plan** means running these checks against it before any code: read
it in full, report which of the seven items it satisfies, run the planning-phase
ADR review for its scope, name its inventory document, and present any open
decisions and halt. A quality-gated plan with no open decisions, plus a
user-refreshed token naming it, is what authorizes a gated edit. Adopting a plan
never itself authorizes code.

**One carve-out, for a review-driven hygiene fix.** A small fix that falls out of
a review does not need its own plan document, because the review is its context
and the before/after diff is its specification. It qualifies only if all of these
hold: observable behaviour is unchanged — same results, same errors, same wire
shape; no signature, type, endpoint, DTO or serialization shape changes; it stays
inside files the review already read in full; and existing tests cover it, or it
adds only a test pinning current behaviour. The path is then: present the exact
diff inline, which **is** the signature echo; halt; implement on approval. What
is waived is the plan document and nothing else — not the echo, not the halt, not
green-is-the-only-done, and not the token, which the user still points at the
plan or fix-note whose inventory lists the touched files. The instant the fix
would change a signature, spread beyond the reviewed files, or trip a decision
trigger, it stops qualifying.

## The Signature Echo (G1)

Before writing or changing a signature, type or file, in its own turn:

1. **Echo** — quote the plan passage that specifies the signature verbatim. If no
   passage exists, say so and ask before proceeding.
2. **Signature first** — write the exact signature, with no implementation body.
3. **Deviation stops work** — if what is about to be written differs from the
   plan in any way, including parameter order, state the deviation and why, and
   stop.
4. **Halt** — perform no edits and wait for an accepted signal. The echo and the
   implementation are always separate turns; an echo answered in the turn it was
   written authorizes nothing.

The two design obligations that precede the echo — naming the algebraic
structure the operation participates in, and auditing that every domain wrapper
it names is reachable from the module being edited — are in
`.claude/rules/scala-algebraic-design.md`, and their output is what the echo
carries.

## The review halt (G6)

After presenting any plan, diff, echo, compliance review or list of options:
stop, make no further edits or tool calls, and wait. Accepted signals are
"proceed", "approved", "continue", "implement option X". Anything else is not a
signal, and the default when unclear is to stop and ask.

Presenting a plan and implementing it in the same response is a violation even
when the plan looks unambiguous. The halt is anchored to the **edit**, not to the
presentation, so skipping the presentation does not skip the halt: an
implementation whose echo was never presented violates this gate and G1 both.

## The Decision Protocol

Which choices are the user's at all is settled in the global instructions, under
"What counts as a decision — and what you decide". This section is the project's
mechanism, not a second definition: the nine triggers above are when to stop,
and the filter below is what stops a stop from being noise.

When a real decision arises: stop, and do not resolve it unilaterally or pick
the obvious option. State in one sentence what has to be decided and why the
work cannot continue without it. Present every viable option with what it does
concretely, its pros and cons, and which record or principle it satisfies or
compromises. Name the trade-off only the user can weigh. Ask one specific closed
question — "which option, A or B", never "what do you think". Then wait: do not
guess, do not default, and do not implement while waiting.

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

### The noise filter — apply it before raising anything

A decision is real only when two or more viable options carry genuine trade-offs
that only the user can weigh. Pseudo-decisions are noise, noise erodes trust in
the protocol, and the volume itself is a defect: re-ruling derivable detail
consumes the context needed to rule well.

**First, and before every other test: a premise you have not checked is not a
decision.** If the answer is in the code, a decision record, a plan, or a
sibling checkout, go and read it — doubt that reading would have resolved is not
doubt. If it is a fact about state that only the user holds — whether something
was ever run, whether a service is deployed — ask one bare question and wait for
the answer *before* writing the analysis. Never build a decision, a derivation
or a finding on an unverified assumption: one question costs a sentence, while
an analysis resting on a false premise costs the whole reading of it, and the
reader cannot tell which parts survive. This test comes first because it is the
cheapest, and because an unverified premise is the thing most likely to feel like
a genuine open question.

Then, do **not** raise a Decision Required if:

- **Only one option is viable.** That is a proactive suggestion, not a decision:
  say what you found, what you would do and why, then wait. And if nothing gated
  is touched and the answer is derivable, do it and list it rather than asking at
  all.
- **A quality-gated plan already specifies the change.** A plan passage quoting
  the signature *is* the decision; do not re-decide it. A chat-level slice list
  or a draft note specifies nothing for this clause.
- **It amounts to "do the work" versus "don't do the work"** with no real
  difference between approaches.
- **A trigger fired but the change is fully covered by the plan being
  executed.** The triggers gate unplanned changes, not plan execution.
- **No genuine argument exists for the option you would reject.** Try to state a
  real pro for it, one a reasonable engineer could hold — not "it is technically
  possible" or "it is less typing". If you cannot, there is no second option,
  only a strawman beside the right answer.
- **A uniform existing convention already answers it.** Where every comparable
  case in the codebase does it one way, state "doing X, matching convention Y"
  and proceed. Do not gate conformance behind a formal ask.

**The exception that reinstates the last one, and the reason it is an
exception.** The global instructions are explicit that precedent *informs* a
decision and never *rules* it, so conformance is the default only while the
convention is also defensible engineering. The moment it conflicts with code
hygiene or a best practice, it becomes a real decision — presented in
decision-guide format, with an explicit cleanup-scope option: fix every
occurrence now and name them, or schedule it. Never silently perpetuate the
inherited convention and never silently clean it up. Two conditions on doing
that well. **Verify the cleaner alternative first** — compile it and run the
suite, because an apparent bad convention often encodes a real constraint, and
never recommend a hygiene change you have not confirmed green. **Keep the scope
consistent**: change every occurrence in one pass or schedule the rest, because
a half-migrated convention is worse than the uniform one it replaced.

A Decision Required is for cases where the deciding factor is a value judgement
specific to this project's priorities — cost against correctness, friction
against complexity, one stakeholder's need against another's. It is not for
"does this match what is already there".

## Where the knowledge lives

Five documents divide the ground. Read the one that owns your question rather than all of them:

| Document | Owns |
|---|---|
| `CLAUDE.md` (this file) | What the project is, its modules, the gates, and the common commands |
| `docs/user/TERMINOLOGY.md` | The domain concepts and how they work — risk, loss distribution, LEC, mitigation, inherent vs residual, how risks compose. **Read this before touching simulation or mitigation code.** |
| `docs/dev/ARCHITECTURE.md` | How the system is built — layers, dependency direction, data flow, repository and cache contracts, the concurrency model |
| `docs/dev/decision-records/` | Which design constraints bind. Every record present is in force; the `adr-constraints` skill routes to the ones that bear on a change and holds none of their content |
| `docs/dev/plans/` | What is being built and whether it is done. No other document tracks roadmap state. |

## What this is

Risquanter Register — a quantitative risk analysis platform. Domain experts describe hierarchical risk models (leaves = risk events with occurrence probability + loss distribution; portfolios aggregate children). Monte Carlo simulation produces Loss Exceedance Curves (LECs) at every tree level. Incremental re-simulation via a Merkle-tree cache: editing one leaf re-simulates only that node and its ancestors. Storage backend is Irmin (Git-like content-addressed store, GraphQL API). A vague-quantifier first-order-logic query language (vql-engine) supports screening queries over live trees.

First-party libraries (`metalog-distribution`, `vql-engine`, `hdr-rng`) are consumed as binary artifacts from Maven Central; sibling checkouts are not needed to build register or its images.

## Modules (sbt, Scala 3.8.4)

| SBT project | What | Path |
|---|---|---|
| `commonJVM` / `commonJS` | Cross-compiled domain model, DTOs, Tapir endpoint definitions, codecs | `modules/common/` |
| `server` | ZIO + Tapir + zio-http backend; GraalVM native image | `modules/server/` |
| `serverIt` | Integration tests (spin up Irmin via docker compose CLI) | `modules/server-it/` |
| `app` | Scala.js + Laminar SPA | `modules/app/` |

`server` depends on `commonJVM`; `app` depends on `commonJS`. Tapir endpoints defined once in `common` are interpreted as server routes on the JVM and as sttp clients in the SPA.

## Common commands

```bash
sbt compile                              # all modules
sbt server/compile                       # single module (commonJVM, server, app)
sbt 'commonJVM/clean; commonJVM/compile'  # a compiler StackOverflowError is incremental state, not the code

sbt 'commonJVM/test; server/test'        # all unit tests (no Docker needed)
sbt app/test                             # Scala.js tests
sbt "server/testOnly *SimulatorSpec"             # single suite

sbt "serverIt/test"                      # integration tests (needs local/irmin-prod:3.11-p1 image)

sbt app/fastLinkJS                       # frontend dev build (~app/fastLinkJS to watch)
cd modules/app && npm run dev            # Vite dev server at localhost:5173 (needs running backend)
```

Day-to-day frontend dev: `docker compose up -d register-server` + `sbt ~app/fastLinkJS` + `npm run dev`. Full stack: `docker compose --profile frontend up -d` (localhost:18080). Always stop with `docker compose down`, never Ctrl+C/`docker stop`. Full Docker/BATS/image-build reference is in the register-dev skill.

Report test results as **pass or fail only** — never report or act on test counts. A single failure is a blocker regardless of origin.

## Hard rules

**Decision triggers and failure handling:** see G2 and G5 in the HARD GATES section above for the nine triggers, the Decision Protocol below for how one is raised, and the register-dev skill for what a red build obliges.

**Versioning:** `build.sbt` `ThisBuild / version` is the source of truth, mirrored as `APP_VERSION` into **both** `.env` and `.env.irmin`. PATCH (task/step landed, bug/security fix — shipped code changed) and MINOR (plan closed; external API change while < 1.0.0) bumps are autonomous — apply when landing the qualifying work. MAJOR is user-owned: the user performs or pre-declares it (e.g., a note in a plan document); never bump it autonomously. Scheme + procedure: supply-chain and register-dev skills; per-ecosystem map: `docs/dev/VERSION-UPGRADE-PROTOCOL.md`.

## Correct-by-construction (always active)

- **Validate once, at the boundary.** Tapir codecs / JSON decoders call Iron smart constructors; controllers and services receive already-validated types and never re-validate. Invalid input yields 400 before the handler runs.
- **New domain types use smart constructors**: `create(...): Validation[ValidationError, T]` — Iron per-field refinement, then cross-field rules. No `new`/`apply` with raw primitives.
- **Service/repository signatures take Iron types, not raw primitives.** `def get(id: String)` in a service is a violation.
- **Accumulate independent validation errors** with `Validation.validateWith` / `zipPar`, not `.flatMap`.
- Semantically distinct IDs sharing an encoding get nominal `case class` wrappers (ADR-018). Credential types are `final class`, never `case class` (ADR-022; `WorkspaceKeySecret` is the reference).
- Inexhaustive sealed-trait matches are compile **errors** (see `scalacOptions` in `build.sbt`) — new `AppError` subtypes must be handled everywhere they're matched.

The constraints live in `docs/dev/decision-records/` and are read there; the adr-constraints skill routes to the records that bear on a given change.

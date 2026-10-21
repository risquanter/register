# Plan: order the review tiers, split criteria from process, and make tier entry mechanical

Plan code: `RTP`. Decisions ruled by this plan are labelled `RTP-D-<n>`.

Status: ruled and ready to implement, with one open decision (`RTP-D-9`).
Implementation is held while review runs are in flight — the plan edits the
skill files those runs are reading.

## Objective

Five review skills exist, four of them user-owned. They split cleanly on depth
and execution but duplicate two other things: the review's own process rules,
copied into four files that have already drifted apart, and the register
project's review criteria, which exist in a skill, in the path-scoped rule
files, and inside a second skill's security passes.

This plan does four things. It moves the process rules into one shared skill.
It demotes the project's criteria to a corpus that carries no process rules at
all. It orders the three review tiers and gives each one a written record of
what the previous tier discharged. It makes entry into a tier a mechanical
consequence of the diff's paths rather than a judgment call made by a cheaper
tier.

## Background — the five skills and the two axes

A skill is a Markdown file whose body is loaded into the session that invokes
it. Three of the review skills then spawn one background agent, and that agent
starts with an empty context: it sees only what the spawn prompt contains. That
single fact explains the duplication this plan removes.

**The four user-owned skills.** `register/.claude/skills/code-quality-review/SKILL.md`
(518 lines) holds register's own review criteria and runs inline in the
invoking session. `~/.claude/skills/code-review-routine/SKILL.md` (217 lines)
checks a diff against an approved plan and the project's conventions, in one
background agent. `~/.claude/skills/code-review-complex/SKILL.md` (191 lines)
reconstructs invariants and reasons about interleavings, in one background
agent. `~/.claude/skills/security-review-deep/SKILL.md` (210 lines) looks for
exploit paths, in one background agent.

**The fifth is not ours.** `/security-review` ships with Claude Code. Its
definition is not present under `~/.claude/` or `register/.claude/`, so it
cannot be read or changed here. It stays available as an occasional independent
check on the corpus this plan creates, and is not part of the pipeline.

**Axis one, depth and execution, is already correct.** The routine tier's eight
escalation conditions are the same eight entry conditions the complex tier
lists, so that handoff is exact.

**Axis two, the process rules, is duplicated four times.** The section headed
"Write boundary" runs 34 lines in `code-quality-review`, 40 in
`code-review-routine`, 40 in `code-review-complex` and 22 in
`security-review-deep`. The routine and complex copies are byte-identical. The
`code-quality-review` copy differs by one sentence. The `security-review-deep`
copy differs in substance: it permits only test writes, while the other three
oblige two further writes without asking.

That difference became a contradiction because `security-review-deep` Pass 0
instructed its agent to run the `code-quality-review` checklist "verbatim as
the first analysis pass", and that document's write boundary sits in the same
file as its criteria. An agent running a deep security review was therefore
told by one loaded instruction to remove an unused import and by another that
nothing outside a test file may be written.

**The drift is already observable.** `code-review-routine` line 193 and
`code-review-complex` line 167 both carry "Presenting the report is a
presentation: halt after it." `code-quality-review` line 45 carries the same
rule in its own words. A search for "halt" in `security-review-deep` returns
nothing. One tier out of four has no halt rule, and it is the tier whose
findings are exploit paths.

**Why escalation cannot be the gate.** A tier that does not recognise a problem
does not escalate it, so a pipeline whose only entry path is an escalation
inherits the recall of whatever stands at its entrance. The routine skill
already encodes the correct principle, in its section "Default entry point when
complexity is unknown": "Quick classification from the diff itself (paths +
hunks): if tripwires are already visibly hit (auth files, concurrency/state
files, wire contracts), skip this tier and go straight to /code-review-complex."
That classification is computed from paths before any agent runs. It needs no
model to notice anything.

## Decisions

`RTP-D-1` through `RTP-D-8` were ruled on 2026-10-20. `RTP-D-9` is open.

**`RTP-D-1` — the process rules move into one shared skill.** A new global
skill, `review-protocol`, holds the write boundary, the halt rule, the
decision-guide vehicle rule, the unverified-premise rule, the output contract
and the permitted write path for the review record. Each tier instructs the
spawning session to paste the named sections verbatim into the spawn prompt, so
the agent still receives the full text and the source of that text is one file.

**`RTP-D-2` — the project's criteria become a corpus with no process rules.**
`code-quality-review` keeps Pass 0a, Pass 0b, the eleven numbered criteria
sections and the placement table. It loses the write boundary, the halt rule,
the report rules and the output contract. It is then read by a harness rather
than being one.

**`RTP-D-3` — Pass 0 keeps the corpus read and loses the verbatim-run clause.**
The clause "run that checklist verbatim as the first analysis pass" is what
imported the other file's write boundary. Under `RTP-D-2` the corpus carries no
write boundary, so there is nothing left to import; the clause goes anyway,
because a harness applies criteria and does not execute another harness. The
corpus read itself stays, as one line, and is what `RTP-D-2` depends on.

**`RTP-D-4` — the security tier owns authentication, authorization and
injection, and consumes the complex tier's record of what it established.** The
complex tier keeps those lines in scope for invariant and interleaving
purposes. It records where each control was established. The security tier does
not re-derive that control flow and spends its pass on tenancy, enumeration
oracles, secrets, containers and supply chain.

**`RTP-D-5` — the coverage record lives in the repository, one file per
branch.** `docs/dev/reviews/REVIEW-<branch>.md` does not exist yet; this plan
creates the directory and the first review run creates the file. A record that
dies with the session cannot support a security pass run a week after the
routine pass, which is how these reviews are actually run.

**`RTP-D-6` — a coverage entry carries locations, never verdicts.** An item a
downstream tier is permitted to rely on names the file and line where the
control was established and the call sites it was reached from. "Proven" is not
consumable by the next tier and would reproduce one tier down the same blind
trust that `RTP-D-7` removes at the entrance.

**`RTP-D-7` — entry into a tier is mechanical; escalation only adds scope.**
Which tiers run is computed from the diff's paths against the entry trigger
list below. An escalation from a lower tier adds scope to a tier that was
already going to run, and can additionally pull in a tier no trigger matched.
No tier's entry depends on another tier having noticed something.

**`RTP-D-8` — a full-surface security pass runs at the release gate.** Every
mechanism above is per-diff, so a defect that no trigger matched and no
criterion asked about would otherwise stay hidden indefinitely. The release
procedure in the `supply-chain` skill is the existing gate, so this adds no new
cadence to maintain, and it bounds the exposure window at one release cycle.

**`RTP-D-9` — OPEN: does `code-quality-review` keep `user-invokable: true`?**
Its current frontmatter declares `user-invokable: true` and
`argument-hint: "files or diff to review (attach changed files, or describe the
scope)"`. As a criteria corpus it no longer performs a review, so invoking it
would load criteria into the session with no harness, no write boundary and no
halt rule around them. Keeping the flag preserves a quick manual criteria pass
and risks a review run with the process rules absent. Dropping it makes the
corpus read-only to harnesses and removes that risk. Both are defensible; the
decision is the user's.

## Exact text this plan introduces

### The entry trigger list

Placed in `review-protocol` and referenced by each tier. Computed from the
diff's paths and hunk headers before any agent is spawned.

```
ENTRY TRIGGERS — computed from the diff, not from any tier's report

routine:  always.

complex:  any of —
  concurrency or shared mutable state (fibers, Ref, locks, reactive
    transaction boundaries, subscription ordering)
  identity or equality machinery (cache keys, hashing, equality, content
    addressing, deduplication)
  resource lifecycle (ownership, cleanup, cancellation)
  changed error-path semantics
  numeric or statistical correctness
  code the approved plan does not specify

security: any of —
  a Tapir endpoint definition or its request/response type
  an authentication, authorization, capability or credential file
  a data transfer object or any serialization or persistence shape
  a string handed to a parser (SQL, GraphQL, the vague-quantifier query
    language, shell, HTML, URL, a log format later parsed)
  a single-page-application output path (Laminar render site, email body,
    portable-document-format export, uniform-resource-locator or
    cascading-style-sheet construction)
  a Dockerfile, a compose file, an entrypoint script or a CI workflow
  a dependency manifest, a lockfile or a pinned version
  the release gate (full surface, not a diff — RTP-D-8)
```

### The coverage entry

Appended by each tier to `docs/dev/reviews/REVIEW-<branch>.md`, and pasted into
the next tier's spawn prompt by the invoking session.

```
COVERAGE  tier=<routine|complex|security>  scope=<the diff command or target>
read-in-full:
  <repo-relative path>
  ...
discharged:
  <checklist item number and name> — <PASS | n findings reported> — <file:line
    where the control or conclusion was established, per RTP-D-6>
  ...
escalated:
  <path> — <what was seen> (<trigger or tripwire it matches>) → <tier>
  ...
```

### The three consuming rules

Placed in `review-protocol`, binding the complex and security tiers.

```
A check the incoming coverage entry records as discharged is not re-run.

A file the entry records as read in full is still read. Reading is cheap, and
the next tier asks a different question of the same lines.

A defect seen inside a discharged check is reported as a coverage dispute
naming that entry's item, and is never suppressed. Without that rule the entry
becomes a way to launder a missed finding.
```

### The Pass 0 replacement

Replaces the whole of `security-review-deep` Pass 0 (`RTP-D-3`).

```
## Pass 0 — read the project's security corpus

Read the project's own review criteria and apply the security criteria among
them as this review's first analysis pass: the project review-criteria
document, the supply-chain policy, the security-relevant decision records, and
any recorded accepted risks. Cite the document and section per finding. Report
a recorded accepted risk in a separate "previously accepted" section, never as
a new finding. Corpus absent → say so explicitly and run the generic passes
below at full strength.
```

### The `review-protocol` skill frontmatter

```yaml
---
name: review-protocol
description: The process rules every review tier obeys — the write boundary, the halt after presenting, the decision-guide vehicle, the unverified-premise rule, the output contract, the entry triggers and the coverage entry format. Loaded by code-review-routine, code-review-complex and security-review-deep; the invoking session pastes the named sections verbatim into the spawn prompt. Not a review on its own.
---
```

Its sections, in order: Entry triggers · Coverage entry · The three consuming
rules · Write boundary · The halt · Decision-guide vehicle · Unverified
premise · Output contract · Permitted writes.

### The write boundary, stated once

The reconciled text, replacing all four copies. It resolves the contradiction
named in the background section by stating the two obliged writes and the
review record as permitted, and everything else as forbidden.

```
- Read: unrestricted.
- Write: behavioural tests. Test code that pins behaviour — a coverage-gap
  pinning test, a regression test reproducing a finding.
- Write: the boyscout obligations, without asking. Anything the `boyscout`
  skill already obliges on a file the change touched. These are performed, not
  reported. They were due in the edit that touched the file.
- Write: a plan passage the work has already superseded, where the shipped code
  is provably the later word, and only in the plan the session is working on.
- Write: this review's coverage entry, appended to
  docs/dev/reviews/REVIEW-<branch>.md and nowhere else.
- Production code, to resolve a finding: forbidden without explicit
  authorization.

A finding never authorizes its own fix.

Nothing the boundary obliges you to fix is also a finding.
```

## File inventory

The inventory document is `docs/dev/plans/PLAN-REVIEW-TIER-PIPELINE-INVENTORY.md`,
which does not exist yet; the user creates it through
`.claude/bin/approve-inventory`.

No path this plan touches is gated by the approval hook, which gates source
edits under `modules/` and `build.sbt`. The inventory exists for Plan Quality
Gate item 2 and for the record, not because an edit would otherwise be refused.
Two of the files are outside this repository, in `~/.claude/skills/`, and the
inventory names them as absolute paths for the same reason.

## ADR alignment

No decision record governs the review tooling. The only mention of code review
in the records is a rationale cell in `docs/dev/decision-records/ADR-022-secret-handling.md`
line 49, requirement R4: "Call sites must opt in to raw value extraction —
visible in code review". That is a property of the `reveal` method, not a
statement about review process, and this plan does not touch it.

The security criteria this plan relocates cite ADR-018 (nominal identifier
wrappers), ADR-021 (capability tokens and the ingress rate limit), ADR-022
(credential handling), ADR-024 (the authorization proof token), ADR-031
(bounded startup readiness) and ADR-036 (internal identifiers never crossing
the client boundary). The relocation preserves every citation; no record is
amended, added or retired by this plan.

## Verification plan

This change touches no executable code. Every file is Markdown, so the project
test suite is not triggered, per the global rule's one exemption. Verification
is that the skills load, that the duplication is gone, and that one real review
run produces the new artifacts.

```bash
ls ~/.claude/skills/review-protocol/SKILL.md
# expected: the path, printed once

grep -c "halt after it" ~/.claude/skills/review-protocol/SKILL.md
# expected: 1

grep -l "halt after it" \
  /home/danago/.claude/skills/code-review-routine/SKILL.md \
  /home/danago/.claude/skills/code-review-complex/SKILL.md \
  /home/danago/.claude/skills/security-review-deep/SKILL.md \
  /home/danago/projects/register/.claude/skills/code-quality-review/SKILL.md
# expected: no output — the rule now lives only in review-protocol

grep -n "verbatim as the first analysis pass" \
  /home/danago/.claude/skills/security-review-deep/SKILL.md
# expected: no output — the clause RTP-D-3 removes

grep -c "Write boundary" \
  /home/danago/.claude/skills/code-review-routine/SKILL.md \
  /home/danago/.claude/skills/code-review-complex/SKILL.md \
  /home/danago/.claude/skills/security-review-deep/SKILL.md \
  /home/danago/projects/register/.claude/skills/code-quality-review/SKILL.md
# expected: 0 for each of the four files
```

Then one trial run, on the working tree as it stands:

```
/code-review-routine
# expected: a report whose last block is a COVERAGE entry naming checks 1-6
# with a location per discharged item, and docs/dev/reviews/REVIEW-main.md
# created holding that entry
```

Then the handoff, with the routine run's escalation list as the complex tier's
scope:

```
/code-review-complex
# expected: the report states which incoming discharged items it relied on,
# re-runs none of them, and appends its own COVERAGE entry to the same file
```

## Sequencing

1. Write `~/.claude/skills/review-protocol/SKILL.md`, holding every block in
   the "Exact text" section above.
2. Strip the duplicated process sections from `code-review-routine` and
   `code-review-complex`, and add the paste-verbatim instruction to each spawn
   prompt specification.
3. Strip them from `security-review-deep`, add the halt rule it never had by
   way of the shared protocol, and replace Pass 0 with the text above.
4. Demote `code-quality-review` to the criteria corpus: remove the write
   boundary, the halt, the report rules and the output contract; resolve
   `RTP-D-9` for its frontmatter.
5. Create `docs/dev/reviews/`.
6. Run the verification above.

Steps 1 through 4 edit files that in-flight review runs are reading, so none of
them starts until those runs have reported.

## Version bump

None. `ThisBuild / version` in `build.sbt` tracks shipped code, and this plan
changes no shipped code.

# Copilot Instructions — Register

## ⛔ HARD GATE — read these files before any other action

Do not respond to any user request involving code, planning, editing, building, or
reviewing until all three skill files below have been read via `read_file`. This is
not a soft guideline. It is a precondition. Skipping this step is a protocol violation
regardless of outcome or perceived urgency.

```
/home/danago/projects/register/.github/skills/working-protocol/SKILL.md
/home/danago/projects/register/.github/skills/adr-constraints/SKILL.md
/home/danago/projects/register/.github/skills/code-quality-review/SKILL.md
```

Read all three. Then read the register-dev skill before any build or test command:

```
/home/danago/projects/register/.github/skills/register-dev/SKILL.md
```

---

## Build and test commands

Before running any compilation, test, or Docker command — as a standalone task or as
a sub-step of a broader task — load the **register-dev** skill and use the exact
commands defined there. Do not infer or construct sbt, npm, or docker compose commands
from first principles.

---

## Dependencies and versioning

Before adding or updating any dependency (npm, sbt, opam, apk, Docker base image,
fetched binary), bumping the project version, or preparing a release, load the
**supply-chain** skill (`.github/skills/supply-chain/SKILL.md`). Its hard rules:
exact version pinning everywhere; a 14-day cooldown on newly published versions
(waived for fixes to disclosed vulnerabilities); dependencies from unestablished
publishers need user approval documented at the pin site; signature verification
wherever the ecosystem supports it (prefer Sigstore/cosign); npm installs are
ask-first, always. PATCH and MINOR bumps of our own version are autonomous;
MAJOR is user-owned. Map of what a change triggers:
`docs/dev/VERSION-UPGRADE-PROTOCOL.md`; policy: `docs/dev/ADR-020-supply-chain-security.md`.

---

## Working protocol — load before any file edit

Load the **working-protocol** skill unconditionally before making any edit to any file.
This is not optional and does not require explicit instruction. A file edit made without
first loading the working-protocol is a protocol violation regardless of outcome.

Load the **adr-constraints** skill during any planning or implementation phase that
introduces new types, endpoints, services, or changes to existing code. Must be loaded
before the first edit in any such session.

Load the **code-quality-review** skill after completing any set of code changes, before
reporting work as done. The review is mandatory and automatic — it does not require
explicit instruction from the user.

---

## Decision triggers — stop and ask before proceeding

Stop immediately, present options, and wait for an explicit decision before taking any
of the following actions. Do not resolve these silently. Do not pick the "obvious"
option. "I'll proceed with X" without asking is a protocol violation. "I'll present the options available, but not waiting for the user for making a choice" is a protocol violation.

1. Any change to an API shape, Tapir endpoint signature, or OpenAPI output
2. Any workaround: `asInstanceOf`, `Schema.any`, unsafe cast, escape hatch
3. Adding a new library dependency not already present in `build.sbt`
4. Modifying an existing case class field, opaque type, or public method signature
5. Changing the behaviour of existing code (not just adding new code alongside it)
6. Any solution that has tradeoffs or caveats — including "it works but..."
7. Any recursive or self-referential type requiring special serialization handling
8. Removing, weakening, disabling, reframing, or renaming any test assertion —
   even when the failure appears unrelated to the current change
9. Following any instruction file rule appears to produce a demonstrably worse
   outcome in the current context, or conflicts with another instruction —
   including platform/system autonomy defaults ("operate autonomously",
   "proceed without asking"). Name the rule, the file, and the concern.
   Never silently deviate; never blindly comply into a known bad outcome.
   The only valid exit is this escalation path.

Decision request format:
```
⚠️ Decision Required
Context: [what was being implemented]
Issue: [what problem arose]
Options: A) … B) … C) …
Decision needed: [single specific closed question]
```

---

## Mandatory review halt

After presenting any plan, diff, signature echo, compliance review, or list of options:
stop immediately, make no further edits or tool calls, and wait for an explicit signal.

Accepted signals: "proceed" · "approved" · "continue" · "implement option X"

Anything else is not a signal. Default action when unclear: stop and ask.

The halt is anchored to the **edit**, not to the presentation: skipping the
presentation does not skip the halt. An implementation whose plan, echo, or
options were never presented is a double violation, not a loophole. The echo
and the implementation are always separate turns — an echo answered in the
same turn it was written authorizes nothing.

An approved implementation-grade plan satisfies the signature-echo
requirement for every signature it contains verbatim — approving the plan
approves those signatures, and the whole plan is then implemented without
per-change halts. A separate echo-turn is required only for a change no
approved plan spells out; any deviation from the plan's signatures stops
work and escalates.

---

## Plan coverage and the Plan Quality Gate

"Covered by the approved plan" refers only to a **written plan document**
(`PLAN-*.md` or equivalent) that specifies the change with exact signatures.
A chat go-signal ("proceed", "start implementation", "approved") authorizes at
most writing or updating that plan document — never source code the document
does not spell out.

A document confers plan coverage only if it is **implementation-grade** — all
five items present:

1. Exact signatures for everything new or changed (verbatim, copy-pasteable — not prose)
2. File inventory (every file to create or modify, by full repo-relative
   path — no abbreviations; enforcement tooling matches edits against it)
3. ADR alignment (which ADRs bear; compliant or flagged deviation for each)
4. Open decisions listed with options, or an explicit "no open decisions"
5. Verification plan (tests to add + exact commands that must be green)

A document failing any item is a draft or scratch note and confers nothing,
regardless of its title or chat approval. Pre-implementation step for a
draft: elevate it into an implementation-grade plan, present it, halt, and
obtain an accepted signal on the document itself.

---

## Precedence and non-waivers

This protocol overrides any platform/system autonomy defaults ("operate
autonomously", "proceed without asking"). If such a default conflicts with a
rule here, name the conflict and stop (decision trigger 9) — silent
resolution in either direction is a violation.

None of the following waives a gate (each has been used as a rationalization;
they are pre-refuted): "the user said proceed / start implementation" (that
reaches only the plan file's contents); "the change is only additive"; "it
matches existing convention" (settles at most a decision, never the echo or
the ADR review); "there is no viable alternative" (present the single option
and wait); "tests are green / the outcome is correct" (outcome does not cure
process); "the halt would be noise" (the noise filter applies only to
decision classification, never to echoes, ADR review, or the review halt).

---

## No "pre-existing failure" excuse

A compile error or failing test in any module you build or run is yours to fix —
the origin of the failure is irrelevant to your obligation to resolve it.

- Never dismiss, defer, or narrate around a build/test failure because it is
  "pre-existing", "unrelated", "already broken", or "not caused by my change".
- Never spend a tool call proving a failure is pre-existing. Spend it fixing.
- Never report work as done while any module you touched is red. Green is the only done.
- The sole exception is a fix with a genuine tradeoff (weakening an assertion, changing
  an API shape, a workaround) → raise ⚠️ Decision Required. "It's unrelated" is not a
  tradeoff.

---

## Correct-by-construction — always-active design constraint

This applies to every new type, method, endpoint, DTO, and service in this codebase.
It is not optional and does not need to be explicitly requested.

- **Validate once, at the boundary.** Tapir codecs and JSON decoders call Iron smart
  constructors. Controllers and services receive already-validated types — they never
  validate or re-validate raw input.
- **New domain types use smart constructors.** Every new domain object exposes
  `create(...): Validation[ValidationError, T]` with per-field Iron refinement in layer 1
  and cross-field business rules in layer 2. No `new`/`apply` with raw primitives.
- **Service and repository signatures accept Iron types, not raw primitives.**
  `def get(id: String)` in a service layer is a violation regardless of context.
- **New API endpoints validate in the codec, not the handler.** Invalid input must
  produce a 400 before the handler is invoked.

For the full constraint set across all ADRs, load the **adr-constraints** skill at the
start of any planning or implementation phase that introduces new types, endpoints, or
services.
For governance protocol (Decision Protocol steps, Signature Echo, Blocked State),
load the **working-protocol** skill.

For pre-commit review, load the **code-quality-review** skill.
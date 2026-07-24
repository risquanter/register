# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Skills — read before acting

This repo keeps its detailed working rules in `.github/skills/` (canonical source, shared with Copilot) and mirrors them to `.claude/skills/` so they load as Claude Code skills. Edit `.github/skills/` first, then re-copy the file to `.claude/skills/` — the mirrors must stay byte-identical. Load the relevant skill before the corresponding activity:

- `register-dev` — **before any build, test, or Docker command.** Use the exact commands defined there; do not construct sbt/npm/docker commands from first principles.
- `working-protocol` — before editing any file (HARD GATES G1–G7, Decision Protocol, Signature Echo, Blocked State).
- `adr-constraints` — before planning or implementing new types, endpoints, or services (distilled ADR rules).
- `code-quality-review` — after completing code changes, before reporting done.
- `supply-chain` — before adding/updating any dependency, bumping the project version, or preparing a release (pinning, 14-day cooldown, trust policy, signature verification, bump scheme).

`.github/copilot-instructions.md` is the equivalent gate file for Copilot; the rules below are distilled from it and apply here too.

## HARD GATES — always in force (distilled; canonical text: working-protocol skill)

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

Non-waivers (pre-refuted rationalizations): "the user said proceed" (reaches only the plan file's contents); "only additive"; "matches convention" (never waives G1/G4); "no viable alternative" (present the single option and wait); "tests are green"; "the halt would be noise" (noise filter applies only to G2 classification).

Mechanical enforcement: a PreToolUse hook gates source edits under `modules/` and `build.sbt` with a **plan-bound** approval — the user-owned token (`.claude/protocol/approved`) names the approved plan document(s), and an edit is allowed only if the edited file is listed in that plan's file inventory (full repo-relative paths required). One approval covers the whole plan; a file the plan doesn't name is denied even mid-plan — that denial is the deviation escalation (stop, present, wait, amend the plan). Never create, touch, read, or modify anything under `.claude/protocol/` yourself — the token is user-owned; circumventing the hook (via Bash or any other means) is a G7 violation. Flag plan completion so the user can close the token.

## What this is

Risquanter Register — a quantitative risk analysis platform. Domain experts describe hierarchical risk models (leaves = risk events with occurrence probability + loss distribution; portfolios aggregate children). Monte Carlo simulation produces Loss Exceedance Curves (LECs) at every tree level. Incremental re-simulation via a Merkle-tree cache: editing one leaf re-simulates only that node and its ancestors. Storage backend is Irmin (Git-like content-addressed store, GraphQL API). A vague-quantifier first-order-logic query language (vql-engine) supports screening queries over live trees.

Sibling repos `../vague-quantifier-logic` and `../hdr-rng` must be checked out for Docker builder-image builds.

## Modules (sbt, Scala 3.7.4)

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

sbt 'commonJVM/test; server/test'        # all unit tests (no Docker needed)
sbt app/test                             # Scala.js tests
sbt "server/testOnly *SimulationSemaphoreSpec"   # single suite

sbt "serverIt/test"                      # integration tests (needs local/irmin-prod:3.11-p1 image)

sbt app/fastLinkJS                       # frontend dev build (~app/fastLinkJS to watch)
cd modules/app && npm run dev            # Vite dev server at localhost:5173 (needs running backend)
```

Day-to-day frontend dev: `docker compose up -d register-server` + `sbt ~app/fastLinkJS` + `npm run dev`. Full stack: `docker compose --profile frontend up -d` (localhost:18080). Always stop with `docker compose down`, never Ctrl+C/`docker stop`. Full Docker/BATS/image-build reference is in the register-dev skill.

Report test results as **pass or fail only** — never report or act on test counts. A single failure is a blocker regardless of origin.

## Hard rules (from copilot-instructions.md)

**Decision triggers and failure handling:** see G2 and G5 in the HARD GATES section above — the nine-trigger list and the no-pre-existing-excuse rule live there (canonical text: working-protocol skill).

**Versioning:** `build.sbt` `ThisBuild / version` is the source of truth, mirrored as `APP_VERSION` into **both** `.env` and `.env.irmin`. PATCH (task/step landed, bug/security fix — shipped code changed) and MINOR (plan closed; external API change while < 1.0.0) bumps are autonomous — apply when landing the qualifying work. MAJOR is user-owned: the user performs or pre-declares it (e.g., a note in a plan document); never bump it autonomously. Scheme + procedure: supply-chain and register-dev skills; per-ecosystem map: `docs/dev/VERSION-UPGRADE-PROTOCOL.md`.

## Correct-by-construction (always active)

- **Validate once, at the boundary.** Tapir codecs / JSON decoders call Iron smart constructors; controllers and services receive already-validated types and never re-validate. Invalid input yields 400 before the handler runs.
- **New domain types use smart constructors**: `create(...): Validation[ValidationError, T]` — Iron per-field refinement, then cross-field rules. No `new`/`apply` with raw primitives.
- **Service/repository signatures take Iron types, not raw primitives.** `def get(id: String)` in a service is a violation.
- **Accumulate independent validation errors** with `Validation.validateWith` / `zipPar`, not `.flatMap`.
- Semantically distinct IDs sharing an encoding get nominal `case class` wrappers (ADR-018). Credential types are `final class`, never `case class` (ADR-022; `WorkspaceKeySecret` is the reference).
- Inexhaustive sealed-trait matches are compile **errors** (see `scalacOptions` in `build.sbt`) — new `AppError` subtypes must be handled everywhere they're matched.

Full constraint set: adr-constraints skill; the ADRs themselves live in `docs/dev/` alongside `ARCHITECTURE.md`.

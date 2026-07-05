# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Skills — read before acting

This repo keeps its detailed working rules in `.github/skills/`. Read the relevant file before the corresponding activity:

- `.github/skills/register-dev/SKILL.md` — **before any build, test, or Docker command.** Use the exact commands defined there; do not construct sbt/npm/docker commands from first principles.
- `.github/skills/working-protocol/SKILL.md` — before editing any file (governance: Decision Protocol, Signature Echo, Blocked State).
- `.github/skills/adr-constraints/SKILL.md` — before planning or implementing new types, endpoints, or services (distilled ADR rules).
- `.github/skills/code-quality-review/SKILL.md` — after completing code changes, before reporting done.

`.github/copilot-instructions.md` is the equivalent gate file for Copilot; the rules below are distilled from it and apply here too.

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

sbt "serverIt/test"                      # integration tests (needs local/irmin-prod:3.11 image)

sbt app/fastLinkJS                       # frontend dev build (~app/fastLinkJS to watch)
cd modules/app && npm run dev            # Vite dev server at localhost:5173 (needs running backend)
```

Day-to-day frontend dev: `docker compose up -d register-server` + `sbt ~app/fastLinkJS` + `npm run dev`. Full stack: `docker compose --profile frontend up -d` (localhost:18080). Always stop with `docker compose down`, never Ctrl+C/`docker stop`. Full Docker/BATS/image-build reference is in the register-dev skill.

Report test results as **pass or fail only** — never report or act on test counts. A single failure is a blocker regardless of origin.

## Hard rules (from copilot-instructions.md)

**Decision triggers — stop and ask before:** changing API shapes/Tapir signatures/OpenAPI output; any workaround (`asInstanceOf`, `Schema.any`, unsafe cast); adding a dependency not in `build.sbt`; modifying existing case class fields, opaque types, or public signatures; changing existing behaviour (vs. adding alongside); any solution with tradeoffs ("it works but…"); recursive types needing special serialization; weakening/removing/renaming any test assertion. Present options and wait for an explicit decision.

**No "pre-existing failure" excuse:** a compile error or failing test in any module you build or run is yours to fix, regardless of origin. Never report done while a touched module is red.

**Version bumps are user-owned:** `build.sbt` `ThisBuild / version` is the source of truth, mirrored into `.env` as `APP_VERSION`. Flag when a bump qualifies; never bump autonomously.

## Correct-by-construction (always active)

- **Validate once, at the boundary.** Tapir codecs / JSON decoders call Iron smart constructors; controllers and services receive already-validated types and never re-validate. Invalid input yields 400 before the handler runs.
- **New domain types use smart constructors**: `create(...): Validation[ValidationError, T]` — Iron per-field refinement, then cross-field rules. No `new`/`apply` with raw primitives.
- **Service/repository signatures take Iron types, not raw primitives.** `def get(id: String)` in a service is a violation.
- **Accumulate independent validation errors** with `Validation.validateWith` / `zipPar`, not `.flatMap`.
- Semantically distinct IDs sharing an encoding get nominal `case class` wrappers (ADR-018). Credential types are `final class`, never `case class` (ADR-022; `WorkspaceKeySecret` is the reference).
- Inexhaustive sealed-trait matches are compile **errors** (see `scalacOptions` in `build.sbt`) — new `AppError` subtypes must be handled everywhere they're matched.

Full constraint set: adr-constraints skill; the ADRs themselves live in `docs/dev/` alongside `ARCHITECTURE.md`.

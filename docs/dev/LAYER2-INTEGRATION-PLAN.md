# Layer 2 Integration Plan — SpiceDB Fine-Grained Authorization

**Date:** May 2026  
**Status:** Pre-integration — app-side Waves 0+1 claimed done, live adapter not yet written  
**Related:**
- [`docs/dev/AUTHORIZATION-PLAN.md`](./AUTHORIZATION-PLAN.md) — master design (Layers 0–2)
- [`docs/dev/prompt-l2-agent.md`](./prompt-l2-agent.md) — step-by-step implementation prompt for an agent working on the **`register` app** (not infra)

---

## Scope of the Agent Prompt

`prompt-l2-agent.md` concerns **`register` only**. It explicitly excludes `register-infra`:

> "SpiceDB infrastructure is managed in `register-infra`. You do NOT modify that repo.
> Your job is application code only."

The prompt's six steps are all Scala/ZIO changes: domain model wiring (`WorkspaceId`),
config-driven mode selection, the live `AuthorizationServiceSpiceDB` HTTP adapter,
`check()` call sites on workspace-lifecycle routes, and bootstrap `seed()` wiring.

---

## The Alignment Gap

The two repos have been progressing independently. The known risk is **schema drift**:
`register-infra` may have already deployed or versioned a `schema.zed` that diverges
from the schema specified in `AUTHORIZATION-PLAN.md §Task L2.1`, which the app prompt
instructs an agent to copy verbatim. A mismatch here breaks the live adapter silently
at runtime (wrong relation names → `PERMISSIONSHIP_NO_PERMISSION` on every check).

---

## Two Parallel Work Streams

These can proceed independently up to integration-test time:

### Stream A — App side (`register`) — no infra dependency

All of Steps 1–5 in `prompt-l2-agent.md` can be written and unit-tested with
`AuthorizationServiceStub` (Set-backed, in-memory). The live HTTP adapter
(`AuthorizationServiceSpiceDB`) can be written and tested against a mock HTTP
server without SpiceDB running. No infra dependency until Step 6 / integration
tests.

| Step | Infra dependency? |
|------|-------------------|
| 1 — Wire `WorkspaceId` into domain model | None |
| 2 — Create `schema.zed` | **Requires schema alignment with infra first** |
| 3 — Config-driven mode selection in `Application.scala` | None |
| 4 — `AuthorizationServiceSpiceDB` live adapter | None (mock HTTP in unit tests) |
| 5 — Workspace-level `check()` call sites | None |
| 6 — Bootstrap `seed()` wiring | None (NoOp stub passes) |
| Integration test with live SpiceDB | **Requires infra to be up + schema loaded** |

### Stream B — Infra side (`register-infra`) — verify readiness

Before Step 2 and before integration testing, confirm:

1. **Schema:** Does `register-infra` have a `schema.zed` / `zed schema write` job?
   Compare it against `AUTHORIZATION-PLAN.md §Task L2.1` line by line.
   Resolve any drift before the app agent copies the schema.

2. **Connectivity:** Are `SPICEDB_URL` and `SPICEDB_TOKEN` defined in Helm values
   or Kubernetes secrets for the target environment?

3. **Deployment state:** Is SpiceDB running and reachable from the cluster?
   (2 replicas, PDB, NetworkPolicy — per `prompt-l2-agent.md §Infrastructure Context`)

---

## Recommended Order

1. **Verify Wave 0 + Wave 1 "already done" claims** — read the actual app files
   listed in `prompt-l2-agent.md §Current Code State` before handing the prompt
   to an agent. Stale "done" status leads to skipped work or double-implementation.

2. **Align schema** — compare `register-infra` schema against
   `AUTHORIZATION-PLAN.md §Task L2.1`. Resolve drift. This gates Step 2 only.

3. **Execute Steps 1, 3–6 in `register`** (stream A, no infra gate) — unit tests
   must stay green throughout; NoOp/Stub path must not regress.

4. **Execute Step 2** (`schema.zed` creation) once schema alignment is confirmed.

5. **Integration test** once infra confirms SpiceDB is reachable and schema is loaded.

---

## Success Criteria

- All existing `HttpApiIntegrationSpec` tests pass with NoOp layers throughout
- `RouteSecurityRegressionSpec` new invariants pass with Stub layers
- In `fine-grained` mode: workspace-lifecycle routes return 403 with empty stub grants,
  200 with matching grants
- Live adapter integration test: `check()` round-trip against real SpiceDB passes
- `authz.check.total` OTel counter increments on every protected request

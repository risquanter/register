# Authorization Implementation — Phase Order

**Date:** 2026-07-01
**References:**
- [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md) — strategic design, wave structure, route matrix
- [AUTHORIZATION-IMPLEMENTATION-PLAN.md](./AUTHORIZATION-IMPLEMENTATION-PLAN.md) — type corrections, `Checked[P]`, `BootstrapProvisioner`, K8s hardening
- [AUTH-TESTING-PLAN.md](./AUTH-TESTING-PLAN.md) — BATS end-to-end tests (infra) and Scala `server-it` test cases

---

## Phase 0 — Parallel immediate starts

All items in this phase have no inter-dependencies and can proceed simultaneously.

**`register` project:**
| Task | Ref | Notes |
|------|-----|-------|
| Wave 0B: `UserId` sum type (`Anonymous \| Authenticated`) ✅| IMPL-PLAN §B | Largest change; start first — blocks Wave 1 and everything downstream |
| Wave 0C: `SpiceDbConfig.scala` (HTTPS-only URL, `PositiveInt` timeout) ✅ | IMPL-PLAN §C | HTTPS-only URL constraint + PositiveInt timeout — done in Wave 0C |
| Wave 0D: `BootstrapProvisioner` trait + NoOp + SpiceDB files ✅| IMPL-PLAN §D | Independent of sum type |
| Wave 2 completion: wire `requirePresent` into `Identity`/`FineGrained` branches ✅ | AUTH-PLAN Wave 2 | Wired in `Application.scala` `chooseUserContextExtractor`; Identity + FineGrained → `UserContextExtractor.requirePresent`. Verified 2026-07-04. |
| ADR-024: add lifecycle write clarification + service account scope ✅| IMPL-PLAN Pre-Wave | Documentation edit only |
| `infra/spicedb/schema.zed` ✅ | AUTH-PLAN §L2.1 | Created at `infra/spicedb/schema.zed`. Schema is verbatim from AUTH-PLAN §L2.1 with one addition: `permission admin_workspace = owner_user + owner_team->manage_team` on `workspace` — required by Wave 5 rotate/delete calls; absent from plan original. Verified 2026-07-04. |

**`register-infra` project:**
| Task | Ref |
|------|-----|
| K.1: k3s cluster bootstrap | AUTH-PLAN §K.1 |
| K.2: Container registry + image pipeline | AUTH-PLAN §K.2 |
| K.3: PostgreSQL on k3s | AUTH-PLAN §K.3 |

---

## Phase 1 — After Phase 0

**`register`:** Wave 1 ✅ — `Checked[P]` proof token implemented; `check()` return type `Unit` → `Checked[P]`; protected service method `using Checked[Permission]` parameters; `bootstrapToken()` + `systemMaintenanceToken()` lifecycle proof tokens; `WorkspaceReaper` updated with `BootstrapProvisioner` dependency; 478 tests pass (verified 2026-07-04). **Implementation note:** service methods use base `Checked[Permission]` type (not specific subtypes) — see ADR-030 §3.
→ Blocked by: Wave 0B (`UserId.Authenticated` required in signatures)
→ Ref: IMPL-PLAN §Wave 1

**`register-infra`:** K.4 — Keycloak on k3s (realm, clients, roles, mappers, OIDC flow).
→ Blocked by: K.3
→ Ref: AUTH-PLAN §K.4

---

## Phase 2 — After Phase 1

All items can run in parallel within this phase.

**`register`:**
| Task | Blocked by | Ref |
|------|------------|-----|
| Wave 3: `AuthorizationServiceSpiceDB` | Wave 0C + Wave 1 | AUTH-PLAN §L2.2, IMPL-PLAN §C. **Exit criteria:** (1) T-U1–T-U5 mock HTTP adapter unit tests pass (see AUTH-TESTING-PLAN §W3). (2) OTel counter `authz.check.total` and histogram `authz.check.latency_ms` increment on every `check()` call — follow `RiskTreeServiceLive` pattern. (3) Structured log per check() call using `user.value` for PII opt-in. |
| Waves 4–5: workspace-level `check()` on all lifecycle routes ✅ | Wave 1 | AUTH-PLAN Waves 4–5. All 12 routes structurally wired with `given Checked[Permission] <- authzService.check(...)`. Enforcement active in fine-grained mode once Wave 3 is deployed. |
| Wave 6: `BootstrapProvisioner.recordOwnership()` wiring | Wave 0D + Wave 0B + Wave 1 | IMPL-PLAN §Wave 6. `bootstrapWorkspace` still anonymous (no `requireAuthenticated`, no `recordOwnership`). |

**`register-infra`:** K.5 — Istio ambient mode, `RequestAuthentication`, `AuthorizationPolicy`, JWT claim header injection, waypoint header-stripping verification.
→ Blocked by: K.4
→ Ref: AUTH-PLAN §K.5

---

## Phase 3 — After Phase 2

**`register`:** `server-it` SpiceDB adapter tests (T-S1–T-S10) — add SpiceDB to `docker-compose.server-it.yml`, write `AuthorizationServiceSpiceDBSpec`.
→ Blocked by: Wave 3 (not yet implemented) + `infra/spicedb/schema.zed` ✅
→ Ref: IMPL-PLAN §SpiceDB Adapter Integration Tests

**`register-infra`:**
| Task | Blocked by | Ref |
|------|------------|-----|
| SpiceDB Helm deployment + apply `schema.zed` | K.3 + `schema.zed` committed | AUTH-PLAN §L2.0 |
| K.6: CI/CD pipeline + provisioning job + drift detection | SpiceDB deployed | AUTH-PLAN §K.6, IMPL-PLAN §K.6 |
| BATS §L0 (B-L0-1–3): capability-only mode | Running service | AUTH-TESTING-PLAN §L0 |
| BATS §K5 (B-K5-1–3): header spoofing | K.5 | AUTH-TESTING-PLAN §K5, **K.5 exit criterion** |
| BATS §L1 (B-L1-1–4): identity mode | K.5 + Wave 2 deployed | AUTH-TESTING-PLAN §L1 |

---

## Phase 4 — Full stack live

**`register-infra`:** All remaining BATS test suites.
| Suite | Blocked by |
|-------|------------|
| §L2, §L2W (fine-grained read/write) | K.5 + Waves 3–5 deployed + schema applied |
| §BOOT (B-BOOT-1–3): bootstrap ownership lifecycle | K.5 + Wave 6 deployed + schema applied |
| §FC (B-FC-1–3): fail-closed behaviour | K.5 + Wave 3 deployed |
| §K6 (B-K6-1–4): drift detection | K.6 provisioning job + Wave 3 deployed |

→ Ref: AUTH-TESTING-PLAN §Completion Criteria — all items must pass before any non-dev environment is declared authorization-complete.

---

## Critical Path

```
Wave 0B (UserId sum type)
  → Wave 1 (Checked[P] proof token)
    → Wave 3 (AuthorizationServiceSpiceDB)
      → server-it T-S1–T-S10
      → BATS §L2 / §BOOT / §FC
```

Everything else is off the critical path and parallelizable.

---

## Project Hand-Off Points

| `register` delivers | `register-infra` can then proceed |
|---------------------|-----------------------------------|
| `infra/spicedb/schema.zed` committed | Deploy SpiceDB + apply schema. **`register-infra` MUST read this file from the `register` checkout — do not maintain a separate copy. See ADR-030 §6.** |
| Wave 2 deployed image | BATS §L1 (also needs K.5 live) |
| Wave 3 deployed image | BATS §L2, §FC (also needs K.5 live) |
| Wave 6 deployed image | BATS §BOOT-1 full ownership lifecycle |

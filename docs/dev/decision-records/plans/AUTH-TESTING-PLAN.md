# Authorization Testing Plan — BATS Smoke Tests

**Date:** 2026-07-01
**Audience:** `register-infra` agent — implement as BATS tests against the deployed K8s stack
**Companion:** [AUTHORIZATION-IMPLEMENTATION-PLAN.md](./AUTHORIZATION-IMPLEMENTATION-PLAN.md) — Scala `server-it` tests (T-S1–T-S10) for the SpiceDB HTTP adapter; overlap is intentional (defense in depth)
**Related:** [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md) — master plan; [ADR-012](./ADR-012.md) — mesh trust; [ADR-024](./ADR-024-externalized-authorization-pep-pattern.md) — PEP pattern

---

## Purpose

This document specifies end-to-end authorization test cases to be implemented as BATS tests
in `register-infra`. They test the full deployed stack: Istio waypoint + Keycloak JWT +
application `AuthorizationService` + SpiceDB — behaviour that cannot be verified by unit
tests or the `server-it` Scala module alone.

**What these tests verify that Scala tests cannot:**
- Istio waypoint strips spoofed external `x-user-*` headers before claim injection
- JWT validation is performed by the mesh (not the application)
- End-to-end HTTP status codes for authorized, forbidden, and unauthenticated requests
- SpiceDB drift detection in the provisioning CI job
- Mode transition behaviour across service restarts

---

## Prerequisites

Each test suite assumes the following are available via env vars:

```bash
REGISTER_URL          # deployed service base URL, e.g. https://register.example.com
ALICE_JWT             # valid JWT for alice (owner of workspace ws1 in SpiceDB)
BOB_JWT               # valid JWT for bob (no SpiceDB relationships)
CAROL_JWT             # valid JWT for carol (viewer on ws1)
WS1_KEY               # workspace key for ws1 (pre-created)
WS1_ID                # WorkspaceId ULID for ws1
TREE1_ID              # TreeId ULID of tree1 in ws1
SPICEDB_URL           # SpiceDB endpoint for direct verification via zed CLI
SPICEDB_TOKEN         # SpiceDB token for zed CLI
```

Test data setup (relationships) is performed via `zed` CLI or the provisioning job before
the test suite runs. Each section notes required pre-seeded state.

---

## Section L0 — Capability-Only Mode

> `register.auth.mode = capability-only`
> SpiceDB is not consulted. `x-user-id` header is ignored. Workspace key is the sole credential.

### B-L0-1 — Workspace accessible with valid key, no JWT

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "200" ]]
```

### B-L0-2 — Workspace accessible with valid key and a JWT (JWT is ignored in this mode)

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "200" ]]
```

### B-L0-3 — Invalid workspace key returns 404 regardless of JWT

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  "${REGISTER_URL}/w/AAAAAAAAAAAAAAAAAAAAAA/risk-trees")
[[ "${status}" == "404" ]]
```

---

## Section L1 — Identity Mode

> `register.auth.mode = identity`
> Valid JWT required. SpiceDB not consulted. Any authenticated user with the key is allowed.
> Overlap with **T-S** tests: T-S tests verify the Scala adapter; these verify the full HTTP pipeline including Istio JWT validation.

### B-L1-1 — Workspace accessible with valid key + valid JWT

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "200" ]]
```

### B-L1-2 — Workspace returns 403 with valid key but no JWT

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "403" ]]
```

### B-L1-3 — Workspace returns 403 with expired JWT

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${EXPIRED_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "403" ]]
```

### B-L1-4 — Workspace returns 403 with JWT from wrong Keycloak realm

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${WRONG_REALM_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "403" ]]
```

---

## Section L2 — Fine-Grained Mode (Read)

> `register.auth.mode = fine-grained`
> Valid JWT required **and** explicit SpiceDB relationship required.
> Overlap with **T-S1, T-S2, T-S3, T-S4**: Scala tests verify adapter mapping; these verify the full HTTP pipeline.

**Pre-seeded state:** `workspace:ws1#owner_user@user:${ALICE_SUB}`,
`workspace:ws1#viewer@user:${CAROL_SUB}`,
`risk_tree:tree1#workspace@workspace:ws1`

### B-L2-1 — Owner can list workspace trees

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "200" ]]
```

### B-L2-2 — User with no relationship gets 403 on workspace read

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${BOB_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "403" ]]
```

### B-L2-3 — Viewer can read trees (view_workspace permission)

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${CAROL_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "200" ]]
```

### B-L2-4 — Tree accessible via workspace inheritance (owner_user → view_tree)

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees/${TREE1_ID}/structure")
[[ "${status}" == "200" ]]
# Alice has no direct tree relationship — access flows via workspace owner_user → view_tree
```

### B-L2-5 — Tree inaccessible to user with no workspace relationship

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${BOB_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees/${TREE1_ID}/structure")
[[ "${status}" == "403" ]]
```

---

## Section L2W — Fine-Grained Mode (Write)

### B-L2W-1 — Owner can create a tree (design_write)

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  -H "Content-Type: application/json" \
  -d '{"name":"New Tree"}' \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "200" ]]
```

### B-L2W-2 — Viewer cannot create a tree (no design_write)

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  -H "Authorization: Bearer ${CAROL_JWT}" \
  -H "Content-Type: application/json" \
  -d '{"name":"New Tree"}' \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "403" ]]
```

### B-L2W-3 — User with no relationship cannot delete workspace (no admin_workspace)

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
  -H "Authorization: Bearer ${BOB_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}")
[[ "${status}" == "403" ]]
```

---

## Section BOOT — Bootstrap and Ownership

> These verify the full lifecycle: workspace creation → SpiceDB ownership tuple written → owner can access.
> Overlap with **T-S10**: Scala test verifies the adapter write; this verifies end-to-end HTTP + SpiceDB state.

### B-BOOT-1 — Bootstrap creates workspace and records SpiceDB owner_user tuple

```bash
# Create a new workspace as alice
response=$(curl -s -X POST \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boot Test WS","rootName":"Root"}' \
  "${REGISTER_URL}/workspaces/bootstrap")

new_key=$(echo "${response}" | jq -r '.workspaceKey')
new_ws_id=$(echo "${response}" | jq -r '.workspaceId')

# Verify the owner_user tuple exists in SpiceDB
zed permission check \
  --endpoint="${SPICEDB_URL}" --token="${SPICEDB_TOKEN}" \
  "workspace:${new_ws_id}" view_workspace "user:${ALICE_SUB}"
# exit code 0 = PERMISSIONSHIP_HAS_PERMISSION
```

### B-BOOT-2 — Creator can immediately access bootstrapped workspace

```bash
# Continuing from B-BOOT-1 — uses new_key from above
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  "${REGISTER_URL}/w/${new_key}/risk-trees")
[[ "${status}" == "200" ]]
```

### B-BOOT-3 — Another user has no access to a just-bootstrapped workspace

```bash
# Bob has no relationship to the new workspace
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${BOB_JWT}" \
  "${REGISTER_URL}/w/${new_key}/risk-trees")
[[ "${status}" == "403" ]]
```

---

## Section K5 — Header Spoofing (K.5 Exit Criterion)

> These verify that the Istio waypoint strips external `x-user-*` headers before injecting
> validated claim headers. See AUTHORIZATION-PLAN.md Phase K.5 and AUTHORIZATION-IMPLEMENTATION-PLAN.md §K.5 Amendment.

### B-K5-1 — Spoofed `x-user-id` header is stripped; identity is not accepted

```bash
# Send a request with a spoofed x-user-id that bypasses JWT validation
SPOOFED_UUID="00000000-0000-0000-0000-deadbeef1337"
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "x-user-id: ${SPOOFED_UUID}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
# Must NOT be 200 — spoofed identity must not grant access
[[ "${status}" != "200" ]]
```

### B-K5-2 — Spoofed `x-user-id` does not grant access even for a known owner UUID

```bash
# alice owns ws1 in SpiceDB, but her UUID sent directly (bypassing JWT) must not work
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "x-user-id: ${ALICE_SUB}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" != "200" ]]
```

### B-K5-3 — External `x-user-email` header is stripped

```bash
# Sending email header without a valid JWT must not produce a response that leaks user data
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "x-user-email: alice@example.com" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
# No JWT = 403 regardless of email header
[[ "${status}" == "403" ]]
```

---

## Section FC — Fail-Closed Behaviour

> These verify that any authorization failure — whether a denial, a connectivity error, or an
> infrastructure fault — results in 403 and never in a silently permitted request.
> Overlap with **T-S5, T-S6, T-S9**: Scala tests verify the adapter error mapping; these verify
> the HTTP response code at the service level.

### B-FC-1 — SpiceDB unavailable → 403 (not 503)

```bash
# Block SpiceDB network access (NetworkPolicy or iptables rule during test)
# OR stop the SpiceDB pod temporarily
status=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer ${ALICE_JWT}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "403" ]]
# NOT 503 — infrastructure state must not be revealed to callers
```

### B-FC-2 — Request with no JWT returns 403, not 200 or 500

```bash
status=$(curl -s -o /dev/null -w "%{http_code}" \
  "${REGISTER_URL}/w/${WS1_KEY}/risk-trees")
[[ "${status}" == "403" ]]
```

### B-FC-3 — Anonymous sentinel UUID has no permissions (T4 guard)

```bash
# Verify via zed that the sentinel has NO relationships at all
result=$(zed relationship read \
  --endpoint="${SPICEDB_URL}" --token="${SPICEDB_TOKEN}" \
  "workspace" | grep "00000000-0000-0000-0000-000000000000")
[[ -z "${result}" ]]
# Also attempt a check — must be denied
zed permission check \
  --endpoint="${SPICEDB_URL}" --token="${SPICEDB_TOKEN}" \
  "workspace:${WS1_ID}" view_workspace "user:00000000-0000-0000-0000-000000000000"
# exit code 1 = PERMISSIONSHIP_NO_PERMISSION
```

---

## Section K6 — Drift Detection (K.6 Exit Criterion)

> These verify the bidirectional reconcile algorithm in the provisioning CI job.
> See AUTHORIZATION-PLAN.md Phase K.6 and AUTHORIZATION-IMPLEMENTATION-PLAN.md §K.6 Amendment.

### B-K6-1 — Provisioning job with clean state reports no drift

```bash
# Run the provisioning job with the intended config
exit_code=$(run_provisioning_job --config="${INTENDED_TUPLES_CONFIG}"; echo $?)
[[ "${exit_code}" == "0" ]]
# Log must contain no WARN level drift messages
```

### B-K6-2 — Provisioning job detects orphaned tuple (manual write outside CI)

```bash
# Manually write a tuple that is NOT in the intended config
zed relationship create \
  --endpoint="${SPICEDB_URL}" --token="${SPICEDB_TOKEN}" \
  "workspace:${WS1_ID}" editor "user:${BOB_SUB}"

# Run the provisioning job
output=$(run_provisioning_job --config="${INTENDED_TUPLES_CONFIG}" 2>&1)

# Job must report the orphaned tuple at WARN level and fail (strict-drift=true)
echo "${output}" | grep -q "WARN.*orphaned.*workspace:${WS1_ID}#editor@user:${BOB_SUB}"
```

### B-K6-3 — Provisioning job re-writes a deleted tuple

```bash
# Delete a tuple that IS in the intended config
zed relationship delete \
  --endpoint="${SPICEDB_URL}" --token="${SPICEDB_TOKEN}" \
  "workspace:${WS1_ID}" viewer "user:${CAROL_SUB}"

# Run the provisioning job
run_provisioning_job --config="${INTENDED_TUPLES_CONFIG}"

# Verify the tuple was re-created
zed permission check \
  --endpoint="${SPICEDB_URL}" --token="${SPICEDB_TOKEN}" \
  "workspace:${WS1_ID}" view_workspace "user:${CAROL_SUB}"
# exit code 0 = PERMISSIONSHIP_HAS_PERMISSION
```

### B-K6-4 — Ownership tuples are NOT managed by the provisioning job

```bash
# Verify that the provisioning job does NOT delete ownership tuples written by the app
# (owner_user tuples are lifecycle-managed by the app, not the CI job)
alice_tuple="workspace:${WS1_ID}#owner_user@user:${ALICE_SUB}"

run_provisioning_job --config="${INTENDED_TUPLES_CONFIG}"

# alice's owner_user tuple must still exist after the provisioning run
zed permission check \
  --endpoint="${SPICEDB_URL}" --token="${SPICEDB_TOKEN}" \
  "workspace:${WS1_ID}" view_workspace "user:${ALICE_SUB}"
# exit code 0 — not deleted by the provisioning job
```

---

## Section W3 — SpiceDB Adapter Unit Tests (Scala, Wave 3 prerequisite)

> **Audience:** `register` project — these are Scala unit tests in the `server` module,
> not BATS tests. They are listed here to complete the test coverage picture.
> They must pass before Wave 3 is considered done and before any BATS §L2/§FC tests are run.
>
> **No live SpiceDB required.** Uses sttp's mock/stub backend to simulate SpiceDB HTTP
> responses. Tests the `AuthorizationServiceSpiceDB` HTTP adapter's response mapping in
> isolation.

### T-U1 — `PERMISSIONSHIP_HAS_PERMISSION` maps to `ZIO.succeed(Checked[P]())`

Mock HTTP response: `200 {"permissionship": "PERMISSIONSHIP_HAS_PERMISSION"}`  
Assert: `check(alice, ViewWorkspace, ws1)` completes successfully with a `Checked[Permission]` proof.

### T-U2 — `PERMISSIONSHIP_NO_PERMISSION` maps to `AuthForbidden`

Mock HTTP response: `200 {"permissionship": "PERMISSIONSHIP_NO_PERMISSION"}`  
Assert: `check(bob, ViewWorkspace, ws1)` fails with `AuthForbidden` — `userId`, `permission`, `resourceType`, `resourceId` fields populated.

### T-U3 — `PERMISSIONSHIP_UNSPECIFIED` treated as deny (fail-closed)

Mock HTTP response: `200 {"permissionship": "PERMISSIONSHIP_UNSPECIFIED"}`  
Assert: fails with `AuthForbidden` — unspecified is treated as denial, not as pass.

### T-U4 — HTTP 4xx (bad token) maps to `AuthServiceUnavailable`

Mock HTTP response: `401 {"code": "UNAUTHENTICATED"}`  
Assert: fails with `AuthServiceUnavailable` — config error, not a forbidden. HTTP layer must map to 403.

### T-U5 — HTTP 5xx / timeout maps to `AuthServiceUnavailable` (fail-closed)

Mock HTTP response: `503` or simulated timeout  
Assert: fails with `AuthServiceUnavailable`, NOT `AuthForbidden` — confirms separate error path. HTTP layer maps both to 403.

### T-U6 — OTel counter `authz.check.total` increments on every call

Assert: after 3 `check()` calls (2 allowed, 1 denied), counter has value 3 with appropriate `result` labels (`allowed`, `denied`).

### T-U7 — Audit log uses `user.value` not `user.toString`

Assert: structured log output for a `check()` call contains the raw UUID string (from `user.value`), not the redacted `UserId(***)` from `user.toString`.

---

## Defense-in-Depth Overlap with Scala `server-it` Tests

The following test pairs cover the same behaviour at two different levels. Both must pass:

| BATS test | Scala `server-it` test | What it verifies |
|-----------|------------------------|-----------------|
| B-L2-1 (HTTP 200 for owner) | T-S1 (check() returns Checked[P]) | Owner permission flows end-to-end |
| B-L2-2 (HTTP 403 for no relationship) | T-S2 (check() returns AuthForbidden) | Denial flows end-to-end |
| B-L2-3 (viewer HTTP 200) | T-S3 (viewer cannot design_write) | Role distinction at HTTP level |
| B-L2-4 (inheritance HTTP 200) | T-S4 (schema inheritance check) | SpiceDB schema inheritance |
| B-FC-1 (SpiceDB down → 403) | T-S5 (AuthServiceUnavailable) | Fail-closed maps to 403, not 503 |
| B-BOOT-2 (creator can access) | T-S10 (recordOwnership → check passes) | Bootstrap ownership lifecycle |
| B-FC-3 (sentinel has no grants) | T-S9 (sentinel → AuthForbidden) | T4 sentinel exclusion at both layers |

---

## Completion Criteria

All items below must pass before any environment beyond `local-dev` is declared authorization-complete:

- [ ] T-U1 through T-U7: SpiceDB adapter unit tests pass with mock HTTP backend (**Wave 3 exit criterion**)
- [ ] B-L0-1 through B-L0-3: capability-only mode verified
- [ ] B-L1-1 through B-L1-4: identity mode verified (requires Keycloak + Istio)
- [ ] B-L2-1 through B-L2-5: fine-grained read verified
- [ ] B-L2W-1 through B-L2W-3: fine-grained write/admin verified
- [ ] B-BOOT-1 through B-BOOT-3: bootstrap ownership lifecycle verified
- [ ] B-K5-1 through B-K5-3: header spoofing blocked by waypoint (**K.5 exit criterion**)
- [ ] B-FC-1 through B-FC-3: fail-closed behaviour verified
- [ ] B-K6-1 through B-K6-4: drift detection verified (**K.6 exit criterion**)

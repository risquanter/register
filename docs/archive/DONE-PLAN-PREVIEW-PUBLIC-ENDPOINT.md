# Plan: Move Distribution Preview to Public Endpoint (Option B)

**Status:** APPROVED — ready for implementation  
**Decision thread:** conversation 2026-06-10 — option B chosen over A and C  
**Rationale:** Preview is a stateless pure computation with no data access. Requiring a
workspace key gates it behind session creation, breaking the design-first UX where a
user models distributions before they have a workspace. The workspace key provided no
meaningful security at L0; at L1+ the JWT handles identity independently. Option C (dual
paths) was rejected because the workspace-attributed logging value was deferred entirely
to nginx access logs, which are available regardless of path structure.

---

## Scope

| Layer | File | Change type |
|-------|------|-------------|
| common (endpoint definition) | `http/endpoints/DistributionPreviewEndpoints.scala` | Replace path + base |
| server (controller) | `http/controllers/DistributionPreviewController.scala` | Simplify: remove auth layers |
| server (wiring) | `Application.scala` | Narrow ZLayer type: remove `WorkspaceStore` + `AuthorizationService`; keep `UserContextExtractor` |
| app (state) | `state/DistributionChartState.scala` | Remove key parameter from `loadPreview` |
| app (view) | `views/DistributionChartView.scala` | Remove key from both call sites |
| infra (nginx) | `containers/prod/Dockerfile.frontend-prod` | Add `/distribution` proxy block |
| docs | `AUTHORIZATION-PLAN.md` | Update endpoint table row 23 |
| server tests | `DistributionPreviewServiceSpec`, any controller IT tests | Verify no workspace key fixture needed |

No new files. No new dependencies. No new types.

---

## Step 1 — Endpoint definition (`DistributionPreviewEndpoints.scala`)

**File:** `modules/common/src/main/scala/com/risquanter/register/http/endpoints/DistributionPreviewEndpoints.scala`

**Current:**
```scala
val distributionPreviewEndpoint =
  authedBaseEndpoint
    .tag("distribution")
    .name("distributionPreview")
    .description("...")
    .in("w" / path[WorkspaceKeySecret]("key") / "distribution" / "preview")
    .post
    .in(jsonBody[DistributionShapeRequest])
    .out(jsonBody[DistributionPreviewResponse])
```

**Target:**
```scala
val distributionPreviewEndpoint =
  authedBaseEndpoint
    .tag("distribution")
    .name("distributionPreview")
    .description("Compute a sampled distribution preview curve from parameters. No workspace key required. L0: open to anyone. L1+: valid JWT required (enforced by the mesh and userCtx.extract in the controller). Rate limiting at nginx level.")
    .in("distribution" / "preview")
    .post
    .in(jsonBody[DistributionShapeRequest])
    .out(jsonBody[DistributionPreviewResponse])
```

**Type change:** endpoint input changes from
`(Option[UserId], WorkspaceKeySecret, DistributionShapeRequest)` to
`(Option[UserId], DistributionShapeRequest)`.

Import to remove: `WorkspaceKeySecret` only. `authedBaseEndpoint` is retained.

**L0 / L1+ behaviour:**
- L0 (`capability-only`): mesh does not inject `x-user-id`; `Option[UserId]` decodes to
  `None`; `UserContextExtractor.noOp` accepts it — endpoint is fully open.
- L1+ (`identity`): mesh requires a valid JWT before the request reaches the app and
  injects `x-user-id`; `userCtx.extract` enforces presence — unauthenticated callers
  are rejected. No new mesh exemption rule is needed; the default policy applies.

**ADR-001:** Tapir codec still validates `DistributionShapeRequest` at the boundary —
no change to validation ownership.  
**ADR-021:** Endpoint intentionally outside `/w/{key}/`. Consistent with §11.1 rationale
being superseded by the B decision.  
**ADR-024 (PEP pattern):** `userCtx.extract` is the sole gate — no resource-level
`authzService.check` for a stateless compute endpoint. No deviation.

---

## Step 2 — Controller (`DistributionPreviewController.scala`)

**File:** `modules/server/src/main/scala/com/risquanter/register/http/controllers/DistributionPreviewController.scala`

**Current handler body:**
```scala
case (maybeUserId, key, req) =>
  (for
    userId <- userCtx.extract(maybeUserId)
    ws     <- workspaceStore.resolve(key)
    _      <- authzService.check(userId, Permission.AnalyzeRun, ws.id.asResource)
    result <- DistributionShapeRequest.validate(req).toZIOValidation
                .flatMap(previewService.preview)
  yield result).either
```

**Target handler body:**
```scala
case (maybeUserId, req) =>
  (for
    _      <- userCtx.extract(maybeUserId)  // L0: noOp passes; L1+: fails if JWT absent
    result <- DistributionShapeRequest.validate(req).toZIOValidation
                .flatMap(previewService.preview)
  yield result).either
```

**Constructor:** remove `workspaceStore` and `authzService` parameters only. `userCtx`
remains — it is the L1 identity gate.  
**`makeZIO`:** remove `WorkspaceStore` and `AuthorizationService` from the ZIO
environment type and `for`-comprehension. `UserContextExtractor` remains.

Imports to remove: `WorkspaceStore`, `AuthorizationService`, `Permission`, `asResource`.
Imports to keep: `UserContextExtractor` and relevant `auth.*` for `userCtx.extract`.

**Rate limiting:** NOT wired here, per decision. Future hook point: if per-IP rate
limiting on this endpoint becomes necessary, `RateLimiter` can be injected following the
`WorkspaceLifecycleController.bootstrapWorkspace` pattern.

---

## Step 3 — Application wiring (`Application.scala`)

**File:** `modules/server/src/main/scala/com/risquanter/register/Application.scala`

`DistributionPreviewController.makeZIO` currently requires
`DistributionPreviewService & WorkspaceStore & UserContextExtractor & AuthorizationService`.

After Step 2 it requires `DistributionPreviewService & UserContextExtractor`.

The `ZLayer.make[...]` type signatures in `appLayer` must be updated to remove
`WorkspaceStore` and `AuthorizationService` from the `DistributionPreviewController`
environment. `UserContextExtractor` and `DistributionPreviewService` layers remain.

---

## Step 4 — Frontend state (`DistributionChartState.scala`)

**File:** `modules/app/src/main/scala/app/state/DistributionChartState.scala`

**Current:**
```scala
def loadPreview(key: WorkspaceKeySecret, req: DistributionShapeRequest): Unit =
  distributionPreviewEndpoint((userIdAccessor(), key, req)).loadInto(previewVar)
```

**Target:**
```scala
def loadPreview(req: DistributionShapeRequest): Unit =
  distributionPreviewEndpoint(req).loadInto(previewVar)
```

`keySignal` and `userIdAccessor` remain on the class — `keySignal` is still used by the
`DesignView` subscription that resets `previewEnabledVar` when the key becomes `None`.
`userIdAccessor` remains for symmetry with `LECChartState` and future L1 wiring.

The `DistributionChartView` passes `key` into both `loadPreview` call sites today; both
are updated in Step 5. No other callers exist (verified by grep).

---

## Step 5 — Frontend view (`DistributionChartView.scala`)

**File:** `modules/app/src/main/scala/app/views/DistributionChartView.scala`

Two call sites, both in `onMountCallback`:

**Stream 1 (current):**
```scala
case (Some(draft), Some(key), true) =>
  chartState.loadPreview(key, toPreviewRequest(draft))
```
**Stream 1 (target):**
```scala
case (Some(draft), _, true) =>
  chartState.loadPreview(toPreviewRequest(draft))
```

**Stream 2 (current):**
```scala
case (true, Some(draft), Some(key)) =>
  chartState.loadPreview(key, toPreviewRequest(draft))
```
**Stream 2 (target):**
```scala
case (true, Some(draft), _) =>
  chartState.loadPreview(toPreviewRequest(draft))
```

The `withCurrentValueOf(..., chartState.keySignal)` combinator can be removed from both
streams since the key is no longer needed for the call. The `keySignal` combinator in the
`child <-- specSignal.combineWith(...)` below these streams (for the
`hasWorkspace`/placeholder UI gate) is **unaffected** — that logic controls whether the
preview toggle is shown, not whether the fetch fires.

---

## Step 6 — nginx routing (`Dockerfile.frontend-prod`)

**File:** `containers/prod/Dockerfile.frontend-prod`

The current proxied API block:
```nginx
location /workspaces {
    set $backend "__BACKEND__";
    proxy_pass $backend;
}
location /health { ... }
location /docs { ... }
```

Add:
```nginx
location /distribution {
    set $backend "__BACKEND__";
    proxy_pass $backend;
}
```

**Placement:** after `/workspaces`, before `/health`. Unconditional proxy — same pattern
as `/workspaces` (no SPA fallback, no content-type check).

**ADR-INFRA-007 §Decision 1:** `/distribution/*` is a pure API path, no SPA concern.
The `location /` SPA fallback would catch it without this block but would return
`index.html` for API calls — explicitly wrong. This block corrects that.

---

## Step 7 — Documentation (`AUTHORIZATION-PLAN.md`)

Update endpoint table row 23:

**Current:**
```
| 23 | POST | `/w/{key}/distribution/preview` | DistributionPreview | `AnalyzeRun` | Workspace | L0+ | Stateless ... |
```

**Target:**
```
| 23 | POST | `/distribution/preview` | DistributionPreview | — | Public | L0+ | Stateless distribution fitting + sampling. Public endpoint — no workspace key or identity required. Rate limiting at nginx level. Workspace-scoped path removed per 2026-06-10 Option B decision. |
```

---

## ADR Compliance Review

| ADR | Assessment |
|-----|------------|
| ADR-001 | PASS — validation ownership unchanged (Tapir codec + `DistributionShapeRequest.validate`) |
| ADR-010 | PASS — error channel unchanged; `toZIOValidation.flatMap(preview)` still typed |
| ADR-017 | PASS — no DTO shape change |
| ADR-021 | PASS — intentional departure from `/w/{key}/` scope; documented above |
| ADR-024 | PASS — no PEP check on a public endpoint is correct; not a deviation |
| ADR-025 | PASS — `/distribution` is a pure API path, no SPA routing concern |
| ADR-INFRA-007 | PASS — nginx block added per §Decision 1 pattern |

---

## Test coverage requirements

| Scope | Requirement |
|-------|-------------|
| `DistributionPreviewController` | Happy path: valid lognormal → 200. Valid expert → 200. Invalid request → 400. No workspace key or `x-user-id` header required at L0 (noOp extractor). |
| `DistributionPreviewController` | L1 enforcement: request with absent `x-user-id` header must return 403 when `requirePresent` extractor is injected. **Deferred to Phase K / L1 wave** — `requirePresent` is not yet wired. |
| `DistributionPreviewController` | Confirm no `workspaceStore` fixture needed — test harness no longer provides it for this controller. |
| `DistributionChartState` (app) | `loadPreview` call site updated — no key argument. Existing spec coverage of `loadPreview` side effects remains valid. |
| Integration (if any) | Any server-it test calling `/w/{key}/distribution/preview` must be updated to call `/distribution/preview` without a key header. |

---

## Completion criteria

- [ ] `commonJVM/compile` and `commonJS/compile` green
- [ ] `server/test` green
- [ ] `app/test` green
- [ ] No `WorkspaceKeySecret` import remaining in `DistributionPreviewEndpoints.scala`
- [ ] No `WorkspaceStore` / `AuthorizationService` remaining in `DistributionPreviewController.scala`
- [ ] `UserContextExtractor` retained in `DistributionPreviewController.scala` (L1 gate)
- [ ] `loadPreview` takes exactly `(req: DistributionShapeRequest)` — no key parameter
- [ ] nginx block for `/distribution` present in `Dockerfile.frontend-prod`
- [ ] `AUTHORIZATION-PLAN.md` row 23 updated
- [ ] ADR functional composition checklist cleared
- [ ] No test assertions weakened

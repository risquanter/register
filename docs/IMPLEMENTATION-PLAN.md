# Consolidated Implementation Plan

**Date:** February 10, 2026
**Status:** Active
**Supersedes:** `APP-IMPLEMENTATION-PLAN.md`, `PLAN-SPLIT-PANE-LEC-UI.md`, `IMPLEMENTATION-PLAN-PROPOSALS.md`, `RISKTREE-REPOSITORY-IRMIN-PLAN.md`
**Related (kept):** `IRMIN-INTEGRATION.md` (Irmin reference guide — not a plan, stays as-is)

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Technology Stack](#technology-stack)
4. [Current State](#current-state)
5. [Tier 1: Frontend GUI](#tier-1-frontend-gui)
6. [Tier 2: Irmin Persistence & Backend Pipeline](#tier-2-irmin-persistence--backend-pipeline)
7. [Tier 3: Real-Time Collaboration & Scenarios](#tier-3-real-time-collaboration--scenarios)
8. [Tier 4: WebSocket Enhancement](#tier-4-websocket-enhancement)
9. [Reference Resources](#reference-resources)
10. [Related ADRs](#related-adrs)
11. [Decisions Log](#decisions-log)

---

## Overview

This document is the single source of truth for all implementation work on the Risquanter Register project. It consolidates frontend GUI plans, backend infrastructure plans, Irmin persistence plans, and future feature plans into one document with clear tier-based prioritization.

### Goals

1. **Split-pane UI** with tree view + Vega-Lite LEC charts (Tier 1)
2. **Irmin-backed persistence** with per-node storage and cache invalidation pipeline (Tier 2)
3. **Real-time collaboration** with conflict detection and **scenario branching** via Irmin branches (Tier 3)
4. **WebSocket enhancement** for bidirectional communication (Tier 4)

### Working Principles

1. **Step-by-step approach** with approval gates at each phase
2. **Testable units** — each phase independently verifiable
3. **No autonomous refactoring** — explicit approval before code changes
4. **Reuse existing code** — `ValidationUtil` from common module, existing Iron types
5. **Backend validation is source of truth** — frontend validation is for UX only

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Frontend Architecture                        │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   Laminar App                            │   │
│  ├─────────────────────────────────────────────────────────┤   │
│  │  Views           │  State          │  API Client        │   │
│  │  ─────           │  ─────          │  ──────────        │   │
│  │  • RiskLeafForm  │  • FormState    │  • REST mutations  │   │
│  │  • TreeView      │  • TreeState    │  • SSE events      │   │
│  │  • LECChart      │  • LECState     │  • Error handling  │   │
│  │  • SplitPane     │  • UIState      │                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                           │                                     │
│                    SSE / WebSocket (future)                     │
│                           │                                     │
│                           ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   ZIO Backend                            │   │
│  │  • Computes LEC via Identity[RiskResult].combine         │   │
│  │  • Caches per-node RiskResult (ADR-005/014/015)         │   │
│  │  • RiskResultResolver: cache-aside simulation           │   │
│  │  • TreeCacheManager: per-tree cache lifecycle            │   │
│  │  • SSEHub: publishes CacheInvalidated events            │   │
│  │  • InvalidationHandler: cache + SSE bridge              │   │
│  └─────────────────────────────────────────────────────────┘   │
│                           │                                     │
│                    GraphQL (Irmin)                              │
│                           │                                     │
│                           ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              Irmin Content-Addressed Store               │   │
│  │  • Per-node storage at risk-trees/{treeId}/nodes/{nodeId}│  │
│  │  • Immutable commit history with audit trail            │   │
│  │  • Branches for scenario analysis (Tier 3)              │   │
│  │  • Watch subscriptions for reactive updates (Tier 2)    │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### UI Layout Target

```
┌──────────────────────────────┬──────────────────────────────┐
│                              │                              │
│                              │    TREE VIEW (Laminar)       │
│                              │    ├─ Portfolio A            │
│     FORM PANEL               │    │  ├─ Risk 1 [selected]  │
│     (RiskLeafFormView)       │    │  └─ Risk 2             │
│                              │    └─ Portfolio B            │
│                              │       └─ Risk 3             │
│                              ├──────────────────────────────┤
│                              │                              │
│                              │    LEC CHART (Vega-Lite)     │
│                              │    [Multi-curve diagram]     │
│                              │    - Selected node (bold)    │
│                              │    - Children curves         │
│                              │                              │
└──────────────────────────────┴──────────────────────────────┘
```

### Key Design Decisions (Confirmed)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tree visualization | Laminar HTML (expandable hierarchy) | Stays within Vega-Lite, no Vega tree transform |
| LEC chart | Vega-Lite via vega-embed | BCG-style multi-curve diagram |
| Split panes | Fixed proportions first, draggable later | CSS Flexbox, simplicity |
| Subtree LEC fetch | Use `getLECCurvesMultiEndpoint` | Existing multi-fetch endpoint, no new `depth` param needed |
| Session/Auth | Skip for now | No session handling needed initially |
| Test framework | zio-test | Already declared in build.sbt, consistent with server module |
| nTrials | Server-side configuration only | Default 10,000; no UI control |

### Key Domain Insight (ADR-009)

The browser only displays precomputed `LECCurveResponse`. All aggregation happens server-side using `Identity[RiskResult].combine`. The frontend treats leaf and aggregate LEC data uniformly. IDs are ULID-based (`TreeId`, `NodeId`) — the server generates all IDs; the frontend never supplies them.

---

## Technology Stack

### Backend
| Component | Version | Purpose |
|-----------|---------|---------|
| Scala | 3.6.4 | Language |
| ZIO | 2.1.24 | Effect system |
| Tapir | 1.13.4 | Endpoint definitions |
| Iron | 3.2.2 | Refinement types |
| zio-json | 0.8.0 | JSON codecs |
| zio-logging | 2.5.2 | Structured logging |
| zio-telemetry | 3.1.13 | Observability |
| sttp-client3 | 3.10.1 | HTTP client (backend + Irmin) |

### Frontend
| Component | Version | Purpose |
|-----------|---------|---------|
| Scala.js | 1.20.0 | JS compilation |
| Laminar | 17.2.0 | Reactive UI |
| Vite | 6.x | Dev server + bundling |
| sttp-client3 | 3.10.1 | HTTP client (browser Fetch) |
| tapir-sttp-client | 1.13.4 | Type-safe endpoint interpretation |
| Iron | 3.2.2 | Shared validation with backend |
| zio-json | 0.8.0 | Shared JSON codecs |

### Persistence (Irmin)
| Component | Version | Purpose |
|-----------|---------|---------|
| Irmin | OCaml 5.2 | Content-addressed store |
| irmin-graphql | — | GraphQL API |
| irmin-pack | — | Pack file storage |
| Docker | Alpine dev image (~650 MB) | Container runtime |

### Not Yet Declared (Needed for Tier 1)
| Component | Purpose | Phase |
|-----------|---------|-------|
| vega / vega-lite / vega-embed | LEC chart rendering | Phase E |

---

## Current State

### Test Counts (as of Feb 10, 2026)

| Module | Tests | Status |
|--------|-------|--------|
| commonJVM | 289 | ✅ Passing |
| server | 223 | ✅ Passing |
| **Total** | **512** | ✅ |

### Backend Endpoints (Implemented)

| Endpoint | Method | Path | Status |
|----------|--------|------|--------|
| Health | GET | `/health` | ✅ |
| Create tree | POST | `/risk-trees` | ✅ |
| Get all trees | GET | `/risk-trees` | ✅ |
| Get tree by ID | GET | `/risk-trees/{id}` | ✅ |
| Invalidate cache | POST | `/risk-trees/{id}/invalidate/{nodeId}` | ✅ |
| Get LEC curve | GET | `/risk-trees/{treeId}/nodes/{nodeId}/lec` | ✅ |
| Prob of exceedance | GET | `/risk-trees/{treeId}/nodes/{nodeId}/prob-of-exceedance` | ✅ |
| Multi LEC curves | POST | `/risk-trees/{treeId}/nodes/lec-multi` | ✅ |
| Cache stats | GET | `/risk-trees/{treeId}/cache/stats` | ✅ |
| Cache nodes | GET | `/risk-trees/{treeId}/cache/nodes` | ✅ |
| Clear tree cache | DELETE | `/risk-trees/{treeId}/cache` | ✅ |
| Clear all caches | DELETE | `/caches` | ✅ |
| SSE events | GET | `/events/tree/{treeId}` | ✅ |

### Backend Services (Implemented)

| Service | Status | Notes |
|---------|--------|-------|
| `RiskTreeService` | ✅ | Full CRUD with validation |
| `RiskResultResolver` | ✅ | Cache-aside simulation (ADR-015) |
| `TreeCacheManager` | ✅ | Per-tree cache lifecycle |
| `InvalidationHandler` | ✅ | Cache invalidation + SSE notification, returns `InvalidationResult` |
| `SSEHub` | ✅ | Fan-out broadcasting with subscriber tracking |
| `IrminClient` | ✅ | GraphQL CRUD: get, set, remove, list, branches, healthCheck |
| `RiskTreeRepositoryIrmin` | ✅ | Per-node Irmin storage (selectable via config) |
| `RiskTreeRepositoryInMemory` | ✅ | Default runtime repository |

### Backend Services (Not Implemented)

| Service | Blocked On | Tier |
|---------|-----------|------|
| `IrminClient.watch` (subscriptions) | WebSocket transport decision | Tier 2 |
| `TreeUpdatePipeline` | `IrminClient.watch` | Tier 2 |
| `LECRecomputer` | `TreeUpdatePipeline` | Tier 2 |
| `EventHub` (collaboration) | Phase 5 pipeline | Tier 3 |
| `ConflictDetector` | `EventHub` | Tier 3 |
| `ScenarioService` | Irmin branches | Tier 3 |

### Frontend (Existing — 8 Source Files)

| File | Purpose | Status |
|------|---------|--------|
| `App.scala` | Entry point, renders `Layout(RiskLeafFormView())` | ✅ Exists (18 lines) |
| `FormState.scala` | Trait: `errorSignals`, `hasErrors`, parse helpers | ✅ Exists (31 lines) |
| `RiskLeafFormState.scala` | Var per field, validation signals, input filters | ⚠️ Has stale `idVar`/`idFilter` |
| `RiskLeafFormView.scala` | Full form with conditional expert/lognormal fields | ✅ Exists (121 lines) |
| `FormComponents.scala` | Reusable `textInput`, `radioGroup`, `submitButton` | ✅ Exists (131 lines) |
| `AppHeader.scala` | Simple header component | ✅ Exists (15 lines) |
| `AppLayout.scala` | Layout wrapper | ✅ Exists (17 lines) |
| `DistributionMode.scala` | `Expert | Lognormal` enum | ✅ Exists (17 lines) |

### Frontend (Missing)

| Component | Needed For | Phase |
|-----------|-----------|-------|
| `BackendClient` | HTTP calls to backend | Phase B |
| `ZJS` | ZIO-to-Laminar bridge | Phase B |
| `SplitPane` | Layout structure | Phase C |
| `TreeViewState` / `TreeService` | Tree data + interaction | Phase D |
| `TreeView` | Expandable tree UI | Phase D |
| `VegaEmbed` facade | Scala.js bindings for charting | Phase E |
| `LECChartBuilder` | Vega-Lite spec generation | Phase E |
| `LECChartView` | Reactive chart component | Phase E |
| `LECService` | Selection → fetch → chart wiring | Phase F |
| `SSEClient` | SSE subscription | Phase H |
| `AppError` / `ErrorBanner` | Error handling | Phase I |

### Irmin Infrastructure (Implemented)

| Component | Status | Notes |
|-----------|--------|-------|
| `dev/Dockerfile.irmin` | ✅ | Alpine dev image, port 9080 |
| `docker-compose.yml` (Irmin service) | ✅ | `--profile persistence` |
| `dev/irmin-schema.graphql` | ✅ | 180 lines, extracted schema |
| `IrminConfig` | ✅ | `SafeUrl`, timeout, health check |
| `IrminPath` | ✅ | Iron-refined, path operations |
| `IrminCommit` / `IrminInfo` | ✅ | Commit metadata types |
| `IrminClient` trait + `IrminClientLive` | ✅ | CRUD via sttp HTTP + GraphQL |
| `IrminQueries` | ✅ | Raw GraphQL query strings |
| `IrminError` types | ✅ | `IrminUnavailable`, `IrminHttpError`, `IrminGraphQLError`, `NetworkTimeout` |
| `TreeMetadata` | ✅ | Schema version + timestamps |
| `RiskTreeRepositoryIrmin` | ✅ | Per-node storage, selectable via `register.repository.repositoryType` |

### Integration Tests (server-it module — 7 files)

| File | Purpose | Status |
|------|---------|-------|
| `HttpApiIntegrationSpec.scala` | Health + create/list/get HTTP tests | ✅ |
| `HttpTestHarness.scala` | Random-port test server (Irmin or in-memory) | ✅ |
| `SttpClientFixture.scala` | HTTP client fixture | ✅ |
| `IrminClientIntegrationSpec.scala` | Irmin CRUD + list operations | ✅ |
| `IrminTestSupport.scala` | Irmin test helpers | ✅ |
| `RiskTreeRepositoryIrminSpec.scala` | Repository CRUD roundtrip | ✅ |
| `TestContainerFixture.scala` | Container support | ✅ |

---

## Tier 1: Frontend GUI

### Build Pipeline (Phase 1) — ✅ COMPLETE

- App module active in `build.sbt`, aggregated into root
- ScalaJS configured: `ESModule`, `MainModuleInitializer`
- Vite dev server on port 5173
- Dev workflow: `sbt ~app/fastLinkJS` + `cd modules/app && npm run dev`

### Phase V: Validate Existing Code — ✅ COMPLETE

**Goal:** Confirm that existing source files compile and render correctly.

**Findings:**
- 7 source files (not 8 as previously documented)
- File naming differs from old plan: `Main.scala` (not `App.scala`), `FormInputs.scala` (not `FormComponents.scala`), `Header.scala` (not `AppHeader.scala`), `Layout.scala` (not `AppLayout.scala`)
- `ValidationUtil.refineId` uses `zio-ulid` (JVM-only) — blocked Scala.js linking. Resolved by removing vestigial `idVar`/`idFilter`/`idError` (pulled forward from Phase A)

**Checkpoint:**
- [x] App compiles without errors
- [x] Form renders in browser at `http://localhost:5173`
- [x] Mode toggle and validation work

---

### Phase A: Align Form to Current DTO Contract — ✅ COMPLETE

**Goal:** Align frontend form to `RiskTreeDefinitionRequest` contract and build full tree construction UI.

**What was done:**

The scope expanded from simple form alignment to a complete tree builder. Key decisions:
- Tree builder pattern with incremental portfolio/leaf construction (ADR-019)
- Cascade delete for node removal (transitive closure of descendants)
- `TreeBuilderLogic` in common module for JVM-testable topology validation
- Composable function pattern over class hierarchies (ADR-019)

**Files created:**

| File | Purpose |
|------|---------|
| `app/state/TreeBuilderState.scala` | Tree assembly: name, portfolio/leaf lists, parent options signal, `addPortfolio`, `addLeaf`, `removeNode` (cascade), `toRequest()` |
| `app/state/PortfolioFormState.scala` | Single portfolio name + parent validation (reactive, Iron-based) |
| `app/views/TreeBuilderView.scala` | Orchestrator: composes tree name input, portfolio form, leaf form, tree preview |
| `app/views/PortfolioFormView.scala` | "Add Portfolio" sub-form with parent dropdown |
| `app/views/TreePreview.scala` | Live preview of portfolios + leaves with remove buttons |
| `common/.../frontend/TreeBuilderLogic.scala` | Pure topology validation + cascade collection (shared, JVM-testable) |
| `common/.../frontend/TreeBuilderLogicSpec.scala` | 7 tests: lone leaf, root constraints, duplicates, cascade |

**Files modified:**

| File | Change |
|------|--------|
| `app/state/RiskLeafFormState.scala` | Removed `idVar`/`idFilter`/`idError`; added `toDistributionDraft` |
| `app/views/RiskLeafFormView.scala` | Accepts `TreeBuilderState`; parent dropdown; "Add Leaf" button |
| `app/Main.scala` | Renders `TreeBuilderView()` instead of `RiskLeafFormView()` |

**Checkpoint:**
- [x] No ID field in form
- [x] Parent dropdown present (derived signal from portfolio list)
- [x] `toRequest()` produces valid `RiskTreeDefinitionRequest` via `Validation`
- [x] Existing validation still works
- [x] Cascade node removal with `TreeBuilderLogic.collectCascade`
- [x] 7 topology tests passing in common module
- [ ] App-module tests for `TreeBuilderState` (deferred to Phase G)

---

### Phase B: BackendClient + ZJS Infrastructure

**Goal:** HTTP client infrastructure enabling the frontend to call backend endpoints.

**Files to create:**
```
modules/app/src/main/scala/app/core/
├── ZJS.scala           # ZIO-to-Laminar bridge (extension methods)
└── BackendClient.scala # Tapir client with FetchBackend
```

**ZJS pattern** (based on BCG reference — simpler, no session):
```scala
extension [E <: Throwable, A](zio: ZIO[BackendClient, E, A])
  def emitTo(eventBus: EventBus[A]): Unit = ...

extension [I, E <: Throwable, O](endpoint: Endpoint[Unit, I, E, O, Any])
  def apply(payload: I): Task[O] = ...
```

**BackendClient pattern:**
- Uses `sttp-client3` with Fetch backend (JS)
- Interprets shared Tapir endpoints from common module
- Base URL configurable (default `http://localhost:8080`)
- No authentication (deferred)

**CORS note:** Backend may need CORS headers for `localhost:5173` → `localhost:8080`.

**Reference implementations:**
- `temp/business-case-generator/` — simpler ZJS, no session
- `temp/cheleb/` — enhanced ZJS with `toEventStream`, `runJs` (skip session/storage)
- `temp/vega-lite-experiments/` — VegaEmbed facade pattern

**Checkpoint:**
- [ ] Health endpoint callable from browser
- [ ] `endpoint(payload).emitTo(bus)` pattern works
- [ ] No CORS errors

---

### Phase C: Split-Pane Layout

**Goal:** CSS-based split-pane layout component. No backend dependency.

**Files to create:**
```
modules/app/src/main/scala/app/components/SplitPane.scala
```

**API:**
```scala
object SplitPane:
  def horizontal(left: HtmlElement, right: HtmlElement, leftPercent: Int = 50): HtmlElement
  def vertical(top: HtmlElement, bottom: HtmlElement, topPercent: Int = 50): HtmlElement
```

**Integration:**
```scala
SplitPane.horizontal(
  left = RiskLeafFormView(),
  right = SplitPane.vertical(
    top = TreeView(treeState),
    bottom = LECChartView(lecSignal)
  )
)
```

**Checkpoint:**
- [ ] 50/50 split renders correctly
- [ ] Form still functional in left pane
- [ ] Right pane has placeholder for tree + chart

---

### Phase D: Tree View Component

**Goal:** Reactive tree state management and interactive expandable tree view.

**Files to create:**
```
modules/app/src/main/scala/app/
├── state/TreeViewState.scala
├── services/TreeService.scala
└── views/TreeView.scala
```

**TreeViewState:**
```scala
class TreeViewState:
  val availableTrees: Var[List[SimulationResponse]] = Var(Nil)
  val selectedTreeId: Var[Option[String]] = Var(None)
  val treeStructure: Var[Option[RiskTree]] = Var(None)
  val expandedNodes: Var[Set[String]] = Var(Set.empty)
  val selectedNodeId: Var[Option[String]] = Var(None)
  val isLoading: Var[Boolean] = Var(false)
  val error: Var[Option[String]] = Var(None)

  def toggleExpanded(nodeId: String): Unit = ...
  def selectNode(nodeId: String): Unit = ...
```

**TreeView rendering:**
- Indentation by depth
- Folder icons (📁/📂 open/closed)
- Highlight selected node
- Click to expand/collapse portfolios
- Click to select node for LEC view

**TreeService data flow:**
1. On app load → `GET /risk-trees` → populate `availableTrees`
2. On tree selection → `GET /risk-trees/{id}` → populate `treeStructure`
3. On form submit → `POST /risk-trees` → refresh tree list

**Checkpoint:**
- [ ] Trees load from backend on app start
- [ ] Tree renders with expand/collapse
- [ ] Node selection updates `selectedNodeId`

---

### Phase E: Vega-Lite LEC Chart

**Goal:** Embed Vega-Lite charts for LEC visualization.

**Files to create:**
```
modules/app/src/main/scala/app/
├── facades/VegaEmbed.scala     # Scala.js facade
└── charts/LECChartBuilder.scala # Spec generation
└── views/LECChartView.scala     # Reactive chart component
```

**NPM dependencies to add** (in `modules/app/package.json`):
```json
"dependencies": {
  "vega": "^5.30.0",
  "vega-lite": "^5.21.0",
  "vega-embed": "^6.26.0"
}
```

**VegaEmbed facade:**
```scala
@js.native
@JSImport("vega-embed", JSImport.Default)
object VegaEmbed extends js.Object:
  def apply(el: dom.Element, spec: js.Any, options: js.UndefOr[js.Any]): Promise[js.Dynamic]
```

**LECChartBuilder** (based on BCG's `VegaLiteLossDiagramm`):
- Multi-curve display (aggregate + children)
- Color palette matching BCG theme
- X: loss (quantitative, formatted B/M)
- Y: exceedance probability (%, smooth "basis" interpolation)
- Data: flattened from `LECCurveResponse`

**LECChartView states:**
- Empty: "Select a node to view LEC"
- Loading: spinner
- Data: rendered chart via `onMountCallback` + `VegaEmbed`
- Error: error message

**Checkpoint:**
- [ ] Hardcoded chart spec renders correctly
- [ ] Chart updates when signal changes
- [ ] Multiple curves display with legend

---

### Phase F: Wire Selection → LEC Fetch → Chart

**Goal:** Complete the data flow from node selection to chart rendering.

**Files to create:**
```
modules/app/src/main/scala/app/services/LECService.scala
```

**Data flow:**
```
User clicks node in TreeView
  ↓
state.selectedNodeId.set(nodeId)
  ↓
Signal triggers effect:
  1. Get children of selected node from tree structure
  2. POST /risk-trees/{treeId}/nodes/lec-multi  (selected + children)
  ↓
Response updates LECChartView
  ↓
Chart re-renders with multi-curve display
```

**Multi-fetch approach** (DP-1 decision):
- Frontend reads node's `childIds` from the tree structure
- Builds list: `[selectedNodeId] ++ childIds`
- Calls `getLECCurvesMultiEndpoint` in one request
- Constructs `LECCurveResponse`-like structure for chart builder

**Checkpoint:**
- [ ] Click node → chart updates with node's LEC + children
- [ ] Loading state shown during fetch
- [ ] Errors displayed gracefully

---

### Phase G: Testing

**Goal:** Meaningful test coverage for the app module using zio-test.

**Testing approach** (DP-3 decision — zio-test, no munit):
1. **State-only testing** (primary) — test `FormState` signals, `TreeViewState` transitions
2. **Integration testing** — Playwright/Cypress for E2E (future)

**Test dependencies** (already in `build.sbt`):
```scala
"dev.zio" %%% "zio-test"     % zioVersion % Test
"dev.zio" %%% "zio-test-sbt" % zioVersion % Test
```

**Test targets:**
- FormState validation rules (name, probability, percentiles, quantiles, cross-field)
- `toRequest()` conversion (happy path + error cases)
- TreeViewState transitions (select, expand, collapse)
- LECService multi-fetch assembly logic

**Checkpoint:**
- [ ] `sbt app/test` runs successfully
- [ ] Validation rules have test coverage
- [ ] State transitions tested

---

### Phase H: SSE Cache Invalidation

**Goal:** Subscribe to SSE events so the frontend knows when displayed LEC data is stale.

**Context:** The backend already publishes `SSEEvent.CacheInvalidated` events via `SSEHub` + `InvalidationHandler`. The frontend can subscribe NOW.

**Files to create:**
```
modules/app/src/main/scala/app/
├── api/SSEClient.scala
└── state/LECState.scala
```

**SSEClient:**
```scala
object SSEClient:
  def connect(treeId: String): EventStream[SSEEvent] =
    EventStream.fromCustomSource[SSEEvent](
      start = (fireEvent, _, _, _) =>
        val source = new EventSource(s"/events/tree/$treeId")
        source.onmessage = (e: MessageEvent) =>
          decode[SSEEvent](e.data.toString).foreach(fireEvent)
        source,
      stop = source => source.close()
    )
```

**LECState** (stale tracking):
```scala
class LECState:
  private val cache: Var[Map[String, LECCurveResponse]] = Var(Map.empty)
  val staleNodes: Var[Set[String]] = Var(Set.empty)

  def markAllStale(treeId: String): Unit =
    staleNodes.set(cache.now().keySet)

  // On CacheInvalidated → mark stale → re-fetch visible nodes
```

**Checkpoint:**
- [ ] SSE connection established on tree selection
- [ ] `CacheInvalidated` events trigger LEC re-fetch for visible nodes
- [ ] Stale indicators shown while re-fetching

---

### Phase I: Error Handling

**Goal:** Robust error handling following ADR-008 patterns.

**Files to create:**
```
modules/app/src/main/scala/app/
├── state/AppError.scala
└── views/ErrorBanner.scala
```

**AppError enum:**
```scala
enum AppError:
  case ValidationFailed(errors: List[String])
  case NetworkError(message: String, retryable: Boolean)
  case Conflict(message: String, refreshAction: () => Unit)
  case ServerError(referenceId: String)
```

**SSE reconnection with exponential backoff:**
- Max 10 retries, delays from 1s to 30s
- Error banner shows reconnection status
- After max retries: "Unable to connect. Please refresh."

**Checkpoint:**
- [ ] Error banner displays on API failure
- [ ] SSE auto-reconnects with exponential backoff
- [ ] Conflict errors show refresh action

---

### Tier 1 Dependency Graph

```
Phase V (Validate)
  ↓
Phase A (Align Form) ──────────────────────────────────────────┐
  ↓                                                            │
Phase B (BackendClient + ZJS) ─────────────────────────────┐   │
  ↓                                                        │   │
Phase C (Split-Pane Layout) ───────────────────────────┐   │   │
  ↓                                                    │   │   │
Phase D (Tree View) ←─────────────────────────────────│───┘   │
  ↓                                                    │       │
Phase E (Vega-Lite Chart) ────────────────────────┐    │       │
  ↓                                               │    │       │
Phase F (Selection → LEC → Chart) ←───────────────┘────┘───────┘
  ↓
Phase G (Testing)
  ↓
Phase H (SSE Cache Invalidation)
  ↓
Phase I (Error Handling)
```

---

## Tier 2: Irmin Persistence & Backend Pipeline

### Overview

Tier 2 connects Irmin watch notifications to cache invalidation and SSE broadcast, completing the reactive data flow. Several prerequisites are already complete.

### Completed Infrastructure

| Phase | Description | Status |
|-------|-------------|--------|
| Error Domain Model | `SimulationError` extended with `IrminUnavailable`, `NetworkTimeout`, `VersionConflict`, `MergeConflict` | ✅ Complete |
| Irmin Dev Environment | `dev/Dockerfile.irmin`, docker-compose, schema extraction | ✅ Complete |
| Irmin GraphQL Client | `IrminClient` with get/set/remove/list/branches/healthCheck | ✅ Complete |
| Tree Index & Cache | `TreeIndex`, `RiskResultCache`, `TreeCacheManager`, `TreeIndexService` | ✅ Complete |
| SSE Infrastructure | `SSEHub`, `SSEEndpoints`, `SSEController`, heartbeat, event types | ✅ Complete |
| Irmin Repository | `RiskTreeRepositoryIrmin` with per-node storage, selectable config | ✅ Complete |
| InvalidationHandler | Cache invalidation + SSE notification bridge | ✅ Complete |

### Phase 5: Cache Invalidation Pipeline

**Status:** Not started
**Blocked on:** WebSocket transport decision for `IrminClient.watch`

**Objective:** Connect Irmin watch notifications to cache invalidation and SSE broadcast.

#### Task 0: `IrminClient.watch` — GraphQL Subscription

Extends `IrminClient` trait with:
```scala
def watch(path: Option[IrminPath]): ZStream[Any, IrminError, IrminCommit]
```

Irmin schema: `subscription { watch(path: Path, branch: BranchName): Diff! }` where `Diff { commit: Commit! }`

**Transport decision required:**
- **Option A:** Caliban client (built-in ZIO subscription + graphql-ws protocol)
- **Option B:** sttp-ws (raw WebSocket, manual graphql-ws framing)
- **Option C:** HTTP polling fallback (simplest, no new dep, higher latency)

Decision criteria: Does Tier 4 (WebSocket Enhancement / ADR-004b) also need the same dependency? If yes → choose a dep that serves both. If Tier 4 is distant → polling fallback is fine.

**Why deferred:** No consumer exists until `TreeUpdatePipeline`; implementing in isolation would create dead code and force a premature transport decision.

#### Task 1: Invalidation Handler Wiring

`InvalidationHandler` already exists and works. This task connects it to the Irmin watch stream instead of manual API triggers.

- Receives Irmin watch events from `IrminClient.watch` ZStream
- Calls `TreeCacheManager.onTreeStructureChanged(treeId)` (invalidates cached results)
- Triggers recomputation for affected path

#### Task 2: LECRecomputer

```
service/pipeline/LECRecomputer.scala
```
- Recomputation strategy: **deferred** (DP-2 decision) — decide eager vs lazy when implementing
- Uses existing `Simulator` for LEC computation
- Updates cache after computation
- Broadcasts `LECUpdated` via SSEHub

**Open question (deferred):** Should recomputation be eager (immediate, better UX for visible nodes) or lazy (on next read, consistent with ADR-015 cache-aside)?

#### Task 3: TreeUpdatePipeline

```
service/pipeline/TreeUpdatePipeline.scala
```
- Subscribes to `IrminClient.watch` (Task 0)
- Routes events to InvalidationHandler
- Manages pipeline lifecycle (background fiber, graceful shutdown)

#### Task 4: Application Integration

- Start pipeline as background fiber in `Main.scala`
- Graceful shutdown on app termination

#### Task 5: Integration Tests

- Simulate Irmin change → verify SSE event emitted
- Verify cache invalidated for correct ancestor path
- Pipeline handles errors without crashing

**Deliverables:**
- [ ] Irmin change triggers cache invalidation
- [ ] Recomputation uses O(depth) path, not full tree
- [ ] SSE clients receive events
- [ ] Pipeline handles errors gracefully

### Outstanding Integration Test Coverage

The server-it module has partial HTTP coverage. Remaining test targets:

| Spec | Purpose | Status |
|------|---------|--------|
| `HttpApiIntegrationSpec` | Health + create/list/get | ✅ Done |
| `RiskTreeApiIntegrationSpec` | Full CRUD (update/delete + errors) | ⬜ Expand |
| `LECApiIntegrationSpec` | LEC query + provenance + multi | ⬜ Not started |
| `CacheApiIntegrationSpec` | Cache stats/nodes/invalidation/clear | ⬜ Not started |
| SSE integration | Event streaming verification | ⬜ Not started |

### Outstanding Technical Debt

From `CODE-QUALITY-REVIEW-2026-01-20.md` — affects code paths used by `RiskTreeRepositoryIrmin` for tree reconstruction:

| Issue | Priority | Effort |
|-------|----------|--------|
| Imperative error collection in `TreeIndex.fromNodes` | Medium | 1 hour |
| Inconsistent validation return types (Either vs Validation) | Low | 30 min |
| Verbose `ZIO.fromEither` conversion pattern | Low | 20 min |
| `if/else` instead of `fromPredicateWith` in `RiskTree.fromNodes` | Low | 10 min |

Additional outstanding items:
- `IrminPath` utilities for tree path construction (Step 2 of repository plan — incomplete)
- Repository test coverage gaps: validation failure tests, concurrent create tests, commit history assertions

### SSE Implementation Notes (Learned Gotchas)

These are important implementation details discovered during Phase 4 (SSE Infrastructure):

1. **Tapir SSE:** There is no dedicated `serverSentEventsBody` in Tapir — use `streamBody(ZioStreams)` with `CodecFormat.TextEventStream()` content type
2. **Subscriber tracking:** `Hub.size` returns pending message count, NOT subscriber count — track subscribers separately with `Ref`
3. **Test timing:** Use `@@ TestAspect.withLiveClock` and `Live.live(ZIO.sleep(...))` for timing-based SSE tests

---

## Tier 3: Real-Time Collaboration & Scenarios

### Overview

This is a **core feature** tier implementing multi-user collaboration with conflict detection and scenario branching via Irmin branches. These features leverage Irmin's native capabilities (branching, merging, content-addressing) to provide what-if analysis and collaborative risk editing.

**Prerequisites:** Tier 2 (cache invalidation pipeline) must be complete for reactive updates.

### Phase 6: Event Hub & Collaboration

**ADR Reference:** ADR-006-proposal (Real-Time Collaboration)

**Objective:** Implement multi-user event distribution and conflict detection.

#### Task 1: Collaboration Event Types

```
domain/event/RiskEvent.scala
```

Event types for multi-user awareness:
- `NodeCreated(nodeId, treeId, userId)` — node added by a user
- `NodeUpdated(nodeId, treeId, userId)` — node modified
- `NodeDeleted(nodeId, treeId, userId)` — node removed
- `UserJoined(userId, treeId)` — user started editing
- `UserLeft(userId, treeId)` — user stopped editing
- `ConflictDetected(nodeId, treeId, userId, conflictType)` — concurrent edit conflict

#### Task 2: EventHub Service

```
service/collaboration/EventHub.scala
```

- Per-user event queues (bounded)
- `broadcast(event): UIO[Int]` — send to all connected users
- `broadcastExcept(event, userId): UIO[Int]` — no self-echo
- Backpressure policy for slow clients

**Backpressure decision required:**
- A) Drop oldest events (lossy but simple)
- B) Disconnect slow client (clean but disruptive)
- C) Coalesce rapid updates (smart but complex)

This extends the existing `SSEHub` infrastructure. The `SSEHub` handles per-tree broadcast; `EventHub` adds per-user filtering, conflict detection, and collaboration-specific event types.

#### Task 3: ConflictDetector

```
service/collaboration/ConflictDetector.scala
```

- Track `baseVersion` (Irmin commit hash) on edit requests
- Compare with current head commit before applying mutation
- Return `EditResult.Success` or `EditResult.Conflict` with conflict info
- Uses Irmin's content-addressed hashes for conflict detection

**Irmin integration:** Irmin commits form a DAG. When a user edits based on commit `C1` but the current head is `C2`, a conflict exists if `C1 ≠ C2` and the same path was modified.

#### Task 4: Update Mutation Endpoints

- Accept `baseVersion` (ETag) in edit requests
- Check for conflicts before applying changes
- Broadcast events after successful mutation
- Exclude originator from broadcast (no self-echo)

#### Task 5: Tests

```
test/.../EventHubSpec.scala
test/.../ConflictDetectorSpec.scala
```

**Deliverables:**
- [ ] Multiple users see each other's changes via SSE
- [ ] Conflict detected when editing stale version
- [ ] Conflict event sent to affected user
- [ ] Events exclude originator (no self-echo)

**Checkpoint:** Two browser tabs see each other's changes, conflicts detected on stale edits.

---

### Phase 7: Scenario Branching

**ADR Reference:** ADR-007-proposal (Scenario Branching)

**Objective:** Implement what-if scenario management via Irmin branches. This is a key differentiating feature — Irmin's native branch/merge support maps directly to scenario analysis.

#### Irmin Branch Semantics

Irmin branches work like Git branches:
```
main:     A → B → C
                   \
scenario:           D → E  (what-if analysis)
```

Each scenario gets its own Irmin branch. Edits in a scenario don't affect the main tree. Scenarios can be compared (diff LECs at key percentiles) and optionally merged back.

#### Task 1: Scenario Domain Model

```
domain/scenario/Scenario.scala
```

```scala
case class Scenario(
  id: ScenarioId,          // Iron-refined ULID
  name: ScenarioName,      // Iron-refined non-blank
  branchRef: String,       // Irmin branch name
  createdFrom: String,     // Source branch (usually "main")
  createdBy: String,       // User identifier
  createdAt: Instant,      // Timestamp
  description: Option[String]
)
```

#### Task 2: ScenarioService

```
service/scenario/ScenarioService.scala
```

| Method | Implementation |
|--------|----------------|
| `create(name, description)` | Creates Irmin branch from current main head |
| `list(userId)` | Lists user's scenarios (Irmin branches with metadata) |
| `switch(scenarioId)` | Changes active branch for the session |
| `delete(scenarioId)` | Removes Irmin branch |

#### Task 3: Merge Functionality

```
service/scenario/ScenarioMerger.scala
```

- `merge(source, target)` → Irmin merge operation
- Handle `MergeResult.Conflict` with conflict info (using `MergeConflict` from `SimulationError`)
- Compute `ScenarioDiff` for merge preview before committing
- Three-way merge: common ancestor + source + target

#### Task 4: Comparison Service

```
service/scenario/ScenarioComparator.scala
```

- `compare(scenarioA, scenarioB)` → diff nodes and LEC impact
- Use cached `RiskResult` for fast comparison (ADR-005/009)
- Compute delta at key percentiles (p50, p90, p95, p99, expected loss)
- Identify added/removed/modified nodes between scenarios

#### Task 5: API Endpoints

```
api/ScenarioEndpoints.scala
```

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/scenarios` | Create new scenario |
| GET | `/scenarios` | List all scenarios |
| POST | `/scenarios/{id}/switch` | Switch active scenario |
| DELETE | `/scenarios/{id}` | Delete scenario |
| POST | `/scenarios/{id}/merge` | Merge scenario to target |
| GET | `/scenarios/{a}/compare/{b}` | Compare two scenarios |

#### Task 6: Frontend Scenario UI

- Scenario switcher component (dropdown + "New Scenario" button)
- Visual indicator for current branch
- Side-by-side LEC comparison view
- Diff summary (added/removed/modified nodes)
- Delta display at key percentiles
- Merge UI with conflict resolution

#### Task 7: Tests

```
test/.../ScenarioServiceSpec.scala
test/.../ScenarioMergerSpec.scala
test/.../ScenarioComparatorSpec.scala
```

**Open questions:**
- Branch naming: unique per user or globally unique?
- Orphan cleanup: manual only, or auto-archive after inactivity?

**Deliverables:**
- [ ] Can create scenario from current tree state
- [ ] Can switch between scenarios
- [ ] Edits in scenario don't affect main
- [ ] Can merge scenario back to main
- [ ] Can compare two scenarios with LEC delta
- [ ] Conflict resolution on merge

**Checkpoint:** End-to-end scenario workflow: create → edit → compare → merge.

---

### ADR Acceptance Checkpoint (Post-Tier 3)

Upon completing Tier 3, promote the following proposals:

| ADR | Action |
|-----|--------|
| ADR-004a-proposal | Rename to `ADR-004a.md`, set status "Accepted" |
| ADR-005-proposal | Already accepted (ADR-015 covers cache-aside) |
| ADR-006-proposal | Rename to `ADR-006.md`, set status "Accepted" |
| ADR-007-proposal | Rename to `ADR-007.md`, set status "Accepted" |
| ADR-008-proposal | Rename to `ADR-008.md`, set status "Accepted" |

---

## Tier 4: WebSocket Enhancement

**ADR Reference:** ADR-004b-proposal (WebSocket Enhancement)

**Objective:** Replace SSE with WebSocket for bidirectional communication when collaborative editing is needed.

**Prerequisites:** Tiers 1–3 complete; user decision on whether WebSocket is needed for initial release.

### Tasks

1. **WebSocket message types**
   ```
   api/ws/WSMessage.scala
   ```
   - Client→Server: `EditNode`, `CursorMove`, `PresenceUpdate`
   - Server→Client: `LECUpdated`, `NodeChanged`, `UserCursor`

2. **WebSocket hub**
   ```
   service/ws/WebSocketHub.scala
   ```
   - Replace/extend SSEHub
   - Handle bidirectional messages
   - Track user presence and cursors

3. **WebSocket endpoint**
   ```
   api/ws/WebSocketEndpoint.scala
   ```
   - ZIO HTTP WebSocket handler
   - Message routing

4. **Frontend WebSocket client**
   - Replace EventSource with WebSocket
   - Send cursor/presence updates
   - Show other users' cursors in tree view

5. **Tests**
   ```
   test/.../WebSocketHubSpec.scala
   ```

**WebSocket advantages over SSE:**
- Bidirectional (client→server messages for cursor, presence)
- Single connection (SSE + REST requires two)
- Pre-commit conflict detection (soft locks)
- Same `TreeOp` schema works across both HTTP batch and WebSocket

**Deliverables:**
- [ ] WebSocket connection established
- [ ] Bidirectional message flow
- [ ] Presence tracking (who's online)
- [ ] Cursor sharing (optional)

---

## Phase X: Capability URL Demo Mode

**ADR Reference:** [ADR-021: Capability URLs](./ADR-021-capability-urls.md)  
**Prerequisites:** Phase B (BackendClient + ZJS), Phase D (Tree View)  
**Priority:** Independent — can be implemented in parallel with Tier 2/3  

**Objective:** Enable a public demo where users create and interact with trees without authentication. Access is controlled by unguessable capability URLs rather than user credentials.

### Task X.1: ShareToken Domain Type

```
common/.../domain/data/ShareToken.scala
```

- `case class ShareToken(value: String)` — 128-bit `SecureRandom`, base64url encoded (22 chars)
- `ShareToken.generate: UIO[ShareToken]` factory
- Tapir codec + JSON codec (same pattern as `TreeId` / `NodeId`)
- **Must use `java.security.SecureRandom`**, not `java.util.Random`

### Task X.2: DemoStore Service

```
server/.../service/demo/DemoStore.scala
server/.../service/demo/DemoStoreLive.scala
server/.../config/DemoConfig.scala
```

- `DemoStore` trait: `create(treeId, ttl) → ShareToken`, `resolve(token) → Option[TreeId]`, `evictExpired → Int`
- `DemoStoreLive`: `TrieMap[ShareToken, (TreeId, Instant)]` with background reaper fiber
- `DemoConfig`: `ttl` (default 24h), `reaperInterval` (default 5m), `maxCreatesPerIpPerHour` (default 10)
- Config loaded from `register.demo` block in `application.conf`

### Task X.3: Demo Endpoints + Controller

```
common/.../endpoints/DemoEndpoints.scala
server/.../controller/DemoController.scala
```

Endpoints scoped under `/demo` — **no** `securityIn`, no JWT:

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/demo/trees` | Create tree → return `{ shareToken, expiresAt }` |
| GET | `/demo/t/{shareToken}` | Resolve token → return tree |
| POST | `/demo/t/{shareToken}/nodes/lec-multi` | LEC curves for demo tree |
| GET | `/demo/t/{shareToken}/events` | SSE stream for demo tree |
| DELETE | `/demo/t/{shareToken}` | Delete demo tree (optional) |

**Not exposed:** `GET /demo/trees` (list-all) — prevents enumeration.

### Task X.4: Rate Limiting

- Simple `Ref[Map[IpAddress, (Int, Instant)]]`-based rate limiter
- Configurable via `DemoConfig.maxCreatesPerIpPerHour`
- Returns HTTP 429 on limit exceeded
- In production with mesh: defer to Istio EnvoyFilter rate limiting

### Task X.5: Security Headers

- `Referrer-Policy: no-referrer` on all `/demo/*` responses
- `Cache-Control: no-store` on all `/demo/*` responses
- HTTPS enforcement (mesh handles in production; documented for standalone)

### Task X.6: Frontend Demo Route

```
app/.../pages/DemoPage.scala
app/.../core/DemoClient.scala
```

- Route: `/#/demo/{shareToken}` — resolves token, renders tree view + LEC chart
- "Create Demo Tree" landing page at `/#/demo`
- On creation: browser navigates to `/#/demo/{shareToken}` — user bookmarks/shares this URL
- No login UI, no session management
- Expiry countdown indicator ("This tree expires in 23h 14m")

### Task X.7: Tests

```
server/.../service/demo/DemoStoreSpec.scala
server/.../controller/DemoControllerSpec.scala  (or integration test)
```

- `ShareToken` generation produces 22-char base64url strings
- `DemoStore` resolves valid tokens, rejects expired/unknown tokens
- Reaper fiber evicts expired entries
- Rate limiter rejects excessive creation
- Demo endpoints return 404 (not 403) for invalid tokens — no information leakage

### Task X.8: Istio Policy Exception (Production)

Add `AuthorizationPolicy` to skip JWT validation for `/demo/*`:

```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: demo-public-access
  namespace: register
spec:
  action: ALLOW
  rules:
  - to:
    - operation:
        paths: ["/demo/*"]
```

All other routes remain protected per ADR-012.

**Checkpoint:**
- [ ] `ShareToken` generated with `SecureRandom` (128 bits)
- [ ] Demo tree creation returns capability URL
- [ ] Token resolves to tree; invalid/expired tokens return 404
- [ ] No enumeration endpoint on demo surface
- [ ] TTL eviction works (background reaper)
- [ ] Rate limiting prevents abuse
- [ ] Security headers set on demo responses
- [ ] Frontend renders tree at `/#/demo/{shareToken}`

---

## Reference Resources

### BCG Implementation (for frontend patterns)

```
temp/business-case-generator/modules/app/src/main/scala/com/promon/bca/core/
├── ZJS.scala              # ZIO-to-Laminar bridge (simpler version)
└── BackendClient.scala    # sttp/tapir HTTP client
temp/business-case-generator/modules/common/src/main/scala/com/promon/bca/
└── domain/data/vegalite/
    ├── VegaLiteLossDiagramm.scala  # Main LEC spec builder
    ├── Data.scala, Layer.scala, Encoding.scala, etc.
```

### Cheleb Implementation (for enhanced ZJS patterns)

```
temp/cheleb/modules/app/src/main/scala/com/rockthejvm/reviewboard/core/
├── ZJS.scala           # Enhanced with toEventStream, runJs
├── BackendClient.scala # With secured endpoint support
├── Session.scala       # JWT token management (NOT USING)
└── Storage.scala       # localStorage wrapper (NOT USING)
```

### Vega-Lite Experiments (for chart interaction patterns)

```
temp/vega-lite-experiments/src/main/scala/Main.scala
# Key pattern: VegaEmbed facade + signal listener for selection
```

### Key Domain Concepts

**LEC (Loss Exceedance Curve):**
- X-axis: Loss amount (in millions)
- Y-axis: P(Loss ≥ x) — probability of exceeding that loss
- One curve per risk node (aggregate + children)
- Smooth B-spline interpolation ("basis")

**Aggregation semantics:**
```
Identity[RiskResult].combine uses outer join:
  Union of trial IDs from both distributions
  Sum losses per trial: loss_combined(i) = a.loss(i) + b.loss(i)
  Creates aggregate LEC from children

Example:
  Portfolio A = Child1 + Child2
  For each trial i: A.loss(i) = Child1.loss(i) + Child2.loss(i)
```

**LECCurveResponse (API response format):**
```scala
final case class LECCurveResponse(
  id: String,
  name: String,
  curve: Vector[LECPoint],
  quantiles: Map[String, Double],    // p50, p90, p95, p99
  childIds: Option[List[String]] = None,
  provenances: List[NodeProvenance] = Nil
)
```

---

## Related ADRs

| ADR | Title | Status | Relevance |
|-----|-------|--------|-----------|
| ADR-001 | Public String API, Internal Iron Types | Accepted | Wire format uses String; internal uses `NodeId`, `TreeId` |
| ADR-002 | Structured Logging | Accepted | All service operations logged |
| ADR-003 | HDR Seed Provenance | Accepted | Simulation reproducibility |
| ADR-004a-proposal | Persistence Architecture (SSE) | Proposal | Irmin ↔ ZIO ↔ Browser data flow |
| ADR-004b-proposal | WebSocket Enhancement | Proposal | Tier 4 bidirectional comms |
| ADR-005-proposal | Cached Subtree Aggregates | Proposal | O(depth) invalidation |
| ADR-006-proposal | Real-Time Collaboration | Proposal | Multi-user editing, Tier 3 |
| ADR-007-proposal | Scenario Branching | Proposal | What-if via Irmin branches, Tier 3 |
| ADR-008-proposal | Error Handling & Resilience | Proposal | Frontend error patterns |
| ADR-009 | Compositional Risk Aggregation | Accepted | `Identity[RiskResult].combine` |
| ADR-010 | Error Handling Strategy | Accepted | `SimulationError` hierarchy |
| ADR-011 | Import Conventions | Accepted | Top-level imports |
| ADR-012 | Service Mesh Strategy | Accepted | Istio Ambient Mode, no app-level retries |
| ADR-014 | Code Quality & Caching Strategy | Accepted | `RiskResultCache`, `TreeCacheManager` |
| ADR-015 | Cache-Aside Pattern | Accepted | `RiskResultResolver` lazy computation |
| ADR-017 | Tree API Design | Accepted | Phase 1 CRUD ✅, Phase 2 batch `TreeOp` pending |
| ADR-018 | Nominal Wrappers | Accepted | `NodeId`, `TreeId` opaque types |
| ADR-019 | Frontend Component Architecture | Accepted | Composable function pattern, tree builder |
| ADR-020 | Supply Chain Security | Accepted | Dependency management |
| ADR-021 | Capability URLs | Proposed | Unauthenticated demo access via `ShareToken` (Phase X) |

### Batch Operations & Algebraic API (ADR-017 Phase 2)

The batch update feature and category-theory-based tree API are fully designed in ADR-017 (Phase 2) but not yet implemented. Key elements:

- **`TreeOp` sealed trait** — 6 operations: `AddLeaf`, `AddPortfolio`, `DeleteNode`, `ReparentNode`, `UpdateDistribution`, `RenameNode`
- **Batch endpoint:** `PATCH /risk-trees/{treeId}/batch` with `{ "operations": [...] }`
- **Zipper-based interpreter** — internal optimization for O(depth) navigation
- **Free monad foundation:** `type TreeProgram[A] = Free[TreeOp, A]`
- **Invertibility** — each operation has a computable inverse for undo/redo
- **WebSocket-ready** — same `TreeOp` schema works across HTTP batch and future WebSocket

The theoretical underpinning for these patterns is documented in `TREE-OPS.md` (zippers, optics, recursion schemes, catamorphisms). No optics/zipper libraries are currently in dependencies.

---

## Decisions Log

| ID | Decision | Choice | Date | Rationale |
|----|----------|--------|------|-----------|
| DP-1 | Subtree LEC fetch approach | Use `getLECCurvesMultiEndpoint` (multi-fetch) | 2026-02-10 | Existing endpoint sufficient; no new `depth` param needed |
| DP-2 | Eager vs lazy LEC recomputation | Deferred | 2026-02-10 | Pipeline doesn't exist yet; decide when implementing |
| DP-3 | Frontend test framework | zio-test | 2026-02-10 | Already declared in build.sbt; consistent with server module |
| DP-4 | Scenario branching scope | Full detail (Tier 3) | 2026-02-10 | Core feature, high priority |
| DP-5 | nTrials UI control | Server-side config only | 2026-02-10 | Current configuration retained; no UI control planned |
| — | Tree visualization | Laminar HTML (not Vega tree) | 2026-01-13 | From PLAN-SPLIT-PANE-LEC-UI.md |
| — | Split pane approach | Fixed proportions first | 2026-01-13 | CSS Flexbox, draggable later |
| — | Session/auth | Skip entirely | 2026-01-13 | No session handling for now |
| — | Irmin resilience | Service mesh (ADR-012) | 2026-01-17 | No app-level retries; Istio handles |
| — | Irmin dev image | Alpine (distroless deferred) | 2026-01-17 | ~650 MB dev image; <50 MB target for prod |
| — | Repository selection | Config-driven (`repositoryType`) | 2026-01-20 | Default `in-memory`; `irmin` available |
| DP-6 | Demo access model | Capability URLs (ADR-021) | 2026-02-12 | `ShareToken` (128-bit SecureRandom) instead of exposing `TreeId`; separate `/demo` routes; TTL eviction; no user management |
| — | Per-node vs per-tree storage | Per-node (Option A) | 2026-01-20 | Fine-grained Irmin watch notifications identify exact node changed → O(depth) ancestor invalidation. Per-tree storage would require full tree diff on every change. |

---

*Document created: February 10, 2026*
*Consolidates: APP-IMPLEMENTATION-PLAN.md, PLAN-SPLIT-PANE-LEC-UI.md, IMPLEMENTATION-PLAN-PROPOSALS.md, RISKTREE-REPOSITORY-IRMIN-PLAN.md*
*Related (kept): IRMIN-INTEGRATION.md*

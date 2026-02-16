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
6. [Tier 1.5: Workspace Capability & Access Control](#tier-15-workspace-capability--access-control)
7. [Tier 2: Irmin Persistence & Backend Pipeline](#tier-2-irmin-persistence--backend-pipeline)
8. [Tier 3: Real-Time Collaboration & Scenarios](#tier-3-real-time-collaboration--scenarios)
9. [Tier 4: WebSocket Enhancement](#tier-4-websocket-enhancement)
10. [Reference Resources](#reference-resources)
11. [Related ADRs](#related-adrs)
12. [Decisions Log](#decisions-log)

---

## Overview

This document is the single source of truth for all implementation work on the Risquanter Register project. It consolidates frontend GUI plans, backend infrastructure plans, Irmin persistence plans, and future feature plans into one document with clear tier-based prioritization.

### Goals

1. **Split-pane UI** with tree view + Vega-Lite LEC charts (Tier 1)
2. **Workspace capability access control** with TTL, reaping, and config-driven deployment modes (Tier 1.5)
3. **Irmin-backed persistence** with per-node storage and cache invalidation pipeline (Tier 2)
4. **Real-time collaboration** with conflict detection and **scenario branching** via Irmin branches (Tier 3)
5. **WebSocket enhancement** for bidirectional communication (Tier 4)

### Deployment Modes (Single Codebase)

The application supports two deployment modes from the same source code, selected via configuration:

| Mode | Access Control | TTL | Reaper | Features |
|------|---------------|-----|--------|----------|
| **Free-tier** (public) | Workspace key in URL (capability) | 24–72h | Active (ZIO fiber) | Sneak-peak feature subset |
| **Enterprise** (local) | Keycloak + OPA/SpiceDB (identity + RBAC) | Infinite | No-op | Full feature set |

Authorization layers beyond workspace capability are documented in [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md).

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

### Test Counts (as of Feb 16, 2026)

| Module | Tests | Status |
|--------|-------|--------|
| commonJVM | 349 | ✅ Passing |
| server | 267 | ✅ Passing |
| **Total** | **616** | ✅ |

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

### Frontend (31 Source Files)

| File | Purpose | Phase |
|------|---------|-------|
| `Main.scala` | Entry point, routing, workspace bootstrap | B/W.6 |
| `BackendClient.scala` | Tapir client with FetchBackend | B |
| `ZJS.scala` | ZIO-to-Laminar bridge (extension methods) | B |
| `Constants.scala` | App constants (base URL) | B |
| `LoadState.scala` | ADT: Idle / Loading / Loaded / Failed | B |
| `SubmitState.scala` | ADT: Idle / Submitting / Submitted / Failed | B |
| `ThrowableExtensions.scala` | `safeMessage` extension (null-safe getMessage) | I.a |
| `FormState.scala` | Trait: field enums, touch tracking, submit errors | A/W.10/W.10b |
| `FormInputs.scala` | Reusable `textInput`, `radioGroup`, `submitButton` | A |
| `FormSubmitUtil.scala` | Bridge: formFieldFor → setSubmitFieldError | W.10 |
| `DistributionMode.scala` | `Expert \| Lognormal` enum | V |
| `TreeBuilderState.scala` | Tree assembly: name, portfolio/leaf lists | A |
| `TreeBuilderView.scala` | Orchestrator: tree name + forms + preview | A |
| `PortfolioFormState.scala` | Portfolio name + parent validation | A |
| `PortfolioFormView.scala` | "Add Portfolio" sub-form | A |
| `RiskLeafFormState.scala` | Leaf field validation (Iron-based) | A |
| `RiskLeafFormView.scala` | "Add Leaf" sub-form | A |
| `TreePreview.scala` | Live preview with box-drawing + remove buttons | A |
| `SplitPane.scala` | CSS-based split-pane layout | C |
| `Layout.scala` | App layout wrapper | C |
| `Header.scala` | App header with health check | C |
| `TreeViewState.scala` | Tree navigation state (expand/collapse/select) | D |
| `LECChartState.scala` | Chart selection + LEC spec lifecycle | D/F |
| `TreeListView.scala` | Tree list dropdown | D |
| `TreeDetailView.scala` | Expandable tree UI | D |
| `VegaEmbed.scala` | Scala.js facade for Vega-Lite | E |
| `LECChartView.scala` | Reactive chart component (mount/dispose) | E |
| `LECChartPlaceholder.scala` | Placeholder when no chart loaded | E |
| `GlobalError.scala` | Client-side error ADT + classifier | I.a |
| `ErrorBanner.scala` | Dismissible global error banner | I.a |
| `WorkspaceState.scala` | Workspace key + bootstrap + URL routing | W.6 |

### Frontend (Not Implemented)

| Component | Needed For | Phase |
|-----------|-----------|-------|
| `SSEClient` | SSE subscription | Phase H (deferred post-1.5) |

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
- [ ] App-module tests for `TreeBuilderState` (nice to have — see Phase G at end of document)

---

### Phase B: BackendClient + ZJS Infrastructure — ✅ COMPLETE

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

### Phase C: Split-Pane Layout — ✅ COMPLETE

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

### Phase D: Tree View Component — ✅ COMPLETE

**Goal:** Reactive tree state management and interactive expandable tree view.

**Files to create:**
```
modules/app/src/main/scala/app/
├── state/TreeViewState.scala    # Navigation state (expand/collapse/select)
├── state/LECChartState.scala    # Chart selection + LEC spec (split from TreeViewState)
├── services/TreeService.scala
└── views/TreeView.scala
```

**TreeViewState** (navigation + tree data lifecycle):
```scala
class TreeViewState extends RiskTreeEndpoints:
  val availableTrees: Var[LoadState[List[SimulationResponse]]] = Var(LoadState.Idle)
  val selectedTreeId: Var[Option[TreeId]] = Var(None)
  val selectedTree: Var[LoadState[RiskTree]] = Var(LoadState.Idle)
  val expandedNodes: Var[Set[NodeId]] = Var(Set.empty)
  val selectedNodeId: Var[Option[NodeId]] = Var(None)

  // Chart state delegated to LECChartState (SRP split)
  val chartState: LECChartState = ...
  // Convenience accessors for backward compatibility:
  def chartNodeIds = chartState.chartNodeIds
  def lecChartSpec = chartState.lecChartSpec
  def toggleChartSelection(nodeId) = chartState.toggleChartSelection(nodeId)
```

**LECChartState** (chart selection + LEC spec lifecycle):
```scala
class LECChartState(
  selectedTreeId: StrictSignal[Option[TreeId]],
  selectedTree: StrictSignal[LoadState[RiskTree]]
) extends RiskTreeEndpoints:
  val chartNodeIds: Var[Set[NodeId]] = Var(Set.empty)
  val lecChartSpec: Var[LoadState[String]] = Var(LoadState.Idle)
  def reset(): Unit = ...
  def toggleChartSelection(nodeId: NodeId): Unit = ...
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

### Phase E: Vega-Lite LEC Chart — ✅ COMPLETE

**Goal:** Server-side Vega-Lite spec generation + client-side rendering for multi-curve LEC visualization.

#### Architecture (DP-12: Server-side chart generation)

Follows the BCG `VegaLiteLossDiagramm` pattern: server constructs a complete Vega-Lite JSON spec using a typed DSL, client renders it with VegaEmbed. This keeps tick recalculation, quantile annotation, and spec assembly as a single server-side concern, testable on the JVM.

```
Server:                                    Client:
┌──────────────────────────────┐           ┌─────────────────────────┐
│ POST /risk-trees/{id}/lec-chart          │ VegaEmbed facade        │
│   ↓                          │           │   ↓                     │
│ Resolve cached RiskResults   │  ──JSON──▶│ vegaEmbed(el, spec)     │
│   ↓                          │           │   ↓                     │
│ LECGenerator.generateCurve   │           │ Signal listener         │
│ PointsMulti (shared ticks)   │           │ (hover → quantile info) │
│   ↓                          │           └─────────────────────────┘
│ LECChartSpecBuilder          │
│ (typed Vega-Lite DSL)        │
│   ↓                          │
│ Complete JSON spec           │
└──────────────────────────────┘
```

#### Two endpoints, different purposes (DP-13)

| Endpoint | Returns | Consumer | Purpose |
|----------|---------|----------|---------|
| `POST .../nodes/lec-multi` | `Map[String, LECNodeCurve]` (enriched) | API consumers, tests, future custom viz | **Data API** — raw curves + quantiles |
| `POST .../lec-chart` (new) | Complete Vega-Lite spec JSON | GUI via VegaEmbed | **Presentation API** — render-ready |

`lec-chart` composes on the same service method as `lec-multi` — no simulation/cache logic duplicated.

#### Core curve type: `LECNodeCurve` (identity + drawing data)

```scala
// common/.../data/LEC.scala
final case class LECNodeCurve(
  id: String,                     // node identity (preserved after map destructuring)
  name: String,                   // chart legend label
  curve: Vector[LECPoint],        // shared tick domain across all nodes
  quantiles: Map[String, Double]  // e.g. "p50" → 12_000_000
)
```

`LECNodeCurve` is the universal curve type for drawing and identification. `LECCurveResponse`
(single-node endpoint) wraps the same core data with tracing (`provenances`) metadata.

Current `lec-multi` returns `Map[String, Vector[LECPoint]]`. After enrichment: `Map[String, LECNodeCurve]`.
Quantiles computed server-side from full `RiskResult.outcomeCount` TreeMap (exact to simulation resolution — not interpolated from the 100-tick curve subset).

#### Files to create/modify

**Server:**
```
server/.../simulation/LECChartSpecBuilder.scala  # zio.json.ast.Json AST → JSON spec (W.11: typed DSL deferred)
```

**Common (shared):**
```
common/.../data/LECCurveResponse.scala           # Add LECNodeCurve type
common/.../endpoints/RiskTreeEndpoints.scala      # Add lec-chart endpoint; update lec-multi return type
```

**App (client):**
```
app/src/main/scala/app/
├── facades/VegaEmbed.scala       # Scala.js facade (vegaEmbed + signal listener)
└── views/LECChartView.scala      # Reactive chart component (mount/update/dispose)
```

**NPM dependencies** (in `modules/app/package.json`):
```json
"dependencies": {
  "vega": "^6.2.0",
  "vega-lite": "^6.4.1",
  "vega-embed": "^7.1.0"
}
```
Note: Vega-Lite v6 — uses `params` API (not deprecated `selection` from v4/v5).

#### Chart spec details (DP-14, DP-15, DP-16)

- **X-axis:** Loss (quantitative, **linear** scale). Formatted with `labelExpr` for finance convention:
  `if(datum.value >= 1e3, format(datum.value / 1e3, ',.1f') + 'B', format(datum.value, ',.0f') + 'M')`
  Log scale may be added as a toggle in a future phase — not default.
- **Y-axis:** Exceedance probability (0–1 or %).
- **Interpolation:** `"monotone"` default (Hermite spline — preserves monotonicity of LEC curves). `"basis"` and `"cardinal"` can overshoot. Toggleable via Vega-Lite `params` bind (no server round trip — client-side spec parameter).
- **Multi-curve:** Color-encoded by node name, legend auto-generated from sorted risk names.
- **Data format:** Flattened `Vector[Map[String, String]]` with `symbol`/`x`/`y` keys (BCG pattern).
- **Quantile annotations:** Vertical rules at key quantiles (P50, P90, P95, P99) from `LECNodeCurve.quantiles`.

#### Subtask: Migrate prototype from `selection` to `params` API

The `temp/vega-lite-experiments` prototype uses deprecated Vega-Lite v4 `selection` syntax.
The `params` API (v5+, required in v6) replaces it:
```js
// Old (v4): "selection": { "hover": { "type": "single", "on": "mouseover" } }
// New (v6): "params": [{ "name": "hover", "select": { "type": "point", "on": "pointerover" } }]
```
Interpolation toggle uses `params` bind:
```js
{ "name": "interp", "value": "monotone",
  "bind": { "input": "select", "options": ["monotone", "linear", "basis"] } }
```

#### VegaEmbed facade

```scala
@js.native
@JSImport("vega-embed", JSImport.Default)
object VegaEmbed extends js.Object:
  def apply(el: dom.Element, spec: js.Any, options: js.UndefOr[js.Any]): js.Promise[EmbedResult]

@js.native
trait EmbedResult extends js.Object:
  val view: js.Dynamic = js.native
  def finalize(): Unit = js.native
```

#### LECChartView states

- Empty: "Select a node to view LEC"
- Loading: spinner
- Data: rendered chart via `onMountCallback` + `VegaEmbed`; disposed via `finalize()` on unmount
- Error: error message with retry action

**Checkpoint:**
- [x] `LECNodeCurve` type added to common (with `id` field); `lec-multi` return type enriched
- [x] `lec-chart` endpoint defined and implemented
- [x] `LECChartSpecBuilder` generates valid Vega-Lite v6 spec (JVM-testable)
- [x] `LECChartSpecBuilder` accepts `Vector[LECNodeCurve]` (not `LECCurveResponse`) — ISP compliance
- [ ] VegaEmbed facade renders server-generated spec
- [ ] Multiple curves display with legend
- [ ] Interpolation toggle works client-side via `params` bind
- [ ] Quantile annotations render as vertical rules

**`childIds` deletion (Phase F completion):**
`LECCurveResponse.childIds` was deleted at Phase F close. No frontend consumer ever
read childIds from the LEC response — the frontend uses `RiskPortfolio.childIds` from
the tree structure in memory.

---

### Phase F: Wire Selection → LEC Fetch → Chart — ✅ COMPLETE

**Goal:** Complete the data flow from node selection to chart rendering.

**Implementation note:** The planned `LECService.scala` was not created. Its responsibility
was absorbed into `LECChartState` — the state class owns the fetch trigger directly via
`toggleChartSelection` → `loadLECChart`. This avoids pointless service indirection for a
single-method effect.

**Data flow (as implemented):**
```
User clicks chart checkbox on node in TreeDetailView
  ↓
LECChartState.toggleChartSelection(nodeId)
  ↓
Resolves group: nodeId + direct childIds from RiskPortfolio (tree structure)
  ↓
POST /w/{key}/risk-trees/{treeId}/lec-chart  (body: nodeIds)
  ↓
Response = complete Vega-Lite spec JSON
  ↓
LECChartView re-renders via VegaEmbed
```

**Checkpoint:**
- [x] Click node → chart updates with node's LEC + children
- [x] Loading state shown during fetch
- [x] Errors displayed gracefully

**Phase F completion review — `childIds` deprecation verdict:** ✅
`LECCurveResponse.childIds` was not consumed by any frontend code. The frontend reads
`childIds` from `RiskPortfolio` (tree structure in memory). Field deleted from
`LECCurveResponse` at Phase F close.

---

### Phase I.a: Error Handling (Non-SSE) ✅

**Goal:** Robust error handling for API calls following ADR-008 patterns.

**Decision:** Option A — supplement per-view error flows with a global `ErrorBanner`.
Per-view handlers (`LoadState.Failed`, `SubmitState.Failed`, `submitError`) are unchanged.
The banner is a safety net for errors with no per-view owner (server down, future
workspace auth failures, SSE disconnection).

**Key design decision — shared type roundtrip:**
The `ErrorResponse.decode` method (common module) was rewritten to reconstruct the shared
`AppError` sealed hierarchy from HTTP responses, rather than producing a raw `RuntimeException`.
This makes `encode`/`decode` a proper codec pair:

```
Server:  ValidationFailed(errors)  →encode→  (400, ErrorResponse)  →wire→  (400, ErrorResponse)
Client:                                                              →decode→  ValidationFailed(errors')
```

The frontend classifier `GlobalError.fromThrowable` then pattern-matches on the **shared
sealed hierarchy** — the same types that cross-compile to JS via the `common` module.
No status-code integer matching, no string parsing (ADR-010 §5). The reconstruction is
not perfectly lossless (a 409 could be `DataConflict`, `VersionConflict`, or `MergeConflict`
— disambiguated by `ErrorDetail.field`) but preserves type safety at the sealed-trait level.

**Files created:**
```
modules/app/src/main/scala/app/
├── state/GlobalError.scala   — Client-side error ADT + fromThrowable classifier
└── views/ErrorBanner.scala   — Dismissible global error banner component
```

**Files modified:**
- `common/.../errors/ErrorResponse.scala` — `decode` reconstructs shared `AppError` subtypes
  (`ValidationFailed`, `DataConflict`, `VersionConflict`, `MergeConflict`, `IrminUnavailable`,
  `NetworkTimeout`, `IrminGraphQLError`, `SimulationFailure`, `RepositoryFailure`)
- `common/.../errors/ErrorResponseSpec.scala` — 22 tests including full roundtrip coverage
- `app/components/Layout.scala` — accepts `globalError: Signal[Option[GlobalError]]` + `onDismissError`
- `app/Main.scala` — creates `globalError: Var[Option[GlobalError]]`, passes to Layout
- `styles/app.css` — `.error-banner` styles (consistent with `--error` / `--error-surface` tokens)

**GlobalError enum** (named to avoid collision with `domain.errors.AppError`):
```scala
enum GlobalError:
  case ValidationFailed(errors: List[ValidationError])  // shared type, not List[String]
  case NetworkError(message: String, retryable: Boolean)
  case Conflict(message: String)
  case ServerError(message: String)
  case DependencyError(message: String)                 // IrminError subtypes
```

All variants are pure values — no embedded side effects (ADR-010: errors are values).
`fromThrowable` matches on the shared hierarchy first, then falls through to JVM exception
types for browser-side transport errors.

**Wiring:** The `globalError: Var[Option[GlobalError]]` in `Main` is not yet connected to
any error producer — that is by design (Option A). It will be consumed when:
- Health-check failure in `Header.scala` is upgraded to push to `globalError` (optional)
- Workspace auth errors surface in Tier 1.5
- SSE disconnection surfaces in deferred Phase I.b

**Checkpoint:**
- [x] `ErrorResponse.decode` reconstructs shared `AppError` hierarchy (not `RuntimeException`)
- [x] Roundtrip test: `decode(encode(e)).isInstanceOf` for all 8 error types
- [x] `GlobalError.fromThrowable` pattern-matches on shared sealed hierarchy
- [x] `GlobalError` variants are pure values (no `() => Unit` callbacks)
- [x] No name collision with `domain.errors.AppError` (named `GlobalError`)
- [x] `ErrorBanner` + Layout wired with dismiss callback
- [x] CSS styles consistent with design tokens
- [x] `sbt app/fastLinkJS` compiles
- [x] `sbt server/test` — 248 tests pass
- [x] `sbt commonJVM/testOnly *ErrorResponseSpec` — 22 tests pass

> **SSE-related items (Phase H + Phase I.b) are deferred to after Tier 1.5.**
> SSE cache invalidation, stale tracking, and SSE reconnection with exponential
> backoff have no value until multi-user or Irmin watch is in play. See the
> "Deferred: SSE Phases" section after Tier 1.5.

---

### Phase J: Domain Type Naming Review (Low Priority)

**Goal:** Improve semantic clarity of core domain type names. Isolated refactoring — no feature changes.

**Priority:** Low. Execute only after Tier 1 feature work is complete. Do as a standalone refactoring pass.

**Candidates under review:**

| Current name | Issue | Notes |
|---|---|---|
| `RiskLeaf` | "Risk" prefix ambiguous — is it the risk itself or a node that holds a risk? | Consider names that clarify leaf-as-risk-event semantics |
| `RiskPortfolio` | "Risk" prefix same ambiguity — portfolio *of* risks, or a risk *that is* a portfolio? | Consider names that clarify aggregation semantics |
| `RiskResult` | Misleading — not a "risk" but the simulation output for a node | Consider names that clarify simulation-output semantics |
| `RiskResultGroup` | Follows from `RiskResult` | Cascades from `RiskResult` rename |
| `SimulationResponse` | HTTP DTO — could be confused with a renamed `RiskResult` | Consider names that clarify it's a tree-metadata DTO, not simulation output |

**Rename pattern:** TBD — requires semantic analysis before committing to a pattern. See naming discussion in decisions log (DP-17).

**Scope:** Domain types + cascading DTOs (`*DefinitionRequest`, `*UpdateRequest`, `*Raw`), companion types (`*Cache`, `*Resolver`, `*TestSupport`, `*IdentityInstances`), string literals in error field paths, and file renames.

**Checkpoint:**
- [ ] Naming pattern decided and documented
- [ ] All renames applied atomically (single refactoring pass)
- [ ] All tests pass after rename
- [ ] ADRs and docs updated to reflect new names

---

### Tier 1 Dependency Graph

```
Phase V (Validate) ✅
  ↓
Phase A (Align Form) ✅ ───────────────────────────────────────┐
  ↓                                                            │
Phase B (BackendClient + ZJS) ✅ ──────────────────────────┐   │
  ↓                                                        │   │
Phase C (Split-Pane Layout) ✅ ────────────────────────┐   │   │
  ↓                                                    │   │   │
Phase D (Tree View) ✅ ←──────────────────────────────│───┘   │
  ↓                                                    │       │
Phase E (Vega-Lite Chart) ✅ ─────────────────────┐    │       │
  ↓                                               │    │       │
Phase F (Selection → LEC → Chart) ✅ ←────────────┘────┘───────┘
  ↓
Phase I.a (Error Handling — non-SSE) ✅
  ↓
Phase J (Type Naming Review) ·········· low priority, optional gate
  ↓
  ╔═══════════════════════════════════════╗
  ║       TIER 1.5 ENTRY POINT           ║
  ║  Phase W (Workspace Capability)       ║
  ╚═══════════════════════════════════════╝
  ↓
Phase H  (SSE Cache Invalidation) ····· deferred, see post-1.5 section
Phase I.b (SSE Reconnection) ·········· deferred, see post-1.5 section
Phase G  (App-Module Testing) ·········· nice to have, see end of document
```

---

## Tier 1.5: Workspace Capability & Access Control

**Updated:** February 13, 2026
**ADR Reference:** [ADR-021: Capability URLs](./ADR-021-capability-urls.md) (to be amended)
**Authorization Roadmap:** [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md)
**Prerequisites:** Tier 1 complete (Phases V–F, I.a, optionally J)
**Priority:** Immediately after Tier 1 — required for public free-tier deployment

### Overview

Tier 1.5 implements workspace-scoped capability-based access control. This is **Layer 0** of the [layered authorization approach](./AUTHORIZATION-PLAN.md), shared by **both** free-tier and enterprise deployments:

- **Layer 0 (this tier):** Workspace key in URL = access to all trees in workspace. Free-tier: TTL-limited. Enterprise: same URLs, same keys.
- **Layer 0+1 (AUTHORIZATION-PLAN.md):** Keycloak identity **added on top**. Key + valid JWT from the right realm = access (**invitation-link pattern** — sharing the URL *is* sharing access, but only to authenticated users).
- **Layer 0+1+2 (AUTHORIZATION-PLAN.md):** SpiceDB/OpenFGA. Key + JWT + explicit membership/role (**ACL pattern** — the user must be explicitly granted access; the key is just a routing token).

The URL scheme is **the same across all layers** — `/#/{workspaceKey}/...`. The workspace key's **semantic role shifts** as layers are added:

| Layer | Key's role | Access pattern | Leaked URL sufficient? |
|-------|-----------|---------------|------------------------|
| 0 (free-tier) | **Sole credential** (true capability) | Key = access | Yes (mitigated by TTL + security headers) |
| 0+1 (enterprise) | **Invitation token** + resource locator | Key + JWT from right realm = access | **No** — valid session also required |
| 0+1+2 (enterprise+) | **Routing token** only (no auth power) | Key + JWT + SpiceDB relationship = access | **No** — explicit membership also required |

Enterprise deployments address the "secret in URL" concern not by removing the key, but by making it **insufficient on its own**. At Layer 0+1, the key acts as an invitation link — any authenticated user with the URL can access the workspace (analogous to Google Docs "anyone with the link who is signed in"). At Layer 0+1+2, the key becomes purely a routing token — access is determined entirely by explicit SpiceDB relationships, and the key has no authorization power.

### Combines: Original Phase X + Workspace Model

This tier replaces the standalone Phase X by combining:
- Phase X's TTL, reaping, rate limiting, and security headers
- Workspace grouping (multiple trees per key, preserves dropdown/list UX)
- Config-driven deployment modes (free-tier vs enterprise)

### OWASP Security Cross-Reference

**Date:** 2026-02-14
**Sources:** OWASP Authentication, Authorization, Abuse Case, JWT for Java, Session Management cheat sheets.

39 actionable items (A1–A39) identified across 14 findings, cross-referenced against the three-layer auth scheme. Items are mapped to phases where they are implemented.

#### Priority Matrix

| Priority | IDs | Finding | Phase |
|----------|-----|---------|-------|
| 🔴 Critical | A5-A7 | No token revocation mechanism | W.2 (trait), W.3 (endpoint), Enterprise (denylist) |
| 🔴 Critical | A15-A16 | SSE unauthenticated / no lifecycle | W.3, W.4 |
| 🔴 Critical | A17 | `GET /risk-trees` open to all | W.3 |
| 🔴 Critical | A27-A28 | No rate limiting | W.4 |
| 🟠 High | A1-A4 | No security headers for `/w/*` | W.5 |
| 🟠 High | A10-A11 | No dual timeout (idle + absolute) | W.2 |
| 🟠 High | A29-A33 | No lifecycle logging | W.2–W.4 (inline) |
| 🟡 Medium | A8-A9 | No token fingerprint binding | W.5 |
| 🟡 Medium | A13-A14 | Timing side-channel (not-found vs expired) | W.3 |
| 🟡 Medium | A18-A20 | CORS too permissive (`reflectHeaders`) | W.5 |
| 🟡 Medium | A21-A23 | Missing global security headers | W.5 |
| 🟡 Medium | A24-A26 | Error information leakage (`IrminHttpError`, `RepositoryFailure`) | W.3, W.7 |
| 🟢 Future | A34-A37 | JWT algorithm hardening | Enterprise phase |
| 🟢 Future | A38-A39 | SPA PKCE + closure storage | Enterprise phase |

#### Actionable Item Index

| ID | Item | Effort | Phase | Notes |
|----|------|--------|-------|-------|
| A1 | `Referrer-Policy: no-referrer` on `/w/*` | Trivial | W.5 | Already planned |
| A2 | `Cache-Control: no-store` on `/w/*` | Trivial | W.5 | Already planned |
| A3 | `X-Content-Type-Options: nosniff` on `/w/*` | Trivial | W.5 | Already planned |
| A4 | `Strict-Transport-Security` header | Trivial | W.5 | Add to W.5 spec |
| A5 | Add `delete(key)` and `rotate(key)` to `WorkspaceStore` trait | Low | W.2 | Bake into initial trait design |
| A6 | Wire `DELETE /w/{key}` endpoint (cascade hard-delete) | Low | W.3 | Natural companion to bootstrap |
| A7 | Denylist for revoked keys (PG-backed, long-lived) | Medium | Enterprise | In-memory: revoke = delete from Map. Denylist for external validity only |
| A8 | Token fingerprint: `Set-Cookie` with hash | Medium | W.5 | Alongside header hardening |
| A9 | Fingerprint validation middleware | Medium | W.5 | Alongside A8 |
| A10 | Add `lastAccessedAt` to `Workspace` model | Low | W.2 | Bake into initial domain model |
| A11 | Dual timeout logic (idle + absolute) in `resolve()` | Medium | W.2 | Single branch in resolve |
| A12 | *(reserved)* | — | — | — |
| A13 | Constant response for not-found vs expired | Low | W.3 | Collapse to single opaque 404 at HTTP layer |
| A14 | Constant-time workspace lookup | Low | W.2/W.3 | `Map.get` already O(1); document principle |
| A15 | SSE scope to workspace key | Low | W.3 | Confirm SSE uses workspace validation middleware |
| A16 | Close SSE on workspace expiry/revocation | Medium | Deferred | Client-side `WorkspaceExpired` error handling is the reliability mechanism; SSE notification deferred |
| A17 | Seal `GET /risk-trees` with config gate | Trivial | W.3 | Already planned (`list-all-trees.enabled = false`) |
| A18 | Remove `reflectHeaders` from CORS | Low | W.5 | Replace with explicit origin whitelist |
| A19 | Environment-specific CORS origin config | Low | W.5 | Config-driven origin list |
| A20 | CORS preflight caching (`Access-Control-Max-Age`) | Trivial | W.5 | Single header addition |
| A21 | `X-Frame-Options: DENY` global | Trivial | W.5 | Global interceptor |
| A22 | `Content-Security-Policy` global | Medium | W.5 | Requires SPA asset audit |
| A23 | `X-XSS-Protection: 0` global | Trivial | W.5 | Modern best practice (disable legacy filter) |
| A24 | Sanitise `IrminHttpError` — don't forward upstream body | Low | W.3 | Log full body, return generic message |
| A25 | Sanitise `RepositoryFailure` — generic message to client | Low | W.3 | Log reason, return "Internal error" |
| A26 | Audit all 500 responses for information leakage | Low | W.7 | Test assertions on 500 response bodies |
| A27 | IP-based rate limit on `POST /workspaces` | Medium | W.4 | Already planned (`RateLimiter`) |
| A28 | Rate limit on resolve/operations per IP | Medium | W.4 | Prevents key brute-force |
| A29 | Log workspace creation events | Low | W.2 | Inline during implementation |
| A30 | Log resolve failures (not-found, expired) | Low | W.3 | Inline during implementation |
| A31 | Log eviction events | Low | W.4 | Already planned in reaper |
| A32 | Log rate-limit trigger events | Low | W.4 | Inline during implementation |
| A33 | Structured log fields (`workspace_key`, `event_type`, `ip`) | Low | W.2–W.4 | Use existing structured logging |
| A34 | JWT algorithm allowlist (RS256 only) | Low | Enterprise | No JWT in Layer 0 |
| A35 | JWT audience validation | Low | Enterprise | Istio `RequestAuthentication` |
| A36 | JWT issuer validation | Low | Enterprise | Istio `RequestAuthentication` |
| A37 | JWT claim extraction + app-level validation | Medium | Enterprise | ~50-80 lines middleware |
| A38 | SPA PKCE flow for Keycloak | Medium | Enterprise | No OAuth in free-tier |
| A39 | Closure-based JWT storage (not global `Var`) | Low | Enterprise | Defence-in-depth for XSS |

#### Initial Pass — Bake Into W.2/W.3/W.4 (not separate phases)

The following items are implemented **inline** during their mapped phase, not as separate work items.
They are either low-effort or structurally load-bearing (retrofitting later changes the interface):

- **W.2:** A5 (delete + rotate in trait), A10 (lastAccessedAt), A11 (dual timeout), A14 (constant-time), A29 (creation logging), A33 (structured fields)
- **W.3:** A6 (DELETE endpoint — cascade hard-delete), A13 (constant response), A15 (SSE workspace scoping), A17 (seal GET /risk-trees), A24-A25 (error sanitisation), A30 (resolve failure logging)
- **W.4:** A27-A28 (rate limiting), A31-A32 (eviction/rate-limit logging). A16 (SSE lifecycle on eviction) deferred — client-side error handling is the reliability mechanism
- **W.5:** A1-A4, A8-A9, A18-A23 (all header/CORS hardening)
- **W.7:** A26 (500 response body test assertions)
- **Enterprise:** A7, A34-A39

### Phase W.1: WorkspaceKey Domain Type — ✅ COMPLETE

**Goal:** Define the workspace capability credential as an Iron-wrapped nominal type.

**Files created:**
```
common/.../domain/data/iron/WorkspaceKey.scala  (opaque type in OpaqueTypes.scala)
common/.../http/codecs/IronTapirCodecs.scala     (Tapir path codec)
common/test/.../domain/data/iron/WorkspaceKeySpec.scala  (15 tests)
```

**Implementation notes:**
- `WorkspaceKey` is an opaque type following ADR-018 nominal wrapper pattern (like `TreeId`, `NodeId`)
- Uses `java.security.SecureRandom` (128-bit, base64url-encoded → 22 chars)
- `fromString` validates: exactly 22 chars, base64url charset only (no `+`, `/`, `=`, whitespace)
- JSON codecs and Tapir path codec registered in `IronTapirCodecs`

**Checkpoint:**
- [x] `WorkspaceKey.generate` produces 22-char base64url strings
- [x] `WorkspaceKey.fromString` validates format
- [x] Tapir path codec works in endpoint definitions
- [x] JSON round-trip works
- [x] 15 tests passing (`WorkspaceKeySpec`)

---

### Phase W.2: Workspace Domain Model & Store — ✅ COMPLETE

**Goal:** Backend service for workspace lifecycle: create, resolve, tree association, TTL, eviction.

**Files to create:**
```
common/.../domain/data/Workspace.scala
server/.../service/workspace/WorkspaceStore.scala
server/.../service/workspace/WorkspaceStoreLive.scala
server/.../config/WorkspaceConfig.scala
```

**Workspace domain:**
```scala
final case class Workspace(
  key: WorkspaceKey,
  trees: Set[TreeId],
  createdAt: Instant,
  lastAccessedAt: Instant,          // A10: idle timeout tracking
  ttl: Duration,                     // absolute timeout
  idleTimeout: Duration              // A11: idle timeout (e.g., 1h)
)
```

**WorkspaceStore trait:**
```scala
trait WorkspaceStore:
  /** Create a new workspace with the configured TTL. */
  def create(): UIO[WorkspaceKey]

  /** Associate a tree with a workspace. Fails if workspace expired. */
  def addTree(key: WorkspaceKey, treeId: TreeId): IO[WorkspaceError, Unit]

  /** List all tree IDs in a workspace. Fails if expired or not found. */
  def listTrees(key: WorkspaceKey): IO[WorkspaceError, List[TreeId]]

  /** Resolve a workspace. Fails with WorkspaceExpired or WorkspaceNotFound.
    * Implements dual timeout: absolute (createdAt + ttl) AND idle (lastAccessedAt + idleTimeout).
    * Updates lastAccessedAt on successful resolution (A10).
    * Constant response for not-found vs expired at HTTP layer (A13).
    */
  def resolve(key: WorkspaceKey): IO[WorkspaceError, Workspace]

  /** Check if a tree belongs to a workspace. Lazy TTL check included. */
  def belongsTo(key: WorkspaceKey, treeId: TreeId): IO[WorkspaceError, Boolean]

  /** Evict all expired workspaces (absolute + idle). Returns count evicted.
    * Called by both the background reaper fiber and the admin endpoint.
    */
  def evictExpired: UIO[Int]

  /** Hard delete. Removes workspace AND cascade-deletes all associated trees
    * from RiskTreeRepository. Preview feature — no data preservation.
    * Controller orchestrates: listTrees → delete each tree → delete workspace.
    */
  def delete(key: WorkspaceKey): IO[WorkspaceError, Unit]

  /** Atomic rotation. Generates new key, transfers all tree associations,
    * instantly invalidates old key. No grace period — old key is immediately
    * dead, new key is immediately live. Single Ref.modify (in-memory) or
    * single transaction (Postgres).
    * Returns new key.
    */
  def rotate(key: WorkspaceKey): IO[WorkspaceError, WorkspaceKey]
```

**WorkspaceError ADT:**
```scala
enum WorkspaceError:
  case WorkspaceNotFound(key: WorkspaceKey)
  case WorkspaceExpired(key: WorkspaceKey, createdAt: Instant, ttl: Duration)
  case TreeNotInWorkspace(key: WorkspaceKey, treeId: TreeId)
```

#### Persistence Architecture: Layered Separation

**Key insight:** Workspace data is an **association/token index**, not domain content.
The workspace layer maps capability keys to sets of TreeIds — it does not store or
duplicate tree data. This means workspace persistence is **orthogonal** to the
tree storage backend (in-memory vs Irmin):

```
┌──────────────────────────────────────────────────────┐
│               WorkspaceStore                         │  association/token index
│   WorkspaceKey → {Set[TreeId], createdAt, ttl, ...}  │  (Ref-based now, Postgres later)
│   create / resolve / addTree / evict / delete / rotate│
└───────────────────────┬──────────────────────────────┘
                        │  treeId references only
                        │  (no tree data flows through here)
┌───────────────────────▼──────────────────────────────┐
│           RiskTreeRepository                         │  domain content store
│   TreeId → RiskTree                                  │  (in-memory OR Irmin, config-driven)
│   create / update / delete / getById / getAll        │  unchanged by workspace feature
└──────────────────────────────────────────────────────┘
```

**Why workspace keys do NOT affect Irmin paths:** The workspace key is a capability
token in the URL (`/#/{workspaceKey}/...`) used for access control routing. It is
resolved to a set of TreeIds by `WorkspaceStore` *before* any tree operations occur.
The `RiskTreeRepository` never sees the workspace key — it continues to use its
existing path structure (`risk-trees/{treeId}/nodes/...`, `risk-trees/{treeId}/meta`)
unchanged. The workspace key is consumed at the HTTP/controller layer and does not
propagate into storage paths.

**Why Irmin is wrong for workspaces:** Irmin is a content-addressed store optimised
for immutable history, branching, and structural sharing. Workspace associations are:
- Ephemeral (TTL-evicted in free-tier)
- Relational (key → set of IDs, not nested content)
- Token-like (the key functions as an access credential)

Content-addressing provides no value here: workspace history is irrelevant, and
recording "workspace W contained trees {A, B}" as Irmin commits would create
permanent garbage in the content-addressed store that conflicts with TTL eviction.

**Why PostgreSQL is right for durable workspace persistence:** Workspaces are
semantically a **token store** — the workspace key is an opaque credential that
grants access to a set of resources. This maps naturally to relational tables
with foreign keys, TTL-based expiry via `DELETE WHERE created_at + ttl < now()`,
and transactional association management. PostgreSQL is the correct durable backend
for workspace data, while Irmin remains the correct backend for tree content data.

#### Persistence Roadmap

| Phase | WorkspaceStore backend | Tree backend | Deployment |
|-------|----------------------|-------------|------------|
| W.2 (this phase) | `Ref[Map]` (in-memory) | In-memory or Irmin (config) | Dev / free-tier |
| Future: enterprise | `WorkspaceStorePostgres` | Irmin (production) | Enterprise |

Selection will be config-driven, following the same pattern as
`RepositoryConfig` → `chooseRepo` in Application.scala. The `WorkspaceStore` trait
is the abstraction boundary.

**WorkspaceStoreLive (initial implementation — Ref-based):**
- `Ref[Map[WorkspaceKey, Workspace]]` — ZIO idiomatic in-memory store
- Lazy TTL check in `resolve()`: compare `Duration.between(createdAt, now)` against `ttl`
- `evictExpired`: update Ref, remove entries where TTL exceeded, return count
- Data is ephemeral — lost on server restart (acceptable for free-tier: create a new workspace)

**WorkspaceConfig:**
```hocon
register.workspace {
  mode = "free-tier"                # "free-tier" | "enterprise"
  free-tier {
    ttl = 72h                       # workspace lifetime
    reaper-interval = 5m            # background eviction cycle
    max-creates-per-ip-per-hour = 5 # rate limit
    max-trees-per-workspace = 10    # prevent abuse
  }
  enterprise {
    ttl = "infinite"                # no expiry
    # rate limiting deferred to service mesh
  }
}
```

**PostgreSQL persistence (planned — enterprise phase):**
- `WorkspaceStorePostgres` implementation behind the same `WorkspaceStore` trait
- Selectable via config (same `chooseRepo` pattern)
- **Reference:** Review cheleb demo source code for ZIO + PostgreSQL persistence patterns BEFORE implementation
- Schema: `workspaces(key TEXT PRIMARY KEY, created_at TIMESTAMPTZ, ttl INTERVAL)` + `workspace_trees(workspace_key TEXT FK, tree_id TEXT FK)`
- DB-level pruning: `DELETE FROM workspaces WHERE created_at + ttl < now()` — callable from admin endpoint or `pg_cron`
- Survives server restart — required for enterprise deployments

**Checkpoint:**
- [x] `WorkspaceStore.create()` generates workspace with configured TTL
- [x] `resolve()` returns `WorkspaceExpired` for expired workspaces (absolute TTL check)
- [x] `resolve()` returns `WorkspaceExpired` for idle workspaces (idle timeout check — A11)
- [x] `resolve()` updates `lastAccessedAt` on successful resolution (A10)
- [x] `addTree()` associates tree with workspace
- [x] `listTrees()` returns only trees in the specified workspace
- [x] `evictExpired` removes expired entries (absolute + idle) and returns count
- [x] `delete()` cascade-deletes workspace + all associated trees
- [x] `rotate()` atomically generates new key + transfers trees + invalidates old key (instant, no grace)
- [x] Config-driven: `ttl = infinite` disables absolute expiry
- [x] Security logging: creation, deletion, rotation events (A29, A33)
- [x] `resolve()` is atomic (single `Ref.modify` — no TOCTOU race)
- [x] `RateLimiter` off-by-one fixed (rejected requests don't consume slots)
- [x] Workspace errors unified into `AppError` sealed hierarchy (ADR-002 §5)
- [x] Error sanitisation: `WorkspaceNotFound`, `WorkspaceExpired` → opaque 404 at HTTP layer (A13)
- [x] 7 tests passing (`WorkspaceStoreSpec`), 3 tests passing (`RateLimiterSpec`)

**Files created/modified (W.2):**

| File | Action | Notes |
|------|--------|-------|
| `common/.../domain/data/Workspace.scala` | Created | Domain model with dual timeout, `isExpired`, `touch`, `expiresAt` |
| `common/.../domain/errors/AppError.scala` | Modified | Added `WorkspaceNotFound`, `WorkspaceExpired`, `TreeNotInWorkspace` as `SimError` subtypes (ADR-002) |
| `common/.../domain/errors/ErrorResponse.scala` | Modified | `encode` maps workspace errors to opaque 404 (A13) |
| `server/.../services/workspace/WorkspaceStore.scala` | Created | Trait with `create`, `resolve`, `addTree`, `listTrees`, `belongsTo`, `evictExpired`, `delete`, `rotate` |
| `server/.../services/workspace/WorkspaceStoreLive.scala` | Created | Ref-based impl with `logSecurity` helpers, `validateWorkspace` pure function, atomic `resolve` |
| `server/.../services/workspace/RateLimiter.scala` | Created | IP-based fixed-window rate limiter (A27) |
| `server/.../configs/WorkspaceConfig.scala` | Created | `ttl`, `idleTimeout`, `reaperInterval`, `maxCreatesPerIpPerHour`, `maxTreesPerWorkspace` |
| `server/.../configs/ApiConfig.scala` | Created | `listAllTreesEnabled` gate (A17) |
| `server/.../configs/TestConfigs.scala` | Modified | Added `workspace` + `workspaceLayer` test defaults |
| `server/test/.../WorkspaceStoreSpec.scala` | Created | 7 security regression tests |
| `server/test/.../RateLimiterSpec.scala` | Created | 3 security regression tests |
| `common/test/.../ErrorResponseSpec.scala` | Modified | Added workspace error round-trip tests (29 total) |

**Security items baked in (as planned):**
- A5: `delete(key)` and `rotate(key)` in trait ✅
- A10: `lastAccessedAt` tracking in domain model ✅
- A11: Dual timeout (absolute + idle) in `resolve()` ✅
- A14: O(1) `Map.get` lookup, constant-time documented ✅
- A29: Creation, deletion, rotation event logging ✅
- A33: Structured log fields (`workspace_key`, `event_type`) ✅

---

### Phase W.3: Workspace-Scoped Endpoints — ✅ COMPLETE

**Goal:** API surface scoped by workspace key. Bootstrap endpoint for first-time use.

**Files to create/modify:**
```
common/.../http/endpoints/WorkspaceEndpoints.scala
server/.../http/controllers/WorkspaceController.scala
common/.../http/responses/WorkspaceResponse.scala
```

**Endpoints:**

| Method | Path | Purpose | Auth |
|--------|------|---------|------|
| POST | `/workspaces` | Bootstrap: create workspace + first tree | Rate-limited, no workspace key |
| GET | `/w/{key}/risk-trees` | List trees in workspace | Workspace key |
| POST | `/w/{key}/risk-trees` | Create tree in workspace | Workspace key |
| GET | `/w/{key}/risk-trees/{treeId}` | Get tree summary (must belong to workspace) | Workspace key |
| GET | `/w/{key}/risk-trees/{treeId}/structure` | Get full tree structure | Workspace key |
| POST | `/w/{key}/risk-trees/{treeId}/nodes/lec-multi` | LEC curves | Workspace key |
| GET | `/w/{key}/risk-trees/{treeId}/nodes/{nodeId}/lec` | Single LEC curve | Workspace key |
| GET | `/w/{key}/events/tree/{treeId}` | SSE stream (A15: workspace-scoped) | Workspace key |
| POST | `/w/{key}/rotate` | Rotate: instant revoke old → new key → transfer trees | Workspace key |
| DELETE | `/w/{key}` | Hard delete workspace + cascade-delete all trees | Workspace key |
| DELETE | `/admin/workspaces/expired` | Evict expired workspaces | Admin-only (configurable gate) |

**Bootstrap endpoint (`POST /workspaces`):**
```scala
// Request: RiskTreeDefinitionRequest (same as existing create)
// Response: WorkspaceResponse
final case class WorkspaceResponse(
  workspaceKey: WorkspaceKey,
  tree: SimulationResponse,
  expiresAt: Option[Instant]  // None in enterprise mode
)
```

The bootstrap endpoint:
1. Generates a `WorkspaceKey` (128-bit `SecureRandom`)
2. Creates the risk tree via existing `RiskTreeService.create()`
3. Associates `(workspaceKey, treeId)` in `WorkspaceStore`
4. Returns the workspace key + tree response + expiry timestamp

**Workspace validation middleware:**

All `/w/{key}/*` endpoints include a workspace resolution step:
1. Extract `WorkspaceKey` from path
2. Call `WorkspaceStore.resolve(key)` — dual TTL check (absolute + idle)
3. If success → proceed (resolve already updated `lastAccessedAt`)
4. If `WorkspaceNotFound` → 404 (A13)
5. If `WorkspaceExpired` → 404 (same as not-found — A13: constant response, no timing oracle)
6. For tree-specific endpoints: verify `belongsTo(key, treeId)` → 404 if not

**A13 rationale:** At the HTTP layer, not-found and expired return identical 404 responses.
This eliminates the timing side-channel that would let an attacker distinguish "this key
never existed" from "this key existed but expired." The `WorkspaceError` ADT retains the
distinction for internal logging (A30). Deleted workspaces simply don't exist → `WorkspaceNotFound`.

**Rotation endpoint (`POST /w/{key}/rotate`):**
1. Call `WorkspaceStore.rotate(key)` — atomic key rotation (instant, no grace period)
2. Returns `WorkspaceResponse` with new workspace key + tree list + expiry
3. Old key is immediately invalid — any subsequent resolve returns `WorkspaceNotFound`
4. SSE connections on old key are dropped (server-side hub closes them)
5. Active clients must obtain the new key out-of-band (this is a feature, not a bug —
   immediate revocation is the security requirement; deferred revocation is a vulnerability)

**Hard delete endpoint (`DELETE /w/{key}`):**
1. Controller orchestrates: `store.listTrees(key)` → `repo.delete(treeId)` for each → `store.delete(key)`
2. Returns 204 No Content
3. All trees cascade-deleted from `RiskTreeRepository`
4. SSE connections on deleted workspace are dropped
5. Worst case (crash mid-delete): orphaned trees — reaper can clean up

**Existing `GET /risk-trees` (list-all):**
- Frontend: unwired (no longer called)
- Backend: sealed with configurable authorization gate
- Config: `register.api.list-all-trees.enabled = false` (default: deny)
- When `enabled = false`: returns 403 Forbidden
- When `enabled = true`: returns all trees (admin/debug use)

**Checkpoint:**
- [ ] Bootstrap `POST /workspaces` creates workspace + tree, returns workspace key
- [ ] `GET /w/{key}/risk-trees` lists only workspace-scoped trees
- [ ] `POST /w/{key}/risk-trees` creates tree within workspace
- [ ] Tree-specific endpoints validate `belongsTo` check
- [ ] Not-found and expired both return identical 404 (A13)
- [ ] `POST /w/{key}/rotate` atomically rotates key (instant), returns new key
- [ ] `DELETE /w/{key}` cascade-deletes workspace + all trees, returns 204
- [ ] Old key immediately invalid after rotate or delete — no grace period
- [ ] SSE scoped to workspace key (A15)
- [ ] SSE connections dropped on delete/rotate (A16) — deferred; client handles `WorkspaceExpired` errors
- [ ] `GET /risk-trees` blocked by default (A17)
- [ ] `IrminHttpError` sanitised — generic message to client (A24)
- [ ] `RepositoryFailure` sanitised — generic message to client (A25)
- [ ] `DELETE /admin/workspaces/expired` callable for manual eviction
- [ ] Security logging: resolve failures, deletion, rotation events (A30, A33)

---

### Phase W.4: Background Reaper & Rate Limiting

**Goal:** Storage hygiene via background eviction and abuse prevention via rate limiting.

**RateLimiter:** ✅ COMPLETE — `RateLimiterLive` with fixed-window IP throttling, HTTP 429.

**WorkspaceReaper — revised design (Feb 2026):**

The reaper is a **background effect, not a service with an API**. Nobody calls
methods on it. It uses `ZLayer.scoped` + `forkScoped` so the fiber's lifetime
is managed by the ZIO layer system — automatic graceful shutdown, no leaked
`Fiber` references.

**Files to create/modify:**
```
server/.../services/workspace/WorkspaceReaper.scala  (new)
server/.../Application.scala                          (wire layer)
server/.../services/workspace/WorkspaceReaperSpec.scala (new)
```

**Implementation:**
```scala
object WorkspaceReaper:
  val layer: ZLayer[WorkspaceStore & WorkspaceConfig, Nothing, Unit] =
    ZLayer.scoped {
      for
        config <- ZIO.service[WorkspaceConfig]
        store  <- ZIO.service[WorkspaceStore]
        _      <- reapLoop(store, config.reaperInterval)
                    .when(!isNoOp(config))
                    .forkScoped
      yield ()
    }

  // Enterprise no-op: both TTL and idleTimeout must be zero/negative
  private def isNoOp(c: WorkspaceConfig): Boolean =
    (c.ttl.isZero || c.ttl.isNegative) && (c.idleTimeout.isZero || c.idleTimeout.isNegative)

  private def reapLoop(store: WorkspaceStore, interval: Duration): UIO[Nothing] =
    (ZIO.sleep(interval) *> store.evictExpired).forever
```

**Key design decisions:**

1. **No trait / no `start` method.** The reaper has no callers — it is a
   layer-scoped daemon. `ZLayer.scoped` + `forkScoped` ties the fiber to the
   layer scope: automatic interrupt on application shutdown, zero manual
   fiber management.

2. **No `evictExpiredDetailed` / no SSE notification on eviction (Option B).**
   SSE is fire-and-forget (`Hub.sliding`). A `workspace_evicted` SSE event
   would reach only *currently connected* clients. Disconnected clients would
   miss it and must handle `WorkspaceExpired` API errors anyway. Since the
   client **must** handle `WorkspaceExpired` errors regardless, the SSE event
   adds interface complexity (`evictExpiredDetailed`, new SSE event type) with
   no reliability gain. See "Client-side error handling" below.

3. **Enterprise no-op guards both `ttl` AND `idleTimeout`.**
   `Workspace.isExpired` fires on *either* absolute or idle timeout. The
   reaper is only truly a no-op when both are disabled (zero/negative).

4. **No changes to `WorkspaceStore` trait.** The existing `evictExpired: UIO[Int]`
   is sufficient. No interface change required.

5. **Application.scala:** Only change is adding `WorkspaceReaper.layer` to
   `appLayer`. No changes to `program` — the reaper is purely a layer concern.

**Client-side error handling (approved enhancement):**

The frontend must treat `WorkspaceExpired` / `WorkspaceNotFound` API errors as
first-class concerns in its error handling layer:
- Intercept these errors in the HTTP client layer (all API calls)
- Show an "expired workspace" modal with a "Create New Workspace" action
- Clear local workspace state (router returns to landing)
- This is **the** reliability mechanism — SSE is a courtesy, error handling is the contract

This is more reliable than SSE notification because it works regardless of
connection state and requires no server-side changes beyond what already exists.

**Checkpoint:**
- [x] Reaper fiber runs at configured interval in free-tier mode ✅
- [x] Reaper is no-op when both `ttl` and `idleTimeout` are zero/negative ✅
- [x] Evicted workspaces are logged (A31 — already in `evictExpired`) ✅
- [x] Rate limiter returns 429 when threshold exceeded (A27) ✅
- [ ] Rate limiter covers resolve attempts per IP (A28)
- [x] Rate-limit trigger events logged (A32) ✅
- [x] Reaper fiber shuts down gracefully with application (automatic via `forkScoped`) ✅
- [ ] Client handles `WorkspaceExpired` errors as first-class concern

**A16 status (SSE lifecycle on eviction):** Deferred. The reaper does **not**
send SSE events on eviction — client-side error handling is the reliability
mechanism. A16 may be revisited if heartbeat-based workspace health checks are
added in a future phase (the server would send periodic heartbeats, and the
client would probe workspace validity on reconnect).

---

### Phase W.5: Security Headers & CORS Hardening

**Goal:** Prevent workspace key leakage, harden CORS, add token fingerprint binding.

**Headers applied to all `/w/*` and `/workspaces` responses (A1-A4):**

| Header | Value | Purpose |
|--------|-------|---------|
| `Referrer-Policy` | `no-referrer` | Prevent workspace key leaking via Referer header when clicking external links |
| `Cache-Control` | `no-store` | Prevent proxy/CDN caching of responses containing workspace key |
| `X-Content-Type-Options` | `nosniff` | Standard security header |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | Enforce HTTPS (A4) |

**Global security headers (A21-A23) — applied to ALL responses:**

| Header | Value | Purpose |
|--------|-------|---------|
| `X-Frame-Options` | `DENY` | Prevent clickjacking (A21) |
| `X-XSS-Protection` | `0` | Disable legacy XSS filter — modern CSP preferred (A23) |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'` | XSS mitigation (A22 — audit SPA assets for inline scripts) |

**CORS hardening (A18-A20):**
- Remove `reflectHeaders` from `CORSConfig` — replace with explicit origin whitelist (A18)
- Config-driven origin list: `register.cors.allowed-origins = ["http://localhost:5173"]` (A19)
- Add `Access-Control-Max-Age: 3600` for preflight caching (A20)

**Token fingerprint binding (A8-A9):**
- On workspace creation, generate a random fingerprint → hash it → store hash in `Workspace`
- Set `__Host-wsfp=<fingerprint>` cookie (`Secure; HttpOnly; SameSite=Strict; Path=/`)
- On `resolve()`, validate that cookie hash matches stored hash
- Prevents sidejacking if workspace key is intercepted (attacker lacks the cookie)
- Free-tier only (enterprise uses JWT with its own binding mechanisms)

**Implementation:** Tapir server interceptor or middleware that matches `/w/*` paths and appends headers.

**HTTPS enforcement:** Documented as requirement. In production, Istio handles TLS termination. For standalone deployment, reverse proxy (nginx/caddy) required.

**Checkpoint:**
- [ ] `Referrer-Policy: no-referrer` on all workspace responses
- [ ] `Cache-Control: no-store` on all workspace responses
- [ ] HTTPS enforcement documented

---

### Phase W.6: Frontend Workspace Flow — ✅ COMPLETE

**Goal:** Frontend workspace-aware routing, tree list within workspace, bootstrap UX.

**Files to create/modify:**
```
app/.../core/Router.scala
app/.../state/WorkspaceState.scala
app/.../views/TreeListView.scala  (modify: workspace-scoped)
app/.../views/TreeBuilderView.scala  (modify: workspace-aware submit)
app/.../views/WorkspaceBanner.scala
app/.../Main.scala  (modify: routing + workspace state)
```

**Router (client-side hash routing):**
```scala
object Router:
  /** Parse workspace key from URL hash.
    * `/#/{workspaceKey}`          → Some(workspaceKey)
    * `/#/{workspaceKey}/tree/{id}` → Some(workspaceKey), Some(treeId)
    * `/#/`                         → None (landing page)
    */
  def parseHash(hash: String): Route = ...

  /** Update URL hash without page reload. */
  def navigateTo(route: Route): Unit =
    dom.window.location.hash = route.toHash
```

**WorkspaceState:**
```scala
final class WorkspaceState:
  val workspaceKey: Var[Option[WorkspaceKey]] = Var(None)
  val expiresAt: Var[Option[Instant]] = Var(None)
```

**User flow:**

1. **Landing page (`/#/`):** User sees tree builder form. No workspace key yet.
2. **First submit:** Frontend calls `POST /workspaces` (bootstrap). Receives `WorkspaceResponse` with workspace key.
3. **Redirect:** Frontend navigates to `/#/{workspaceKey}`. URL now contains the capability.
4. **Workspace loaded:** Tree list dropdown appears (populated via `GET /w/{key}/risk-trees`). The just-created tree is shown.
5. **Subsequent creates:** Frontend calls `POST /w/{key}/risk-trees`. Tree added to existing workspace.
6. **Sharing:** User copies URL. Recipient opens `/#/{workspaceKey}` → sees the same workspace.
7. **Return visit:** User bookmarks URL. On return, workspace loads from URL hash.

**WorkspaceBanner:**
- Displays workspace key (truncated) and expiry countdown: "Workspace expires in 71h 42m"
- Copy-link button for sharing
- In enterprise mode (no TTL): no banner shown

**TreeListView changes:**
- `loadTreeList()` now calls workspace-scoped `GET /w/{key}/risk-trees` instead of `GET /risk-trees`
- Dropdown preserved — shows trees within the workspace
- No change to `TreeDetailView`

**TreeBuilderView changes:**
- Submit flow branched:
  - No workspace key → call `POST /workspaces` (bootstrap) → navigate to `/#/{key}`
  - Has workspace key → call `POST /w/{key}/risk-trees` → refresh tree list

**Checkpoint:**
- [ ] Landing page renders tree builder (no workspace key)
- [ ] First submit creates workspace + tree, redirects to `/#/{workspaceKey}`
- [ ] URL contains workspace key after creation
- [ ] Tree list loads workspace-scoped trees
- [ ] Subsequent tree creates add to existing workspace
- [ ] Return visits restore workspace from URL hash
- [ ] Workspace banner shows expiry countdown
- [ ] Sharing URL gives recipient full workspace access

---

### Phase W.7: Tests — ✅ COMPLETE

**Goal:** Test coverage for workspace lifecycle, TTL, reaping, rate limiting.

**Test files:**
```
server/.../service/workspace/WorkspaceStoreSpec.scala
server/.../service/workspace/WorkspaceReaperSpec.scala
server/.../service/workspace/RateLimiterSpec.scala
server/.../http/controllers/WorkspaceControllerSpec.scala
common/.../domain/data/iron/WorkspaceKeySpec.scala
```

**Test targets:**

| Spec | Tests |
|------|-------|
| `WorkspaceKeySpec` | Generate produces 22-char base64url; fromString validates; round-trip JSON |
| `WorkspaceStoreSpec` | Create + resolve; addTree + listTrees; belongsTo check; expired → WorkspaceExpired; idle timeout → WorkspaceExpired; evictExpired removes correct entries; enterprise mode (infinite TTL) never expires; delete cascade-removes workspace; rotate instant-invalidates old key + transfers trees |
| `WorkspaceReaperSpec` | Reaper evicts after TTL; reaper is no-op in enterprise mode; logging on eviction |
| `RateLimiterSpec` | Under limit → success; over limit → 429; window resets after hour |
| `WorkspaceControllerSpec` | Bootstrap creates workspace; scoped list returns correct trees; expired workspace → 404; invalid key → 404 (identical responses — A13); belongsTo rejects cross-workspace access; DELETE cascade-deletes trees; rotate returns new key + old key dead |

**Checkpoint:**
- [ ] `sbt server/test` passes with workspace tests
- [ ] TTL logic tested with `TestClock` (advance time → verify expiry)
- [ ] Rate limiter tested with counter assertions
- [ ] Enterprise mode (infinite TTL) tested

---

### Phase W.7b: HTTP Integration Tests for Security Semantics (Optional Improvement)

**Goal:** Verify security-critical behaviour through the full Tapir HTTP stack, not just unit tests against service traits.

**Motivation:** The W.2 unit tests validate `WorkspaceStore` and `RateLimiter` in isolation. However, several security properties are only observable at the HTTP boundary:

- **A17:** `GET /risk-trees` returns 403 when `listAllTreesEnabled = false` — requires the endpoint to be wired with the `ApiConfig` gate
- **A6:** `DELETE /w/{key}` cascade-deletes all associated trees — requires the controller's orchestration through `RiskTreeService.delete`
- **A13:** Not-found and expired workspaces return identical 404 responses — requires `ErrorResponse.encode` integration with Tapir's error output

These are currently verified indirectly (unit tests on `ErrorResponse.encode`, `WorkspaceStoreLive`, etc.) but not end-to-end through a Tapir server interpreter.

**Approach:** Use the existing `server-it` module's `HttpTestHarness` (random-port test server) to add HTTP-level assertions:

| Test | Asserts |
|------|---------|
| `GET /risk-trees` with gate=false | 403 Forbidden, body matches `ErrorResponse` |
| `DELETE /w/{key}` cascade | 204 No Content; subsequent `GET /w/{key}/risk-trees` → 404 |
| Expired workspace → 404 | Same status + body as non-existent workspace |
| Cross-workspace tree access → 404 | Tree exists but wrong workspace key → 404 |

**Priority:** Low — the unit tests cover the logic. This is a defence-in-depth measure.
Implement opportunistically when adding `WorkspaceController` HTTP wiring in W.3.

---

### Phase W.8: API Content-Type Correctness — ✅ COMPLETE

**Goal:** Fix `lec-chart` endpoint Content-Type from `text/plain` to `application/json`.

**Problem:** The `lec-chart` endpoint uses Tapir's `stringBody` which defaults to `Content-Type: text/plain`. The response is a valid JSON string (Vega-Lite spec), so the correct Content-Type is `application/json`. This matters for:
1. **Browser fetch API** — `response.json()` works only with `application/json` Content-Type (some implementations are strict)
2. **API documentation** — Swagger/OpenAPI shows `text/plain` which is misleading
3. **Middleware** — compression, caching, and CORS middleware may treat `text/plain` differently from `application/json`

**Why `stringBody` was chosen:** `LECChartSpecBuilder.generateMultiCurveSpec` returns a pre-built JSON string. Using Tapir's `jsonBody[String]` would double-encode it (wrapping the JSON in quotes as a JSON string literal). The correct approach is `stringBody` with an explicit content type override.

**Fix** (single line in `RiskTreeEndpoints.scala`):
```scala
// Before:
.out(stringBody.description("Vega-Lite JSON specification"))

// After:
.out(stringBody.description("Vega-Lite JSON specification")
  .contentType(sttp.model.MediaType.ApplicationJson))
```

**Checkpoint:**
- [ ] `lec-chart` response has `Content-Type: application/json`
- [ ] Swagger UI shows correct media type
- [ ] VegaEmbed frontend still parses response correctly

---

### Tier 1.5 Dependency Graph

```
Phase W.1 (WorkspaceKey type)
  ↓
Phase W.2 (WorkspaceStore + config)
  ↓
Phase W.3 (Workspace endpoints + controller) ←── Phase W.4 (Reaper + Rate limiter)
  ↓                                                 ↓
Phase W.5 (Security headers)                    Phase W.7 (Tests)
  ↓                                                 ↑
Phase W.6 (Frontend workspace flow) ────────────────┘
```

### Changes to Existing Phases (Summary)

| Existing Component | Change | Phase |
|-------------------|--------|-------|
| `TreeListView` | Calls workspace-scoped endpoint instead of `getAllEndpoint` | W.6 |
| `TreeBuilderView` | Bootstrap submit vs workspace submit branching | W.6 |
| `TreeViewState` | `loadTreeList()` accepts workspace key parameter | W.6 |
| `Main.scala` (frontend) | Adds `Router` + `WorkspaceState`, conditional rendering | W.6 |
| `Application.scala` (server) | Starts reaper fiber | W.4 |
| `loadInto` / `loadOptionInto` (ZJS) | No change — workspace endpoints use same pattern | — |
| `getAllEndpoint` (backend) | Sealed with configurable auth gate (default deny) | W.3 |
| ADR-021 | Amended: `ShareToken` → `WorkspaceKey`, `DemoStore` → `WorkspaceStore` | W.1 |

### Phase W.9: Telemetry Span Regression Audit — ✅ COMPLETE

**Background:** `RiskTreeServiceLive` methods use a repeated boilerplate pattern for OpenTelemetry tracing — each method wraps its body in `ZIO.serviceWithZIO[Tracing] { tracing => tracing.span(...)(body) }`. This was identified during the Phase E code review as a pre-existing pattern that adds ~5 lines of ceremony per method and obscures the business logic.

**Problem:** The pattern is copy-pasted across every service method (≈12 occurrences in `RiskTreeServiceLive`). If the tracing API changes or the span attribute conventions evolve, every method must be updated individually. The pattern also makes it easy to accidentally nest spans or forget to propagate the span context.

**Proposed fix:** Extract a reusable `traced` combinator (or ZIO aspect) that captures the span-name + attribute-extraction pattern once:
```scala
// Example approach — evaluate at implementation time
private def traced[R, E, A](name: String, attrs: (String, String)*)(zio: ZIO[R, E, A]): ZIO[R & Tracing, E, A] =
  ZIO.serviceWithZIO[Tracing](_.span(name, attrs*)(zio))
```
This would reduce each call site from ~5 lines to 1 line while preserving identical trace output.

**Scope:**
1. Audit all `RiskTreeServiceLive` methods for the boilerplate pattern
2. Design the combinator (standalone function, extension method, or ZIO aspect)
3. Refactor all occurrences
4. Verify trace output is unchanged (same span names, same attributes)
5. Run full test suite — no behavioural change expected

**Checkpoint:**
- [ ] All tracing call sites use the new combinator
- [ ] `sbt server/test` passes (223+ tests)
- [ ] Manual verification: trace output unchanged in collector

### Phase W.10: Submit-Time Error Border Routing (UX Polish) — ✅ COMPLETE

**Depends on:** None (independent of I.a). Must be completed before W.10b.

**Background:** Form fields (`RiskLeafFormView`, `PortfolioFormView`) have two error display paths:
1. **Reactive validation signals** — per-field `Signal[Option[String]]` from `RiskLeafFormState` / `PortfolioFormState` (e.g., `nameError`, `probabilityError`). These drive both error messages *and* red border CSS (`cls.toggle("error") <-- errorSignal.map(_.isDefined)`).
2. **Submit-time topology errors** — validation failures from `TreeBuilderLogic` (e.g., duplicate node name, missing parent, root conflict). These are caught in `handleSubmit` and routed to a `submitError: Var[Option[String]]` rendered as text at the form bottom.

**Problem:** When a submit-time error has a `field` attribute (e.g., `ValidationError("tree.names", DUPLICATE_VALUE, "Duplicate names: Server")`) that corresponds to a form field, the user sees the error message at the bottom of the form but the offending field has **no red border** — because the error is not injected into the per-field `errorSignal`.

**Reproduction:**
1. Create a tree with a leaf node named "Server".
2. Add a second leaf node also named "Server".
3. Click "Add Risk Leaf" → error message appears at form bottom: "Duplicate names: Server".
4. Expected: the Name field should also have a red border.
5. Actual: no red border — the Name field's `nameError` signal is `None` (the name passes SafeName validation individually; the duplicate is a topology-level concern).

**Root cause:** Two disconnected error channels. The reactive signals (`RiskLeafFormState.nameError`, etc.) derive from current field values. Submit-time topology errors route to a separate `submitError: Var[Option[String]]` rendered only as text at the form bottom. There is no mechanism to inject an imperative error into the per-field `Signal` that drives the red border CSS.

**Design — three parts:**

**Part 1: `submitFieldErrors` in `FormState` (infrastructure)**

Add to `FormState.scala`:
```scala
private val submitFieldErrors: Var[Map[String, String]] = Var(Map.empty)

def setSubmitFieldError(fieldName: String, message: String): Unit =
  submitFieldErrors.update(_ + (fieldName -> message))

def withSubmitErrors(fieldName: String, rawError: Signal[Option[String]]): Signal[Option[String]] =
  val submitErr = submitFieldErrors.signal.map(_.get(fieldName))
  withDisplayControl(fieldName, rawError.combineWith(submitErr).map {
    case (reactive, submitted) => reactive.orElse(submitted)
  })
```

**Stale-state prevention:** `triggerValidation()` must clear `submitFieldErrors` at the start of
each submit cycle. This is the single correct reset point — it means "new submit cycle begins,
prior submit errors are stale." Between submits, the reactive validation signal takes over as
soon as the user types, so per-field `changes -->` listeners do not need to know about
`submitFieldErrors`.
```scala
def triggerValidation(): Unit =
  showErrorsVar.set(true)
  submitFieldErrors.set(Map.empty)  // ← reset stale submit errors
```

**Field key type:** Uses `String` keys matching the existing `markTouched(fieldName: String)` /
`withDisplayControl(fieldName: String, ...)` convention. This is a deliberate choice to keep
W.10 minimal. W.10b replaces all string keys with per-form field enums for compile-time safety.

**Part 2: `formFieldFor` in `TreeBuilderLogic` (field mapping)**

Add to `TreeBuilderLogic.scala` (common module, testable on JVM):
```scala
/** Map a topology validation field to the form field it should highlight.
  * Returns None for structural errors that have no corresponding form field
  * (e.g., "tree.portfolios" empty-collection error).
  *
  * Co-located with the validation rules that produce these field strings
  * so that adding a new validation rule forces updating the mapping.
  */
def formFieldFor(validationField: String): Option[String] =
  validationField match
    case "tree.names"                          => Some("name")
    case f if f.endsWith(".parentName")        => Some("parent")
    case f if f.startsWith("tree.portfolios")  => None  // structural
    case f if f.startsWith("tree.leaves")      => None  // structural
    case f if f.startsWith("tree")             => None  // structural
    case _                                     => None
```

This replaces the fragile `contains`-based heuristic. `endsWith` is safe because `TreeBuilderLogic`
constructs field strings as `"prefix[name].fieldName"` — the form-relevant part is always the
dot-separated suffix.

**Part 3: Wire into `handleSubmit` (view layer)**

In `PortfolioFormView.handleSubmit` and `RiskLeafFormView.handleSubmit`, replace the current
pattern that discards field information:
```scala
// Before (discards field → form mapping):
case Validation.Failure(_, errs) =>
  submitError.set(Some(errs.head.message))

// After (routes field-bound errors to red borders):
case Validation.Failure(_, errs) =>
  val allErrors = errs.toList
  allErrors.foreach { err =>
    TreeBuilderLogic.formFieldFor(err.field) match
      case Some(formField) => form.setSubmitFieldError(formField, err.message)
      case None            => () // structural, stays in submitError
  }
  // Non-field-bound errors still go to the banner
  val bannerErrors = allErrors.filter(e => TreeBuilderLogic.formFieldFor(e.field).isEmpty)
  submitError.set(bannerErrors.headOption.map(_.message))
```

**Note on empty-portfolio errors:** The `EMPTY_COLLECTION` error (`"tree.portfolios"`) is
structural — it means "portfolio X has no children." There is no form field to highlight
because the portfolio form that created that node has already reset. This error correctly
stays in the submit-error banner in `TreeBuilderView`. A future enhancement could highlight
the portfolio name in `TreePreview`, but that is out of scope for W.10.

**Subclass changes:**

In `PortfolioFormState.scala`, change:
```scala
// Before:
val nameError = withDisplayControl("name", nameErrorRaw)
// After:
val nameError = withSubmitErrors("name", nameErrorRaw)
```

In `RiskLeafFormState.scala`, change all `withDisplayControl` calls to `withSubmitErrors`:
```scala
val nameError = withSubmitErrors("name", nameErrorRaw)
val probabilityError = withSubmitErrors("probability", probabilityErrorRaw)
// ... etc for all per-field error signals
```

**Affected files:**
- `FormState.scala` — add `submitFieldErrors` Var, `setSubmitFieldError`, `withSubmitErrors`; modify `triggerValidation`
- `TreeBuilderLogic.scala` — add `formFieldFor` method
- `TreeBuilderLogicSpec.scala` — add tests for `formFieldFor`
- `PortfolioFormState.scala` — `withDisplayControl` → `withSubmitErrors` for name
- `RiskLeafFormState.scala` — `withDisplayControl` → `withSubmitErrors` for all fields
- `PortfolioFormView.scala` — `handleSubmit` routes field-bound errors via `formFieldFor`
- `RiskLeafFormView.scala` — same

**Scope:** UX polish only — no backend changes, no API changes.

**Checkpoint:**
- [ ] Duplicate leaf name shows red border on Name field
- [ ] Missing parent reference shows red border on Parent field
- [ ] Error clears on next submit (via `triggerValidation` reset)
- [ ] Existing reactive validation (blank name, invalid probability) still works
- [ ] `formFieldFor` has JVM test coverage in `TreeBuilderLogicSpec`
- [ ] `sbt app/fastLinkJS` compiles

---

### Phase W.10b: Type-Safe FormState Field Enums (Refactor) — ✅ COMPLETE

**Depends on:** W.10 (uses the `submitFieldErrors` infrastructure introduced there).

**Problem:** All field references in `FormState` are stringly-typed:
```scala
markTouched("name")                          // typo → silent no-op
withDisplayControl("name", nameErrorRaw)     // "name" vs "Name" → silent mismatch
setSubmitFieldError("nmae", msg)             // typo → error never reaches field
```

There is no compile-time check that a field name used in a view matches a field name
used in a form state. This applies to the existing `touchedFields: Var[Set[String]]`,
`withDisplayControl(fieldName: String, ...)`, and the new `submitFieldErrors: Var[Map[String, String]]`
from W.10.

**Proposed fix:** Parameterize `FormState` with a per-form field enum:

```scala
trait FormState[F]:
  private val touchedFields: Var[Set[F]] = Var(Set.empty)
  private val submitFieldErrors: Var[Map[F, String]] = Var(Map.empty)

  def markTouched(field: F): Unit =
    touchedFields.update(_ + field)

  def shouldShowError(field: F): Signal[Boolean] =
    touchedFields.signal.map(_.contains(field))

  def withDisplayControl(field: F, rawError: Signal[Option[String]]): Signal[Option[String]] =
    shouldShowError(field).combineWith(rawError).map {
      case (true, error) => error
      case (false, _)    => None
    }

  def withSubmitErrors(field: F, rawError: Signal[Option[String]]): Signal[Option[String]] =
    val submitErr = submitFieldErrors.signal.map(_.get(field))
    withDisplayControl(field, rawError.combineWith(submitErr).map {
      case (reactive, submitted) => reactive.orElse(submitted)
    })
```

Each form defines its own enum:
```scala
enum PortfolioField:
  case Name, Parent

class PortfolioFormState extends FormState[PortfolioField]:
  val nameError = withSubmitErrors(PortfolioField.Name, nameErrorRaw)
```

The `formFieldFor` mapping in `TreeBuilderLogic` returns `Option[PortfolioField]` (or a
union type / generic `F`) instead of `Option[String]`.

**Scope analysis:**
- `FormState.scala` — add type parameter `[F]` to trait, update all method signatures
- `PortfolioFormState.scala` — define `PortfolioField` enum, change `extends FormState` to `extends FormState[PortfolioField]`
- `RiskLeafFormState.scala` — define `RiskLeafField` enum (8+ variants: Name, Probability, Percentiles, Quantiles, MinLoss, MaxLoss, etc.), change extends
- `TreeBuilderState.scala` — define `TreeBuilderField` enum (TreeName), change extends
- `PortfolioFormView.scala` — `markTouched("name")` → `markTouched(PortfolioField.Name)`, update `formFieldFor` call
- `RiskLeafFormView.scala` — same pattern for all field references
- `TreeBuilderView.scala` — `markTouched("treeName")` → `markTouched(TreeBuilderField.TreeName)`
- `TreeBuilderLogic.scala` — `formFieldFor` return type changes (design decision: see below)

**Open design decision — `formFieldFor` return type:**
`TreeBuilderLogic` lives in `common` (shared JVM/JS). It cannot import `PortfolioField` or
`RiskLeafField` (those are app-module types). Options:
1. **Keep `formFieldFor` returning `Option[String]`**, map to enum at call site in the view.
   Pro: no coupling. Con: one string↔enum mapping point remains.
2. **Move field enums to `common`** so `formFieldFor` can return them.
   Pro: fully typed end-to-end. Con: `common` now knows about GUI field names.
3. **`formFieldFor` returns a generic tag** (e.g., `FormFieldTag` enum in common),
   and each form state maps `FormFieldTag → F`. Pro: typed without coupling. Con: extra
   indirection.

Recommendation: Option 1 (keep string, map at call site). The mapping is a one-liner in
the view and avoids coupling common-module to GUI concerns.

**Checkpoint:**
- [ ] `markTouched(SomeField.Typo)` is a compile error
- [ ] `setSubmitFieldError(SomeField.Typo, msg)` is a compile error
- [ ] All existing tests pass unchanged
- [ ] `sbt app/fastLinkJS` compiles

### Phase W.11: Typed Vega-Lite DSL Migration (LECChartSpecBuilder) — ✅ INTERMEDIATE

**Background:** DP-12 describes `LECChartSpecBuilder` as using "a typed DSL" for Vega-Lite spec construction. The initial implementation used string interpolation (Phase E). A Phase F code review identified this as a plan-vs-reality gap: string interpolation is fragile for JSON construction (quoting bugs, no structural guarantees).

**Current state (intermediate step — completed):** `LECChartSpecBuilder` now uses `zio.json.ast.Json` AST construction (`Json.Obj`, `Json.Arr`, `Json.Str`, `Json.Num`, `Json.Bool`) instead of string interpolation. This eliminates:
- Manual `escapeJson()` — the AST handles escaping via `.toJson`
- Triple-quote ambiguity bugs (e.g., `s""""${name}""""``)
- Invalid JSON from missing commas or mismatched brackets

**Remaining work (deferred — low priority):** Full typed DSL with case classes mirroring Vega-Lite schema:
```scala
// Example target (not yet implemented):
case class VegaLiteSpec(schema: String, width: Int, height: Int, layer: Seq[Layer], ...)
case class Layer(mark: Mark, encoding: Encoding, data: Data)
case class Mark(tpe: MarkType, interpolate: Option[Interpolate], ...)
// ...generates Json via derived codecs
```

**Effort estimate:**
| Step | Effort | Status |
|------|--------|--------|
| JSON AST intermediate (eliminate string interpolation) | ~2 hours | ✅ Complete |
| Typed DSL case classes + derived codecs | ~2 days | ⬜ Deferred |

**Why defer the full DSL:** The AST approach is structurally sound (valid JSON guaranteed by construction). A full typed DSL adds compile-time schema enforcement but requires modeling the Vega-Lite schema — high effort for marginal safety gain at this stage. Revisit when spec builder grows beyond ~200 lines or when adding new chart types.

**Affected files:**
- `server/.../simulation/LECChartSpecBuilder.scala` — ✅ migrated to `zio.json.ast.Json`
- `server/.../simulation/LECChartSpecBuilderSpec.scala` — ✅ tests updated to AST-based assertions (20 tests)

**Checkpoint:**
- [x] `LECChartSpecBuilder` uses `zio.json.ast.Json` AST (no string interpolation)
- [x] `escapeJson` helper removed (AST handles escaping)
- [x] Color mapping keyed by `id: String` (not full `LECNodeCurve` case class)
- [x] All 20 tests pass (`sbt server/testOnly *LECChartSpecBuilderSpec`)
- [ ] (Deferred) Typed DSL case classes with derived codecs

---

## Deferred: SSE Phases (Post Tier 1.5)

These phases were originally on the Tier 1 critical path but have been deferred.
In a single-user demo environment SSE push notifications add no value — the user
mutates data, manually re-selects, and sees the updated chart. SSE becomes
meaningful in **Tier 2** (Irmin watch → cache invalidation) and **Tier 3**
(multi-user collaboration). Grouping them here avoids blocking the workspace
capability work that is actually needed for public deployment.

### Phase H: SSE Cache Invalidation

**Goal:** Subscribe to SSE events so the frontend knows when displayed LEC data is stale.

**Context:** The backend already publishes `SSEEvent.CacheInvalidated` events via `SSEHub` + `InvalidationHandler`. The frontend can subscribe when multi-user or Irmin watch is live.

**Files to create/modify:**
```
modules/app/src/main/scala/app/
├── api/SSEClient.scala
└── state/LECChartState.scala  # Extend with stale tracking (already exists from SRP split)
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

**LECChartState** extension (stale tracking — builds on existing SRP split):
```scala
// LECChartState already owns chartNodeIds + lecChartSpec.
// Phase H adds stale-tracking fields:
class LECChartState(...):
  // ... existing fields ...
  val staleNodes: Var[Set[NodeId]] = Var(Set.empty)

  def markAllStale(): Unit =
    staleNodes.set(chartNodeIds.now())

  // On CacheInvalidated → mark stale → re-fetch visible nodes
```

**Checkpoint:**
- [ ] SSE connection established on tree selection
- [ ] `CacheInvalidated` events trigger LEC re-fetch for visible nodes
- [ ] Stale indicators shown while re-fetching

### Phase I.b: SSE Reconnection

**Goal:** Resilient SSE connection with exponential backoff. Split from Phase I — the non-SSE error handling (I.a) is on the critical path; this part depends on Phase H.

**SSE reconnection with exponential backoff:**
- Max 10 retries, delays from 1s to 30s
- Error banner shows reconnection status
- After max retries: "Unable to connect. Please refresh."

**Checkpoint:**
- [ ] SSE auto-reconnects with exponential backoff
- [ ] Reconnection status shown in error banner

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

## Phase X: Capability URL Demo Mode — SUPERSEDED

**Status:** Superseded by Tier 1.5 (Workspace Capability & Access Control) as of 2026-02-13.

Phase X's features (TTL, reaping, rate limiting, security headers, capability URLs) have been combined with the workspace model and relocated to **Tier 1.5 (Phases W.1–W.7)** above. Key changes:

- `ShareToken` → `WorkspaceKey` (same crypto: 128-bit SecureRandom, base64url)
- `DemoStore` → `WorkspaceStore` (adds tree grouping, workspace lifecycle)
- `/demo/*` routes → `/w/{key}/*` routes (workspace-scoped)
- TTL and reaper preserved unchanged
- Rate limiting preserved unchanged
- Security headers preserved unchanged
- Frontend demo route → workspace-aware routing with `/#/{workspaceKey}/...`

See [AUTHORIZATION-PLAN.md](./AUTHORIZATION-PLAN.md) for Layers 1–2 (identity + fine-grained authorization).

---

### Original Task X.1 (for reference — do not implement)

```
common/.../domain/data/ShareToken.scala — SUPERSEDED by WorkspaceKey
```

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
| ADR-021 | Capability URLs | Proposed → Amend | Workspace capability model; `ShareToken` → `WorkspaceKey`; Phase X → Tier 1.5 |

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

## Technical Debt / Follow-Up Tasks

### TD-1: Convert `commonDependencies` from `%%` to `%%%`

**Status:** Open  
**Discovered:** 2026-02-12 (Phase B.1)  
**Priority:** Medium

`commonDependencies` in `build.sbt` uses `%%` for all entries. In a `crossProject`, `%%` does NOT auto-expand to `%%%` — it resolves the JVM artifact for both platforms. The `app` project masks this by re-declaring most deps with `%%%`, but it's fragile: any `common`-only dependency without a matching `app` `%%%` declaration will fail at ScalaJS link time (as happened with `zio-ulid`). All `commonDependencies` entries that are cross-published for ScalaJS should use `%%%`.

### TD-2: Remove redundant Iron regex in `ValidationUtil.refineUlid`

**Status:** Open  
**Discovered:** 2026-02-12 (Phase B.1)  
**Priority:** Low

`refineUlid` performs two sequential checks: (1) `ULID(normalized)` — library validates length, Crockford Base32 charset, and 128-bit overflow; (2) `.refineEither[Match["^[0-9A-HJKMNP-TV-Z]{26}$"]]` — Iron regex on the library's canonical output. Step 2 is strictly weaker than step 1 (no overflow check) and operates on the library's own output, making it redundant. Prefer the library check per ADR-001 (validation via smart constructors / dedicated libraries). Step 2 can be removed.

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
| — | Session/auth | Workspace capability first | 2026-02-13 | Originally "skip entirely" (2026-01-13). Updated: workspace-key capability for free-tier; identity-based auth for enterprise. See DP-7. |
| — | Irmin resilience | Service mesh (ADR-012) | 2026-01-17 | No app-level retries; Istio handles |
| — | Irmin dev image | Alpine (distroless deferred) | 2026-01-17 | ~650 MB dev image; <50 MB target for prod |
| — | Repository selection | Config-driven (`repositoryType`) | 2026-01-20 | Default `in-memory`; `irmin` available |
| DP-6 | Demo access model | Workspace capability (updated) | 2026-02-13 | `WorkspaceKey` (128-bit SecureRandom); workspace groups trees; TTL + reaper; replaces Phase X `ShareToken` model |
| DP-7 | Layered authorization | Three layers, single codebase | 2026-02-13 | Layer 0: workspace capability (Tier 1.5). Layer 1: Keycloak + OPA (AUTHORIZATION-PLAN.md). Layer 2: SpiceDB/OpenFGA (AUTHORIZATION-PLAN.md). Config-driven mode switching. |
| DP-8 | Reaping strategy | Combined (lazy check + reaper fiber) | 2026-02-13 | Lazy TTL check on access → "expired" UX. Background ZIO fiber → storage hygiene. Admin endpoint for external CronJob. |
| DP-9 | Workspace persistence | PostgreSQL (planned) | 2026-02-13 | In-memory TrieMap initially. PG implementation follows cheleb demo patterns. Config-selectable. |
| DP-10 | `GET /risk-trees` (list-all) | Configurable auth gate | 2026-02-13 | Default deny. Config: `register.api.list-all-trees.enabled = false`. Frontend unwired. |
| DP-11 | URL scheme consistency | Same workspace key URL everywhere | 2026-02-13 | URL `/#/{workspaceKey}/...` is identical across free-tier and enterprise. Enterprise adds JWT as additional gate — leaked URL alone insufficient. No URL scheme change between layers. |
| DP-12 | Chart generation strategy | Server-side (BCG pattern) | 2026-02-13 | Server constructs complete Vega-Lite JSON spec via `zio.json.ast.Json` AST (intermediate step; typed DSL deferred — see W.11). Client only renders via VegaEmbed. Tick recalculation + quantile annotation = single server concern. JVM-testable. |
| DP-13 | `lec-multi` vs `lec-chart` coexistence | Both endpoints coexist | 2026-02-13 | `lec-multi` = data API (raw curves + quantiles, for API consumers/tests). `lec-chart` = presentation API (render-ready Vega-Lite spec, for GUI). `lec-chart` composes on same service method — no duplication. |
| DP-14 | X-axis scale | Linear default (not log) | 2026-02-13 | Linear scale with `labelExpr` B/M formatting. Log scale may be added as toggle in future phase. |
| DP-15 | Interpolation method | `monotone` default, toggleable | 2026-02-13 | Hermite spline preserves monotonicity of LEC curves. `basis`/`cardinal` can overshoot. Toggle via Vega-Lite `params` bind (client-side, no server round trip). |
| DP-16 | Vega-Lite API version | v6 (`params` API) | 2026-02-13 | Prototype uses deprecated v4 `selection` syntax. Migrate to v6 `params` API. NPM deps: vega ^6.2.0, vega-lite ^6.4.1, vega-embed ^7.1.0. |
| DP-17 | Domain type naming review | Deferred (Phase J) | 2026-02-13 | `RiskLeaf`, `RiskPortfolio`, `RiskResult`, `SimulationResponse` candidates for rename. Pattern TBD — requires semantic analysis. Low priority; execute as isolated refactoring after Tier 1 feature work. |
| — | Per-node vs per-tree storage | Per-node (Option A) | 2026-01-20 | Fine-grained Irmin watch notifications identify exact node changed → O(depth) ancestor invalidation. Per-tree storage would require full tree diff on every change. |

---

---

## Nice to Have

Items below are deprioritised based on cost-benefit analysis. They can be
picked up opportunistically but are not gating any tier.

### Phase G: App-Module Testing

**Original goal:** Unit test coverage for `TreeBuilderState`, `FormState`,
`TreeViewState`, and `LECChartState` via `sbt app/test`.

**Why deprioritised — cost-benefit analysis:**

The app module is a **ScalaJS project** (`enablePlugins(ScalaJSPlugin)`).
Every state class depends on Laminar reactive primitives (`Var`, `Signal`,
`StrictSignal`) and the ZJS bridge (`FetchZioBackend`, browser Fetch API).
These have **no JVM implementations** — tests must run under a ScalaJS
test runner (jsdom / Node.js), not plain `sbt test`.

| Cost | Detail |
|------|--------|
| jsEnv infrastructure | Configure `scalajs-env-jsdom-nodejs` in build.sbt; install Node.js + jsdom in CI |
| Mock backend layer | `ZJS.forkProvided` calls `FetchZioBackend` — requires a test `BackendClient` stub layer |
| Ongoing maintenance | ScalaJS test toolchain is a second CI axis to keep green |

| Benefit | Detail |
|---------|--------|
| Low | The testable **business logic** (`TreeBuilderLogic`, `ValidationUtil`, domain model validation, `LECGenerator`, `LECChartSpecBuilder`) already lives in the `common` and `server` modules and has **44+ JVM tests** with full coverage |
| Low | The state classes are **thin reactive wiring** — they glue Laminar `Var`/`Signal` to backend calls via `ZJS`. There is very little logic that isn't already validated by common/server module tests + integration tests |

**Conclusion:** The ROI of standing up a ScalaJS test harness for thin
reactive glue code is poor while the underlying logic is already well-tested
on the JVM. If E2E coverage becomes a priority, **Playwright browser tests**
(Phase G.2, integration testing) would provide far higher confidence per unit
of effort.

**Checkpoint (unchanged, for future reference):**
- [ ] `sbt app/test` runs successfully
- [ ] Validation rules have test coverage
- [ ] State transitions tested

---

*Document created: February 10, 2026*
*Consolidates: APP-IMPLEMENTATION-PLAN.md, PLAN-SPLIT-PANE-LEC-UI.md, IMPLEMENTATION-PLAN-PROPOSALS.md, RISKTREE-REPOSITORY-IRMIN-PLAN.md*
*Related (kept): IRMIN-INTEGRATION.md*

# Project Plan: Split-Pane UI with Tree View + Vega-Lite LEC Charts

## Document Purpose
This document captures the complete implementation plan to enable continuation from scratch if needed. It includes context, design decisions, user preferences, and step-by-step implementation details.

---

## Project Context

### Repository Information
- **Repository**: `risquanter/register`
- **Branch**: `main`
- **Workspace**: `/home/danago/projects/register`
- **Date**: January 13, 2026

### Technology Stack
- **Backend**: Scala 3.6.4, ZIO, Tapir, sttp
- **Frontend**: Scala.js 1.20.0, Laminar 17.2.0, Airstream
- **Validation**: Iron 3.2.2 (refinement types in `common` module)
- **Charting**: Vega-Lite v6 (via vega-embed)
- **Build**: sbt with cross-compilation (JVM + JS)
- **Dev Server**: Vite 6.4.1

### Current State
- `RiskLeafFormView` with reactive validation (using `ValidationUtil` from common)
- Input filters and on-blur validation working
- Backend has `Simulator.simulateTree` for Monte Carlo simulation
- `VegaLiteBuilder` generates LEC chart specs server-side
- `LECCurveData` hierarchical structure exists

---

## User Working Preferences

### Process Requirements
1. **Step-by-step approach**: Do NOT start major changes without permission
2. **Approval gates**: User must approve progress at each step
3. **Testable units**: Each step should be independently verifiable
4. **Ask clarification questions**: Before making decisions, consult user
5. **No autonomous refactoring**: Get explicit approval before code changes
6. **Digestible increments**: Break work into small, reviewable chunks

### Technical Preferences
1. **Reuse existing code**: Use `ValidationUtil` from common module (don't duplicate)
2. **Backend validation stays**: Frontend validation is for UX, backend is source of truth
3. **Prefer simplicity**: Start with fixed proportions, refine later
4. **Skip auth for now**: No session handling needed initially

---

## Design Decisions (Confirmed with User)

### Q1: Tree Visualization
**Decision**: Hybrid approach
- Tree structure rendered with **Laminar HTML** (expandable hierarchy)
- LEC chart rendered with **Vega-Lite** (BCG-style multi-curve diagram)
- NOT using Vega's tree transform (stays within Vega-Lite)

### Q2: Subtree Simulation Semantics
**Decision**: Option A - New endpoint
- `GET /risk-trees/{treeId}/nodes/{nodeId}/lec?depth=1`
- Returns selected node's aggregate LEC + immediate children's LECs
- Simulation runs full recursion to leaves (correct semantics)
- **TODO**: Add caching/memoization investigation for later optimization

### Q3: Split-Pane Implementation
**Decision**: Phased approach
- **Initial**: Fixed proportions with CSS Grid/Flexbox (Option 3)
- **Later**: Draggable handles (Option 1) as planned refinement

### Q4: Session/Auth
**Decision**: Skip entirely for now
- No session handling
- No authentication
- Use unsecured endpoints

---

## Reference Resources

### BCG Implementation (for reference)
```
/home/danago/projects/register/temp/business-case-generator/
├── modules/app/src/main/scala/com/promon/bca/core/
│   ├── ZJS.scala              # ZIO-to-Laminar bridge (simpler version)
│   └── BackendClient.scala    # sttp/tapir HTTP client
└── modules/common/src/main/scala/com/promon/bca/
    ├── http/responses/SimulationDetailResponse.scala
    └── domain/data/vegalite/
        ├── VegaLiteLossDiagramm.scala  # Main spec builder
        ├── Data.scala                   # Vega data format
        ├── Layer.scala, Encoding.scala, etc.
```

### Cheleb Implementation (for reference, more complete)
```
/home/danago/projects/register/temp/cheleb/modules/app/src/main/scala/com/rockthejvm/reviewboard/core/
├── ZJS.scala           # Enhanced with toEventStream, runJs
├── BackendClient.scala # With secured endpoint support
├── Session.scala       # JWT token management (NOT USING)
└── Storage.scala       # localStorage wrapper (NOT USING)
```

### Vega-Lite Experiments (interaction patterns)
```
/home/danago/projects/register/temp/vega-lite-experiments/src/main/scala/Main.scala
# Key pattern: VegaEmbed facade + signal listener for selection
```

### Current Backend Simulation Code
```
/home/danago/projects/register/modules/server/src/main/scala/com/risquanter/register/
├── simulation/
│   ├── Simulator.scala         # simulateTree (recursive)
│   ├── LECGenerator.scala      # generateCurvePoints, getTicks
│   ├── VegaLiteBuilder.scala   # generateSpec for Vega-Lite
│   └── RiskSampler.scala       # Monte Carlo sampling
└── services/
    ├── RiskTreeService.scala       # Service trait
    └── RiskTreeServiceLive.scala   # Implementation with buildLECNode
```

### Current Frontend Code
```
/home/danago/projects/register/modules/app/src/main/scala/app/
├── Main.scala
├── components/
│   ├── Layout.scala
│   ├── Header.scala
│   └── FormInputs.scala
├── state/
│   ├── FormState.scala
│   ├── RiskLeafFormState.scala
│   └── DistributionMode.scala
└── views/
    └── RiskLeafFormView.scala
```

---

## Key Domain Concepts

### Aggregation Semantics
```scala
// Identity[RiskResult].combine uses outer join:
// - Union of trial IDs from both distributions
// - Sum losses per trial: loss_combined(i) = a.loss(i) + b.loss(i)
// - Creates aggregate LEC from children

// Example:
// Portfolio A = Child1 + Child2
// For each trial i: A.loss(i) = Child1.loss(i) + Child2.loss(i)
```

### LEC (Loss Exceedance Curve)
- X-axis: Loss amount (in millions)
- Y-axis: P(Loss >= x) - probability of exceeding that loss
- One curve per risk node (aggregate + children)
- Smooth B-spline interpolation ("basis")

### Tree Structure
```scala
// RiskNode = RiskLeaf | RiskPortfolio
sealed trait RiskNode
case class RiskLeaf(...) extends RiskNode        // Terminal: has distribution
case class RiskPortfolio(children: Array[RiskNode]) extends RiskNode  // Branch

// RiskTreeResult = Leaf | Branch (simulation results)
sealed trait RiskTreeResult
case class Leaf(id: String, result: RiskResult) extends RiskTreeResult
case class Branch(id: String, result: RiskResult, children: Vector[RiskTreeResult])
```

### LECCurveData (API response format)
```scala
case class LECCurveData(
  id: String,
  name: String,
  curve: Vector[LECPoint],           // (loss, exceedanceProbability) points
  quantiles: Map[String, Double],    // p50, p90, p95, p99
  children: Option[Vector[LECCurveData]]  // Hierarchical children
)
```

---

## UI Layout Target

```
┌──────────────────────────────┬──────────────────────────────┐
│                              │                              │
│                              │    TREE VIEW (Laminar)       │
│                              │    ├─ Portfolio A            │
│     FORM PANEL               │    │  ├─ Risk 1 [selected]   │
│     (RiskLeafFormView)       │    │  └─ Risk 2              │
│                              │    └─ Portfolio B            │
│                              │       └─ Risk 3              │
│                              ├──────────────────────────────┤
│                              │                              │
│                              │    LEC CHART (Vega-Lite)     │
│                              │    [Multi-curve diagram]     │
│                              │    - Selected node (bold)    │
│                              │    - Children curves         │
│                              │                              │
└──────────────────────────────┴──────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: Backend Infrastructure

#### Step 1.1: Add Subtree LEC Endpoint
**Goal**: New endpoint to simulate from any node in tree

**Endpoint**: `GET /risk-trees/{treeId}/nodes/{nodeId}/lec`
- Query params: `depth` (default 1), `nTrials` (optional)
- Returns: `LECCurveData` for selected node + children at depth

**Files to modify**:
1. `modules/common/src/main/scala/.../http/endpoints/RiskTreeEndpoints.scala`
   - Add `computeNodeLECEndpoint` definition
2. `modules/server/src/main/scala/.../services/RiskTreeService.scala`
   - Add `computeNodeLEC(treeId, nodeId, depth, nTrials)` method
3. `modules/server/src/main/scala/.../services/RiskTreeServiceLive.scala`
   - Implement subtree extraction and simulation
4. `modules/server/src/main/scala/.../http/routes/RiskTreeRoutes.scala`
   - Wire endpoint to service

**Test**: `curl http://localhost:8080/api/risk-trees/1/nodes/cyber/lec?depth=1`

**Deliverable**: Working endpoint returning hierarchical LEC data

---

#### Step 1.2: Verify Common Module DTOs
**Goal**: Ensure frontend can deserialize backend responses

**Check existing**:
- `LECCurveData` in common module ✓
- `LECPoint` in common module ✓
- JSON codecs with `zio-json` ✓

**Test**: `sbt "common/compile"` for JS target

**Deliverable**: Shared types compile for both JVM and JS

---

### Phase 2: Frontend Core Infrastructure

#### Step 2.1: ZJS + BackendClient
**Goal**: HTTP client infrastructure for frontend

**Files to create**:
```
modules/app/src/main/scala/app/
├── core/
│   ├── ZJS.scala           # Extension methods for ZIO→Laminar
│   └── BackendClient.scala # Tapir client implementation
└── config/
    └── BackendClientConfig.scala
```

**Based on BCG version** (simpler, no session):
```scala
// ZJS.scala key patterns:
extension [E <: Throwable, A](zio: ZIO[BackendClient, E, A])
  def emitTo(eventBus: EventBus[A]): Unit = ...
  
extension [I, E <: Throwable, O](endpoint: Endpoint[Unit, I, E, O, Any])
  def apply(payload: I): Task[O] = ...
```

**Test**: Call health endpoint, log result to console

**Deliverable**: `endpoint(payload).emitTo(bus)` works

---

#### Step 2.2: Vega-Embed Facade
**Goal**: Scala.js bindings for vega-embed

**Files to create**:
```
modules/app/src/main/scala/app/facades/VegaEmbed.scala
```

**Pattern from vega-lite-experiments**:
```scala
@js.native
@JSImport("vega-embed", JSImport.Default)
object VegaEmbed extends js.Object {
  def apply(el: dom.Element, spec: js.Any, options: js.UndefOr[js.Any]): Promise[js.Dynamic]
}
```

**Package.json additions**:
```json
"dependencies": {
  "vega": "^5.30.0",
  "vega-lite": "^5.21.0",
  "vega-embed": "^6.26.0"
}
```

**Test**: Render hardcoded chart spec

**Deliverable**: `VegaEmbed(element, spec)` renders chart

---

### Phase 3: Split-Pane Layout

#### Step 3.1: Fixed-Proportion Split Layout
**Goal**: CSS-based split view component

**Files to create**:
```
modules/app/src/main/scala/app/components/SplitPane.scala
```

**API**:
```scala
object SplitPane:
  def horizontal(left: HtmlElement, right: HtmlElement, leftPercent: Int = 50): HtmlElement
  def vertical(top: HtmlElement, bottom: HtmlElement, topPercent: Int = 50): HtmlElement
```

**CSS approach**: Flexbox with percentage widths/heights

**Test**: Visual inspection of 50/50 split

**Deliverable**: Reusable split layout component

---

#### Step 3.2: Integrate Layout
**Goal**: Replace current single-form layout

**Files to modify**:
1. `modules/app/src/main/scala/app/Main.scala`
2. `modules/app/src/main/scala/app/components/Layout.scala`

**Structure**:
```scala
SplitPane.horizontal(
  left = RiskLeafFormView(),
  right = SplitPane.vertical(
    top = TreeView(treeState),
    bottom = LECChartView(lecSignal)
  )
)
```

**Test**: App loads with split layout, form still functional

**Deliverable**: Split-pane layout integrated

---

### Phase 4: Tree View Component

#### Step 4.1: Tree State Management
**Goal**: Reactive state for tree data and interaction

**Files to create**:
```
modules/app/src/main/scala/app/state/TreeViewState.scala
```

**State model**:
```scala
class TreeViewState:
  val availableTrees: Var[List[RiskTreeSummary]] = Var(Nil)
  val selectedTreeId: Var[Option[Long]] = Var(None)
  val treeStructure: Var[Option[RiskNode]] = Var(None)
  val expandedNodes: Var[Set[String]] = Var(Set.empty)
  val selectedNodeId: Var[Option[String]] = Var(None)
  val lecData: Var[Option[LECCurveData]] = Var(None)
  val isLoading: Var[Boolean] = Var(false)
  val error: Var[Option[String]] = Var(None)
  
  def toggleExpanded(nodeId: String): Unit = ...
  def selectNode(nodeId: String): Unit = ...
```

**Test**: Unit test state transitions

**Deliverable**: State container with clean API

---

#### Step 4.2: Tree View UI Component
**Goal**: Laminar-based expandable tree

**Files to create**:
```
modules/app/src/main/scala/app/views/TreeView.scala
```

**Rendering approach**:
```scala
def renderNode(node: RiskNode, depth: Int, state: TreeViewState): HtmlElement =
  node match
    case leaf: RiskLeaf => 
      div(
        cls := "tree-leaf",
        style := s"padding-left: ${depth * 20}px",
        onClick --> { _ => state.selectNode(leaf.id) },
        "📄 " + leaf.name
      )
    case portfolio: RiskPortfolio =>
      div(
        div(
          cls := "tree-branch",
          onClick --> { _ => state.toggleExpanded(portfolio.id) },
          child.text <-- state.expandedNodes.signal.map(exp => 
            if exp.contains(portfolio.id) then "📂" else "📁"
          ),
          " " + portfolio.name
        ),
        children <-- state.expandedNodes.signal.map { expanded =>
          if expanded.contains(portfolio.id) then
            portfolio.children.map(c => renderNode(c, depth + 1, state)).toSeq
          else Nil
        }
      )
```

**Visual features**:
- Indentation by depth
- Folder icons (open/closed)
- Highlight selected node
- Click to expand/collapse portfolios
- Click to select for LEC view

**Test**: Render mock tree, verify interactions

**Deliverable**: Interactive tree component

---

#### Step 4.3: Tree Data Loading
**Goal**: Fetch trees from backend

**Files to create**:
```
modules/app/src/main/scala/app/services/TreeService.scala
```

**Endpoints used**:
- `GET /risk-trees` → `List[SimulationResponse]` (tree list)
- `GET /risk-trees/{id}` → `Option[SimulationResponse]` (includes root node)

**Flow**:
1. On app load: Fetch tree list
2. On tree selection: Fetch full tree structure
3. Update `TreeViewState` accordingly

**Test**: Trees appear in tree view on app load

**Deliverable**: Backend integration for tree data

---

### Phase 5: LEC Chart Component

#### Step 5.1: Vega-Lite Spec Builder (Frontend)
**Goal**: Generate BCG-style chart spec from `LECCurveData`

**Files to create**:
```
modules/app/src/main/scala/app/charts/LECChartBuilder.scala
```

**Based on BCG's VegaLiteLossDiagramm**:
```scala
object LECChartBuilder:
  val themeColors: Vector[String] = Vector(
    "#60b0f0", "#F2A64A", "#75B56A", "#E1716A", ...
  )
  
  def build(data: LECCurveData, width: Int = 600, height: Int = 300): js.Object =
    // Build Vega-Lite spec with:
    // - Mark: line with "basis" interpolation
    // - X: loss (quantitative), formatted for B/M
    // - Y: exceedance probability (quantitative), formatted as %
    // - Color: by risk name
    // - Data: flatten LECCurveData into values array
```

**Key features**:
- Multi-curve (aggregate + children)
- Color palette matching BCG
- Axis formatting for large numbers
- Smooth interpolation

**Test**: Generate spec, validate JSON structure

**Deliverable**: `LECChartBuilder.build(data)` returns valid spec

---

#### Step 5.2: LEC Chart View Component
**Goal**: Laminar component embedding Vega-Lite

**Files to create**:
```
modules/app/src/main/scala/app/views/LECChartView.scala
```

**Pattern**:
```scala
def apply(lecSignal: Signal[Option[LECCurveData]]): HtmlElement =
  div(
    cls := "lec-chart-container",
    child <-- lecSignal.map {
      case None => div("Select a node to view LEC")
      case Some(data) => 
        div(
          idAttr := "lec-chart",
          onMountCallback { ctx =>
            val spec = LECChartBuilder.build(data)
            VegaEmbed(ctx.thisNode.ref, spec)
          }
        )
    }
  )
```

**States**:
- Empty: "Select a node to view LEC"
- Loading: Spinner
- Data: Rendered chart
- Error: Error message

**Test**: Chart renders when data provided

**Deliverable**: Reactive chart component

---

#### Step 5.3: Wire Selection → LEC Fetch → Chart
**Goal**: Complete data flow

**Files to create/modify**:
```
modules/app/src/main/scala/app/services/LECService.scala
```

**Flow**:
```
User clicks node in TreeView
  ↓
state.selectedNodeId.set(nodeId)
  ↓
Signal triggers effect:
  GET /risk-trees/{treeId}/nodes/{nodeId}/lec?depth=1
  ↓
Response updates state.lecData
  ↓
LECChartView re-renders with new data
```

**Implementation**:
```scala
// In TreeViewState or separate effect handler:
selectedNodeId.signal.changes.foreach { maybeNodeId =>
  maybeNodeId.foreach { nodeId =>
    selectedTreeId.now().foreach { treeId =>
      isLoading.set(true)
      computeNodeLECEndpoint((treeId, nodeId, 1))
        .tap(data => ZIO.attempt(lecData.set(Some(data))))
        .tapError(e => ZIO.attempt(error.set(Some(e.getMessage))))
        .ensuring(ZIO.attempt(isLoading.set(false)))
        .emitTo(resultBus)
    }
  }
}
```

**Test**: Click node → chart updates

**Deliverable**: End-to-end selection flow working

---

### Phase 6: Integration & Polish

#### Step 6.1: Form Submission → Tree Refresh
**Goal**: Keep form and tree in sync

**Modification**: After successful RiskLeaf creation:
1. Refresh tree list
2. Optionally select new tree/node

**Test**: Create leaf via form, verify tree updates

**Deliverable**: Consistent UI state

---

#### Step 6.2: Error Handling & Loading States
**Goal**: Robust UX for async operations

**Patterns**:
- Loading spinner component
- Error message display
- Retry buttons

**Test**: Simulate failures, verify graceful handling

**Deliverable**: Production-ready error handling

---

#### Step 6.3: Caching TODO Documentation
**Goal**: Document future optimization

**Create**: `docs/TODO-LEC-CACHING.md`

**Content**:
```markdown
# LEC Caching/Memoization TODO

## Problem
- Each node selection triggers full Monte Carlo simulation
- Redundant computation when re-selecting nodes

## Options
1. **Client-side LRU cache**: Cache `LECCurveData` by (treeId, nodeId, nTrials)
2. **Server-side memoization**: Cache `RiskResult` per node
3. **Persistent cache**: Store computed LECs in database

## Invalidation
- Tree structure changes → invalidate affected subtree
- nTrials changes → invalidate all

## Recommendation
Start with client-side LRU (simple), evaluate server-side later.
```

**Deliverable**: Documented TODO for future sprint

---

### Phase 7: Future Enhancement (Deferred)

#### Step 7.1: Resizable Split Panes
**Goal**: Draggable dividers

**Approach**: Pure Laminar with mouse event handlers

**Deferred**: Implement after core functionality stable

---

## Dependency Graph

```
Phase 1 ─────────────────────────────────────────────────────────────────────
  1.1 Subtree Endpoint ───────────────────────────────────┐
  1.2 Common DTOs ─────────────────────────────────────┐  │
                                                       │  │
Phase 2 ─────────────────────────────────────────────────────────────────────
  2.1 ZJS + BackendClient ←───────────────────────────┘  │
  2.2 Vega-Embed Facade ───────────────────────────┐     │
                                                   │     │
Phase 3 ─────────────────────────────────────────────────────────────────────
  3.1 Split Layout ────────────────────────────────│─────│───┐
  3.2 Integrate Layout ←───────────────────────────│─────│───┤
                                                   │     │   │
Phase 4 ─────────────────────────────────────────────────────────────────────
  4.1 Tree State ──────────────────────────────────│─────│───┤
  4.2 Tree View UI ←───────────────────────────────│─────│───┤
  4.3 Tree Data Loading ←──────────────────────────│─────│───┤ (needs 2.1)
                                                   │     │   │
Phase 5 ─────────────────────────────────────────────────────────────────────
  5.1 Vega-Lite Spec Builder ──────────────────────│─────│───┤
  5.2 LEC Chart View ←─────────────────────────────┘     │   │ (needs 2.2, 5.1)
  5.3 Selection → LEC Flow ←─────────────────────────────┘   │ (needs 1.1, 4.1, 5.2)
                                                             │
Phase 6 ─────────────────────────────────────────────────────────────────────
  6.1 Form → Tree Refresh ←──────────────────────────────────┘
  6.2 Error Handling
  6.3 Caching TODO Doc
```

---

## Questions Resolved

| Question | Decision |
|----------|----------|
| Tree visualization | Laminar HTML (not Vega tree) |
| LEC chart | Vega-Lite (BCG style) |
| Subtree simulation | New endpoint, full recursion, depth param |
| Split panes | Fixed first, draggable later |
| Session/auth | Skip entirely |
| Caching | TODO for later, document options |

---

## Open Questions (For Future)

1. **nTrials UI control**: Should user be able to adjust? (Default: 10000)
2. **Tree persistence**: Currently in-memory repository - will this change?
3. **Multiple trees**: How to handle tree selection UI when many trees exist?

---

## Approval Gates

Before starting each phase, confirm:
- [ ] Phase 1: Backend endpoint design approved
- [ ] Phase 2: Frontend infrastructure approach approved  
- [ ] Phase 3: Layout structure approved
- [ ] Phase 4: Tree view design approved
- [ ] Phase 5: Chart integration approach approved
- [ ] Phase 6: Polish scope approved

---

**Status**: Plan exported. Awaiting user mark to begin implementation.

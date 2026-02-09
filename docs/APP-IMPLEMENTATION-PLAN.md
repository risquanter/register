# Laminar Frontend App Implementation Plan

## Overview

This document outlines an incremental plan to create a Laminar frontend app for the Register project. The app will provide a form for creating `RiskLeaf` entities with expert/lognormal mode toggle, validation feedback, and backend submission.

## REMARK I
this decision was made regarding SEE notification of stale state
Implement SSE Notification as Infrastructure

CacheInvalidated events are published
No frontend consumes them yet
Useful for testing and future integration

## REMARK II 
~~revalidation of the whole document is needed and critical update based on [the state of the proposal](IMPLEMENTATION-PLAN-PROPOSALS.md)~~

✅ **Revalidated 2026-02-09.** Updated to reflect: ULID-based `TreeId`/`NodeId`, server-generated IDs (no user-supplied IDs), `RiskResultCache` + `TreeCacheManager` (replaces conceptual `LECCache`), `LECCurveResponse` (replaces `LECCurveData`), `RiskResultResolver` (replaces `SimulationExecutionService`), `RiskTreeResult` removed. Phase 8 split into 8a/8b to separate available SSE events from pipeline-dependent features.

**Architecture Context:** This plan implements the browser layer of the architecture described in ADR-004a/b. The frontend receives real-time cache-invalidation notifications via SSE, with the ZIO backend performing all simulation and aggregation (ADR-009). Future phases add eager LEC push via `LECUpdated` events (requires Irmin watch pipeline) and optional WebSocket for collaboration.

## Goals

1. **Expert/lognormal mode toggle** via radio button
2. **Same validation approach** as BCG app (FormState pattern)
3. **POST request submission** to backend
4. **Latest Laminar version** (17.2.1+)
5. **Good local developer experience** using Vite
6. **Meaningful test coverage** for view layer
7. **Real-time LEC updates** via SSE stream (future: WebSocket)
8. **Clear error feedback** following ADR-008 patterns

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     Frontend Architecture                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   Laminar App                            │   │
│  ├─────────────────────────────────────────────────────────┤   │
│  │  Views           │  State          │  API Client        │   │
│  │  ─────           │  ─────          │  ──────────        │   │
│  │  • RiskLeafForm  │  • FormState    │  • REST mutations  │   │
│  │  • TreeView      │  • TreeState    │  • SSE events      │   │
│  │  • LECChart      │  • LECCache     │  • Error handling  │   │
│  │  • ScenarioBar   │  • UIState      │                    │   │
│  └─────────────────────────────────────────────────────────┘   │
│                           │                                     │
│                    SSE / WebSocket                              │
│                           │                                     │
│                           ▼                                     │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                   ZIO Backend                            │   │
│  │  • Computes LEC via Identity[RiskResult].combine         │   │
│  │  • Caches per-node RiskResult (ADR-005/014)             │   │
│  │  • RiskResultResolver: cache-aside simulation           │   │
│  │  • TreeCacheManager: per-tree cache lifecycle            │   │
│  │  • SSEHub: publishes CacheInvalidated events            │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key insight from ADR-009:** The browser only displays precomputed `LECCurveResponse`. All aggregation happens server-side using `Identity[RiskResult].combine`. The frontend treats leaf and aggregate LEC data uniformly. IDs are ULID-based (`TreeId`, `NodeId`) — the server generates all IDs; the frontend never supplies them.

---

## Phase 1: Build Pipeline Setup ✅ CHECKPOINT

### Tasks

1. **Enable app module** in `build.sbt`
   - Uncomment/update app module definition
   - Set ScalaJS settings:
     ```scala
     scalaJSUseMainModuleInitializer := true
     scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) }
     ```

2. **Add Laminar dependencies** to app module:
   ```scala
   libraryDependencies ++= Seq(
     "com.raquo" %%% "laminar" % "17.2.1",
     "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % tapirVersion,
     "com.softwaremill.sttp.client4" %%% "core" % sttpVersion
   )
   ```

3. **Create Vite build structure**:
   ```
   modules/app/
   ├── src/main/scala/app/
   │   └── Main.scala          # Entry point
   ├── src/main/resources/
   │   └── index.html          # HTML template
   ├── package.json            # NPM dependencies
   ├── vite.config.js          # Vite configuration
   └── main.js                 # JS entry point
   ```

4. **Vite config** (`vite.config.js`):
   ```javascript
   import { defineConfig } from 'vite'
   import scalaJSPlugin from '@anthropic/vite-plugin-scalajs'
   
   export default defineConfig({
     plugins: [scalaJSPlugin({ projectID: 'app', cwd: '../..' })]
   })
   ```

5. **Verify dev workflow**:
   - Terminal 1: `sbt ~app/fastLinkJS`
   - Terminal 2: `cd modules/app && npm run dev`
   - Open http://localhost:5173

### Approval Checkpoint
- [x] Dev server starts and shows "Hello from Laminar"
- [x] Hot reload works on Scala changes

**Status:** ✅ Complete — app module is active in `build.sbt`, aggregated into root, 8 source files exist under `modules/app/src/`.

---

## Phase 2: Basic App Shell

### Tasks

1. **Create Main.scala**:
   ```scala
   package app
   
   import com.raquo.laminar.api.L.{*, given}
   import org.scalajs.dom
   
   object Main:
     def main(args: Array[String]): Unit =
       lazy val container = dom.document.querySelector("#app")
       val appElement = div(
         h1("Risk Tree Builder"),
         p("Form will go here")
       )
       renderOnDomContentLoaded(container, appElement)
   ```

2. **Create index.html**:
   ```html
   <!DOCTYPE html>
   <html lang="en">
   <head>
     <meta charset="UTF-8">
     <title>Risk Tree Builder</title>
   </head>
   <body>
     <div id="app"></div>
     <script type="module" src="./main.js"></script>
   </body>
   </html>
   ```

3. **Create main.js**:
   ```javascript
   import 'scalajs:main.js'
   ```

### Approval Checkpoint
- [ ] App renders heading in browser
- [ ] Console has no errors

---

## Phase 3: Form State Management (BCG Pattern)

### Tasks

1. **Create FormState trait** (matching BCG pattern):
   ```scala
   package app.state
   
   trait FormState:
     def errorList: List[Option[String]]
     def hasErrors(): Boolean = errorList.exists(_.isDefined)
     def maybeError(errorMessage: String, isError: Boolean): Option[String] =
       if isError then Some(errorMessage) else None
   ```

2. **Create RiskLeafFormState**:
   ```scala
   package app.state
   
   import com.raquo.laminar.api.L.{*, given}
   
   enum DistributionMode:
     case Expert, Lognormal
   
   class RiskLeafFormState:
     // Mode selection
     val distributionModeVar: Var[DistributionMode] = Var(DistributionMode.Expert)
     
     // Common fields (no ID field — server generates ULID IDs)
     val nameVar: Var[String] = Var("")
     val probabilityVar: Var[String] = Var("")
     
     // Expert mode fields
     val percentilesVar: Var[String] = Var("")  // comma-separated
     val quantilesVar: Var[String] = Var("")    // comma-separated
     
     // Lognormal mode fields  
     val minLossVar: Var[String] = Var("")
     val maxLossVar: Var[String] = Var("")
     
     // Validation signals
     val nameError: Signal[Option[String]] = nameVar.signal.map: v =>
       if v.isBlank then Some("Name is required") else None
     
     val probabilityError: Signal[Option[String]] = probabilityVar.signal.map: v =>
       parseDouble(v) match
         case None => Some("Probability must be a number")
         case Some(p) if p < 0 || p > 1 => Some("Probability must be between 0 and 1")
         case _ => None
     
     // Expert mode validation
     val expertError: Signal[Option[String]] = 
       distributionModeVar.signal.combineWith(percentilesVar.signal, quantilesVar.signal).map:
         case (DistributionMode.Expert, percentiles, quantiles) =>
           val pList = parseDoubleList(percentiles)
           val qList = parseDoubleList(quantiles)
           if pList.isEmpty then Some("Percentiles required for expert mode")
           else if qList.isEmpty then Some("Quantiles required for expert mode")
           else if pList.size != qList.size then Some("Percentiles and quantiles must have same length")
           else None
         case _ => None
     
     // Lognormal mode validation
     val lognormalError: Signal[Option[String]] =
       distributionModeVar.signal.combineWith(minLossVar.signal, maxLossVar.signal).map:
         case (DistributionMode.Lognormal, minStr, maxStr) =>
           (parseDouble(minStr), parseDouble(maxStr)) match
             case (None, _) => Some("Minimum loss must be a number")
             case (_, None) => Some("Maximum loss must be a number")
             case (Some(min), _) if min < 0 => Some("Minimum loss must be non-negative")
             case (_, Some(max)) if max < 0 => Some("Maximum loss must be non-negative")
             case (Some(min), Some(max)) if min >= max => Some("Minimum must be less than maximum")
             case _ => None
         case _ => None
     
     // Overall validity
     val hasErrors: Signal[Boolean] = 
       Signal.combineAll(nameError, probabilityError, expertError, lognormalError)
         .map(_.exists(_.isDefined))
     
     private def parseDouble(s: String): Option[Double] =
       scala.util.Try(s.trim.toDouble).toOption
     
     private def parseDoubleList(s: String): List[Double] =
       s.split(",").toList.flatMap(s => parseDouble(s.trim))
   ```

### Approval Checkpoint
- [ ] FormState compiles
- [ ] Validation signals emit correct values (manual console test)

---

## Phase 4: Input Components

### Tasks

1. **Create reusable input component**:
   ```scala
   package app.components
   
   import com.raquo.laminar.api.L.{*, given}
   
   def textInput(
     labelText: String,
     valueVar: Var[String],
     errorSignal: Signal[Option[String]],
     placeholder: String = ""
   ): HtmlElement =
     div(
       cls := "field",
       label(labelText),
       input(
         typ := "text",
         placeholder := placeholder,
         controlled(
           value <-- valueVar.signal,
           onInput.mapToValue --> valueVar
         ),
         cls <-- errorSignal.map(_.fold("")(_ => "error"))
       ),
       child.maybe <-- errorSignal.map(_.map(msg => span(cls := "error-message", msg)))
     )
   ```

2. **Create radio toggle component**:
   ```scala
   def distributionModeToggle(modeVar: Var[DistributionMode]): HtmlElement =
     div(
       cls := "mode-toggle",
       label(
         input(
           typ := "radio",
           nameAttr := "distributionMode",
           checked <-- modeVar.signal.map(_ == DistributionMode.Expert),
           onChange.mapTo(DistributionMode.Expert) --> modeVar
         ),
         "Expert Mode"
       ),
       label(
         input(
           typ := "radio",
           nameAttr := "distributionMode",
           checked <-- modeVar.signal.map(_ == DistributionMode.Lognormal),
           onChange.mapTo(DistributionMode.Lognormal) --> modeVar
         ),
         "Lognormal Mode"
       )
     )
   ```

3. **Create conditional field groups**:
   ```scala
   def expertFields(state: RiskLeafFormState): HtmlElement =
     div(
       cls := "expert-fields",
       textInput("Percentiles", state.percentilesVar, state.expertError, "10,50,90"),
       textInput("Quantiles", state.quantilesVar, Signal.fromValue(None), "100,500,1000")
     )
   
   def lognormalFields(state: RiskLeafFormState): HtmlElement =
     div(
       cls := "lognormal-fields",
       textInput("Minimum Loss", state.minLossVar, state.lognormalError),
       textInput("Maximum Loss", state.maxLossVar, Signal.fromValue(None))
     )
   ```

### Approval Checkpoint
- [ ] Radio toggle switches between Expert/Lognormal
- [ ] Conditional fields show/hide based on mode
- [ ] Validation errors display inline

---

## Phase 5: Complete Form Assembly

### Tasks

1. **Assemble full form**:
   ```scala
   package app.views
   
   import app.state.{DistributionMode, RiskLeafFormState}
   import app.components.*
   import com.raquo.laminar.api.L.{*, given}
   
   def riskLeafForm(state: RiskLeafFormState, onSubmit: Observer[Unit]): HtmlElement =
     form(
       onSubmit.preventDefault.mapTo(()) --> onSubmit,
       
       h2("Create Risk Leaf"),
       
       // Common fields (no ID — server generates ULID)
       textInput("Name", state.nameVar, state.nameError),
       textInput("Probability", state.probabilityVar, state.probabilityError, "0.0 - 1.0"),
       
       // Mode toggle
       distributionModeToggle(state.distributionModeVar),
       
       // Conditional fields
       child <-- state.distributionModeVar.signal.map:
         case DistributionMode.Expert => expertFields(state)
         case DistributionMode.Lognormal => lognormalFields(state)
       ,
       
       // Submit button
       button(
         typ := "submit",
         disabled <-- state.hasErrors,
         "Create Risk Leaf"
       )
     )
   ```

2. **Update Main.scala** to use form:
   ```scala
   object Main:
     def main(args: Array[String]): Unit =
       lazy val container = dom.document.querySelector("#app")
       
       val formState = new RiskLeafFormState
       val submitObserver = Observer[Unit](_ => println("Submit clicked!"))
       
       val appElement = div(
         h1("Risk Tree Builder"),
         riskLeafForm(formState, submitObserver)
       )
       
       renderOnDomContentLoaded(container, appElement)
   ```

### Approval Checkpoint
- [ ] Full form renders correctly
- [ ] Mode toggle works
- [ ] Submit button disabled when errors present
- [ ] Submit button enabled when form is valid

---

## Phase 6: Backend Client Integration

### Tasks

1. **Create API client**:
   ```scala
   package app.api
   
   import sttp.client4.*
   import sttp.client4.fetch.FetchBackend
   import scala.concurrent.Future
   import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
   
   object RiskTreeClient:
     private val backend = FetchBackend()
     
     // Create a tree — server generates TreeId and all NodeIds (ULIDs)
     def createRiskTree(request: RiskTreeDefinitionRequest): Future[Either[String, RiskTree]] =
       val req = basicRequest
         .post(uri"http://localhost:8080/risk-trees")
         .contentType("application/json")
         .body(request.toJson)
       
       req.send(backend).map: response =>
         response.body.left.map(identity)
     
     // Fetch LEC curve for a specific node
     def getLECCurve(treeId: String, nodeId: String): Future[Either[String, LECCurveResponse]] =
       val req = basicRequest
         .get(uri"http://localhost:8080/risk-trees/$treeId/nodes/$nodeId/lec")
       
       req.send(backend).map: response =>
         response.body.left.map(identity)
   ```

2. **Add submit handling**:
   ```scala
   val submitObserver = Observer[Unit]: _ =>
     state.toRequest match
       case Left(errors) => 
         println(s"Validation errors: $errors")
       case Right(request) =>
         // Server generates TreeId and NodeIds
         RiskTreeClient.createRiskTree(request).foreach:
           case Right(tree) => println(s"Created tree: ${tree.id}")
           case Left(err) => println(s"Error: $err")
   ```

3. **Add request conversion to FormState**:
   ```scala
   def toRequest: Either[List[String], RiskTreeDefinitionRequest] =
     // Build a RiskTreeDefinitionRequest from form values
     // No ID fields — server generates all IDs (TreeId, NodeId) as ULIDs
   ```

### Approval Checkpoint
- [ ] Form submits to backend
- [ ] Success/error feedback shown to user
- [ ] CORS configured on backend (if needed)

---

## Phase 7: Testing Strategy

### Testing Approaches for Laminar

Based on research, Laminar testing typically uses one of these approaches:

1. **Direct DOM Testing** (Recommended for simple components)
   - Mount component to jsdom
   - Query DOM and assert
   - Use `scala-dom-testutils` for assertions

2. **State-Only Testing** (Recommended for complex logic)
   - Test `FormState` signals directly
   - Use `Observer` to capture emitted values
   - No DOM needed

3. **Integration Testing** (For critical flows)
   - Use Playwright/Cypress for E2E
   - Test actual browser behavior

### Tasks

1. **Add test dependencies**:
   ```scala
   libraryDependencies ++= Seq(
     "org.scalameta" %%% "munit" % "1.0.0" % Test,
     "com.raquo" %%% "domtestutils" % "0.18.0" % Test
   )
   ```

2. **Create FormState tests**:
   ```scala
   package app.state
   
   import munit.FunSuite
   
   class RiskLeafFormStateSpec extends FunSuite:
     
     test("nameError should be Some when name is blank"):
       val state = new RiskLeafFormState
       var capturedError: Option[String] = null
       
       // Capture signal value
       val subscription = state.nameError.foreach(capturedError = _)(???)
       
       state.nameVar.set("")
       assertEquals(capturedError, Some("Name is required"))
     
     test("expertError requires percentiles in expert mode"):
       val state = new RiskLeafFormState
       state.distributionModeVar.set(DistributionMode.Expert)
       state.percentilesVar.set("")
       
       // Assert error signal emits expected value
   ```

3. **Create component tests** (if needed):
   ```scala
   // Using scala-dom-testutils
   test("textInput displays error message"):
     val errorVar = Var[Option[String]](Some("Test error"))
     val component = textInput("Label", Var(""), errorVar.signal)
     
     mount(component)
     assert(contains(".error-message", "Test error"))
   ```

### Approval Checkpoint
- [ ] FormState unit tests pass
- [ ] Critical validation rules have test coverage
- [ ] Test can run in CI (sbt app/test)

---

## Phase 8a: Real-Time Cache Invalidation (SSE — Available Now)

> **Reference:** ADR-004a-proposal (SSE variant)
> **Dependency:** None — backend already publishes `CacheInvalidated` events via `SSEHub`

### Context

The backend `InvalidationHandler` already publishes `SSEEvent.CacheInvalidated` events when tree cache is cleared (e.g., after a node update). The frontend can subscribe to these events NOW to know when displayed LEC data is stale and should be re-fetched.

### Tasks

1. **Create SSE event stream client**:
   ```scala
   package app.api
   
   import org.scalajs.dom.{EventSource, MessageEvent}
   import com.raquo.laminar.api.L.*
   
   object SSEClient:
     def connect(treeId: String): EventStream[SSEEvent] =
       EventStream.fromCustomSource[SSEEvent](
         start = (fireEvent, _, _, _) =>
           val source = new EventSource(s"/risk-trees/$treeId/events")
           source.onmessage = (e: MessageEvent) =>
             decode[SSEEvent](e.data.toString).foreach(fireEvent)
           source,
         stop = source => source.close()
       )
   
   enum SSEEvent:
     case CacheInvalidated(treeId: String, nodesCleared: Int)
     case TreeUpdated(treeId: String)
   ```

2. **Create frontend LEC state** (re-fetch on invalidation):
   ```scala
   package app.state
   
   class LECState:
     private val cache: Var[Map[String, LECCurveResponse]] = Var(Map.empty)
     val staleNodes: Var[Set[String]] = Var(Set.empty)  // nodes needing re-fetch
     
     def lecFor(nodeId: String): Signal[Option[LECCurveResponse]] =
       cache.signal.map(_.get(nodeId))
     
     def update(nodeId: String, lec: LECCurveResponse): Unit =
       cache.update(_ + (nodeId -> lec))
       staleNodes.update(_ - nodeId)
     
     def markAllStale(treeId: String): Unit =
       // On CacheInvalidated: mark all cached nodes as stale
       staleNodes.set(cache.now().keySet)
   ```

3. **Wire SSE to state**:
   ```scala
   val sseObserver = Observer[SSEEvent]:
     case SSEEvent.CacheInvalidated(treeId, _) =>
       lecState.markAllStale(treeId)
       // Re-fetch LEC for currently visible nodes
       visibleNodeIds.foreach: nodeId =>
         RiskTreeClient.getLECCurve(treeId, nodeId).foreach:
           case Right(lec) => lecState.update(nodeId, lec)
           case Left(err) => showError(err)
     case SSEEvent.TreeUpdated(treeId) =>
       // Refresh tree structure from backend
   ```

### Approval Checkpoint
- [ ] SSE connection established on page load
- [ ] `CacheInvalidated` events trigger LEC re-fetch for visible nodes
- [ ] Stale indicators shown while re-fetching

---

## Phase 8b: Eager LEC Push (SSE — Requires Irmin Pipeline) 🔒

> **Reference:** ADR-004a-proposal, ADR-005-proposal
> **Blocked on:** `IrminClient.watch` (GraphQL subscription) + `TreeUpdatePipeline` + `LECRecomputer`

### Context

This phase upgrades from "re-fetch on invalidation" (8a) to "server pushes fresh LEC data automatically." It requires three backend components that don't exist yet:
1. `IrminClient.watch` — Irmin GraphQL subscription for tree changes
2. `TreeUpdatePipeline` — orchestrates watch → invalidation → recomputation
3. `LECRecomputer` — re-simulates affected nodes and publishes `LECUpdated` via SSEHub

`SSEEvent.LECUpdated` is already **defined** with codecs and tests, but **nothing in production publishes it** yet.

### Tasks (when unblocked)

1. **Extend SSE event handling** for `LECUpdated`:
   ```scala
   // Add to SSEEvent enum
   case LECUpdated(nodeId: String, treeId: String, quantiles: Map[String, Double])
   
   // Add to observer
   case SSEEvent.LECUpdated(nodeId, treeId, quantiles) =>
     // Fetch fresh LECCurveResponse for this node
     RiskTreeClient.getLECCurve(treeId, nodeId).foreach:
       case Right(lec) => lecState.update(nodeId, lec)
       case Left(err) => showError(err)
   ```

2. **Create LEC visualization component** (Vega-Lite):
   ```scala
   def lecChart(lecSignal: Signal[Option[LECCurveResponse]]): HtmlElement =
     div(
       cls := "lec-chart",
       child <-- lecSignal.map:
         case None => div(cls := "loading", "Computing LEC...")
         case Some(lec) => 
           // Embed Vega-Lite spec or render with vega-embed
           div(idAttr := "vega-container")
           // Use onMountCallback to call vegaEmbed
     )
   ```

### Approval Checkpoint
- [ ] `LECUpdated` events auto-update chart without manual re-fetch
- [ ] Graceful degradation: falls back to 8a behavior if pipeline not running

---

## Phase 9: Error Handling (ADR-008 Patterns)

> **Reference:** ADR-008-proposal

### Tasks

1. **Create error state model**:
   ```scala
   package app.state
   
   enum AppError:
     case ValidationFailed(errors: List[String])
     case NetworkError(message: String, retryable: Boolean)
     case Conflict(message: String, refreshAction: () => Unit)
     case ServerError(referenceId: String)
   
   class ErrorState:
     val currentError: Var[Option[AppError]] = Var(None)
     val isRetrying: Var[Boolean] = Var(false)
     
     def show(error: AppError): Unit = currentError.set(Some(error))
     def clear(): Unit = currentError.set(None)
   ```

2. **Create error display component**:
   ```scala
   def errorBanner(errorState: ErrorState): HtmlElement =
     div(
       cls := "error-banner",
       display <-- errorState.currentError.signal.map(_.fold("none")(_ => "block")),
       child.maybe <-- errorState.currentError.signal.map(_.map(renderError))
     )
   
   private def renderError(error: AppError): HtmlElement = error match
     case AppError.ValidationFailed(errors) =>
       div(cls := "error validation", errors.map(e => p(e)))
     case AppError.NetworkError(msg, retryable) =>
       div(
         cls := "error network",
         p(msg),
         if retryable then button("Retry", onClick --> retryAction) else emptyNode
       )
     case AppError.Conflict(msg, refresh) =>
       div(
         cls := "error conflict",
         p(msg),
         button("Refresh", onClick.mapTo(()) --> Observer(_ => refresh()))
       )
     case AppError.ServerError(refId) =>
       div(cls := "error server", p(s"Server error. Reference: $refId"))
   ```

3. **Implement SSE reconnection with backoff**:
   ```scala
   def maintainSSEConnection(treeId: String, errorState: ErrorState): Unit =
     var retryCount = 0
     val maxRetries = 10
     
     def connect(): Unit =
       val source = new EventSource(s"/risk-trees/$treeId/events")
       source.onerror = _ =>
         source.close()
         if retryCount < maxRetries then
           retryCount += 1
           val delay = Math.min(1000 * Math.pow(2, retryCount), 30000)
           errorState.show(AppError.NetworkError(
             s"Connection lost. Retrying in ${delay/1000}s...",
             retryable = false
           ))
           js.timers.setTimeout(delay)(connect())
         else
           errorState.show(AppError.NetworkError(
             "Unable to connect. Please refresh the page.",
             retryable = true
           ))
       source.onopen = _ =>
         retryCount = 0
         errorState.clear()
   ```

### Approval Checkpoint
- [ ] Error banner displays on API failure
- [ ] SSE auto-reconnects with exponential backoff
- [ ] Conflict errors show refresh action

---

## Phase 10: Scenario Branching UI (Future)

> **Reference:** ADR-007-proposal

### Context

Once the core tree editing and LEC visualization work, add scenario management for what-if analysis.

### Tasks (Outline)

1. **Scenario state**:
   ```scala
   class ScenarioState:
     val currentScenario: Var[Option[Scenario]] = Var(None)  // None = main
     val availableScenarios: Var[List[Scenario]] = Var(List.empty)
   ```

2. **Scenario switcher component**:
   - Dropdown showing available scenarios
   - "New Scenario" button
   - Visual indicator for current branch

3. **Scenario comparison view**:
   - Side-by-side LEC curves
   - Diff summary (added/removed/modified nodes)
   - Delta at key percentiles (p95, expected loss)

4. **Merge UI** (if conflicts):
   - Show conflict details from `MergeResult.Conflict`
   - Resolution options per ADR-007

### Approval Checkpoint (Future)
- [ ] Can create new scenario from current state
- [ ] Can switch between scenarios
- [ ] LEC comparison view works

---

## Phase 11: WebSocket Enhancement (Future)

> **Reference:** ADR-004b-proposal

### Context

Replace SSE with WebSocket for bidirectional communication when collaborative editing is needed.

### Tasks (Outline)

1. Replace `EventSource` with `WebSocket`
2. Add client→server messages (cursor position, presence)
3. Show other users' cursors in tree view
4. Pre-commit conflict detection (soft locks)

---

## Summary of Checkpoints

| Phase | Description | Key Deliverable | Status |
|-------|-------------|-----------------|--------|
| 1 | Build Pipeline | `sbt ~fastLinkJS` + Vite works | ✅ Done |
| 2 | App Shell | Hello world renders | Needs validation |
| 3 | Form State | Validation signals work | Needs validation |
| 4 | Input Components | Fields render with validation | Needs validation |
| 5 | Form Assembly | Complete form with toggle | Needs validation |
| 6 | Backend Integration | Submit works end-to-end | Not started |
| 7 | Testing | Meaningful test coverage | Not started |
| 8a | Cache Invalidation SSE | `CacheInvalidated` → re-fetch | Not started |
| 8b | Eager LEC Push SSE | `LECUpdated` → auto-update | 🔒 Blocked (Irmin pipeline) |
| 9 | Error Handling | ADR-008 patterns implemented | Not started |
| 10 | Scenario Branching | What-if analysis UI | Future |
| 11 | WebSocket | Collaborative features | Future |

---

## Dependencies Summary

```scala
// In build.sbt app module
libraryDependencies ++= Seq(
  "com.raquo" %%% "laminar" % "17.2.1",
  "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % tapirVersion,
  "com.softwaremill.sttp.client4" %%% "core" % sttpVersion,
  // JSON encoding for API communication
  "dev.zio" %%% "zio-json" % zioJsonVersion,
  // Test
  "org.scalameta" %%% "munit" % "1.0.0" % Test,
  "com.raquo" %%% "domtestutils" % "0.18.0" % Test
)
```

```json
// In package.json
{
  "devDependencies": {
    "vite": "^7.3.0",
    "@anthropic/vite-plugin-scalajs": "^1.1.0"
  },
  "dependencies": {
    "vega-embed": "^6.24.0",
    "vega-lite": "^5.16.0"
  }
}
```

---

## Related ADRs

| ADR | Relevance to Frontend |
|-----|----------------------|
| ADR-004a-proposal | SSE for real-time updates (Phase 8a: `CacheInvalidated`, Phase 8b: `LECUpdated`) |
| ADR-004b-proposal | WebSocket for collaboration (Phase 11) |
| ADR-005-proposal | Backend caches `RiskResult`; `LECCurveResponse` rendered on demand; frontend just displays |
| ADR-006-proposal | Event types for real-time updates |
| ADR-007-proposal | Scenario UI patterns (Phase 10) |
| ADR-008-proposal | Error handling patterns (Phase 9) |
| ADR-009 | Frontend treats leaf/aggregate LEC uniformly |
| ADR-014 | RiskResult caching strategy (`RiskResultCache`, `TreeCacheManager`) |
| ADR-015 | `RiskResultResolver` cache-aside pattern; `RiskTreeResult` removed |
| ADR-018 | Nominal `NodeId`/`TreeId` wrappers |

---

## Next Steps

**Phase 1 is complete.** The app module compiles and is aggregated into the root build.

**Immediate next actions:**
1. Validate Phases 2–5 — source files exist under `modules/app/src/`, but need verification that they compile and render correctly.
2. Phase 6 (Backend Integration) — update API client to use current endpoint signatures (`/risk-trees`, `TreeId` paths, `LECCurveResponse`).
3. Phase 7 (Testing) — confirm `sbt app/test` runs; add app test count to CI.
4. Phase 8a (SSE) — can proceed immediately; backend already publishes `CacheInvalidated` events.
5. Phase 8b (Eager LEC Push) — blocked on Irmin watch pipeline (see IMPLEMENTATION-PLAN-PROPOSALS.md Phase 5).

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
revalidation of the whole document is needed and critical update based on [the state of the proposal](IMPLEMENTATION-PLAN-PROPOSALS.md)

**Architecture Context:** This plan implements the browser layer of the architecture described in ADR-004a/b. The frontend receives real-time LEC updates via SSE (Phase 1) or WebSocket (Phase 2), with the ZIO backend performing all simulation and aggregation (ADR-009).

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
│  │  • Caches per-node LECCurveData (ADR-005)               │   │
│  │  • Pushes updates on tree changes                        │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key insight from ADR-009:** The browser only displays precomputed `LECCurveData`. All aggregation happens server-side using `Identity[RiskResult].combine`. The frontend treats leaf and aggregate LEC data uniformly.

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
- [ ] Dev server starts and shows "Hello from Laminar"
- [ ] Hot reload works on Scala changes

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
     
     // Common fields
     val idVar: Var[String] = Var("")
     val nameVar: Var[String] = Var("")
     val probabilityVar: Var[String] = Var("")
     
     // Expert mode fields
     val percentilesVar: Var[String] = Var("")  // comma-separated
     val quantilesVar: Var[String] = Var("")    // comma-separated
     
     // Lognormal mode fields  
     val minLossVar: Var[String] = Var("")
     val maxLossVar: Var[String] = Var("")
     
     // Validation signals
     val idError: Signal[Option[String]] = idVar.signal.map: v =>
       if v.isBlank then Some("ID is required") else None
     
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
       Signal.combineAll(idError, probabilityError, expertError, lognormalError)
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
       
       // Common fields
       textInput("ID", state.idVar, state.idError),
       textInput("Name", state.nameVar, Signal.fromValue(None)),
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
   import common.model.RiskNode
   import scala.concurrent.Future
   import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
   
   object RiskTreeClient:
     private val backend = FetchBackend()
     
     def createRiskLeaf(riskLeaf: RiskNode.RiskLeaf): Future[Either[String, Unit]] =
       val request = basicRequest
         .post(uri"http://localhost:8080/api/risk-tree")
         .contentType("application/json")
         .body(/* JSON encode riskLeaf */)
       
       request.send(backend).map: response =>
         response.body.left.map(identity)
   ```

2. **Add submit handling**:
   ```scala
   val submitObserver = Observer[Unit]: _ =>
     state.toRiskLeaf match
       case Left(errors) => 
         println(s"Validation errors: $errors")
       case Right(riskLeaf) =>
         RiskTreeClient.createRiskLeaf(riskLeaf).foreach:
           case Right(_) => println("Success!")
           case Left(err) => println(s"Error: $err")
   ```

3. **Add RiskLeaf conversion to FormState**:
   ```scala
   def toRiskLeaf: Either[List[String], RiskNode.RiskLeaf] =
     // Use ZIO Prelude Validation or manual validation
     // to construct RiskLeaf from form values
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
     
     test("idError should be Some when id is blank"):
       val state = new RiskLeafFormState
       var capturedError: Option[String] = null
       
       // Capture signal value
       val subscription = state.idError.foreach(capturedError = _)(???)
       
       state.idVar.set("")
       assertEquals(capturedError, Some("ID is required"))
     
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

## Phase 8: Real-Time LEC Updates (SSE)

> **Reference:** ADR-004a-proposal (SSE variant)

### Context

After form submission, the backend computes LEC curves and pushes updates via Server-Sent Events. The browser maintains a local cache of `LECCurveData` per node.

### Tasks

1. **Create SSE event stream client**:
   ```scala
   package app.api
   
   import org.scalajs.dom.{EventSource, MessageEvent}
   import com.raquo.laminar.api.L.*
   
   object LECEventStream:
     def connect(treeId: String): EventStream[LECEvent] =
       EventStream.fromCustomSource[LECEvent](
         start = (fireEvent, _, _, _) =>
           val source = new EventSource(s"/api/events/tree/$treeId")
           source.onmessage = (e: MessageEvent) =>
             decode[LECEvent](e.data.toString).foreach(fireEvent)
           source,
         stop = source => source.close()
       )
   
   enum LECEvent:
     case LECUpdated(nodeId: String, curve: LECCurveData)
     case NodeChanged(nodeId: String)
     case Error(message: String)
   ```

2. **Create frontend LEC cache**:
   ```scala
   package app.state
   
   class LECCacheState:
     private val cache: Var[Map[String, LECCurveData]] = Var(Map.empty)
     
     def lecFor(nodeId: String): Signal[Option[LECCurveData]] =
       cache.signal.map(_.get(nodeId))
     
     def update(nodeId: String, lec: LECCurveData): Unit =
       cache.update(_ + (nodeId -> lec))
     
     def invalidate(nodeId: String): Unit =
       cache.update(_ - nodeId)
   ```

3. **Wire SSE to cache**:
   ```scala
   val sseObserver = Observer[LECEvent]:
     case LECEvent.LECUpdated(nodeId, curve) =>
       lecCache.update(nodeId, curve)
       // Optionally update Vega chart
     case LECEvent.NodeChanged(nodeId) =>
       lecCache.invalidate(nodeId)
       // Chart shows loading state until LECUpdated arrives
     case LECEvent.Error(msg) =>
       showError(msg)  // See ADR-008 error handling
   ```

4. **Create LEC visualization component** (Vega-Lite):
   ```scala
   def lecChart(lecSignal: Signal[Option[LECCurveData]]): HtmlElement =
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
- [ ] SSE connection established on page load
- [ ] LEC updates reflected in chart
- [ ] Graceful handling of connection drops (auto-reconnect)

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
       val source = new EventSource(s"/api/events/tree/$treeId")
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

| Phase | Description | Key Deliverable |
|-------|-------------|-----------------|
| 1 | Build Pipeline | `sbt ~fastLinkJS` + Vite works |
| 2 | App Shell | Hello world renders |
| 3 | Form State | Validation signals work |
| 4 | Input Components | Fields render with validation |
| 5 | Form Assembly | Complete form with toggle |
| 6 | Backend Integration | Submit works end-to-end |
| 7 | Testing | Meaningful test coverage |
| 8 | Real-Time LEC | SSE stream → chart updates |
| 9 | Error Handling | ADR-008 patterns implemented |
| 10 | Scenario Branching | What-if analysis UI (future) |
| 11 | WebSocket | Collaborative features (future) |

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
| ADR-004a-proposal | SSE for real-time LEC updates (Phase 8) |
| ADR-004b-proposal | WebSocket for collaboration (Phase 11) |
| ADR-005-proposal | Backend caches LECCurveData; frontend just displays |
| ADR-006-proposal | Event types for real-time updates |
| ADR-007-proposal | Scenario UI patterns (Phase 10) |
| ADR-008-proposal | Error handling patterns (Phase 9) |
| ADR-009 | Frontend treats leaf/aggregate LEC uniformly |

---

## Next Steps

**Ready to proceed with Phase 1?** 

I'll set up the build pipeline and create the basic Vite structure. Let me know when you'd like to start, and we'll work through each checkpoint together.

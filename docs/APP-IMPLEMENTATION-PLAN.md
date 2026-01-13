# Laminar Frontend App Implementation Plan

## Overview

This document outlines an incremental plan to create a Laminar frontend app for the Register project. The app will provide a form for creating `RiskLeaf` entities with expert/lognormal mode toggle, validation feedback, and backend submission.

## Goals

1. **Expert/lognormal mode toggle** via radio button
2. **Same validation approach** as BCG app (FormState pattern)
3. **POST request submission** to backend
4. **Latest Laminar version** (17.2.1+)
5. **Good local developer experience** using Vite
6. **Meaningful test coverage** for view layer

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

---

## Dependencies Summary

```scala
// In build.sbt app module
libraryDependencies ++= Seq(
  "com.raquo" %%% "laminar" % "17.2.1",
  "com.softwaremill.sttp.tapir" %%% "tapir-sttp-client" % tapirVersion,
  "com.softwaremill.sttp.client4" %%% "core" % sttpVersion,
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
  }
}
```

---

## Next Steps

**Ready to proceed with Phase 1?** 

I'll set up the build pipeline and create the basic Vite structure. Let me know when you'd like to start, and we'll work through each checkpoint together.

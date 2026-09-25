# ADR-019: Frontend Component Architecture — Laminar + Scala.js

**Status:** Accepted  
**Date:** 2026-02-10  
**Tags:** frontend, laminar, scala-js, component-design, reactive

---

## Context

- Frontend uses **Scala.js** with **Laminar** for reactive UI rendering
- The app module depends on `common.js` — shared DTOs, Iron types, and Tapir endpoints compile for both JVM and JS
- Laminar's model is **signals down, events up** — parent components own state, children receive signals and emit callbacks
- UI validation reuses the same `ValidationUtil` (Iron-based) as the backend — single source of truth for field constraints
- Laminar's direct DOM binding (no virtual DOM) means component updates must be managed via **Signal granularity** — coarse signals cause unnecessary DOM updates
- Named, mutually exclusive phases with real transitions between them are a different kind of state than a value that is purely a projection of other state — collapsing both into ad-hoc `Var` correction invites feedback loops indistinguishable, at the call site, from a genuine external change

---

## Decision

### 1. Composable Functions over Class Hierarchies

Components are **`object` methods returning `HtmlElement`**, not abstract classes with template methods.
State flows via function parameters, not inheritance:

```scala
// GOOD: Composable function — caller controls state
object FormInputs:
  def textInput(
    labelText: String,
    valueVar: Var[String],
    errorSignal: Signal[Option[String]],
    filter: String => Boolean = _ => true,
    onBlurCallback: () => Unit = () => ()
  ): HtmlElement = div(...)
```

### 2. Signals Down, Callbacks Up

Parent owns `Var`s and derived `Signal`s. Children receive them as parameters.
Children notify parent via `() => Unit` or `Observer[A]` callbacks — never by mutating shared state:

```scala
// Parent (orchestrator) — owns state, passes signals down
object TreeBuilderView:
  def apply(): HtmlElement =
    val state = TreeBuilderState()
    div(
      RiskLeafFormView(state),              // child receives state
      TreePreview(state.treeSignal)         // child receives derived signal
    )

// Child — receives signal, emits via callback
object RiskLeafFormView:
  def apply(builderState: TreeBuilderState): HtmlElement =
    val leafState = RiskLeafFormState()
    div(
      FormInputs.textInput(
        valueVar = leafState.nameVar,
        errorSignal = leafState.nameError,
        onBlurCallback = () => leafState.markTouched("name")  // callback up
      ),
      button(onClick --> (_ => builderState.addLeaf(leafState)))  // callback up
    )
```

### 3. State Layering: Form State vs Builder State

Separate **field-level validation** (per-form) from **assembly-level coordination** (cross-form):

| Layer | Responsibility | Example |
|-------|---------------|---------|
| **FormState** (trait) | Error signal aggregation, `hasErrors` | `FormState.errorSignals` |
| **LeafFormState** | Single leaf field `Var`s + validation signals | `nameVar`, `probabilityVar`, `nameError` |
| **BuilderState** | Collection of validated items, `toRequest()` | `Var[List[LeafEntry]]`, parent options |

FormState instances are **short-lived** — created when the sub-form mounts, discarded after "Add" commits the data.
BuilderState is **long-lived** — persists for the duration of tree construction.

### 4. Derived Signals for Computed Views

Read-only views derive from state signals — never store redundant state:

```scala
// GOOD: derived signal, always consistent
val parentOptions: Signal[List[String]] =
  portfolios.signal.map(ps => "(root)" :: ps.map(_.name))

// GOOD: ASCII tree preview derived from portfolios + leaves
val treePreview: Signal[String] =
  portfolios.signal.combineWith(leaves.signal).map(renderAsciiTree)
```

### 5. Reusable Input Components are Pure Functions

Input components in `FormInputs` are **stateless functions** — they receive a `Var` to bind and a `Signal` for error display.
They never create their own `Var`s or perform validation logic:

```scala
// FormInputs.textInput — pure view function
// FormInputs.radioGroup[T] — generic, type-parameterized
// FormInputs.submitButton — disabled state via Signal[Boolean]
// FormInputs.crossFieldError — conditional error display
```

### 6. State Machines for Named Phases, Derived Signals for Projections

A value with a small set of named phases and real transitions between them is
modeled as a sealed enum/ADT plus a pure transition or decision function —
never as a reactive subscription that corrects a raw `Var` based on its own
changes. Pattern 4 (Derived Signals) stays the tool for the other case: a
value with no phases of its own, purely computed from other state.

```scala
// GOOD: named phases, explicit transitions
enum FormMode:
  case Blank
  case Locked(target: FormTarget)
  case Editing(target: FormTarget)
  case Templating(source: FormTarget)

// GOOD: a load event resolved to a named outcome, then applied imperatively
enum TreeLoadDecision:
  case SameContext, ReloadClean, NeedsConfirm

def decide(previousId: Option[TreeId], loadedBranch: Option[ScenarioName], ...): TreeLoadDecision = ...
```

A value qualifies for a state machine, not a derived signal, when: (a) it has
a small, named set of possible phases: not just `Boolean`/`Option`; (b) at
least one transition is triggered by something other than "this value's own
inputs changed" — a submit, a load, a user action; (c) different phases
enable or disable different behavior, rather than just displaying differently.

### 7. Error Presentation: Inline by Default, One Global Banner as the Catch-All

A view that issued a request owns its failure and renders it inline
(`LoadState.Failed`, `SubmitState.Failed`). Failures no view owns — a
fire-and-forget call, the startup health probe, an expired workspace — go to a
single `GlobalError` signal that `ErrorBanner` renders above the layout. An
error belongs to exactly one tier; sending a per-view failure to the banner as
well shows it to the user twice.

`GlobalError` is the app module's own sealed enum, named apart from the server's
`AppError` because the two describe different things — what went wrong, versus
what the user is told:

```scala
enum GlobalError:
  case ValidationFailed(errors: List[ValidationError])
  case NetworkError(message: String)
  case Conflict(message: String)
  case ServerError(message: String)
  case DependencyError(message: String)
  case WorkspaceExpired(message: String)
```

Three properties keep it usable:

- **Variants are pure values.** No variant carries a callback or an effect. Which
  action the user is offered — a refresh on `Conflict`, informational rather than
  error styling on `WorkspaceExpired` — is chosen at the rendering site, so one
  value can render differently in different contexts (ADR-010: errors are values).
- **Classification is compiler-enforced.** `fromThrowable` routes domain failures
  through `fromAppError`, whose match over the sealed `AppError` hierarchy is
  exhaustive, so a new server-side error family cannot quietly degrade into
  `NetworkError`.
- **The SPA does not retry.** Request-path resilience is the mesh's
  (ADR-012 §4, ADR-031); a failure that reaches the browser is final for that
  request and is displayed rather than re-attempted.

---

## Code Smells

### ❌ Template Method / Class Hierarchy

```scala
// BAD: Inheritance couples layout to base class
abstract class FormPage[S <: FormState](title: String):
  val stateVar: Var[S] = Var(basicState)
  def renderChildren(): List[HtmlElement]
  def apply(): HtmlElement = div(h2(title), renderChildren())

class LeafFormPage extends FormPage[LeafState]("Create Leaf"):
  override def renderChildren() = List(...)
```

```scala
// GOOD: Composition via function parameters
object RiskLeafFormView:
  def apply(builderState: TreeBuilderState): HtmlElement =
    val state = RiskLeafFormState()
    div(h2("Add Risk Leaf"), ...)
```

### ❌ Validation via `.now()` on Submit

```scala
// BAD: Imperative validation at submit time
def handleSubmit(): Unit =
  val name = nameVar.now()
  if name.isBlank then showError("Name required")  // imperative check
```

```scala
// GOOD: Reactive validation signals, always current
private val nameErrorRaw: Signal[Option[String]] = nameVar.signal.map { v =>
  ValidationUtil.refineName(v) match
    case Right(_) => None
    case Left(errors) => Some(errors.head.message)
}
```

### ❌ Child Mutating Parent State Directly

```scala
// BAD: Child knows about and mutates parent internals
def leafForm(parentLeaves: Var[List[LeafEntry]]): HtmlElement =
  button(onClick --> (_ => parentLeaves.update(_ :+ buildLeaf())))
```

```scala
// GOOD: Child calls parent-provided callback
def leafForm(onAddLeaf: LeafEntry => Unit): HtmlElement =
  button(onClick --> (_ => onAddLeaf(buildLeaf())))
```

### ❌ Redundant State (Computed Values in Var)

```scala
// BAD: Manual sync of derived data
val parentOptions: Var[List[String]] = Var(List("(root)"))
def addPortfolio(name: String): Unit =
  portfolios.update(_ :+ entry)
  parentOptions.update(_ :+ name)  // manual sync — can drift
```

```scala
// GOOD: Derived signal — always consistent
val parentOptions: Signal[List[String]] =
  portfolios.signal.map(ps => "(root)" :: ps.map(_.name))
```

### ❌ Self-Correcting Reactive Var

```scala
// BAD: subscription reacts to the same Var it corrects — races with any
// caller's own multi-step reset/populate sequence, since Airstream gives
// no guarantee about propagation order between the two
parentVar.signal.combineWith(options) --> { (sel, opts) =>
  if !opts.contains(sel) then parentVar.set(opts.head)
}
```

```scala
// GOOD: correction triggers only off the external signal that actually
// invalidates the value, never off the value's own changes — cannot
// self-trigger, cannot race a caller's own write to the same Var
options --> { opts =>
  if !opts.contains(parentVar.now()) then parentVar.set(opts.head)
}
```

### ❌ An Effect Stored Inside the Error Value

```scala
// BAD: the error carries its own remedy, so it renders one way everywhere
case Conflict(message: String, onRefresh: () => Unit)
```

```scala
// GOOD: a pure value; the rendering site decides what to offer
case Conflict(message: String)
// ErrorBanner chooses "Reload trees", or no action at all, per context
```

### ❌ Duplicating a Per-View Error into the Global Banner

```scala
// BAD: the view already renders this inline — the user sees it twice
submitState.set(SubmitState.Failed(e))
globalError.set(Some(GlobalError.fromThrowable(e)))
```

```scala
// GOOD: the owning view renders it; the banner takes only unowned failures
submitState.set(SubmitState.Failed(e))
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `Main` | Orchestrator — creates shared `TreeBuilderState`, wires layout (Pattern 2 owner) |
| `FormInputs.textInput` | Stateless function, Var + Signal params (Pattern 5) |
| `FormInputs.radioGroup[T]` | Generic type-parameterized component (Pattern 5) |
| `RiskLeafFormState` | Field-level Vars + reactive validation (Pattern 3) |
| `FormState.hasErrors` | Derived Signal from `errorSignals` (Pattern 4) |
| `TreeBuilderView` | Receives shared state, owns submit lifecycle (Pattern 2 consumer) |
| `TreePreview` | Pure derived view: Signal → HtmlElement (Pattern 4) |
| `SplitPane` | Stateless layout — inline flex-weight bridge, no state (Pattern 5) |
| `AppShell` | Pure structural shell — receives all state as signals, owns no effects (Pattern 1/2) |
| `FormMode` | Sealed enum + pure transition/dirty-check functions (Pattern 6) |
| `TreeLoadPolicy.decide` | Pure decision function producing a named outcome type (Pattern 6) |
| `GlobalError` | Sealed enum of failures no view owns; pure values, exhaustive classification (Pattern 7) |
| `ErrorBanner` | Renders the global signal; picks per-variant styling and action (Pattern 7) |
| `HealthState` + `Main` | Health probe state extracted from view; `Main` orchestrates one-shot startup probe |
| `DistributionChartPlaceholder` | Stateless placeholder in Design view for future modelling chart (Pattern 5) |

---

## References

- [Laminar documentation — State Management](https://laminar.dev/documentation)
- ADR-001: Validation strategy (Iron types reused in frontend)
- ADR-009: Iron type constraints (shared via `common.js` cross-project)
- ADR-010: Errors are values — the rule Pattern 7's pure variants follow
- ADR-012 §4 / ADR-031: request-path resilience is the mesh's, which is why the SPA does not retry

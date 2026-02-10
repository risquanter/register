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
- No virtual DOM — Laminar binds reactive signals directly to real DOM nodes

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

---

## Implementation

| Location | Pattern |
|----------|---------|
| `FormInputs.textInput` | Stateless function, Var + Signal params (Pattern 5) |
| `FormInputs.radioGroup[T]` | Generic type-parameterized component (Pattern 5) |
| `RiskLeafFormState` | Field-level Vars + reactive validation (Pattern 3) |
| `FormState.hasErrors` | Derived Signal from `errorSignals` (Pattern 4) |
| `TreeBuilderView` | Orchestrator — owns BuilderState, passes to children (Pattern 2) |
| `TreePreview` | Pure function: Signal → HtmlElement (Pattern 4) |

---

## References

- [Laminar documentation — State Management](https://laminar.dev/documentation)
- ADR-001: Validation strategy (Iron types reused in frontend)
- ADR-009: Iron type constraints (shared via `common.js` cross-project)

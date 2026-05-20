---
applyTo: "modules/app/**/*.scala"
---

# Laminar Reactive UI Architecture (ADR-019)

## Before you build any component

1. Who owns the `Var`? (Always the parent that coordinates state.)
2. What `Signal`s does the parent pass down?
3. What callbacks propagate events up to the parent?

Answer all three before writing any `HtmlElement`-returning function.

## Signals down, callbacks up

Parents own `Var`s and derived `Signal`s. Children receive them as parameters.
Children notify parents via `() => Unit` or `Observer[A]` callbacks — never by
reading or mutating shared mutable state directly.

```scala
// ✅ CORRECT — parent owns Var, child receives Signal + callback
object MyComponent:
  def apply(
    valueSignal: Signal[String],
    onSubmit: String => Unit
  ): HtmlElement = ???

// ❌ NEVER — child reads or mutates a Var it does not own
object MyComponent:
  def apply(sharedVar: Var[String]): HtmlElement =
    div(onClick --> (_ => sharedVar.set("mutation")))  // wrong owner
```

## Reusable components are stateless

Reusable component functions never create internal `Var`s.
Internal state belongs to `FormState` or `BuilderState`, not the component function.
A component that creates its own `Var` cannot be reused — the caller loses control
of the state lifecycle.

## Never call `.now()` in a rendering pipeline

`.now()` reads the current snapshot and breaks reactivity. Always derive via `Signal`.

```scala
// ❌ NEVER in rendering
val current = myVar.now()
div(span(current))  // static — will not update

// ✅ CORRECT
div(child.text <-- myVar.signal)
```

## State layering

Two distinct scopes — assign new state to exactly one before writing:

| Layer | Type | Lifetime | Owns |
|---|---|---|---|
| Field-level | `FormState` | Form submission | Per-field `Var`, validation errors |
| Assembly-level | `BuilderState` | Cross-form session | Assembled tree, cross-form coordination |

State that crosses both scopes is a design signal — stop and ask before proceeding.

## Signal granularity

Coarse signals cause unnecessary DOM re-renders. Derive the smallest `Signal`
needed at each binding site.

```scala
// ❌ COARSE — entire state redraws on any change
child.text <-- builderState.signal.map(_.someField.toString)

// ✅ GRANULAR — only redraws when someField changes
child.text <-- builderState.somFieldSignal.map(_.toString)
```

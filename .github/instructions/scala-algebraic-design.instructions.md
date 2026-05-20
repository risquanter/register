---
applyTo: "**/*.scala"
---

# Algebraic Design Vocabulary

Algebraic design is a modelling language, not a compliance rule.
Consult this file at three points:
- When introducing a new field or parameter (Layer A₀)
- When introducing a new type (Layer A)
- Before writing a specific function (Layer B)

---

## Layer A₀ — Domain primitive recognition

*Asked when introducing any new field, parameter, or case class member.*

### The core question: what are the valid values?

If the answer is a strict subset of what `String`, `Int`, `Long`, or `Double`
allows, a raw JVM type is wrong. Every consumer must then defend itself
independently; the compiler enforces nothing. Iron refinements move that defence
to the entry point, once, permanently.

### Length and size bounds

Does the field have a maximum length (DB column, UI limit, protocol limit)?
→ `MaxLength[N]`. Ask what the column width is before writing the type.

Does the field have a minimum length (non-empty, minimum content)?
→ `MinLength[N]`.

**Signal words:** `name`, `label`, `description`, `title`, `slug` — all have
implicit length bounds. If you cannot state the max length, find out before writing.

### Character set restrictions

Will the value be rendered in HTML?
→ Disallow `<>"'&` at minimum. A named Iron predicate makes the intent legible.

Will the value appear in a file path or URL segment?
→ Disallow `/\..%` and control characters.

**Signal words:** `name`, `path`, `slug`, `query`, `label` — all have implicit
character restrictions. Ask what the valid characters are before writing the type.

Character-set refinements are your primary domain-level defence against injection.
They are not a replacement for context-specific escaping (HTML encoding,
parameterised queries) but reduce the attack surface before any processing runs.

### Numeric ranges

Is the value a probability or percentage?
→ `Interval.Closed[0.0, 1.0]` or `Interval.Closed[0, 100]`.

Is the value a count that cannot be negative?
→ `NonNegative` or `Positive`.

**Signal words:** `count`, `size`, `probability`, `weight`, `rate`, `score` — each
has a natural valid range. State it in the type.

### Format constraints

Is the value a ULID?
→ Iron predicate (26-char Crockford base32).

Is the value an email, URL, ISO date, or other structured format?
→ Iron regex or named predicate.

**Signal words:** `id`, `identifier`, `email`, `url`, `date`, `timestamp`.

### Type confusion (semantic identity)

Could this field be confused with another field of the same raw underlying type?
`userId: String` and `treeId: String` are both strings — passing one where the
other is expected compiles silently and fails at runtime or never.

→ Iron opaque type + nominal `case class` wrapper per ADR-018. Each domain ID
concept gets its own type even if the underlying encoding is the same ULID format.

### Provenance

Value from user input (HTTP body, query param, header)?
→ **Always refine.** The attacker controls the raw value.

Value from a DB column with a constraint?
→ The refinement documents the constraint. `VARCHAR(100) NOT NULL` →
`MinLength[1] & MaxLength[100]`. Divergence is a schema mismatch bug.

Value from internal computation?
→ Consider whether cross-type confusion is possible. Refinement may still apply.

### Within-domain adhesion

Layer A₀ fires when introducing any new **field, parameter, or case class member** —
not only at the HTTP boundary. The questions below apply equally when writing a new
helper function, private method, or local `val`.

Once a value enters the domain as `SafeName`, every subsequent function that
conceptually operates on "the name of a node" should accept and return `SafeName`,
not `String`. Iron types are adhesive — they should propagate through all domain
operations, not only survive the boundary crossing.

**`.value` is a widening signal.** The only valid reasons to call `.value` on an
Iron-refined type are:

1. Passing to a third-party library that has no knowledge of Iron types.
2. Serialisation at an output boundary (JSON encoder, Tapir codec).
3. Writing to the database in a repository method.

Every other `.value` call is a within-domain widening. Apply the Layer A₀
questions to the receiving parameter before proceeding:

```scala
// ❌ WIDENING — concept is SafeName, parameter is String
def formatDisplayName(name: String): String = ???

// ✅ ADHESION — refined type propagates
def formatDisplayName(name: SafeName): SafeName = ???

// ❌ WIDENING — raw intermediate, adhesion breaks here
val nameStr: String = node.name.value
someHelper(nameStr)

// ✅ ADHESION — pass the refined type directly
someHelper(node.name)
```

If the function you are calling takes `String` and you own that function, fix its
signature first. If you do not own it (third-party library), re-refine the output
before returning it into the domain:

```scala
// Third-party call: must re-enter the domain through the smart constructor
val rawResult: String = thirdPartyLib.transform(name.value)
SafeName.from(rawResult) // back through the boundary
```

**Detection heuristic:** search for `.value` in any file you are editing.
For each call: is it in a codec, a repository, or a third-party bridge? If not,
the type that value flows into may need to be the refined type instead.

### Example

Introducing `SafeName` for a tree node:
1. Valid values: `VARCHAR(200)`, rendered in HTML → max 200 chars, no control
   chars, no `<>"'&`.
2. Distinct from other IDs? Yes — no confusion risk.
3. From user input? Yes → must refine.

```scala
type SafeName = String :| (MinLength[1] & MaxLength[200] & SafeText)
// where SafeText is a named Iron predicate filtering <>"'& and control chars
```

Define the type, then the smart constructor, then the field.
Once in place, Layer contract enforcement handles the rest — codec validates, service trusts.

---

## Layer A — Aggregate type-design recognition

*Asked when introducing any new type. Answer before defining methods or fields.*

### Monoid / Semigroup recognition

1. Will two instances of this type ever be combined into one? → Semigroup candidate.
2. Is there a meaningful "empty", "identity", or "nothing happened" state? → Monoid
   (not just Semigroup). Define `empty` before writing any aggregation caller.
3. Does the combination produce the same result regardless of order? → Commutative
   Monoid. Document this — it enables safe parallelism.
4. Will instances be folded over a collection that may be empty? → Must be Monoid;
   Semigroup alone requires a non-empty guarantee the caller may not provide.
5. Does the type represent a result, summary, accumulation, or aggregate? → Monoid
   signal. Check whether a zero element exists.

*Example — `RiskResult`:* "outcomes combined when aggregating portfolios" → Q1 yes;
"zero trials / no losses" → Q2 yes; "portfolio order irrelevant" → Q3 yes.
Conclusion: define `Identity[RiskResult]` (ZIO Prelude) before writing any
aggregation method. The instance is the contract; methods are consumers of it.

### Functor recognition

1. Is the type parameterized by a payload type `A` (`Wrapper[A]`)? → Functor.
   Define `map[B](f: A => B): Wrapper[B]` before writing any specialized transform.
2. Does the type have a "success" case with data and one or more structural cases
   (Idle, Loading, Failed) that should pass through unchanged? → Functor on the
   success case. Without `map`, every caller repeats the structural threading.

### Applicative / Validation recognition

1. Multiple fields, each of which can fail independently, all failures surfacing
   together? → `Validation[E, A]` (Applicative). Do not use `.flatMap`.
2. Does the type represent a "checked" or "validated" thing wrapping its proof? →
   Iron refinement + smart constructor returning `Validation`.

### Traversable recognition

1. Will you apply an effectful operation to each element of a collection and need
   all results? → `ZIO.foreach` (sequential) or `ZIO.foreachPar` (parallel).
   Do not write a manual accumulator loop.
2. Is processing order semantically significant? → Sequential; if not → parallel.

### Monad recognition

1. Does computing the next value require the result of the previous? → Monad /
   `flatMap` / `for`-comprehension.
2. Are computations independent? → Prefer Applicative over Monad; Applicative
   permits parallel execution. `flatMap` implies sequencing even when unnecessary.

---

## Layer B — Operation lookup

*Asked before writing a specific function.*

| Domain operation | Structure | ZIO Prelude / Scala type | When |
|---|---|---|---|
| Combine two results of same type | Monoid / Identity | `Identity[A]`, `Semigroup[A]` | Aggregating nodes, merging partials |
| Accumulate all validation errors | Applicative | `Validation[E, A]` | Any multi-field input validation |
| Transform a wrapped value | Functor | `.map` on ADT or ZIO effect | Change the data, preserve the structure |
| Chain dependent effects | Monad | ZIO `flatMap`, `for` | Step B needs the result of step A |
| Apply effects to a collection | Traversable | `ZIO.foreach`, `ZIO.foreachPar` | Simulate all children, validate a list |
| Fold a tree bottom-up | Catamorphism | Explicit recursion on `TreeIndex` | Aggregate subtrees, compute depth |

### Additional rules

**Before writing an aggregation function:** confirm the type is a Monoid (Layer A).
If it is not yet — add the instance first, then write the aggregation.
Do not write `reduce` with a hard-coded zero inline.

**Before writing a pipeline of transforms:** verify associativity.
If `(f andThen g) andThen h ≢ f andThen (g andThen h)` for any input, the
abstraction is unsound. Surface the tension before shipping.

**Type class instances must satisfy laws.** An `Identity` that is not truly
identity, or a `combine` that is not associative, is a latent bug that law tests
would catch. Write law tests for every new instance. See ADR-009 for the pattern.

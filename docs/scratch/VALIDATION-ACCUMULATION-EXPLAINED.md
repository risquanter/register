# Accumulating validation, from the ground up

A walkthrough of how this codebase reports several input errors at once, why
the types have to be shaped the way they are, and where the shape stops being
load-bearing and becomes an indirection. Written for a reader who knows Scala
but not ZIO.

Companion to `MITIGATION-VALUATION-EXPLAINED.md`, which does the same for the
mitigation value model.

---

## 1. The problem this is all for

A user submits a risk tree with two things wrong: twelve thousand nodes, and
two nodes called "Supplier outage". Only one of those is reported, and the user
fixes it, resubmits, and is told about the other. Two round trips for two
mistakes that were both visible on the first submission.

Reporting both at once is called **error accumulation**. It sounds like a
formatting concern and it is not. Whether it is even possible is decided by the
type the checks return, before any implementation choice is made. The rest of
this document derives that, then shows what the codebase does with it.

---

## 2. Two ways to put a failure in a type

### Either: one failure, and it stops there

Scala's `Either[E, A]` holds either a failure of type `E` on the left or a
success of type `A` on the right. Chaining is done with `flatMap`:

```scala
checkNodeCount(nodes).flatMap(_ => checkNodeNames(nodes))
```

Read what `flatMap` is handed. Its argument is a **function** from the first
check's success value to the second check. So the second check does not exist
as a value until the first one has succeeded and produced something to apply
the function to.

Now suppose the first check fails. There is no success value, so the function
is never applied, so the second check is never built, so its error was never
created. Nothing can go back and find it. It is not that the implementation
chose to stop early — the error does not exist anywhere.

This is worth stating as a rule, because it holds for every type with this
shape, in every language:

> A type whose chaining operator takes the next step as a function of the
> previous step's result cannot report more than one failure. The second
> failure is unreachable by construction.

Types with that shape are called **monads**. `Either`, `Option`, `Future`, and
ZIO's own effect type are all monads. Their chaining operator is `flatMap`, and
all of them are fail-fast for exactly the reason above.

### Validation: several failures, collected

To collect two failures you must have both of them in hand at the same time.
That means the combining function must be handed both checks as finished
values, with neither depending on the other:

```scala
Validation.validateWith(checkNodeCount(nodes), checkNodeNames(nodes))((_, _) => ())
```

Both arguments are evaluated into values before `validateWith` sees them.
Neither is a function of the other. So if both failed, both failure sets exist,
and the combining step can take their union.

A type that supports combining independent values this way, but does **not**
support making the second depend on the first, is called an **applicative**.
Every monad is also an applicative, but the reverse does not hold, and the
types built specifically for accumulation are applicatives that deliberately
are not monads.

`Validation` comes from **zio-prelude** (a library of such structures,
published alongside ZIO). Its full type is `Validation[+E, +A]`: on failure it
holds one or more errors of type `E`; on success it holds one value of type `A`.
"One or more" is literal — the failure side is a `NonEmptyChunk[E]`, a sequence
the type system guarantees is not empty, so a failed `Validation` can never
claim to have failed without saying why.

In this codebase `E` is always `ValidationError`, a record of three things:

```scala
ValidationError(field = "nodes.name", code = ValidationErrorCode.AMBIGUOUS_REFERENCE,
                message = "duplicate node name(s): Supplier outage")
```

`field` is the path the client sent, so a form can highlight the right input.
That is the whole reason accumulation is worth having: three errors with three
field paths light up three fields at once.

---

## 3. Why a successful check still has to return something

Here is the part that looks like a wart and is not.

`Validation[E, A]` has a success payload of type `A`, and the combining
functions are generic in `A` — they work for any payload and do not know
anything about it. So a check must pick some `A`, even when checking is all it
does.

Consider "no two nodes in this tree share a name". It is handed the node
collection and answers a question about it. It has nothing to hand back: the
nodes were already validated individually, and uniqueness is a property of the
set, not a new value. The honest type for "this succeeded and there is nothing
to tell you" is `Unit`, the type with exactly one value:

```scala
  private def requireDistinctNodeNames(nodes: Seq[RiskNode]): Validation[ValidationError, Unit] = {
    val dups = nodes.groupBy(_.name.value).collect { case (n, ns) if ns.sizeIs > 1 => n }
    if (dups.isEmpty) Validation.succeed(())
    else Validation.fail(ValidationError(
      field = "nodes.name",
      code = ValidationErrorCode.AMBIGUOUS_REFERENCE,
      message = s"duplicate node name(s): ${dups.toList.sorted.mkString(", ")}"
    ))
  }
```

`Validation.succeed(())` is "succeeded, payload is the one value of `Unit`".
`Validation.fail(e)` is "failed, here is the error".

So `Validation[E, Unit]` is not a workaround for an awkward library. It is the
only accurate encoding of a successful fact-check in a structure that carries a
payload. There are thirty-six such declarations in `modules/common`, and the
`Unit` is right in all of them.

---

## 4. Checking an invariant is not the same as parsing

There is a second kind of validation in the codebase, and distinguishing the
two explains a lot of the code's shape.

**Parsing** means the validation is *how you obtain* the refined thing. Look at
`ValidationUtil.refineName`:

```scala
  def refineName(value: String, fieldPath: String = "name"): Either[List[ValidationError], SafeName.SafeName]
```

It is handed a raw `String` and returns a `SafeName`, a type that cannot be
constructed any other way. The returned value **is** the evidence the check
ran: holding a `SafeName` later in the program proves the name passed the
character whitelist, and no later code has to re-check it. This is the idea
behind the phrase "parse, don't validate" — a validation that returns nothing
leaves you holding the same unproven input you started with, so someone
downstream will check it again.

**Checking an invariant** means asking a question about data you already have.
`requireDistinctNodeNames` is handed the nodes and gives back nothing, because
there is no new proof-carrying value to make. The caller already holds the
nodes.

zio-prelude has a constructor built for the parsing case:

```scala
Validation.fromPredicateWith[E, A](error)(value)(predicate): Validation[E, A]
```

It returns the **value**, not `Unit`, precisely so the result can carry the
evidence onward. It appears twice in this codebase, at `RiskTree.scala:173` and
`RiskNode.scala:561`.

Note what it is *given*, as well as what it returns. The error is a parameter,
so the caller builds it before the predicate has been consulted. That is free
when the message is a constant and not free when it interpolates the offending
data, as most of this codebase's messages do:
`s"duplicate node name(s): ${dups.toList.sorted.mkString(", ")}"` builds a
string every time the check succeeds as well as every time it fails. Trees are
validated on every read, not only on every write, so a cost like that is
multiplied by read frequency. A helper that takes the message by name —
`message: => String` in Scala — builds it only on the failing path. The lesson
generalises: when deciding whether a library function is the one you want, read
what it accepts, not only what it returns.

The distinction matters because it tells you which payload is correct, rather
than leaving it to taste:

| The check is | Payload | Why |
|---|---|---|
| How you obtain the refined value | the value | Holding it later proves the check ran |
| A question about data the caller already holds | `Unit` | There is nothing new to hand back |

---

## 5. Putting several checks together

Two combining functions are in use, and the choice between them is decided by
one thing: do the checks have the same payload type or different ones?

### `validateWith` — a fixed number of checks with different payloads

```scala
Validation.validateWith(v1, v2, v3)(f)
```

It takes a fixed count of checks, each with its own payload type, and hands all
three results to `f` positionally. It exists for the case where each check
contributes a different piece of the thing being built.

`RiskTree.fromNodes` is that case. Of its five checks, `TreeIndex.fromNodeSeq`
produces a tree index and `resolveSeedVarHighWater` produces a watermark, while
the other three produce `Unit`:

```scala
    Validation
      .validateWith(
        TreeIndex.fromNodeSeq(nodes),
        requireDistinctSeedVarIds(nodes),
        resolveSeedVarHighWater(nodes, seedVarHighWater),
        validateNodeCount(nodes),
        requireDistinctNodeNames(nodes)
      ) { (index, _, highWater, _, _) => (index, highWater) }
```

The three underscores are the two useful payloads being picked out of five
positions. Mixed payload types are exactly what `validateWith` is for, so this
is the right call here.

### `validateAll` — any number of checks with the same payload

```scala
Validation.validateAll(checks): Validation[E, Collection[A]]
```

It takes a collection of checks that all share one payload type and gives back
a collection of results. Adding a check means adding a list element. There is
no positional function to keep aligned, and no arity limit.

`TreeBuilderLogic` uses it three times, following it with `.as(())` to replace
the collection of `Unit`s with a single `Unit`:

```scala
    Validation.validateAll(checks).as(())
```

### The rule for choosing

Payloads differ → `validateWith`, and the positional function is doing real
work. Payloads are all the same → `validateAll`, and a `validateWith` whose
function discards every argument is `validateAll` written out by hand. The
signal is a function of nothing but underscores: `{ (_, _, _) => () }` means
the arity is carrying no information.

### Where the two meet: `flatMap` between stages

`fromNodes` has two stages, and between them it uses `flatMap`:

```scala
      .flatMap { (index, highWater) => … }
```

That is not a mistake. The second stage needs the `index` the first stage
produced — one of its checks asks whether `rootId` is present in that index.
The dependency is real, so accumulation across the boundary is impossible, and
`flatMap` is the honest way to say "this stage cannot run until that one
succeeded". Checks accumulate *within* each stage.

---

## 6. Worked example: the two-mistake tree

Take the submission from section 1 — twelve thousand nodes, two of them called
"Supplier outage" — and run it through `RiskTree.fromNodes`.

Stage one builds its five checks as values:

| Check | Payload type | Result |
|---|---|---|
| `TreeIndex.fromNodeSeq(nodes)` | `TreeIndex` | succeeds; parent and child maps are consistent |
| `requireDistinctSeedVarIds(nodes)` | `Unit` | succeeds; no two leaves share a random-stream identifier |
| `resolveSeedVarHighWater(nodes, None)` | `SeedVarId` | succeeds; derives the highest identifier in use |
| `validateNodeCount(nodes)` | `Unit` | **fails**: `[nodes] too many nodes: 12000 exceeds the limit of 10000` |
| `requireDistinctNodeNames(nodes)` | `Unit` | **fails**: `[nodes.name] duplicate node name(s): Supplier outage` |

All five were evaluated before `validateWith` combined anything, so both
failures exist. `validateWith` sees at least one failure, skips the combining
function entirely, and returns a failed `Validation` whose `NonEmptyChunk`
holds both errors. Stage two never runs, because `flatMap` has no success value
to feed it.

The client receives both, each with its own field path, and fixes both in one
pass.

Now change one thing: write stage one with `flatMap` instead.

```scala
validateNodeCount(nodes).flatMap(_ => requireDistinctNodeNames(nodes))
```

`validateNodeCount` fails. The function passed to `flatMap` is never applied.
`requireDistinctNodeNames` is never called, so `dups` is never computed and the
duplicate-name error is never constructed. The response carries one error, the
user fixes it, resubmits twelve thousand nodes, and learns about the duplicate
name on the second round trip. The limit of one error per response is not a
policy anyone wrote down; it follows from `flatMap`'s type.

---

## 7. The mirror image: when `Unit` and a sequencing operator are an indirection

Everything above is about the accumulating case. There is a non-accumulating
case that looks identical on the surface and is not, and telling them apart is
the practical payoff of all this.

First, one piece of ZIO. `Task[A]` is a description of a computation that, when
run, either fails with a `Throwable` or succeeds with an `A`. Like `Either` it
has a failure channel and a success channel; unlike `Either` it is a
*description*, so building one runs nothing. The operator `*>` (also spelled
`zipRight`) means "run the left one, discard its success value, then run the
right one", and if the left one fails the right one never runs.

Here is a guard written the way the accumulating checks are written, in the
in-memory repository. It answers one question — is this the main branch? — and
reports the answer by succeeding with `Unit` or failing:

```scala
  private def requireMain(branch: BranchRef): Task[Unit] =
    if branch == BranchRef.Main then ZIO.unit
    else ZIO.fail(RepositoryFailure("In-memory repository has no branches: …"))

  override def create(…): Task[RiskTree] =
    requireMain(branch) *> ZIO.attempt { … }
```

Count the decisions. `requireMain` decides one thing: which of `BranchRef`'s
two situations it is holding. It encodes that answer as success-or-failure.
Then `*>` decides whether to run the body by reading which channel the guard
landed in.

Those are not two decisions. The encoding `Main → success`, `non-main →
failure` is total and reversible, so reading the channel tells you exactly what
reading the branch told you, and nothing more. The answer made a round trip
through a type that cannot hold anything the question did not already have.

Remove the round trip by letting the decision pick the continuation directly.
The guard takes the body as a by-name parameter and returns it from the
matching case:

```scala
  private def requireMain[A](branch: BranchRef)(effect: => Task[A]): Task[A] =
    branch match
      case BranchRef.Main => effect
      case _              => ZIO.fail(RepositoryFailure("In-memory repository has no branches: …"))

  override def create(…): Task[RiskTree] =
    requireMain(branch) {
      ZIO.attempt { … }
    }
```

Behaviour is identical, and the reason is that ZIO describes effects rather
than performing them. Under `*>` the body was built on the failing path and
then discarded unrun; under a by-name parameter it is not built at all. Nothing
observable differs either way.

### What separates the two cases

The question is not whether the helper returns `Unit`. It is **who reads the
result.**

- If the result is **combined** with another check's result so that several
  failures report together, the `Unit` is load-bearing. Accumulation combines
  values, and a continuation cannot be combined with another continuation —
  there is no meaningful union of "run this body" and "run that body". Keep the
  `Unit`. All thirty-six declarations in `modules/common` are here.
- If the result's only reader is a sequencing operator that discards it and
  continues — `*>`, `_ <- …`, `flatMap(_ => …)` — nothing is being combined.
  The guard is the sole decision, and routing its answer through the failure
  channel is a round trip. Pass the continuation instead.

One further condition keeps the second case narrow. The guard's body must be a
case analysis on a value's **cases** — a sealed type's variants, an enum, or
equality against a named constant — rather than a predicate computed over data.
Two guards in the server illustrate the difference:

- `RiskTreeRepositoryIrmin.ensureRootPresent` tests `nodes.exists(_.id ==
  rootId)`. That is a predicate over a collection, not a case analysis, so
  there is no weaker re-encoding of anything. It stays a guard.
- `RiskTreeServiceLive.ensureUniqueTree` tests `errors.nonEmpty` after an
  effectful lookup. It must perform an effect before it can answer, so it could
  not return a continuation even if that were wanted. It stays a guard.

---

## 8. Summary

| Question | Answer | Because |
|---|---|---|
| Can `flatMap`-chained checks report two errors? | No, ever | The second check is a function of the first's success value, so on failure it is never built |
| What makes accumulation possible? | Independent checks combined as values | Both failure sets exist at the moment of combining |
| Why does a successful check return anything? | `Validation[E, A]` carries a payload and the combinators are generic in `A` | A fact-check with nothing to report picks `Unit`, the type with one value |
| Payload: value or `Unit`? | Value if the check is how you obtain the refined thing; `Unit` if it asks about data the caller already holds | A returned value is evidence the check ran; re-returning the input is noise |
| `validateWith` or `validateAll`? | `validateWith` for mixed payload types, `validateAll` for one shared type | A combining function of nothing but underscores means the arity carries no information |
| Why does `fromNodes` use `flatMap` between its stages? | The second stage needs the first stage's index | A real dependency makes accumulation impossible, and `flatMap` says so honestly |
| When is a `F[Unit]` guard an indirection instead? | When its only reader is a sequencing operator, and its body is a case analysis rather than a computed predicate | The answer round-trips through a channel that holds nothing the question did not |

---

## Where the code is

| Thing | Location |
|---|---|
| `Validation[E, Unit]` invariant checks | `RiskTree.scala`, `RiskNode.scala`, `TreeIndex.scala`, `Distribution.scala`, `Mitigation.scala`, `TreeBuilderLogic.scala`, `RiskTreeRequests.scala`, `OpaqueTypes.scala` |
| Two-stage accumulation with a real dependency between stages | `RiskTree.fromNodes` |
| Iron refinement constructors — the parsing case | `ValidationUtil.scala` |
| `validateAll(…).as(())` — the homogeneous case | `TreeBuilderLogic.scala` |
| The guard that was turning its answer into a channel | `RiskTreeRepositoryInMemory.scala` |

# PLAN-VALIDATION-IDIOM — share one validation primitive, use zio-prelude's own traversal, and codify the three patterns behind both

**Status:** written 2026-10-04, not approved
**Plan code:** `VI` (decisions are labelled `VI-D-<n>`)
**Scope:** `modules/common` validation construction and combination, plus three
codified review patterns
**Relationship to other plans:** independent. It was split out of
PLAN-RISKTRANSFORM.md's M2 slice 6 by direction on 2026-10-04 and shares no
file with that slice's remaining work.

---

## 1. What this plan is for

Three findings came out of reviewing the branch guard in
`RiskTreeRepositoryInMemory`, which landed under PLAN-RISKTRANSFORM.md
§8.19.2c and is not repeated here.

**Finding 1 — one correct local abstraction is private to the wrong file, and
nine sites hand-write it.** `TreeBuilderLogic.requireCond` turns a computed
Boolean plus three error fields into a `Validation`. It is correct, and it is
`private` to a frontend tree-building file. Nine other sites across three files
write its two arms out by hand.

**Finding 2 — zio-prelude's collection traversal is rebuilt twice.**
zio-prelude is the library supplying `Validation`, the accumulating validation
type. It provides `Validation.validateAll` for combining a collection of checks
that share one payload type. Two files instead build that from a left fold over
pairwise `Validation.validateWith`, under two different names.

**Finding 3 — `validateWith`'s arity is used where it carries no
information.** `Validation.validateWith(v1, …, vn)(f)` exists to combine checks
whose payload types differ, handing each result to `f` positionally. At six
sites every payload is the same and `f` discards all of them, so the arity does
nothing and `validateAll` is the fitting combinator. The codebase already uses
`validateAll` four times, so this is an internal inconsistency rather than a
missing capability.

All three are hygiene, not defects. No behaviour is wrong today: accumulation
works, every error reaches the client with its field path, and the suite is
green. The reason to act is that the same two shapes have now been factored out
independently at three names in three files, which is a reliable signal that the
next person to need one will write a fourth.

The plan's fourth piece is the one with value beyond this codebase. Each finding
has a mechanical detection test, so the tests are written down as review
patterns rather than left as this plan's one-off cleanup.

### Terms used below

- **zio-prelude** — a library of algebraic structures published alongside ZIO.
  It supplies `Validation`.
- **`Validation[E, A]`** — a type holding either one or more errors of type `E`
  or one success value of type `A`. Combining two failed `Validation` values
  unions their errors, which is what makes reporting several input errors in one
  response possible.
- **applicative** — a structure whose combining operation takes its arguments as
  independent finished values. Only independence permits error accumulation; a
  structure whose next step is a function of the previous step's result (a
  **monad**, chained with `flatMap`) cannot report a second failure, because on
  the first failure the second step is never built. The derivation is written
  out in `docs/scratch/VALIDATION-ACCUMULATION-EXPLAINED.md`.
- **payload** — the success-side value of a `Validation`. A check that produces
  nothing uses `Unit`, the type with exactly one value.

---

## 2. What is in the code today

### 2.1 Ten sites lift a Boolean into `Validation` by hand

The shape is a two-arm decision on an already-computed Boolean: succeed with no
payload, or fail with one `ValidationError`. The representative instance, at
`RiskTree.scala:120-127`:

```scala
  private def validateNodeCount(nodes: Seq[RiskNode]): Validation[ValidationError, Unit] =
    if (nodes.sizeIs <= MaxNodes) Validation.succeed(())
    else Validation.fail(ValidationError(
      field = "nodes",
      code = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = s"too many nodes: ${nodes.size} exceeds the limit of $MaxNodes"
    ))
```

The ten sites, verified by reading each one:

| File | Lines | What each decides |
|---|---|---|
| `RiskTree.scala` | 121, 135, 202, 210, 218 | node count bound; node-name uniqueness; mitigation id uniqueness, name uniqueness and count bound |
| `TreeBuilderLogic.scala` | 99, 126 | every portfolio has a child; `requireCond` itself |
| `RiskTreeRequests.scala` | 145, 352 | no reserved node names; every portfolio has a child |
| `Distribution.scala` | 35 | percentiles or quantiles strictly increasing |

Three of them write the arms in the other order — `if (bad) fail else succeed`
— which is the same shape.

The `Unit` payload is correct at all ten and this plan does not change it. These
are invariant checks over data the caller already holds, so there is no refined
value to hand back.

### 2.2 Twenty-one further `Validation.succeed(())` sites are correct as they are

A search for `Validation.succeed(())` returns thirty-one hits. Only the ten
above are instances of the finding. The other twenty-one are arms of multi-way
pattern matches, where `Validation.succeed(())` means "this case is acceptable",
and they stay exactly as they are. Two reasons, and the second is the one that
would otherwise be missed.

First, a predicate constructor takes one Boolean and cannot express a three- or
four-way analysis. `Mitigation.scala:126-147` decides over the tuple
`(hasOverrideComponent(transform), stamp, anchor)` and has two accepting arms
and several rejecting ones. `TreeBuilderLogic.scala:82-85` decides over an
`Option` with a guard and has two accepting arms. Flattening either into a
Boolean would lose the structure the match makes explicit, which is the opposite
of what this plan is for.

Second, several of these arms build their failure message out of the value the
match just bound. `RiskTreeRequests.scala:296-298` binds the cycle it found and
names it in the message; `OpaqueTypes.scala:231-232` names the duplicate
identifiers it found. A constructor that takes a Boolean and a finished message
cannot do that without computing the message before knowing whether it is
needed.

The full list, for the record: `TreeBuilderLogic.scala` 56, 67, 82, 85;
`RiskTreeRequests.scala` 107, 197, 200, 214, 216, 298; `Mitigation.scala` 128,
129, 147, 163; `Distribution.scala` 85, 97; `TreeIndex.scala` 151, 227, 260;
`OpaqueTypes.scala` 232; `RiskNode.scala` 348.

### 2.3 Two local rebuilds of `validateAll`, under two names

**`TreeIndex.scala:150-153`** — a local `def` inside `fromNodeSeq`, two call
sites in the same method.

```scala
    def combineValidations(validations: List[Validation[ValidationError, Unit]]): Validation[ValidationError, Unit] =
      validations.foldLeft[Validation[ValidationError, Unit]](Validation.succeed(())) { (acc, v) =>
        Validation.validateWith(acc, v)((_, _) => ())
      }
```

**`RiskTreeRequests.scala:355-358`** — the same fold, generalised to carry an
index and build a list of results.

```scala
  private[requests] def collectAllWithIndex[A, B](as: Seq[A])(f: (A, Int) => Validation[ValidationError, B]): Validation[ValidationError, Seq[B]] =
    as.zipWithIndex.foldLeft[Validation[ValidationError, List[B]]](Validation.succeed(Nil)) {
      case (acc, (a, idx)) => Validation.validateWith(acc, f(a, idx))((xs, b) => xs :+ b)
    }.map(_.toSeq)
```

Both are `Validation.validateAll` written out. Together with
`TreeBuilderLogic.requireCond` from §2.1, that is three private helpers in three
files for two shapes, which is the codebase-level signal §5's second pattern
names.

### 2.4 Six sites where `validateAll` fits and `validateWith` is used

`Validation.validateAll(checks)` takes a collection of checks sharing one
payload type and returns a collection of results. `Validation.validateWith(v1,
…, vn)(f)` takes a fixed count of checks with possibly different payload types.
Where every payload is the same and `f` discards all of them, the second is the
first written out by hand.

| Site | Shape today | Why it qualifies |
|---|---|---|
| `RiskTree.scala:225` | `validateWith(distinctIdsV, distinctNamesV, countV) { (_, _, _) => () }` | Three `Unit` payloads, all discarded |
| `RiskNode.scala:576` | `validateWith(nonEmptyV, countV)((_, _) => arr)` | Both payloads are `Array[NodeId]`, both discarded, result is the input `arr` |
| `TreeIndex.scala:152` | the `combineValidations` fold | Homogeneous `Unit` list; the whole helper goes |
| `TreeIndex.scala:167` | `validateWith(childToParentV, parentToChildV)((_, _) => TreeIndex(…))` | Two `Unit` payloads, discarded, result is a constructed value |
| `Mitigation.scala:167` | `.validateWith(overrideRulesV, stepsV) { (_, _) => () }` | Two `Unit` payloads, both discarded |
| `RiskTreeRequests.scala:357` | the `collectAllWithIndex` fold | Homogeneous `B` list; the whole helper goes |

Two sites deliberately **stay** as `validateWith`, recorded so the question is
not reopened. `RiskTree.scala:162-168` — `fromNodes` stage one — mixes a
`TreeIndex`, a `SeedVarId` and three `Unit` payloads, and `validateAll` requires
one shared payload type, so it cannot express this; the three underscores in
`{ (index, _, highWater, _, _) => (index, highWater) }` are the unavoidable cost
of mixing. `Distribution.scala:87` combines `elementV` with three `Unit` checks,
for the same reason.

### 2.5 What is deliberately not touched

The thirty-six `Validation[ValidationError, Unit]` return types stay. Each is an
invariant check over data the caller already holds, so `Unit` is the accurate
payload, and each is combined with at least one sibling check so several
failures report together, which is what makes the `Unit` load-bearing. §5's
first pattern states the test that puts them on this side of the line.

`flatMap` between `fromNodes`' two stages stays. The second stage reads the
`TreeIndex` the first produced, so the dependency is real and accumulation
across the boundary is impossible.

---

## 3. Exact signatures

### 3.1 The shared predicate constructor

One shared member replaces `TreeBuilderLogic.requireCond` and the nine
hand-written sites. `VI-D-1` decides where it lives; the signature is the same
either way.

```scala
  /** Succeeds with no payload when `condition` holds, and otherwise fails with
    * a single `ValidationError` carrying the field path, code and message.
    * `message` is evaluated only on failure, so a call site may interpolate the
    * offending values into it without paying for that on the success path.
    * Combine several of these with `Validation.validateAll`.
    */
  def requireThat(
    condition: Boolean,
    field: String,
    code: ValidationErrorCode,
    message: => String
  ): Validation[ValidationError, Unit] =
    if condition then Validation.succeed(())
    else Validation.fail(ValidationError(field, code, message))
```

**Why this does not delegate to `Validation.fromPredicateWith`.** zio-prelude
provides `fromPredicateWith[E, A](error)(value)(predicate): Validation[E, A]`,
and it is the obvious candidate to build on. It is the wrong base here for two
reasons.

The first is that it takes the error, not a way of making one. Every call site
in §2.1 interpolates the offending values into its message —
`s"too many nodes: ${nodes.size} exceeds the limit of $MaxNodes"`,
`s"duplicate node name(s): ${dups.toList.sorted.mkString(", ")}"`. Handing that
to a by-value parameter builds the string on every successful validation.
`RiskTree.fromNodes` runs on every tree read, not only on writes, so that cost
is multiplied by read frequency rather than paid once per submission. The
by-name `message: => String` above keeps the current behaviour, where the string
is built only when it is needed. Whether zio-prelude declares its parameter
by-value or by-name is not something this repository can settle — the library is
consumed as a published artifact and has no local checkout — so the plan does
not depend on the answer: by-name is correct either way.

The second is that `fromPredicateWith` returns the value it was given, which
these sites do not want. It exists for the case where the check is *how the
refined value is obtained*, so that holding the result later proves the check
ran. An invariant check over data the caller already holds has nothing new to
return, and re-returning the input would mean every call site discarding it. The
two existing uses, at `RiskTree.scala:173` and `RiskNode.scala:561`, both do
exactly that discard.

So the correct action for Finding 1 is to **promote a correct local
abstraction**, not to replace it with a library call. `requireCond`'s body is
already right; what is wrong is that it is private to a frontend file while nine
sites elsewhere repeat it.

The name changes from `requireCond` to `requireThat` only to read as a sentence
at the call site — `requireThat(dups.isEmpty, "nodes.name", …)`. `VI-D-2`
decides whether to keep the original name instead.

### 3.2 A converted call site, in full

```scala
  private def requireDistinctNodeNames(nodes: Seq[RiskNode]): Validation[ValidationError, Unit] =
    val dups = nodes.groupBy(_.name.value).collect { case (n, ns) if ns.sizeIs > 1 => n }
    requireThat(
      dups.isEmpty,
      field   = "nodes.name",
      code    = ValidationErrorCode.AMBIGUOUS_REFERENCE,
      message = s"duplicate node name(s): ${dups.toList.sorted.mkString(", ")}"
    )
```

Field path, code and message text are unchanged at every one of the nine sites.
§6 names the check that proves it.

### 3.3 `TreeIndex.fromNodeSeq` — the local fold goes

`combineValidations` is deleted. Its two call sites and the final combination
become `validateAll`:

```scala
    val childToParentV: Validation[ValidationError, Unit] =
      Validation.validateAll(
        children.toList.flatMap { case (parentId, childIds) =>
          childIds.map(childId => validateChildToParent(nodes, parentId, childId))
        }
      ).as(())

    val parentToChildV: Validation[ValidationError, Unit] =
      Validation.validateAll(
        parents.toList.map { case (nodeId, parentId) =>
          validateParentToChild(nodes, nodeId, parentId)
        }
      ).as(())

    Validation
      .validateAll(List(childToParentV, parentToChildV))
      .as(TreeIndex(nodes, parents, children))
```

### 3.4 `RiskTreeRequests.collectAllWithIndex` — the fold goes, the name stays

The name says "traverse with the index", which the expansion does not, so the
signature and both call sites are kept and only the body changes:

```scala
  private[requests] def collectAllWithIndex[A, B](as: Seq[A])(f: (A, Int) => Validation[ValidationError, B]): Validation[ValidationError, Seq[B]] =
    Validation.validateAll(as.zipWithIndex.map { case (a, idx) => f(a, idx) }).map(_.toSeq)
```

### 3.5 The four remaining `validateAll` conversions

```scala
// RiskTree.scala:225 — validateMitigations
    Validation.validateAll(List(distinctIdsV, distinctNamesV, countV)).as(())

// RiskNode.scala:576 — childIdsValidation
    val childIdsValidation: Validation[ValidationError, Array[NodeId]] =
      Validation.validateAll(List(nonEmptyV, countV)).as(arr)

// TreeIndex.scala:167 — shown in §3.3 above

// Mitigation.scala:167 — create
    Validation
      .validateAll(List(overrideRulesV, stepsV))
      .as(new Mitigation(id, name, target, spec, precedence))
```

The `Mitigation` site also drops the `.map(_ => …)` that followed its
`validateWith`, because `.as` does both.

### 3.6 One consequence to verify, not assume

A hand-rolled left fold and a library traversal can emit accumulated errors in
different orders. The fold in `TreeIndex` combines pairwise from the left; what
order `validateAll` produces is a property of the library, not of this code. Any
specification that asserts the order of two or more accumulated errors is
therefore a site where the order has to be confirmed. §6 names the search that
finds those sites.

---

## 4. Decisions

### VI-D-1 — where the shared predicate constructor lives

**Status:** OPEN

**Goal.** Pick the home for `requireThat`. It is called from four files in
`modules/common`, one of which (`TreeBuilderLogic`) is shared with the Scala.js
frontend, so the home must be importable from both compilation targets.

**Option A — `ValidationUtil`**, at
`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala`.

- For: already imported by the call sites, and already holds `toValidation`, its
  one other `Validation`-returning member.
- Against: every other member returns `Either[List[ValidationError], A]` and
  performs an Iron refinement on raw input. `requireThat` does neither, so it
  sits off-theme in a file otherwise entirely about refining input at the
  boundary.

**Option B — a new object beside the error type**, for example
`new: modules/common/src/main/scala/com/risquanter/register/domain/errors/Checks.scala`.

- For: the file has exactly one subject, and it sits next to the
  `ValidationError` it constructs.
- Against: one more file and one more import, for one method.

**Option C — make `TreeBuilderLogic.requireCond` public and import it.**

- For: smallest change; the method exists and is correct.
- Against: `TreeBuilderLogic` is frontend tree-building logic. A domain
  invariant in `RiskTree` importing a primitive from it inverts the dependency
  direction the module layout otherwise keeps.

**My recommendation: Option B.** The deciding factor is that this is a
dependency question rather than a convenience one. `ValidationUtil` sits in the
`iron` package and its subject is refining raw input at the boundary, which is a
different job from asserting an invariant over already-refined data, and
Option C points a domain file at a frontend one. One small file in the `errors`
package is where a constructor of `ValidationError` belongs, and the cost is
four import lines.

### VI-D-2 — the name

**Status:** OPEN

**Goal.** `requireThat` or the existing `requireCond`.

**Option A — `requireThat`.** Reads as a sentence at the call site:
`requireThat(dups.isEmpty, …)`.

**Option B — keep `requireCond`.** No rename, so the two existing call sites in
`TreeBuilderLogic` are untouched and the diff is smaller.

**My recommendation: Option A**, weakly. The call sites read better and the
rename costs two lines. This is a preference, not an argument, and Option B is
entirely reasonable.

### VI-D-3 — where the three patterns are codified

**Status:** OPEN

**Goal.** Decide the home for §5's three patterns, so they apply to future code
rather than only to this cleanup.

**Option A — split by scope.** Pattern 1, the guard pattern, goes to the global
guardrail file `~/.claude/CLAUDE.md`. Patterns 2 and 3 go to the project review
skill at `.github/skills/code-quality-review/SKILL.md`, mirrored
byte-identically to `.claude/skills/code-quality-review/SKILL.md`.

- For: pattern 1 is a general rule about re-encoding a decision, and it
  generalises the `if`/`else`-to-`match` rule already in `~/.claude/CLAUDE.md`,
  which binds every project. Patterns 2 and 3 name specific zio-prelude
  combinators and bind only where zio-prelude is used.
- Against: a reader looking for all three finds two in one place.

**Option B — all three in the project review skill.**

- For: one home, and the skill already has a numbered `if`/`else` checklist and
  an algebraic-structure pass where all three fit.
- Against: pattern 1 then does not bind in other projects, where the same shape
  will recur with a different effect type.

**Option C — Option A plus a new accepted decision record.**

- For: a record is the strongest form, and plans cite records rather than
  skills.
- Against: these are review criteria, not architectural constraints — nothing
  about the system's structure changes if one is broken. Choosing this also
  activates the plan quality gate's requirement that a plan amending a record
  update the `adr-constraints` distillation in the same plan, which pulls both
  skill mirrors in regardless.

**My recommendation: Option A.** It follows the division already in use:
guardrails about how code is written anywhere live in the global file, and
library-specific review criteria live in the project skill. Option C adds a
record for something no part of the system depends on.

---

## 5. The three patterns, written for reuse

Each is stated as a detection test with its exceptions, so it can be applied
without re-deriving the reasoning.

### Pattern 1 — a guard whose only reader is a sequencing operator

**The shape.** A helper decides which case of a value it is holding, encodes the
answer as success-or-failure in an effect or validation type, and the next
operator does nothing but read which channel it landed in so as to pick what
runs.

**Why it is a defect.** The encoding is total and reversible — one case maps to
success, the other to failure — so reading the channel yields exactly what
reading the value yielded, in a type that holds strictly less. One decision is
expressed twice, and the second expression is weaker than the first.

**Detection — all four must hold.**

1. The helper returns `F[Unit]`, for some effect or validation type `F`.
2. Its body is a two-arm case analysis on a value's **cases** — a sealed type's
   variants, an enum's cases, or equality against a named constant.
3. Every call site consumes it with a sequencing operator that discards the
   `Unit` and continues: `*>`, `_ <- …`, `zipRight`, `flatMap(_ => …)`.
4. Its result is never combined with another check's result.

**The fix.** Give the helper the continuation and return it from the matching
arm:

```scala
  private def requireMain[A](branch: BranchRef)(effect: => Task[A]): Task[A] =
    branch match
      case BranchRef.Main => effect
      case _              => ZIO.fail(RepositoryFailure(…))
```

Behaviour does not change when `F` describes effects rather than performing
them. Under the sequencing operator the body was built on the failing path and
discarded unrun; under a by-name parameter it is not built at all.

**The exception signal 4 encodes.** If two or more such results are combined so
that several failures report together, the `Unit` is load-bearing and the
pattern does not apply. Accumulation combines values, and continuations have no
meaningful combination — there is no union of "run this body" and "run that
body". This is the line separating the thirty-six `Validation[ValidationError,
Unit]` checks in `modules/common`, which all stay, from the two branch guards in
`RiskTreeRepositoryInMemory`, which did not.

**The exception signal 2 encodes.** A predicate computed over data is not a case
analysis, so nothing is being re-encoded and the guard shape is correct.
`RiskTreeRepositoryIrmin.ensureRootPresent` tests `nodes.exists(_.id == rootId)`
and stays a guard. `RiskTreeServiceLive.ensureUniqueTree` tests
`errors.nonEmpty` after an effectful lookup, so it must perform an effect before
it can answer and could not return a continuation at all.

### Pattern 2 — the same shape factored out independently under several names

**The codebase-level signal, which is the useful one.** Not reading each helper
and recognising it, but noticing that **the same shape has been factored out
independently in more than one file, under more than one name.** One local
helper is a convenience. Three, under three names, in three files, means several
authors each needed the same thing and none found an existing answer. Here that
signal produced `TreeBuilderLogic.requireCond`, `TreeIndex.combineValidations`
and `RiskTreeRequests.collectAllWithIndex`.

**What the signal does not tell you: which way to resolve it.** There are two
outcomes and they are opposite, so the signal starts an investigation rather
than prescribing a fix.

- **The library has it.** Delete the helper and call the library function.
  `combineValidations` and `collectAllWithIndex` are both
  `Validation.validateAll`, and both go.
- **The library does not have it, or has something subtly different.** Promote
  the local helper to a shared home. `requireCond` is this case:
  `Validation.fromPredicateWith` looks like its library counterpart and is not
  one, because it takes the error by value where these call sites interpolate
  the offending data into the message, and because it returns the input value
  where these sites have nothing to return.

**The check that separates the two.** Compare what the library function is
*given*, not only what it returns. A constructor handed a finished error builds
that error whether or not it is needed; a helper handed a way of making one does
not. On a validation that runs on every read rather than only on writes, that
difference is a real cost, and it is invisible in the signature's shape.

**Check the error order when a fold becomes a traversal.** A hand-rolled left
fold and a library traversal can emit accumulated errors in different orders.
Any specification asserting the order of several accumulated errors has to be
read, not assumed.

### Pattern 3 — an applicative arity carrying no information

**The shape.** `validateWith(v1, …, vn)(f)` where `f` discards every argument.

**Why it is a defect.** The arity exists so that checks with *different* payload
types can each contribute a distinct piece to `f`. When `f` discards all of
them, no payload is distinct, the arity carries nothing, and `validateAll` over
a collection says the same thing. Adding a check then means adding a list
element rather than extending both a parameter list and an underscore list, and
the arity ceiling disappears.

**Detection.** A combining function of nothing but underscores:
`{ (_, _, _) => () }`, or `((_, _) => someValueTheCallerAlreadyHolds)`.

**The fix.** `Validation.validateAll(List(v1, …, vn)).as(result)`.

**The exception.** Mixed payload types. `validateAll` requires one shared
payload type, so a set of checks producing different types cannot use it, and
the underscores for the `Unit` members are then unavoidable.
`RiskTree.scala:162-168` and `Distribution.scala:87` are both in this position
and stay as they are.

**What this pattern is not.** A `Validation.succeed(())` appearing as an arm of a
multi-way pattern match is not an instance of anything here. It means "this case
is acceptable", a predicate constructor cannot express a three-way analysis, and
arms that name the value the match bound in their failure message cannot be
expressed by a constructor taking a finished message. Twenty-one of the
thirty-one `Validation.succeed(())` sites in `modules/common` are this, and all
twenty-one are correct.

---

## 6. Verification plan

Every step runs from the repository root.

**Compile both cross-compiled targets.** `modules/common` compiles for the Java
virtual machine and for Scala.js, and `TreeBuilderLogic` is shared by both, so a
change there has to be checked on both sides.

```bash
sbt -batch 'commonJVM/compile; commonJS/compile; server/compile; app/compile'
```

Expected: four `[success]` lines and no `[error]` line.

**Find the specifications that assert accumulated error order, before running
them.** §3.6 explains why the order may move. Doing this first means a
reordering is recognised as expected rather than investigated as a regression.

```bash
grep -rn 'errors(0)\|errors(1)\|errors.head\|\.map(_.field)\|\.map(_.message)' \
  modules/common/src/test modules/server/src/test --include=*.scala
```

Expected: a list of assertion sites to read. Any that asserts a sequence of two
or more errors by position is a site where the new order must be confirmed
correct, and the assertion updated if it changed.

**Run every test tier.** The complete run for this repository is four tiers. The
integration tier starts containers, so leaked state from earlier runs is cleared
first; without that, Docker's address pool eventually runs out and the tier
fails for a reason unrelated to the code.

```bash
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; docker network ls --filter name=register_it_ -q | xargs -r docker network rm; docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm
sbt -batch 'commonJVM/test; server/test; app/test'
sbt -batch 'serverIt/test'
```

Expected: `0 tests failed` and a `[success]` line on every tier.

**Confirm the nine converted predicate sites changed no client-visible text.**
Field paths, codes and messages are what a client receives and what a form uses
to highlight an input, so each must survive the conversion exactly.

```bash
git diff -U0 -- modules/common/src/main | grep -E '^[-+].*(field\s*=|code\s*=|message\s*=)'
```

Expected: every removed line has a matching added line with identical content.
A field path, code or message appearing on only one side is an unintended
change.

---

## 7. Alignment with the accepted decision records

No record is amended, so the plan quality gate's requirement that a plan
amending a record also update the `adr-constraints` distillation does not apply.
If `VI-D-3` is ruled Option C that requirement activates and both skill mirrors
come into scope.

Three accepted records bear on the work, and all three are satisfied rather than
stretched.

**ADR-010 (accumulated validation errors).** The plan changes how a single check
is constructed and how a homogeneous set is combined. It does not change which
checks accumulate, and §2.5 records that every `Validation[…, Unit]` return type
stays. `validateAll` and `validateWith` are both the accumulating applicative
combinator, so accumulation is preserved at every converted site.

**Correct-by-construction (the project instruction file's standing section).**
It requires accumulating independent validation errors with
`Validation.validateWith` or `zipPar` rather than `flatMap`. `validateAll` is
the same applicative generalised from a fixed arity to a collection, so a
conversion from `validateWith` to `validateAll` stays inside what that section
prescribes. The plan adds no `flatMap`, and §2.5 records that the one existing
`flatMap` between `fromNodes`' two stages stays, because the second stage reads
a value the first produced.

**ADR-018 (semantically distinct concepts get distinct nominal types).**
Untouched. No type is added, removed or widened; `requireThat` returns the same
`Validation[ValidationError, Unit]` its nine call sites already return.

---

## 8. File inventory

The user creates `PLAN-VALIDATION-IDIOM-INVENTORY.md` from this list; this plan
does not write it.

Files edited under the approval gate:

- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/Distribution.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/tree/TreeIndex.scala`
- `modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala`
- `modules/common/src/main/scala/com/risquanter/register/frontend/TreeBuilderLogic.scala`
- `new: modules/common/src/main/scala/com/risquanter/register/domain/errors/Checks.scala`

The last entry assumes `VI-D-1` is ruled Option B, and is marked `new:` because
it does not exist yet. Option A replaces it with
`modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala`;
Option C removes it, since `TreeBuilderLogic.scala` is already listed.

`OpaqueTypes.scala` is **not** listed. Its one `Validation.succeed(())`, at line
232, is a match arm that names the duplicates it found, so §2.2 keeps it
unchanged.

Test files are covered by the hook's same-module allowance, which authorizes
`modules/common/src/test/**` because the list above names files under
`modules/common/src/main/**`. No test change is expected except where §6's
error-order search finds a positional assertion.

Documents edited outside the approval gate, listed for completeness:

- `~/.claude/CLAUDE.md` — pattern 1, if `VI-D-3` is ruled Option A
- `.github/skills/code-quality-review/SKILL.md` and its byte-identical mirror
  `.claude/skills/code-quality-review/SKILL.md` — patterns 2 and 3

---

## 9. Background

`docs/scratch/VALIDATION-ACCUMULATION-EXPLAINED.md` derives, from the types,
why accumulation requires an applicative, why a successful check still returns a
payload, when that payload should be `Unit` rather than a value, and where the
`Unit` stops being load-bearing. It is the long form of the reasoning summarised
in §1 and §5, written for a reader who does not know ZIO.

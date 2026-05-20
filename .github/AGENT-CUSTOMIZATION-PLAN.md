# Agent Customization Plan — Register

Tracks the incremental rollout of agent instructions, skills, and ADR improvements
that turn passive documentation into structurally enforced agent guardrails.

---

## Progress

| Step | Status | Artifact |
|------|--------|---------|
| 1 | ✅ Done | `.github/copilot-instructions.md` |
| 2 | ✅ Done | `.github/instructions/scala-domain.instructions.md`, `scala-style`, `laminar`, `scala-test`, `security` |
| 3 | ✅ Done | `.github/skills/adr-constraints/SKILL.md` |
| 4 | ✅ Done | `.github/skills/working-protocol/SKILL.md`, `.github/skills/code-quality-review/SKILL.md` |
| 5 | ✅ Done | `.github/instructions/scala-algebraic-design.instructions.md` |

---

## Step 1 — Expand `copilot-instructions.md` ✅

**What was done:** Added the 8 Decision Triggers, Mandatory Review Halt, and the
Correct-by-Construction always-active design constraint block.

**Quality improvements still open:**

*Agent instructions:*
- Trigger #8 is the most commonly violated — consider adding examples inline:
  "including `@ignore`, moving to a deferred suite, or replacing a value assertion
  with a structural one to make a failure pass."
- The correct-by-construction block is descriptive; consider a one-line pre-design
  prompt: "Before writing any new type or endpoint, name its validation layer and
  its consumer layer."

*ADR-001 (source ADR):*
- Add a "Design-first" section at the top: "When introducing any new domain concept,
  identify its Iron constraint, its smart constructor shape, and its owning layer
  before writing any code." The current ADR leads with implementation; design intent
  comes later. Reversing this order would reinforce the right habit.

---

## Step 2 — File-scoped instructions

**What to create:**

Five instruction files, each loaded only when the agent touches matching files.
Content must be **proactive** (reach for X when you see Y) not just retrospective
(smell detectors). Each file starts with a short "Before you write" checklist.

### `scala-domain.instructions.md` · `applyTo: "**/*.scala"`
Theme: **Domain Modelling & Correct-by-Construction**

The cross-cutting gap. Fires on every `.scala` file. Covers:
- Before writing any new type: identify Iron constraint (layer 1) + business rules
  (layer 2) + owning layer → then design the smart constructor shape.
- Nominal wrapper decision: use `case class NodeId(id: SafeId)` when two concepts
  share a constraint but must be compile-time distinct. Never `type NodeId = SafeId`.
- Validation accumulation: `.flatMap` and `Validation.validateWith` produce identical
  types and both compile, but encode different error semantics. `.flatMap` stops on
  the first error (Monad); `validateWith` collects all errors before returning
  (Applicative). A user submitting a form with three invalid fields should see all
  three errors, not just the first. Reach for `validateWith` for any multi-field
  validation; reserve `.flatMap` for dependent steps where the second field is only
  meaningful if the first succeeds.
- Layer contract: the codec has one job (validate raw input → produce Iron types);
  the service has one job (trust that types are valid). If a service method accepts
  `String`, any caller that constructs `String` values directly bypasses the validation
  layer silently — the compiler cannot warn you. The correctness guarantee holds only
  if every layer honours its boundary.
- Smart constructors are the only valid construction path. `RiskLeaf(rawStr, rawDouble)`
  compiles and silently skips all validation. When you find yourself passing primitive
  arguments to a constructor, extract a smart constructor instead — that is the
  boundary where validation belongs.

*ADR-001 quality improvement:* Current framing is implementation-first. Prepend a
"Design checklist" section covering the questions above. The code examples are good
and should stay; they just need a pre-implementation anchor.

*ADR-018 quality improvement:* Add a decision tree: "If two concepts share the same
Iron constraint but must be distinct at compile time → case class wrapper. If a type
exists purely for performance and has one semantic meaning → opaque type."

### `scala-style.instructions.md` · `applyTo: "**/*.scala"`
Theme: **Scala 3 / ZIO Code Style & Functional Composition**

Mechanical rules that apply to every line of Scala:
- Top-level imports always; local imports only on name collision (ADR-011).
- If you're writing a match that threads non-success cases unchanged
  (`case Idle => Idle; case Loading => Loading; case Failed(m) => Failed(m)`),
  the repetition is not style — it is a structural gap. The type is missing `map`.
  Every caller that copies this pattern pays a maintenance tax: a new ADT variant
  requires a change at every match site. Add `map` once; all callers collapse to a
  single line.
- A multi-line lambda inside `.map`, `.combineWith`, or a signal combinator is a
  domain operation with no name, no test, and no documentation. Extract it to a
  named pure function on the companion object. The name communicates intent; the
  function can be tested in isolation; the call site becomes a one-liner.
- No public method without a call site at time of writing. Check before committing.
- ZIO 2.x `for`-comprehensions for sequential effects; no nested `.flatMap` chains.

*ADR-011 quality improvement:* Add a negative example showing a fully-qualified
signature in an error message — the kind of readability problem the rule exists to
prevent — to make the cost concrete.

### `laminar.instructions.md` · `applyTo: "modules/app/**/*.scala"`
Theme: **Laminar Reactive UI Architecture**

Scoped to the frontend. Covers rules that have no analogue in backend Scala:
- Before building a component: identify who owns the `Var`, what `Signal`s the
  parent passes down, and what callbacks propagate events up.
- Reusable component functions are stateless — they never create internal `Var`s.
  Internal state belongs to FormState, not the component function.
- Never call `.now()` in a rendering pipeline; always derive via `Signal`.
- State layering: FormState (field-level, short-lived) vs BuilderState
  (cross-form, long-lived). New state always assigned to one layer before writing.

*ADR-019 quality improvement:* Add a "Before you build" pre-implementation checklist
at the top to mirror the domain modelling guidance above. The existing ADR has strong
examples but no proactive framing.

### `scala-test.instructions.md` · `applyTo: "**/test/**/*.scala"`
Theme: **Test Quality & Assertion Integrity**

Tests are specifications. Covers:
- Before writing: read one existing test in the same module for fixture idioms and
  assertion style. Deviations require explicit approval.
- Coverage: happy path alone is insufficient. Every validation path, error branch,
  and cross-field rule needs a test case.
- Never weaken an assertion to make a test pass. This is Decision Trigger #8 applied
  at the file level. If a test reveals a design tension → stop and ask.
- ZIO Test: use `assertTrue`, `assert(value)(Assertion.equalTo(...))` patterns.
  Do not use raw `throw` or `assert` in ZIO effects.

### `security.instructions.md` · `applyTo: "**/*.scala"`
Theme: **Credential Handling & Authorization Boundaries**

- `case class` is the Scala developer's default and is wrong for credentials. The
  compiler generates `copy(raw = exposed)`, `unapply(secret)`, and a `toString` that
  prints the raw value — each is a distinct leakage path. The correct default is
  `final class` with a private field, a redacted `toString`, no compiler-generated
  methods, and an explicit `.reveal()` whose call sites are visible in code review.
  R1–R8 in ADR-022 are not style preferences — each item closes one specific
  leakage path. Apply the checklist before writing the first line of a new
  credential type, not during review.
- Authorization boundary: the application is a pure PEP. It calls `check()`;
  it never calls `grant()` or `revoke()`. No exceptions.
- JWT validation belongs to the mesh (Istio waypoint), never to application code.
- `SecureRandom` for all capability URL generation. Never `scala.util.Random`.

*ADR-022 quality improvement:* The R1–R8 checklist exists but is scattered across
prose. Consolidate into a single checkbox table at the top of the ADR for
quick reference during implementation and review.

---

## Step 3 — `adr-constraints` skill

**What to create:** `.github/skills/adr-constraints/SKILL.md`

A single agent-efficient distillation of all accepted ADRs. Loaded during planning
phases. Structure:
- **Boundary ownership table:** which layer is responsible for what (validation,
  typing, error mapping, authorization). Makes the architecture scannable in 30s.
- **Positive invariants:** reach for these (e.g. `Validation.validateWith` for
  accumulation, `Identity[RiskResult].combine` for aggregation).
- **Negative constraints (❌ NEVER):** grouped by domain — types, APIs, security,
  persistence, frontend, container/infra. One line per constraint with ADR provenance.

**Quality improvements:**
- Standardize every constraint to a paired format:
  `❌ NEVER [specific action] — [one-line consequence]`
  `✅ INSTEAD [specific alternative]`
  A constraint without an alternative forces improvisation at exactly the point where
  improvisation is most dangerous. If the correct alternative cannot be stated in one
  line, the constraint is not specific enough.
- The positive invariants (what to reach for proactively) are currently absent from
  most ADRs. The skill is the right place to consolidate them.

---

## Step 4 — Skills from existing documents

**What to create:**

### `.github/skills/code-quality-review/SKILL.md`
Source: `docs/dev/PROMPT-CODE-QUALITY-REVIEW.md` — move content, add frontmatter.

Quality improvement: Add a **"Design-first pass"** before the existing 10 criteria.
Before checking compliance, ask: Did the implementation reach for algebraic
structures proactively (Step 5), or did it use ad-hoc logic that a Monoid/Functor
would have eliminated? This pass should come before functional design criterion #3,
not after it.

### `.github/skills/working-protocol/SKILL.md`
Source: `docs/dev/WORKING-INSTRUCTIONS.md` — governance sections only (Decision
Protocol, Mandatory Review Halt, Signature Echo Protocol, Blocked/Failing State).
Communication format templates stay in the skill; code standards move to instruction
files.

Quality improvement: Add to **Signature Echo Protocol** a pre-step 0:
"Identify the algebraic structure this operation participates in (aggregation,
transformation, validation, sequencing) before writing the signature. Name it."
This makes algebraic thinking a structural part of the design ritual, not an
afterthought in the review.

---

## Step 5 — `scala-algebraic-design.instructions.md`

**Recommendation: Create as a separate file, `applyTo: "**/*.scala"`**

Rationale for separation: algebraic design vocabulary is a modelling *language*, not
a compliance rule. Mixing it with domain/validation rules (Step 2) risks burying it.
A separate file makes it visible and signals its equal status alongside DDD concerns.

**Content structure — three layers:**

Layer A₀ is domain primitive recognition: asked when introducing a new field or
parameter. It answers "should this be an Iron refinement?"
Layer A is aggregate type-design recognition: asked when introducing a new type.
It answers "what algebraic structure does this type participate in?"
Layer B is operation lookup: asked before writing a specific function.
All three layers are proactive — consulted before writing code, not during review.

### Layer A₀ — Domain primitive recognition ("Should this field's type be a refinement?")

Asked when introducing any new field, parameter, or case class member.

**The core question: what are the valid values?**
If the answer is a strict subset of what `String`, `Int`, `Long`, or `Double` allows,
a raw JVM type is wrong. Every consumer must then defend itself independently; the
compiler enforces nothing. Iron refinements move that defence to the entry point,
once, permanently.

**Length and size bounds:**
- Does the field have a maximum length? (DB column width, UI limit, protocol limit)
  → `MaxLength[N]`. Ask: what is the column width before writing the type.
- Does the field have a minimum length? (non-empty, minimum content) → `MinLength[N]`.
- Signal words: `name`, `label`, `description`, `title`, `slug` — all have implicit
  length bounds. If you cannot state the max length, find out before proceeding.

**Character set restrictions:**
- Will the value be rendered in HTML? → Disallow `<>"'&` at minimum. A named Iron
  predicate makes the intent legible at every use site.
- Will the value appear in a file path or URL segment? → Disallow `/\..%` and
  control characters.
- Signal words: `name`, `path`, `slug`, `query`, `label` — all have implicit
  character restrictions. Ask what the valid characters are before writing the type.
  Character-set refinements are your primary domain-level defence against injection.
  They are not a replacement for context-specific escaping (HTML encoding,
  parameterised queries), but they reduce the attack surface before any processing
  logic runs.

**Numeric ranges:**
- Is the value a probability or percentage? → `Interval.Closed[0.0, 1.0]` or
  `Interval.Closed[0, 100]`.
- Is the value a count that cannot be negative? → `NonNegative` or `Positive`.
- Signal words: `count`, `size`, `probability`, `weight`, `rate`, `score` — each has
  a natural valid range. State it in the type.

**Format constraints:**
- Is the value a ULID? → Iron predicate (26-char Crockford base32).
- Is the value an email, URL, ISO date, or other structured format? → Iron regex or
  named predicate.
- Signal words: `id`, `identifier`, `email`, `url`, `date`, `timestamp`.

**Type confusion (semantic identity):**
- Could this field be confused with another field of the same raw underlying type?
  `userId: String` and `treeId: String` are both strings; passing one where the
  other is expected compiles silently and fails at runtime or never, depending on
  where the confusion surfaces. Each domain ID concept gets its own opaque type per
  ADR-018, even if the underlying encoding is the same ULID format.

**Provenance:**
- Value originates from user input (HTTP body, query param, header)? → Always
  refine. The attacker controls the raw value; the Iron predicate is the last
  defence before it enters the domain model.
- Value originates from a DB column with a constraint? → The refinement documents
  the constraint in the type system. `VARCHAR(100) NOT NULL` → `MinLength[1] &
  MaxLength[100]`. The DB constraint and the Iron refinement should be in agreement;
  divergence is a schema mismatch bug.
- Value originates from internal computation? → Consider whether cross-type
  confusion is possible (semantic identity above). Refinement may still apply.

*Example:* Introducing `SafeName` for a tree node:
1. Valid values: stored in `VARCHAR(200)`, rendered in HTML → max 200 chars, no
   control chars, no `<>"'&`.
2. Distinct from other IDs? Yes — no confusion risk with ULID-based IDs.
3. From user input? Yes → must refine.
Conclusion: `type SafeName = String :| (MinLength[1] & MaxLength[200] & SafeText)`
where `SafeText` is a named Iron predicate. The type is defined before the field;
the smart constructor returning `Validation[ValidationError, SafeName]` is defined
before the service. Once in place, Layer contract enforcement handles the rest
automatically — codec validates, service trusts.

---

### Layer A — Type-design recognition ("Is this type a Monoid candidate?")

When introducing any new type, work through these questions before defining any
methods or fields. The answers determine what algebraic instances to derive first.

**Monoid / Semigroup recognition:**
1. Will two instances of this type ever be combined into one? → Semigroup candidate.
2. Is there a meaningful "empty", "identity", or "nothing happened" state? → Monoid
   (not just Semigroup). Define `empty` before writing any aggregation caller.
3. Does the combination produce the same result regardless of order? → Commutative
   Monoid. Document this property — it enables safe parallelism.
4. Will instances be folded over a collection that may be empty? → Must be Monoid;
   Semigroup alone requires a non-empty guarantee the caller may not provide.
5. Does the type represent a result, summary, accumulation, or aggregate?
   These words are Monoid signals. Check whether a zero element exists.

*Example:* Designing `RiskResult`: "outcomes are combined when aggregating portfolios"
→ Q1 yes; "zero trials / no losses" → Q2 yes; "portfolio order is irrelevant" → Q3
yes. Conclusion: define `Identity[RiskResult]` (ZIO Prelude) before writing any
aggregation method. The instance is the contract; the method is a consumer of it.

**Functor recognition:**
1. Is the type parameterized by a payload type `A` (i.e. `Wrapper[A]`)? → Functor
   candidate. Define `map[B](f: A => B): Wrapper[B]` before writing any
   specialized transform.
2. Does the type have a "success" case carrying data and one or more structural
   cases (Idle, Loading, Failed) that should pass through unchanged? → Functor on
   the success case. Absence of `map` forces every caller to repeat the structural
   threading manually.

**Applicative / Validation recognition:**
1. Are there multiple fields or inputs that can each fail independently and all
   failures should surface together? → `Validation[E, A]` (Applicative). Do not
   use `.flatMap` — it hides errors after the first.
2. Does the type represent a "checked" or "validated" thing that wraps its proof? →
   Iron refinement + smart constructor returning `Validation`.

**Traversable recognition:**
1. Will you apply an effectful operation to each element of a collection of this
   type and need all results? → `ZIO.foreach` (sequential) or `ZIO.foreachPar`
   (parallel). Do not use a manual accumulator loop.
2. Is the order of processing semantically significant? → Sequential
   (`ZIO.foreach`); if not → `ZIO.foreachPar`.

**Monad recognition:**
1. Does computing the next value require the result of the previous? → Monad /
   `flatMap` / `for`-comprehension.
2. Are the computations independent? → Prefer Applicative over Monad; Applicative
   permits parallel execution. `flatMap` implies sequencing even when not needed.

---

### Layer B — Operation lookup ("What structure does this function have?")

Asked before writing a specific function, not a type.

| Domain operation | Algebraic structure | ZIO Prelude type | Reach for when... |
|---|---|---|---|
| Combine two results of same type | Monoid / Identity | `Identity[A]`, `Semigroup[A]` | Aggregating child nodes, merging partial results |
| Accumulate all validation errors | Applicative | `Validation[E, A]` | Any multi-field input validation |
| Transform a wrapped value | Functor | `.map` on ADT or ZIO effect | Any "change the data, preserve the structure" operation |
| Chain dependent effects | Monad | ZIO `flatMap`, `for` | When step B needs the result of step A |
| Traverse a structure with effects | Traversable | `ZIO.foreach`, `.forEach` | Simulating all children, validating a collection |
| Fold a tree bottom-up | Catamorphism | Explicit recursion on `TreeIndex` | Aggregating subtrees, computing depth metrics |

Additional rules:
- **Before writing an aggregation function:** confirm the type is a Monoid (Layer A
  above). If it is not yet — add the instance first, then write the aggregation.
  Do not write `reduce` with a hard-coded zero inline.
- **Before writing a pipeline of transforms:** verify associativity. If
  `(f andThen g) andThen h ≢ f andThen (g andThen h)` for any input, the
  abstraction is unsound. Surface the tension before shipping.
- **Type class instances must satisfy laws.** An `Identity` that is not truly
  identity, or a `combine` that is not associative, is a latent bug that law tests
  would catch. Write law tests for every new instance. See ADR-009 for the pattern.

*ADR-009 quality improvement:* Add a "Design vocabulary" section at the top that
names the algebraic structure explicitly: "portfolio aggregation is a Monoid fold;
`RiskResult.combine` is the binary operation; `emptyResult` is the identity element."
The current ADR explains the mechanics but does not name the pattern. Naming it
makes it easier to reach for the same structure in analogous situations.

*Working Instructions quality improvement:* Reframe the "Functional Composition"
section from smell-detector ("ADTs must define map/flatMap") to pattern-selector
("Before writing any aggregation/validation/transformation, identify its algebraic
structure from the vocabulary table and reach for the corresponding ZIO Prelude
abstraction. The smell-detector rules below catch cases where this was not done.").

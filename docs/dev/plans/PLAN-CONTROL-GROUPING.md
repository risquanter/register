# PLAN — Control grouping: composition as the only combiner

**Plan code `CG`.** Decisions in this document would be labelled `CG-D-1` upward.
None is ruled.

## Status — NOT PLANNED, NOT RULED, NOT RESEARCHED

This document is a placeholder. It is **not** implementation-grade and confers no
implementation coverage: it carries no exact signatures, no verification plan and
no file inventory, so it fails items 1, 2 and 5 of the plan quality gate in
[`CLAUDE.md`](../../../CLAUDE.md) on purpose. Nothing in it authorizes an edit.

Its whole job is to hold what is already established and what has to be
researched or ruled, so neither has to be rebuilt. The direction was chosen on
2026-10-23 — the idea is the user's — and the choice was only that this becomes
its own plan rather than being folded into
[`PLAN-LOSS-SHAPE-ALGEBRA.md`](PLAN-LOSS-SHAPE-ALGEBRA.md), which runs first and
is unfinished.

The user creates `PLAN-CONTROL-GROUPING-INVENTORY.md` if and when this plan is
elevated and approved.

---

## 1. What this would be

A **control** is what a person authors: one named risk-reduction measure. Today
the thing that is named, targeted, ordered and toggled is a `Mitigation`, and a
mitigation carries exactly one `MitigationSpec`, so one authored control that
needs two effects is already two mitigations with two names and two targeting
predicates.

This plan would introduce a grouping level above the mitigation. A control holds
an ordered list of mitigations and owns the name; each mitigation states exactly
one operation; and composition becomes the only way effects combine, at every
level and in both stages.

Sketched, not specified:

```scala
final case class Control(
  id: ControlId,
  name: SafeName.SafeName,
  target: MitigationTarget,
  precedence: MitigationPrecedence,
  members: List[Mitigation]
)
```

Three things in the current design exist only because one authored judgement has
to fit inside one spec, and each would go:

- `AbsoluteReplacement.Both` — a control holding one `Likelihood` mitigation and
  one `Severity` mitigation.
- `RelativeLikelihood.Keep` and `RelativeSeverity.Keep` — each mitigation names
  one component, so there is no "and nothing to the other component" left to
  express.
- `TransformPipeline` — a control holding several single-operation result-stage
  mitigations, which also makes the two stages symmetric at one operation per
  mitigation.

---

## 2. Why it is on the table

The current design has not settled whether the authoring unit is the single spec
or the composition, and it answers differently in three places that are
structurally identical. In each, the single-spec form and the several-mitigation
form produce the same figures:

- `AbsoluteReplacement.Both(p, s)` produces the same parameters as
  `Likelihood(p)` followed by `Severity(s)`.
- A statement that is relative in one component and absolute in the other has no
  single spec, and is written as two mitigations.
- A result-stage pipeline of `[a, b]` composes to the same function as two
  mitigations carrying `[a]` and `[b]` at adjacent precedence keys.

Two of those keep the grouped form and one refuses it. Nothing distinguishes the
three cases, so one of the two answers is unmotivated, and the vocabulary that
resulted is what made the naming hard to follow.

---

## 3. What is already established

These were verified against the code on 2026-10-22 and 2026-10-23 and do not
need researching again.

**The grouping is presentational, not semantic.** Composition is associative in
both stages — the leaf stage composes with `flatMap` over
`Validation[ValidationError, LeafParams]`, the result stage with `andThen` over
`TrialOutcomes` — so a fold over a flattened member list gives the same answer as
a fold over nested groups. A control level can therefore be added without
changing any computed figure.

**Both stages already compose across mitigations, ordered by precedence.**
`MitigationApplication.scoped` sorts each node's mitigations by ascending
precedence key with the mitigation identifier as tiebreak;
`MitigationApplication.effectiveTree` folds the leaf-stage specs through that
order, and `MitigationApplication.run` composes the result-stage pipelines through
it. So the mechanism a control would rely on is the one already running.

**The pipeline's internal list is observationally redundant.**
`ResultTransformInterpreter.toTransform` folds a pipeline's steps with `andThen`,
and `MitigationApplication.run` folds a node's pipelines with `andThen`, so one
mitigation carrying two steps and two mitigations carrying one step each produce
the same function.

**The strongest argument for keeping a grouped spec has weakened.**
`ResultTransformSpec.InsurancePolicy` carries a cross-field rule relating two of
its fields, and a flat list of members cannot state a relation between two
members. That looked like a case only a grouped spec could express. The rule's
stated reason turned out to be false, and the correct insurance arithmetic is one
operation with two parameters rather than two operations, so it does not need a
relation across members. That correction is its own work and is not part of this
plan.

**The two-layer stage split is compatible with this plan.**
`PLAN-LOSS-SHAPE-ALGEBRA.md` has landed, and the split it shipped is not symmetric:
`MitigationSpec` has a `sealed trait LeafStageSpec` grouping `LeafRelative` and
`LeafAbsolute`, and one `ResultStage` case holding a `TransformPipeline`. There is
no `ResultStageSpec`. Under this plan the result-stage side would hold one
operation per case instead of a pipeline, which is where that grouping trait would
come from; the layer itself stays either way.

---

## 4. What needs researching or ruling

Five areas, each one a piece of machinery that exists at the mitigation level
today and would have to exist at the control level instead. None of the five is
new machinery; all five are relocations, which is what makes this a substantial
change rather than a risky one.

### 4.1 The stored format — a new persisted entity

**To research.** `RiskTree` carries `mitigations: Seq[Mitigation]`, and that
sequence is what is written to and read from the Irmin store. Controls would
replace it, each holding its members, so the stored tree format changes. What has
to be established is the shape of the stored record, where the member order
lives, and what `RiskTree.fromNodes` validates about it — today it checks
mitigation identifier and name uniqueness, and both would move.

**`CG-D-1` — does a control's member list carry its own order, or do members keep
precedence keys?** Open. A list position is simpler and cannot disagree with
itself. A per-member key survives a merge that reorders.

### 4.2 The wire shape

**To research.** `Mitigation` has a `JsonCodec` and a `Schema` and crosses the
wire in both the tree read and the tree write. A control is what a client would
display and toggle, so it needs its own codec and schema, and the request and
response types that carry mitigations today would carry controls. An API shape
change is a decision trigger in its own right, so this is ruled before anything
is written.

**`CG-D-2` — does a member mitigation appear on the wire at all, or is a control
opaque from outside?** Open. An opaque control is a smaller surface. A visible
member list is what lets a client show what a control is made of.

### 4.3 Merge behaviour

**To research.** Scenarios are Irmin branches and trees merge per path. Today two
branches that each added a mitigation merge by combining two sequences, gated by
`Mitigation.requireDistinctIds` and `requireDistinctNames`. Controls add a second
level, and with it a conflict shape that does not exist today: two branches
editing the *same* control's member list, one adding a member while the other
reorders. `ScenarioMergeService` is where a rule would go, and no rule exists.

**`CG-D-3` — what happens when two branches edit one control's members?** Open.
The options range from refusing the merge to merging member lists by identifier.
This needs the existing per-path merge behaviour examined first.

### 4.4 Targeting moves up a level

**To research.** Each `Mitigation` carries `target: MitigationTarget`, a predicate
resolved server-side into a node set by `MitigationScopeResolver`, which memoizes
per mitigation identifier. If members kept their own targets, a control would be a
bag of independently-scoped things and the problem this plan exists to remove —
two predicates to keep in step for one authored control — would remain. So the
target belongs on the control and members inherit it, and the resolver's
memoization key changes with it.

**`CG-D-4` — may a member narrow its control's scope, or does it always inherit
the whole of it?** Open. Strict inheritance is simpler to reason about. A
narrowing member would let one control treat part of its scope differently,
which is a capability nobody has asked for yet.

### 4.5 The selection surface and the query language

**To research.** `MitigationSelection.Selected` is keyed by `MitigationId`, and
`Mitigation.requireDistinctNames` exists so that a user naming one in a selection
cannot mean two. The analytics query language references mitigations by that name.
If a control is what an author toggles, the selection map keys on control
identifiers and the uniqueness rule moves with it — which changes the vocabulary
users type, not only an internal name.

**`CG-D-5` — can a selection still reach a single member, or only a whole
control?** Open. Whole-control toggling matches how a person thinks about a
control. Per-member selection is what `ScopeRestriction` already offers one level
down, so removing it narrows an existing capability.

---

## 5. What this plan does not include

The correction to `RiskResultTransform.applyDeductible` and
`RiskResultTransform.insurancePolicy` — the arithmetic computes the insurer's
payment where it should compute the loss the entity still bears. That is a
correctness defect in shipped code, it is being fixed separately, and this plan
assumes it is already done.

`PLAN-LOSS-SHAPE-ALGEBRA.md`'s own scope: the loss-shape coproduct, the deletion
of `AbsoluteReplacement.Both`, the two-layer stage split, and the `EffectiveTree`
wrapper. That plan runs first.

---

## 6. Gate status

No signatures, no file inventory, no verification plan, no ADR alignment sweep.
This document authorizes nothing and must be elevated to implementation grade,
presented, and approved before any edit is made under it.

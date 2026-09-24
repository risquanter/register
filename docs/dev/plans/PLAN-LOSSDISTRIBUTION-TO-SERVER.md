# PLAN-LOSSDISTRIBUTION-TO-SERVER

Move the server-only simulation and mitigation-application code out of the
shared `common` module and into `server`, keeping the `LossDistribution`
hierarchy sealed.

**Status:** awaiting ruling on the two open decisions below, then approval.

---

## Why

`common` is the cross-compiled module: everything in it is compiled for both the
JVM and the browser, and `CLAUDE.md` describes its contents as "cross-compiled
domain model, DTOs, Tapir endpoint definitions, codecs". Four groups of types
sitting there are none of those. They are the code that runs a simulation and
applies a mitigation to its output — produced by the server, consumed by the
server, and never named by the browser.

The move puts them beside the code that produces them and leaves `common`
holding only what both sides genuinely share.

**One argument that does not apply, stated so it is not reached for later.** The
move does not shrink the browser bundle. The Scala.js linker already strips every
one of these types: measured against the linked app output, `RiskResult`,
`RiskResultGroup`, `LossDistribution`, `TrialOutcomes`, `RiskResultTransform` and
`MitigationApplication` each occur zero times, while `LECNodeCurve` occurs 661
times and `NodeId` 402. The saving is `commonJS` compile time, nothing more. The
case for this change is placement, not size.

The hierarchy stays **sealed**. Scala 3 permits a subclass of a sealed class only
in the same source file, so sealing constrains the file, never the module.
Sealing is load-bearing: `build.sbt:12` promotes a non-exhaustive match to a
compile error, which is what will make adding `ValuationResult` a build failure
rather than a silent omission.

## The dependency wall — what can move and what cannot

`common` cannot reference `server`. So the boundary is decided by which types the
wire contract reaches, not by which types the server happens to use.

**The wire contract, which pins its types to `common`.** A tree carries
mitigations: `RiskTree.mitigations: Seq[Mitigation]` → `Mitigation.spec:
MitigationSpec` → `MitigationSpec.ResultStage(pipeline: TransformPipeline)` and
`MitigationSpec.LeafStage(transform: RiskLeafTransform, …)`. `TransformPipeline`
is defined in `ResultTransformSpec.scala` and `RiskLeafTransform` in its own
file, so both of those files stay. The browser decodes all of it.

**What is free to move.** `LossDistribution.scala`, `RiskResultTransform.scala`
and `MitigationApplication.scala` are referenced from `common/src/main` only in
scaladoc — `LEC.scala:34,37`, `Provenance.scala:17,29,30`, `RiskTree.scala:31`,
`Mitigation.scala:59,112`, `ResultTransformSpec.scala:10,11`,
`OpaqueTypes.scala:83` — every one of them verified to be a comment, not code.
`MitigationApplicationRecord` is a different type that happens to share a prefix;
it is a wire DTO declared in `Mitigation.scala` and stays.

**The one link that has to be cut.** `ResultTransformSpec.scala` must stay, and
its companion defines `toTransform(spec: ResultTransformSpec):
RiskResultTransform` at line 47, plus `TransformPipeline.toTransform` at line
187. A staying file therefore names a moving type. Its only production caller is
`MitigationApplication.scala:177`, which moves. Cutting the link is what makes
the whole move possible — see open decision 1.

**Why this matters for the file's contents.** With the link cut, `TrialOutcomes`
travels with the hierarchy instead of being stranded. Its only code users are the
three moving files; `Mitigation.scala:59` and `ResultTransformSpec.scala:10`
mention it in comments only. So `LossDistribution.scala` moves whole and
`ADR-009`'s deliberate grouping — the lawful commutative monoid, the leaf and
aggregate constructors, and the n-ary fold over them, all in one file — survives
intact. An earlier draft of this plan split that file; it does not need to be
split.

## Open decision 1 — where `toTransform` lives

`toTransform` turns a serializable description of a mitigation's effect into the
function that executes it. It is the single interpretation point, and
`ResultTransformSpec.scala:11` says so.

**Option A — move both `toTransform` methods into a new server file, leaving the
spec types in `common`.** `ResultTransformSpec` and `TransformPipeline` become
pure serializable data with no knowledge of how they are executed; a new server
object interprets them.

*What this looks like in practice:* a caller writes
`ResultTransformInterpreter.toTransform(spec)` instead of
`ResultTransformSpec.toTransform(spec)`. There is one production caller and it
moves anyway, so the only other affected code is the interpreter tests.

Pros: the shared module holds descriptions and the server holds their
interpretation, which is the separation the two types already imply. It is the
only option that lets `TrialOutcomes` move, which is what avoids splitting
`LossDistribution.scala`.
Cons: it takes a method off a companion where it is discoverable, and it is a
design change rather than a file move — the one thing in this plan that is.

**Option B — leave `toTransform` where it is.** Then `RiskResultTransform` and
`TrialOutcomes` both stay in `common`, `MitigationApplication` cannot move
either, and the change shrinks back to moving the sealed hierarchy alone — which
forces the split of `LossDistribution.scala` that this plan just established is
unnecessary.

*What this looks like in practice:* you get roughly a third of the move and pay
for it by breaking up a file `ADR-009` groups deliberately.

Pros: no design change at all. Cons: it is the shape already examined and set
aside; it takes the split's cost without the group move's benefit.

**My recommendation: Option A.** Option B is not really a smaller version of this
plan — it is the previous plan, which the measurement above showed to be the
worst of the three shapes.

## Open decision 2 — one package or two

The earlier ruling put the moved types in `com.risquanter.register.simulation`.
That ruling was made when the move was the hierarchy alone. The widened set adds
mitigation application, which is not simulation.

**Option A — two packages.** `com.risquanter.register.simulation` takes
`LossDistribution.scala`, beside the existing `LECGenerator.scala`.
`com.risquanter.register.mitigation` takes `RiskResultTransform.scala`,
`MitigationApplication.scala` and the new interpreter.

**Option B — one package**, `com.risquanter.register.simulation`, for all four.

*What this looks like in practice:* an import line either reads
`com.risquanter.register.mitigation.MitigationApplication` or
`com.risquanter.register.simulation.MitigationApplication`.

**My recommendation: Option A.** Applying a mitigation is not simulating, and the
server already keeps mitigation scope resolution in its own place. One package
named for one of the two things it holds is the kind of small inaccuracy that
survives for years.

The rest of this plan is written against Option A on both decisions.

## Exact signatures

No type, constructor, field or method changes shape. Two things change identity:

**The interpreter's new home.** These two methods leave
`ResultTransformSpec.scala` unchanged in body and arrive in a new file:

```scala
package com.risquanter.register.mitigation

import com.risquanter.register.domain.data.{ResultTransformSpec, TransformPipeline}

/** Turns a serializable transform description into the function that executes
  * it. The spec types are shared with the browser; only this interpretation of
  * them runs here. */
object ResultTransformInterpreter {

  def toTransform(spec: ResultTransformSpec): RiskResultTransform = spec match {
    case ApplyDeductible(d)      => RiskResultTransform.applyDeductible(d)
    case CapLosses(c)            => RiskResultTransform.capLosses(c)
    case ScaleLosses(f)          => RiskResultTransform.scaleLosses(f)
    case FilterBelowThreshold(t) => RiskResultTransform.filterBelowThreshold(t)
    case InsurancePolicy(d, c)   =>
      RiskResultTransform.applyDeductible(d).andThen(RiskResultTransform.capLosses(c))
  }

  def toTransform(p: TransformPipeline): RiskResultTransform =
    p.steps.foldLeft(RiskResultTransform.identityTransform)((acc, s) =>
      acc.andThen(toTransform(s)))
}
```

The `InsurancePolicy` arm and the pipeline fold are reproduced from
`ResultTransformSpec.scala:47-53` and `:187-189` without change. The interpretation
law those two satisfy — `toTransform(a <> b)` behaves as `toTransform(a) andThen
toTransform(b)` — is stated at `ResultTransformSpec.scala:186` and keeps its test.

**The moved files' package declarations.**

```scala
package com.risquanter.register.simulation   // LossDistribution.scala
package com.risquanter.register.mitigation   // RiskResultTransform.scala, MitigationApplication.scala
```

Each gains imports for what it used to reach by being in the same package —
`Loss`, `TrialId`, the iron types, `ValidationError`, `MitigationId`, `NodeId`,
`MitigationApplicationRecord` — all of which stay in `common`, which `server`
depends on.

`LossDistribution.scala` moves whole: `LECCurve`, `TrialOutcomes` and its
companion, `sealed abstract class LossDistribution`, `RiskResult` and its
companion, `RiskResultGroup` and its companion, and `object LossDistribution`.
All of them must remain in one file — Scala 3 refuses a sealed subtype declared
anywhere else.

## File inventory

- `modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/RiskResultTransform.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/MitigationApplication.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/LEC.scala`
- `modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/LossDistributionSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/TrialOutcomesSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/RiskResultTransformSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationApplicationSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/data/ResultTransformSpecSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/PreludeOrdUsageSpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/domain/IdentityPropertySpec.scala`
- `modules/common/src/test/scala/com/risquanter/register/testutil/RiskResultTestSupport.scala`
- `modules/common/src/test/scala/com/risquanter/register/testutil/ConfigTestLoader.scala`
- `modules/app/src/main/scala/app/chart/LECSpecBuilder.scala`
- `modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala`
- `modules/server/src/main/scala/com/risquanter/register/mitigation/RiskResultTransform.scala`
- `modules/server/src/main/scala/com/risquanter/register/mitigation/MitigationApplication.scala`
- `modules/server/src/main/scala/com/risquanter/register/mitigation/ResultTransformInterpreter.scala`
- `modules/server/src/main/scala/com/risquanter/register/simulation/LECGenerator.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/LeafSimResult.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolver.scala`
- `modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala`
- `modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala`
- `modules/server/src/test/scala/com/risquanter/register/simulation/LossDistributionSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/simulation/TrialOutcomesSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/simulation/PreludeOrdUsageSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/mitigation/RiskResultTransformSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/mitigation/MitigationApplicationSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/mitigation/ResultTransformInterpreterSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/testutil/RiskResultTestSupport.scala`
- `modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/BinderIntegrationSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/QueryServiceLiveSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/cache/MitigationStalenessSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/helper/SimulatorSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/services/pipeline/InvalidationHandlerSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/simulation/LECGeneratorSpec.scala`
- `modules/server/src/test/scala/com/risquanter/register/simulation/RiskSamplerSpec.scala`
- `modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala`

Entries under `modules/common/` are listed because the change deletes or edits
them; the hook gates a deletion the same way it gates an edit. `LEC.scala`,
`Provenance.scala`, `ConfigTestLoader.scala`, `IdentityPropertySpec.scala` and
`LECSpecBuilder.scala` are listed for the comment sweep only — no code in them
changes.

## What happens to each test file

| File | What |
|---|---|
| `LossDistributionSpec`, `TrialOutcomesSpec`, `PreludeOrdUsageSpec` | move whole to `server/src/test/.../simulation/` — their subjects move |
| `RiskResultTransformSpec`, `MitigationApplicationSpec` | move whole to `server/src/test/.../mitigation/` — their subjects move |
| `RiskResultTestSupport` | moves to `server/src/test/.../testutil/`; five server specs already use it and keep working, now from the same module |
| `ResultTransformSpecSpec` | **splits.** Its `interpreter` suite and the two `TransformPipeline` interpretation-law tests move into `ResultTransformInterpreterSpec`; its `Equal on reified data` and `codec` suites stay with the type they test |
| `IdentityPropertySpec` | stays — it imports only `Loss`; its two `TrialOutcomes` mentions are comments that need repointing |
| `ConfigTestLoader` | stays — its `RiskResult` mention is an example in a comment |

No assertion is added, weakened or removed anywhere. The `ResultTransformSpecSpec`
split is the one place tests are redistributed, and it follows the production
split exactly, which is itself evidence the boundary is real.

## Documentation sweep

| File | What changes |
|---|---|
| `modules/common/src/main/.../domain/data/LEC.scala:34,37` | names "server-side result types"; should name where they now are |
| `modules/common/src/main/.../domain/data/Provenance.scala:17,29,30` | describes walking `RiskResultGroup.children`, now a type in another module |
| `modules/common/src/main/.../domain/data/Mitigation.scala:59,112` | points at `TrialOutcomes` and `MitigationApplication` |
| `modules/common/src/main/.../domain/data/ResultTransformSpec.scala:10,11` | calls `toTransform` "the single interpretation point" while describing it as living here |
| `modules/common/src/main/.../domain/data/RiskTree.scala:31` | points at `MitigationApplication` |
| `modules/common/src/main/.../domain/data/iron/OpaqueTypes.scala:83` | cites `RiskResultTransform.scaleLosses` |
| `modules/common/src/test/.../testutil/ConfigTestLoader.scala:20,24` | example builds a `RiskResult` this module can no longer see |
| `modules/common/src/test/.../domain/IdentityPropertySpec.scala:21,22` | points at `TrialOutcomesSpec`, which moves |
| `modules/app/src/main/scala/app/chart/LECSpecBuilder.scala:404` | cites `LossDistribution`'s unit convention; not on this module's classpath any more |
| `docs/dev/decision-records/ADR-009.md` | Implementation table gives file paths for `TrialOutcomes`, the two subtypes and `LossDistribution.merge`, and says the law suite lives in `common` tests |
| `docs/dev/SENSITIVITY-ANALYSIS-PLAN.md`, `docs/dev/plans/PLAN-MITIGATION-ROI.md`, `docs/dev/plans/PLAN-PROVENANCE-ENDPOINT.md`, `docs/dev/plans/PLAN-RISKTRANSFORM.md` | name the old file path |
| `CLAUDE.md` module table | describes `common` as holding the domain model; after this change the description is accurate for what remains, so check rather than assume an edit is needed |

`docs/archive/` is not swept — archived documents record what was true when they
closed.

## Coordination with PLAN-RISKTRANSFORM

`PLAN-RISKTRANSFORM` §8.16 adds `ValuationResult` as a third subtype of this same
sealed file, and its `ValuationResult` sub-slice specifies new tests in
`RiskResultTransformSpec.scala` — a file this plan moves.

**This plan lands first.** It is behaviour-preserving; the sub-slice is not, and
it has two open decisions of its own. Landing the move first means the new
subtype and its tests are written once, in their final home.

Three consequences for that plan, recorded there rather than here: §7.6.12
decision 8 is answered; its file inventory entries for `LossDistribution.scala`
and `RiskResultTransformSpec.scala` change path; and `ADR-009` gains a second
reason to be amended in slice 5, since this plan stales its file paths as well as
its subtype enumeration.

## ADR alignment

| ADR | Bearing | Status |
|---|---|---|
| ADR-009 (result type hierarchy) | its Implementation table gives the file path for every moved item and places the law suite in `common` tests | **Amended by this plan** |
| ADR-001 (smart constructors) | `RiskResult.create`, `RiskResultGroup.create` and the transform constructors move unchanged and stay the only construction gates | Compliant |
| ADR-033 (exception boundaries) | `TrialOutcomes.combine` keeps its named-exception behaviour and travels with the type; `RiskResultGroup.create` keeps converting it | Compliant |
| ADR-034 (mitigation valuation model) | names the types in prose, not by path | Compliant, no edit |
| ADR-017 (DTO shapes) | the wire contract is untouched: `Mitigation`, `MitigationSpec`, `TransformPipeline`, `RiskLeafTransform` and `MitigationApplicationRecord` all stay in `common` with their codecs | Compliant |
| ADR-018 (nominal id wrappers) | untouched | Compliant |
| ADR-005, ADR-015 | write the resolver return type two generations stale; already scheduled as `ADR-HOUSEKEEPING.md` T5 | No new work here |

## Verification plan

The change is behaviour-preserving. No assertion is added, weakened or removed.

Compilation is the primary proof, and `commonJS` is the load-bearing one — it is
what demonstrates nothing the browser compiles was left depending on a moved
type:

```bash
sbt commonJVM/compile
sbt commonJS/compile
sbt server/compile
sbt app/compile
```

Then every tier, all green. A failure in any tier blocks, whatever its origin:

```bash
sbt commonJVM/test
sbt server/test
sbt app/test
```

Integration tier, with the mandatory leaked-network cleanup as a pre-step:

```bash
docker ps -a --filter name=register_it_ --format '{{.ID}}' | xargs -r docker rm -f; docker network ls --filter name=register_it_ --format '{{.ID}}' | xargs -r docker network rm; echo "--- remaining register_it_ networks ---"; docker network ls --filter name=register_it_ --format '{{.Name}}' | wc -l

sbt "serverIt/test"
```

`serverIt` needs the `local/irmin-prod:3.11-p1` image, which is present.

Results are reported pass or fail only.

Two checks close the sweep. No file under `modules/` or `docs/dev/` may still
name `modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala`.
And the claim this plan rests on is re-measured rather than assumed: after
`sbt app/fullLinkJS`, the linked output must still contain zero occurrences of
`RiskResult`, `TrialOutcomes` and `MitigationApplication`, confirming the move
changed nothing the browser ships.

## Version

Behaviour-preserving refactor of shipped code — PATCH bump when the work lands,
mirrored into `.env` and `.env.irmin`.

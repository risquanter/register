# Plan — transplant the FB-F valuation shape into production

**Status:** Draft presented for approval — **no open decisions.** All eight of §10
are ruled. G3 coverage begins when the user approves this document and points the
approval token at it; until then no source edit is authorized.
**Authority (ruled 2026-09-27, user):** this document is the specification for
the four valuation types. PLAN-RISKTRANSFORM §8.17 and §8.18 are superseded in
that respect and are retained as informative material — how this work sits in
the M4 epic, and which side effects were identified while it was being
specified.
**Inventory:** `docs/dev/plans/PLAN-FBF-VALUATION-TRANSPLANT-INVENTORY.md` — does
not exist yet; created by `.claude/bin/approve-inventory` at approval time (§9).
**Shape source:** `docs/scratch/valuation-prototypes/fb-f/FBF.scala` and its
README, for the **type shape only**.
**Implementation source:** production. Nothing in the prototype folder is
transcribed.
**Reasoning source:** `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md` and
`docs/scratch/MITIGATION-PORTFOLIO-CATEGORY-THEORY.md`.

---

## 0. Relationship to PLAN-RISKTRANSFORM — read this first

This work is the same subject as **PLAN-RISKTRANSFORM §8.17** ("The mitigated
value's type — Option FB", ruled 2026-09-25) and **§8.18** ("Option FB —
implementation-grade elevation", 2026-09-25). It is not adjacent to them; it
replaces the shape they specify with a later one.

§8.17 quotes the ruling it rests on:

> LossDistribution keeps its name and its API — probOfExceedance, maxLoss,
> minLoss, the histogram, nodeId, trials — and stops being a hierarchy.
> It becomes the single concrete type every consumer takes. A new internal
> parent takes over the role it vacates: that one is sealed, it has RiskResult
> and RiskResultGroup as its cases. BUT LossDistribution becomas the type for
> the mitigated value.

and writes the type as

```scala
final case class LossDistribution private (
  nodeId, trials, source: TrialOutcomes, applied,
  private val origin: NodeValuation            // ← the public value HOLDS the internal case
)
private[simulation] sealed trait NodeValuation
private[simulation] final case class RiskResult(provenances: List[NodeProvenance])
private[simulation] final case class RiskResultGroup(children: List[LossDistribution])
```

The prototype README records the FB-F shape instead:

> ```scala
> sealed trait FBF_NodeLosses:
>   def nodeId: NodeId
>   def trials: TrialOutcomes
>
> final case class FBF_LeafLosses      private (nodeId, trials, provenances)
> final case class FBF_PortfolioLosses private (nodeId, trials, children: List[FBF_LossDistribution])
>
> final case class FBF_LossDistribution private (nodeId, trials, source: TrialOutcomes, applied)
>   extends LECCurve
> ```
>
> `FBF_PortfolioLosses.create` derives its total from the children, so a
> portfolio cannot claim a total its children do not sum to. […]
> In production these are `NodeLosses`, `LeafLosses`, `PortfolioLosses` and
> `LossDistribution`, with `LECCurve` unchanged.

Three differences, and each one changes production code:

1. **The public value no longer holds the internal case.** §8.18's `origin`
   field disappears. This is what the README means by "it carries figures only,
   so it names no internal type".
2. **The internal family carries the figures and the node id.** In §8.18 it
   carried neither, so the public value's own factories did the combining; under
   FB-F `PortfolioLosses.create` does it.
3. **`private[simulation]` is no longer available for the family.** Under §8.18
   only `LossDistribution`'s own companion built the cases, so the family could
   be private to its file's package. Under FB-F the resolver builds them, and
   the resolver lives in `services.cache`. §2 of this plan's open decisions
   (§10, decision 2) picks the replacement.

Consequences that follow directly:

- §8.18's **open decision 2** ("whether `LossDistribution` exposes its origin")
  is void. There is no origin. The question it was really asking — how the
  provenance endpoint reads a leaf's records — comes back as decision 3 below,
  in a different shape.
- §8.18's **open decisions 1 and 3** (`LossDistribution.merge`'s fate;
  what `Equal[LossDistribution]` compares) survive unchanged and are restated
  here as decisions 4 and 5, because they are this change's to answer.
- §8.18's **"Two corrections to §8.17"** stand and are not re-litigated:
  the wire record is `LECNodeSeries` and not a new `NodeReading`; and
  `withMitigations` is not a projection of `applied`.

**Ruled 2026-09-27 (user): this document replaces §8.18's specification.**
PLAN-RISKTRANSFORM is outdated in this respect; its §8.17 and §8.18 stay in
place as informative material and each gains a banner saying so. What they
remain useful for is stated above: the relationship to the M4 epic, and the
side effects identified along the way — the `recordsByNode` / `run` split, the
C1 scale-overflow fix, the documentation sweep list and the test blast radius,
all of which this plan carries forward in §3, §5 and §6.

Three cross-references inside PLAN-RISKTRANSFORM point at the replaced
specification and become wrong pointers on that ruling. They are corrected in
the §6 sweep: §7.6.12's preamble ("stated at the end of §8.18, which is the
implementation-grade specification for that sub-slice"), §7.6.4's gating
sentence, and §7.6.10's ADR-alignment row for ADR-034.

---

## 1. Step 1 — delete `LossDistribution.flatten`, and land it on its own

**This is the first step of this plan, and it is committed green before any of §3
starts.** Ruled 2026-09-15 and reaffirmed 2026-09-27 as the lower-risk path (§10
decision 6): a deletion in two files, reviewed and banked on its own, so that
nothing in the type change has to be unpicked together with it. §3 and §5 below
are written for a tree in which `flatten` is already gone.

It is one approval, not two. The approval script accepts only a
`docs/dev/plans/PLAN-*.md` document as the plan a token names, so this step lives
here rather than in a fix-note of its own.

### What `flatten` is, and why it goes

`flatten` returns a node's whole subtree as one flat `Vector[LossDistribution]`.
It was written so that a caller holding a portfolio result could walk everything
beneath it. Ruled for deletion in `MITIGATION-VALUATION-EXPLAINED.md` §11 and
§12.3.

Nothing calls it. Searching the repository for the name gives three declarations
and two test suites, and no other use. The fifteen other `.flatten` occurrences in
the codebase are the standard collection and `Option` method on unrelated values —
`RiskTreeServiceLive.scala:101`, `ScenarioMergeService.scala:193`,
`WorkspaceStorePostgres.scala:166`, `Simulator.scala:158`, `RiskNode.scala:301`,
`Mitigation.scala:172`.

It has no future caller either. Drill-down is a separate request for the child
node, and a subtree's provenance takes its set of leaves from `TreeIndex` and
resolves each leaf under its own node identifier, which is what
`PLAN-PROVENANCE-ENDPOINT.md` specifies. A method that hands back the whole subtree
would also make a returned value's size depend on the tree beneath it, which is
the opposite of what §3.2 is built to guarantee.

### The deletions in `modules/server/src/main/scala/.../simulation/LossDistribution.scala`

Three, with no additions.

**The abstract member on the sealed base class, lines 175–176.** It is the class's
only abstract member.

```scala
  /** All trial IDs with non-zero outcomes */
  def trialIds(): Set[TrialId] = trialOutcomes.trialIds

-  /** Flatten hierarchy to vector of all distributions */
-  def flatten: Vector[LossDistribution]
}
```

**The `RiskResult` override, line 191.** The class body becomes empty, so the
braces go with it.

```scala
 case class RiskResult private (
   override val nodeId: NodeId,
   override val trialOutcomes: TrialOutcomes,
   provenances: List[NodeProvenance] = Nil
-) extends LossDistribution(nodeId, trialOutcomes) {
-
-  override def flatten: Vector[LossDistribution] = Vector(this)
-}
+) extends LossDistribution(nodeId, trialOutcomes)
```

**The `RiskResultGroup` override, lines 251–252.** Same: the body becomes empty.

```scala
 final case class RiskResultGroup private (
   children: List[LossDistribution],
   override val nodeId: NodeId,
   override val trialOutcomes: TrialOutcomes
-) extends LossDistribution(nodeId, trialOutcomes) {
-
-  override def flatten: Vector[LossDistribution] =
-    this +: children.toVector.sortBy(_.nodeId.value)
-}
+) extends LossDistribution(nodeId, trialOutcomes)
```

### The deletions in `modules/server/src/test/scala/.../simulation/LossDistributionSpec.scala`

Two. Both test only the deleted method, so neither holds a property to keep.
Removing a test assertion is Decision Trigger #8; this section is where it is
presented, and §10 decision 6 is the ruling behind it.

**`test("flatten returns hierarchy")`, lines 164–174**, inside the
`RiskResultGroup` suite:

```scala
      test("flatten returns hierarchy") {
        val r1    = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val r2    = withCfg(100) { RiskResult(nodeId("risk-002"), Map(2 -> 2000L), Nil) }
        val group = withCfg(100) { RiskResultGroup.create(nodeId("TOTAL"), r1, r2).toEither.toOption.get }

        val flattened = group.flatten

        assertTrue(flattened.size == 3) &&
        assertTrue(flattened(0) == group) &&
        assertTrue(flattened.tail.toSet == Set(r1, r2))
      },
```

The test immediately above it already asserts `group.children == List(r1, r2)`, so
"a group holds its children" stays covered after this one is gone.

**The whole `suite("RiskResult - flatten")`, lines 207–214**, which holds one test:

```scala
    suite("RiskResult - flatten")(
      test("single result flattens to itself") {
        val result    = withCfg(100) { RiskResult(nodeId("risk-001"), Map(1 -> 1000L), Nil) }
        val flattened = result.flatten

        assertTrue(flattened == Vector(result))
      }
    ),
```

The suite is empty once its only test is removed, so the suite goes too, together
with the trailing comma before `suite("RiskResult - equality")`.

### How step 1 is verified

The full run in §11, green, before this step is committed and before §3 starts.
`sbt compile` carries most of the weight: `LossDistribution` is a sealed class and
an inexhaustive match on a sealed hierarchy is a compile error in this build, so a
remaining caller anywhere in `server`, `commonJVM` or `app` fails the build rather
than slipping through.

Step 1 carries its own PATCH bump, `0.10.38` → `0.10.39`, because shipped code
changes. §8 covers the bump for the rest of the plan.

---

## 1b. Other preconditions

1. **The hierarchy already lives in `server`.** Verified: the file is
   `modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala`.
   The inventory bullet
   `modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala`
   is a pre-move path and grants nothing.

Not a precondition, and **not implemented**: M4 slice 1 (§7.6.5). `LECNodeSeries`
and `ExceedanceSeries` do not exist in the tree
(`grep -rn LECNodeSeries modules/` returns nothing), and
`modules/common/src/main/scala/com/risquanter/register/http/responses/AnalysisResponses.scala`
has not been created. Nothing in this plan reaches the wire, so slice 1 can land
before or after it.

---

## 2. Substitution table

This is the plan's contract with the prototype. **Production is the source of
every implementation.** Each row names the production element that stands in the
prototype construct's place. A prototype construct with no row would be a gap in
this plan; there are none — every construct in the folder is accounted for
below, including the ones the answer is "not transplanted".

### 2.1 One row per entry in the README's "What the prototype does not model"

| # | Prototype does not model | Production element that replaces it |
|---|---|---|
| N1 | **Iron refinement and nominal wrappers.** `NodeId`, `MitigationId` and `ContentHash` take raw strings; a trial count is an `Int`; a cap, deductible and scale factor take raw numbers. Nothing rejects an empty identifier or a negative factor. | `NodeId` and `MitigationId` are the ADR-018 nominal `case class` wrappers over `SafeId`, built by `fromString`; `ContentHash` is the Iron-refined digest produced by `ContentHashIndex.hashOf`; a trial count is `PositiveInt` (`TrialOutcomes.nTrials`); `CapLosses`/`ApplyDeductible` take `NonNegativeLong` and `ScaleLosses` takes `RetentionFactor`, all in `ResultTransformSpec`. No signature in §3 takes a raw primitive carrying a domain value (ADR-001 §3). |
| N2 | **Typed validation errors.** `Validated[A] = Either[List[String], A]` accumulating plain strings. | `zio.prelude.Validation[ValidationError, A]`. `ValidationError(field, code, message)` with `ValidationErrorCode.CONSTRAINT_VIOLATION`; the code drives the HTTP mapping at the edge (ADR-010 §2, ADR-035 §1). `Validated.all` → `Validation.validateAll`; `Validated.succeed`/`fail` → `Validation.succeed`/`fail`. |
| N3 | **Concurrency.** The cache is a plain mutable `Map` driven from one thread. | `ContentCache` holds its store in a ZIO `Ref`; every `get`/`put`/`stats` is a `UIO`. Instances are per-workspace through `ContentCacheRegistry.forWorkspace(seedEntityId)` (ADR-014 §4). The portfolio arm resolves children with `ZIO.foreachPar`, licensed by the `TrialOutcomes` laws (ADR-009 §4). |
| N4 | **Two of the five result-stage specifications** — `FilterBelowThreshold` and `InsurancePolicy` are omitted. | All five live in `ResultTransformSpec` and are interpreted by `ResultTransformInterpreter.toTransform`, which this plan does not change. The prototype's three-case `MitigationSpec` is replaced wholesale; see S12. |
| N5 | **The shared tick domain.** The prototype's response builds a point at each observed loss level. | `LECGenerator.generateCurvePointsMulti` computes one shared tick vector across every result in the request (ADR-014 §5). Out of this plan's scope: nothing here touches curve generation, and `LECGenerator` reads only members that survive the change (S24). |
| N6 | **Simulation itself.** One trial, one deterministic figure per leaf. | `Simulator.createSamplerFromLeaf` + `Simulator.performTrials`, driven from `CachedResultResolverLive.simulateLeaf` under the four-layer HDR seed hierarchy (ADR-003 §1). Untouched by this plan except for the two local renames in S22. |

### 2.2 Every other stand-in, found by reading the folder

**`common/Domain.scala`**

| # | Prototype construct | Production element |
|---|---|---|
| S1 | `type Loss = Long` | `com.risquanter.register.domain.data.Loss` (same underlying `Long`, 1L = $1M) |
| S2 | `final case class TrialId(value: Int)` | `com.risquanter.register.domain.data.TrialId` |
| S3 | `final case class NodeId(value: String)` | `com.risquanter.register.domain.data.iron.NodeId` (see N1) |
| S4 | `final case class SimulationConfig(defaultNTrials: Int)` | `com.risquanter.register.configs.SimulationConfig`, loaded from `application.conf` (ADR-003 §3, ADR-016) |
| S5 | `TrialOutcomes(nTrials: Int, outcomes)` with `maxLoss` and `probOfExceedance` **on it** | `com.risquanter.register.simulation.TrialOutcomes(nTrials: PositiveInt, outcomes)` — **unchanged by this plan.** Production deliberately keeps `maxLoss`/`minLoss`/`probOfExceedance` on the distribution, derived from `outcomeCount`, not on `TrialOutcomes`. The prototype put them there only so its sketch could read a figure without building a distribution. Nothing is moved. |
| S6 | `TrialOutcomes.combine`, `.empty`, `.single` | `TrialOutcomes.combine` and `TrialOutcomes.empty` verbatim as they stand today, plus the `Commutative` and config-scoped `Identity` instances (ADR-009 §1). `.single` has **no production counterpart** and is not transplanted — it is a one-trial fixture helper; the new spec builds fixtures with `TrialOutcomes(n, Map(...))` under `withCfg`, as every existing spec does. |
| S7 | `trait LECCurve` | `com.risquanter.register.simulation.LECCurve` — **unchanged**, as the README states. |
| S8 | `NodeProvenance(entityId, lossVarId, distributionType)` | `com.risquanter.register.domain.data.NodeProvenance` — nine fields, content-only, no node identity (ADR-003 §4). |
| S9 | `Validated` / `Validated.succeed` / `.fail` / `.all` | See N2. |

**`common/Mitigation.scala`**

| # | Prototype construct | Production element |
|---|---|---|
| S10 | `sealed trait MitigationSpec` with `CapTotal` / `ApplyDeductible` / `ScaleLosses` | `ResultTransformSpec` (five cases) carried inside `MitigationSpec.ResultStage(pipeline: TransformPipeline)`. The prototype's name `MitigationSpec` collides with a production type of the same name and a different meaning; the prototype's is the one that goes. |
| S11 | `final case class MitigationId(value: String)` | `com.risquanter.register.domain.data.iron.MitigationId` |
| S12 | `MitigationApplicationRecord(mitigationId, spec, precedence: Int)` | `com.risquanter.register.domain.data.MitigationApplicationRecord(mitigationId: MitigationId, spec: MitigationSpec, resolvedScope: Set[NodeId], precedence: MitigationPrecedence)` — **already exists, unchanged.** Two extra fields: `resolvedScope` (the node set the application touched under this tree version and selection) and a `MitigationPrecedence` wrapper instead of a bare `Int`. |
| S13 | `MitigationApplication.run(applied, outcomes)` | A **new** `MitigationApplication.run` with the same meaning, spelled out in §3.3. It collects the `ResultStage` pipelines off `applied`, interprets each with `ResultTransformInterpreter.toTransform`, composes them and applies the composition. The empty list returns the same reference. |
| S14 | `MitigationApplication.step(spec, to)` | `ResultTransformInterpreter.toTransform` + `RiskResultTransform.{applyDeductible, capLosses, scaleLosses, filterBelowThreshold, insurancePolicy}` — **all already exist and are not rewritten.** |
| S15 | `MitigationApplication.recordsFor(nodeId, byNode)` | A **new** `MitigationApplication.recordsByNode(scoped)` computing the whole map once per resolution, plus `records.getOrElse(nodeId, Nil)` at each node. The per-node form cannot be used directly because each record's `resolvedScope` needs a tree-wide inversion. §3.3. |
| S16 | The prototype's `ScaleLosses` step **throws** `ArithmeticException` on overflow | Production's `RiskResultTransform.scaleLosses` **saturates silently today** (`(loss * factor).toLong`). This plan applies the C1 ruling of `docs/scratch/ADR-REVIEW-2026-09-15.md` — make it throw — because `decorate`'s conversion of a layer overflow into a `ValidationError` is otherwise unreachable. §3.4. This is the **one behaviour change to existing code** in the plan and it is a ruled correctness fix, not a discovered bug. |
| S17 | `byNode: Map[NodeId, List[MitigationApplicationRecord]]` supplied directly by the caller | Derived per resolution from `MitigationSelection` + `resolvedScopes` through `MitigationApplication.scoped(tree, selection, resolvedScopes)`, then `recordsByNode`. The resolver already computes `scoped`; only the second step is new. |

**`common/Tree.scala`**

| # | Prototype construct | Production element |
|---|---|---|
| S18 | `RiskNode` / `RiskLeaf(contentKey, rawLoss)` / `RiskPortfolio(childIds)` | `com.risquanter.register.domain.data.{RiskNode, RiskLeaf, RiskPortfolio}`. `contentKey` stands in for the `LeafSimContent` projection (ADR-032 §1) and `rawLoss` for the whole distribution; neither is transplanted. |
| S19 | `TreeIndex(nodes, parents, children)` with `descendants`, `leafIds`, `ancestorPath`; `RiskTree(nodes, rootId)` recomputing `index` | `com.risquanter.register.domain.data.{TreeIndex, RiskTree}`, built through `RiskTree.fromNodes` (ADR-001 §5). Used unchanged. |
| S20 | `object Example` — the §4.2 tree, its §4.3 variant, `capDisk`, `capServers`, `mitigated`, `inherent` | **No production counterpart, and none is created.** These become fixtures inside the new/extended specs in §5, built from real `RiskLeaf`/`RiskPortfolio`/`Mitigation` values. The worked example's *figures* are transplanted as assertions; its code is not. |

**`common/Cache.scala`**

| # | Prototype construct | Production element |
|---|---|---|
| S21 | `ContentHash(value: String)` = the projection itself; `ContentHashIndex.hashOf/build` | `com.risquanter.register.domain.data.iron.ContentHash` = `sha256(LeafSimContent.toJson)`, produced by `ContentHashIndex.hashOf` / `.build` (ADR-014 §2, ADR-032 §1). Unchanged. |
| S22 | `LeafSimResult(outcomes, provenance)`; `CacheStats(entries, hits, misses)`; `ContentCache` | `services/cache/LeafSimResult.scala`, `CacheStats` (which also carries `evictedTotal`), and `services/cache/ContentCache.scala`. Unchanged; see N3. |
| S23 | `Simulator.simulate(leaf)` and `Simulator.leafContent(cache, leaf)` | `CachedResultResolverLive.simulateLeaf` and `CachedResultResolverLive.rawLeafResult`. Both change shape in this plan — they stop attaching node identity and return the pair the cache already holds (§3.5) — but their bodies are otherwise untouched. No eviction and no workspace scoping exist in the prototype; production has `EvictionStrategy` and per-workspace instances, both untouched. |

**`fb-f/FBF.scala`**

| # | Prototype construct | Production element |
|---|---|---|
| S24 | `FBF_NodeLosses` / `FBF_LeafLosses` / `FBF_PortfolioLosses` | `NodeLosses` / `LeafLosses` / `PortfolioLosses`, the names the README gives, each `private[cache]` in `modules/server/src/main/scala/com/risquanter/register/services/cache/NodeLosses.scala` (decision 2, ruled B) and each carrying a scaladoc line saying it is an internal implementation detail. §3.1. |
| S25 | `FBF_LossDistribution` and its nine public members | `LossDistribution`, with every member's production body carried over verbatim from today's sealed base class, reading `trials` where it reads `trialOutcomes`. It carries **five** fields, not the prototype's four: `provenance` is added (decision 3, ruled). `flatten` has no counterpart and is deleted (§1). §3.2. |
| S26 | `FBF_LossDistribution.decorate(nv, applied)` | `LossDistribution.decorate(nodeId, source, provenance, applied, run)` — figures rather than a family member, because a type `private[cache]` cannot be named from `simulation` (decision 2's consequence, stated there). §3.2. |
| S27 | `object FBF_NodeLosses` delegating `leaf` / `portfolio` | `object NodeLosses` with the same two delegating factories. A private primary constructor is reachable only from its own type's companion, so each case keeps its own factory and the family object delegates (ADR-001 §5). |
| S28 | `FBF_Resolver.resolve` / `.resolveAll` / `.value` | `CachedResultResolverLive.distributionOf` / `.distributionForId`, reached through `CachedResultResolver.ensureCached` and `.ensureCachedAll`, whose signatures are unchanged. `.value` collapses into `ensureCached`. §3.5. |
| S29 | `FBF_Resolver.attributedProvenance` | `LossDistribution.leafProvenances(results: Map[NodeId, LossDistribution])`, exactly as `PLAN-PROVENANCE-ENDPOINT.md` already specifies it and in the simplified form that plan already anticipates — `results.flatMap { case (id, d) => d.provenance.map(id -> _) }`, with no type test. The leaf set comes from `tree.index.descendants(nodeId).intersect(tree.index.leafIds)` and each leaf is resolved under its own identifier, so nothing traverses the value structure. That plan owns the endpoint; this plan owns the field it reads. |
| S30 | `FBF_Resolver.childrenOf(losses)` | **Not transplanted.** The README says outright it is recorded "so the limit is visible rather than implied" — a probe for the property suite, with no production caller. A public method without a call site is a violation of the working-protocol functional-composition checklist. |
| S31 | `FBF_LossDistribution.removedHere` (and `FBC`'s) | **Not transplanted.** It is `source.maxLoss - trials.maxLoss`, both public fields, and no production or planned caller reads it. When the interface needs the layer it computes it at the call site from two public fields. |
| S32 | `require(applied.nonEmpty \|\| (trials eq source))` on the public value | Not transplanted as a `require`. The property it asserts is guaranteed structurally instead: `decorate` short-circuits the empty layer and passes one reference twice, so the state the `require` guards cannot be constructed. The property is pinned by a test (§5) rather than by an assertion that can only fire after the fact. |

**`fb-f/FBF_Wire.scala`** — the whole file is out of this plan's scope; it
describes M4 slice 1. Rows are given so the file is accounted for.

| # | Prototype construct | Production element |
|---|---|---|
| S33 | `FBF_WireCodecs` — hand-rolled `JsonEncoder`/`JsonKeyEncoder` for `NodeId`, `MitigationId` | `OpaqueTypes.scala` (zio-json codecs) and `IronTapirCodecs.scala` (Tapir schemas). Already exist. Both identifiers are client-facing by ADR-036 §4. |
| S34 | `FBF_LECPoint`, `FBF_LECNodeCurve` | `LECPoint`, `LECNodeCurve` in `modules/common/.../domain/data/LEC.scala`. Already exist, unchanged. |
| S35 | `FBF_NodeReading(curve, withMitigations)` | `LECNodeSeries`, specified in PLAN-RISKTRANSFORM §7.6.5 and **not yet built**. §8.18's first correction stands: there is no second wire record. Out of scope. |
| S36 | `FBF_Wire.unconditionalQuantile` / `quantiles` / `averageAnnualLoss` / `probabilityOfNoLoss` / `curveOf` | `LECGenerator.unconditionalQuantile` / `calculateQuantiles` / `averageAnnualLoss` / `probabilityOfNoLoss` / the multi-curve path. Already exist; the prototype transcribed them. Untouched. |
| S37 | `FBF_Wire.response(readings)` | The Tapir `jsonBody[Map[NodeId, List[LECNodeSeries]]]` of M4 slice 1. Out of scope. |
| S38 | `Check.noInstance(...)` via `scala.compiletime.testing.typeChecks` — the confinement assertion that no valuation type has an encoder | **Not transplanted as a test.** The property holds structurally and is checked by reading: no `JsonCodec`, `JsonEncoder`, `JsonDecoder` or Tapir `Schema` is defined for `LossDistribution`, `NodeLosses`, `LeafLosses`, `PortfolioLosses`, `TrialOutcomes` or `NodeProvenance`'s carrier on the valuation path, and no DTO under `http/responses/` references any of them. §7 records the check. |

**`common/Check.scala`, `spec/*`** — the assertion harness, the four adapters,
the property suite and its entry point. Replaced wholesale by **zio-test**
(`ZIOSpecDefault`, `assertTrue`), which every existing spec in the repository
uses. `Check.that` → `assertTrue`; `Check.equals` → `assertTrue(a == b)`;
`Check.throws[E]` → `ZIO.attempt(...).exit` with `fails(isSubtype[E])`;
`Check.rejects` → asserting the `Validation` is a failure carrying
`CONSTRAINT_VIOLATION`; `Check.notExpressible` has no counterpart, because one
variant is being built rather than four compared.

---

## 3. Exact signatures

### 3.1 The internal family

**New file:**
`modules/server/src/main/scala/com/risquanter/register/services/cache/NodeLosses.scala`.
Decision 2 was ruled B: the family is `private[cache]`, so it lives in the
resolver's own package and nothing outside it can name any of the three. Written
for decision 7 **option A** — the family as the prototype draws it. Option B
deletes `NodeLosses` and `LeafLosses` and keeps only the portfolio gate; §10
decision 7 states that diff.

```scala
package com.risquanter.register.services.cache

/** INTERNAL IMPLEMENTATION DETAIL. Not part of any consumer-facing surface.
  *
  * The node's figures before its own layer of result-stage mitigations. Only
  * `CachedResultResolverLive` builds or reads one; the value every consumer
  * receives is `LossDistribution`, which names no member of this family and
  * holds no reference to one. `private[cache]` is the compiler's statement of
  * that: this type cannot be named outside `services.cache`.
  *
  * The family does the combining. `PortfolioLosses.create` derives its total
  * from the children it is given and offers no parameter through which a
  * different total could arrive, so a portfolio cannot claim an aggregate its
  * children do not support. That is the honesty-by-construction mechanism the
  * category-theory derivation names.
  *
  * A portfolio holds its children as `LossDistribution` values — the mitigated
  * children — because the rule being folded is `m(P) = f_P((+) m(children))`
  * and the things combined are the mitigated children.
  */
private[cache] sealed trait NodeLosses {
  def nodeId: NodeId
  def trials: TrialOutcomes
}

/** INTERNAL IMPLEMENTATION DETAIL — see `NodeLosses`.
  *
  * A simulated leaf: the cached figures for the effective leaf, and the
  * provenance record that produced them. */
private[cache] final case class LeafLosses private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  provenance: NodeProvenance
) extends NodeLosses

private[cache] object LeafLosses {
  /** No failure mode: the figures and the record both arrive already built from
    * the cache. Returns the value rather than a `Validation` for that reason,
    * matching the `RiskResult.fromTrialOutcomes` it replaces.
    *
    * Both are required, and the record is one rather than a list: a simulated
    * leaf has exactly one, and `LeafSimResult` — the only source of either —
    * holds the two together. */
  def create(
    nodeId: NodeId,
    trials: TrialOutcomes,
    provenance: NodeProvenance
  ): LeafLosses = LeafLosses(nodeId, trials, provenance)
}

/** INTERNAL IMPLEMENTATION DETAIL — see `NodeLosses`.
  *
  * An aggregated portfolio. `trials` is always the combine of the children's
  * `trials`, guaranteed by the factory below. */
private[cache] final case class PortfolioLosses private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  children: List[LossDistribution]
) extends NodeLosses

private[cache] object PortfolioLosses {
  /** The only way to build one. It takes the children and derives the total;
    * there is no parameter through which a wrong total could arrive.
    *
    * Failure modes separated by origin (ADR-010, ADR-033 §3 and §5). Children
    * at differing trial counts is a programming error — the resolver builds
    * them all under one `SimulationConfig` — so the `require` stays and
    * propagates; it sits before the `try` so that widening the catch later
    * cannot convert it into a validation error. A per-trial sum above
    * `Long.MaxValue` is reachable from validated user data through extreme
    * distribution parameters, so `Math.addExact`'s `ArithmeticException` is
    * caught here and converted. */
  def create(
    nodeId: NodeId,
    children: List[LossDistribution]
  ): Validation[ValidationError, PortfolioLosses] = {
    require(
      children.isEmpty || children.map(_.nTrials).distinct.sizeIs == 1,
      s"Cannot aggregate distributions with different trial counts: ${children.map(_.nTrials).mkString(", ")}"
    )
    try
      children.map(_.trials).reduceOption(TrialOutcomes.combine) match {
        // No children means nothing supplies a trial count, and naming one would
        // claim an identity this type has only per slice. It is refused instead
        // (decision 4) — which is also what the domain already says, in four
        // other places.
        case Some(combined) => Validation.succeed(PortfolioLosses(nodeId, combined, children))
        case None           => Validation.fail(emptyPortfolio(nodeId))
      }
    catch { case _: ArithmeticException => Validation.fail(aggregateOverflow(nodeId)) }
  }

  private def aggregateOverflow(nodeId: NodeId): ValidationError =
    ValidationError(
      field   = s"riskPortfolio.${nodeId.value}",
      code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = ValidationMessages.aggregatedLossOverflow
    )

  /** `ValidationMessages.portfolioHasNoChildren` is added by this change; the
    * resolver's existing inline `s"RiskPortfolio '${portfolio.id}' has no
    * children"` moves to it, so the two sites that can raise this carry one
    * wording. */
  private def emptyPortfolio(nodeId: NodeId): ValidationError =
    ValidationError(
      field   = s"riskPortfolio.${nodeId.value}.childIds",
      code    = ValidationErrorCode.EMPTY_COLLECTION,
      message = ValidationMessages.portfolioHasNoChildren
    )
}

private[cache] object NodeLosses {
  def leaf(
    nodeId: NodeId,
    trials: TrialOutcomes,
    provenance: NodeProvenance
  ): LeafLosses = LeafLosses.create(nodeId, trials, provenance)

  def portfolio(
    nodeId: NodeId,
    children: List[LossDistribution]
  ): Validation[ValidationError, PortfolioLosses] =
    PortfolioLosses.create(nodeId, children)
}
```

**What confines this family, beyond `private[cache]`.** Visibility says who may
name the type; three separate mechanisms say what may be built, and they are the
ones that carry the correctness weight:

1. **Private primary constructors.** Neither case can be built except through its
   own companion's factory. Scala 3 makes the generated `.copy` and `.apply`
   private along with the primary constructor (E173), so there is no second path
   (ADR-001 §5).
2. **Derivation rather than acceptance.** `PortfolioLosses.create` computes the
   total from the children. No caller can supply one, anywhere, at any visibility.
   This is the guarantee that does not depend on who can see the type.
3. **Required parameters, singular where the data is singular.**
   `LeafLosses.create` takes the figures and the provenance record, both required,
   the record as one value rather than a list. A simulated leaf always has exactly
   one, so no absent and no plural state is representable (decision 8).

What visibility adds on top of mechanisms 1 and 2 is narrow: an extra caller
could at worst build a redundant value, never a dishonest one.

**One deliberate behaviour change, recorded rather than escalated separately
because it is this section's own subject** (Decision Trigger #5). Today
`RiskResultGroup`'s private `apply` writes the aggregate as
`TrialOutcomes(cfg.defaultNTrials, LossDistribution.merge(results*))`, stamping
the configured trial count onto the aggregate whatever the children carry.
`PortfolioLosses.create` reduces with `TrialOutcomes.combine`, which carries the
children's own count through. The two agree whenever the `require` above holds
and the children were built under one config, which is every production path.
They disagree only in a test that builds children at one trial count under a
config naming another. The new behaviour is the correct one: `nTrials` is the
denominator of `probOfExceedance`, so reporting a count the data does not have
makes every probability from that value wrong. This change is also carried by
§9.3 of `docs/scratch/OBSOLATE-FB-VALUATION-TYPE-DESIGNS.md`, which notes all
four candidate designs share it and that it needs a test.

### 3.2 The public value

Same file. `LECCurve` and `TrialOutcomes` and its companion are untouched. The
`sealed abstract class LossDistribution` and its two subclasses are replaced by
one concrete type; the members that were `final` on the base class lose the
modifier, which a `final case class` makes redundant, and their bodies are
unchanged.

```scala
/** One node's loss distribution under one mitigation selection.
  *
  * `trials` is the figure after this node's own layer of result-stage
  * mitigations. `source` is the figure that layer was applied to: at a leaf
  * the cached simulation output, which already carries any parameter-stage
  * mitigation because those are folded into the tree before its content hash
  * is computed; at a portfolio the combine of the children's mitigated values.
  *
  * `source` is not the inherent figure. A node with a mitigated descendant and
  * no layer of its own carries `trials eq source`, and neither equals what the
  * same node carries under `MitigationSelection.Inherent`. The inherent figure
  * is a second reading, never a field.
  *
  * `applied` is this node's own layer in precedence order, and is empty exactly
  * when the layer is. It does not cross the wire: a response names the
  * mitigations that shaped each reading, and the client already holds each
  * mitigation's spec and resolved scope from the tree read.
  *
  * `provenance` holds the simulation record for this node: present for a
  * simulated leaf, absent for a portfolio, which draws nothing of its own
  * (ADR-003 §2). It is an `Option` rather than a list because a node has one
  * record or none; nothing produces two. Capture is unconditional and happens at simulation time
  * (ADR-003 §4); what varies is only what is returned. No curve or exceedance
  * response carries it — those wire types are built from the figures — and the
  * dedicated audit endpoint is the only reader.
  *
  * The value names no member of the internal family and holds no reference to
  * one, so its size is bounded by the node rather than by the subtree beneath
  * it, and a walk over a returned value cannot reach another node.
  */
final case class LossDistribution private (
  nodeId: NodeId,
  trials: TrialOutcomes,
  source: TrialOutcomes,
  applied: List[MitigationApplicationRecord],
  provenance: Option[NodeProvenance]
) extends LECCurve {

  /** Sparse trial→loss map (delegates to the embedded TrialOutcomes) */
  def outcomes: Map[TrialId, Loss] = trials.outcomes

  override def nTrials: Int = trials.nTrials

  /** Frequency distribution of loss amounts (histogram view) */
  lazy val outcomeCount: TreeMap[Loss, Int] =
    TreeMap.from(outcomes.values.groupMapReduce(x => x)(_ => 1)(_ + _))(using Ord[Loss].toScala)

  override lazy val maxLoss: Loss =
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.max(using Ord[Loss].toScala)

  override lazy val minLoss: Loss =
    if (outcomeCount.isEmpty) 0L else outcomeCount.keys.min(using Ord[Loss].toScala)

  override def probOfExceedance(threshold: Loss): Double = {
    val exceedingCount = outcomeCount.rangeFrom(threshold).values.sum
    exceedingCount.toDouble / nTrials.toDouble
  }

  /** Get outcome for specific trial (0 if not present) */
  def outcomeOf(trial: TrialId): Loss = trials.outcomeOf(trial)

  /** All trial IDs with non-zero outcomes */
  def trialIds(): Set[TrialId] = trials.trialIds
}

object LossDistribution {

  /** Apply a node's layer to the figures a member of the internal family
    * derived, producing the public value. This is `decorate` from the reasoning
    * document's construction algorithm (§8.3); `create` would not say what it
    * does.
    *
    * It takes figures rather than the member itself because the family is
    * `private[cache]` and cannot be named from this package. The one call site
    * is the resolver, which has just built the member; the property that
    * `source` is the combine of a portfolio's children is guaranteed by
    * `PortfolioLosses.create` deriving it and pinned by a test, rather than by
    * this signature.
    *
    * `run` is the layer's composed transform. It arrives as a function rather
    * than being derived here because interpreting a `MitigationSpec` belongs to
    * the mitigation package and this file does not depend on it; the caller
    * takes it from the same `applied` it passes.
    *
    * An empty layer short-circuits and passes one reference twice, which is
    * what makes "the identity leaves the figures unchanged by reference" a
    * fact about the constructed value rather than a rule `run` has to honour.
    * Most nodes carry no mitigation; rebuilding would give every one of them a
    * second copy of its outcome map.
    *
    * The arithmetic failure converted here is the layer's own: a scale factor
    * that takes a loss past `Long.MaxValue`, or a deductible-and-cap pipeline
    * that overflows. It is independent of the combine, which
    * `PortfolioLosses.create` converts (ADR-010, ADR-033 §3). */
  def decorate(
    nodeId: NodeId,
    source: TrialOutcomes,
    provenance: Option[NodeProvenance],
    applied: List[MitigationApplicationRecord],
    run: TrialOutcomes => TrialOutcomes
  ): Validation[ValidationError, LossDistribution] =
    if (applied.isEmpty)
      Validation.succeed(LossDistribution(nodeId, source, source, Nil, provenance))
    else
      try Validation.succeed(LossDistribution(nodeId, run(source), source, applied, provenance))
      catch { case _: ArithmeticException => Validation.fail(layerOverflow(nodeId)) }

  /** Each simulated leaf's provenance record, keyed by the node that carries it.
    *
    * A leaf resolution always carries one record; a portfolio carries none, so a
    * portfolio entry contributes nothing. The caller supplies the leaf
    * set from `TreeIndex` and resolves each leaf under its own identifier, so
    * nothing here walks a value structure. Specified by
    * `PLAN-PROVENANCE-ENDPOINT.md`, in the form that plan already anticipates
    * for a flat valuation type. */
  def leafProvenances(results: Map[NodeId, LossDistribution]): Map[NodeId, NodeProvenance] =
    results.iterator.flatMap { case (id, d) => d.provenance.map(id -> _) }.toMap

  private def layerOverflow(nodeId: NodeId): ValidationError =
    ValidationError(
      field   = s"mitigatedResult.${nodeId.value}",
      code    = ValidationErrorCode.CONSTRAINT_VIOLATION,
      message = ValidationMessages.aggregatedLossOverflow
    )

  /** Structural equality on the whole value, matching every other `Equal`
    * instance in the codebase (decision 5). Unlike the `Equal[RiskResult]` it
    * replaces, it does not exclude `provenance`: two readings that differ only
    * in a run timestamp are different values under this relation. Nothing in
    * production summons it. */
  given Equal[LossDistribution] = Equal.default

  given Debug[LossDistribution] = Debug.make { d =>
    s"LossDistribution(${d.nodeId}, ${d.outcomes.size} outcomes, ${d.nTrials} trials, " +
    s"max=${d.maxLoss}, ${d.applied.size} applied)"
  }
}
```

`LossDistribution.merge` is absent from the listing, and §3.1 is written to fold
over `TrialOutcomes.combine` directly. Decision 4 records why that loses nothing:
`merge` is already `reduceOption(TrialOutcomes.combine)` with a `.map(_.outcomes)`
on the end, so its only distinct property is that it throws the trial count away
— which is precisely the cause of the config-stamped count §3.1 corrects.

### 3.3 The mitigation package

`modules/server/src/main/scala/com/risquanter/register/mitigation/MitigationApplication.scala`.
Two functions change; `scoped`, `effectiveTree` and `applicationRecords` keep
their shape, their callers and their tests. `resultTransformFor` loses its only
production caller and is replaced by a function over a layer, so the records and
the transform are always read off the same list and cannot disagree.

```scala
  /** One record per **result-stage** mitigation scoping each node, in
    * precedence order — the layer that node's own valuation applies.
    *
    * This is the per-node counterpart of `applicationRecords`, which answers a
    * whole-tree question and does not filter by stage. Parameter-stage
    * transforms are folded into the effective tree before the content hash is
    * computed, so they shape the figure the layer is applied to and are never
    * part of the layer. Each record's `resolvedScope` is that mitigation's
    * whole node set under this tree version and selection, which is why the
    * map is computed once for the tree rather than per node. */
  def recordsByNode(
    scoped: Map[NodeId, List[Mitigation]]
  ): Map[NodeId, List[MitigationApplicationRecord]]

  /** The composed transform of one node's layer, applied. Every record a layer
    * holds is result-stage by construction, so an empty layer composes to
    * nothing and this returns the same reference it was given. */
  def run(
    applied: List[MitigationApplicationRecord],
    outcomes: TrialOutcomes
  ): TrialOutcomes
```

Both names come from the reasoning document's own construction algorithm (§8.3):
`recordsFor(node)` and `run(records, outcomes)`. `scoped` already returns each
node's mitigations in precedence order, so `recordsByNode` preserves that order
by filtering rather than re-sorting.

### 3.4 The scale-loss overflow guard (ruling C1)

`modules/server/src/main/scala/com/risquanter/register/mitigation/RiskResultTransform.scala`.
`scaleLosses` narrows `loss * factor` with `.toLong`; a `Double` outside
`Long`'s range saturates at `Long.MaxValue` instead of throwing, so an
over-scaled loss is presented as a real figure. Ruled 2026-09-15
(`docs/scratch/ADR-REVIEW-2026-09-15.md` C1) to be fixed inside this change,
with a test pinning it as a required deliverable.

```scala
  def scaleLosses(factor: NonNegativeDouble): RiskResultTransform = RiskResultTransform { to =>
    val scaled = to.outcomes.map { case (trial, loss) =>
      val product = loss * factor
      // Double→Long narrowing saturates silently at Long.MaxValue. Throw the way
      // TrialOutcomes.combine does; LossDistribution.decorate converts it (ADR-033 §3).
      if (product.isNaN || product >= Long.MaxValue.toDouble)
        throw new ArithmeticException(s"scaled loss overflow: $loss * $factor")
      trial -> product.toLong
    }.filter(_._2 > 0)

    to.copy(outcomes = scaled)
  }
```

The bound is conservative: doubles lose integer precision near 2^63, so `>=`
rejects a narrow band that would in fact have narrowed correctly rather than
admitting one that would not. `RiskResultTransform` keeps its total shape; the
conversion boundary is `LossDistribution.decorate`, exactly as
`RiskResultGroup.create` is the boundary for `TrialOutcomes.combine` today.

The other three transforms are safe and are not touched: `applyDeductible`
subtracts non-negative values and floors at zero, `capLosses` takes a minimum,
`insurancePolicy` composes those two.

### 3.5 The resolver

`modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala`.
The two trait methods keep their signatures exactly — `Task[LossDistribution]`
and `Task[Map[NodeId, LossDistribution]]`.

Both entry points compute `records` where they compute `scoped` today:

```scala
        scoped     = MitigationApplication.scoped(tree, selection, resolvedScopes)
        records    = MitigationApplication.recordsByNode(scoped)
```

The recursion returns the public value alone. No pair is threaded and no method
is added to the trait: provenance reaches the audit endpoint on the returned
value's own `provenance` field, read by `LossDistribution.leafProvenances`
(decision 3).

```scala
  private def distributionForId(
    tree: RiskTree,
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    nodeId: NodeId,
    seedEntityId: SeedEntityId.SeedEntityId,
    records: Map[NodeId, List[MitigationApplicationRecord]]
  ): Task[LossDistribution]

  private def distributionOf(
    tree: RiskTree,
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    node: RiskNode,
    seedEntityId: SeedEntityId.SeedEntityId,
    records: Map[NodeId, List[MitigationApplicationRecord]]
  ): Task[LossDistribution] =
    node match {
      case leaf: RiskLeaf =>
        for {
          content <- rawLeafResult(hashes, cache, leaf, seedEntityId)
          losses   = NodeLosses.leaf(leaf.id, content.outcomes, content.provenance)
          applied  = records.getOrElse(leaf.id, Nil)
          value   <- fromValidation(LossDistribution.decorate(
                       losses.nodeId, losses.trials, Some(losses.provenance), applied,
                       MitigationApplication.run(applied, _)))
        } yield value

      case portfolio: RiskPortfolio =>
        for {
          childResults <- ZIO.foreachPar(portfolio.childIds.toList) { childId => /* unchanged */ }
          _            <- ZIO.when(childResults.isEmpty) { /* unchanged EMPTY_COLLECTION failure */ }
          losses       <- fromValidation(NodeLosses.portfolio(portfolio.id, childResults))
          applied       = records.getOrElse(portfolio.id, Nil)
          value        <- fromValidation(LossDistribution.decorate(
                            losses.nodeId, losses.trials, Nil, applied,
                            MitigationApplication.run(applied, _)))
        } yield value
    }

  private def fromValidation[A](v: Validation[ValidationError, A]): Task[A] =
    ZIO.fromEither(v.toEither).mapError(errs => ValidationFailed(errs.toList))

  private def rawLeafResult(
    hashes: Map[NodeId, ContentHash],
    cache: ContentCache,
    leaf: RiskLeaf,
    seedEntityId: SeedEntityId.SeedEntityId
  ): Task[LeafSimResult]

  private def simulateLeaf(
    cache: ContentCache,
    key: ContentHash,
    leaf: RiskLeaf,
    seedEntityId: SeedEntityId.SeedEntityId
  ): Task[LeafSimResult]
```

`rawLeafResult` and `simulateLeaf` stop attaching node identity, because
`NodeLosses.leaf` now does it, and they return **the cache's own value type
unchanged** — `LeafSimResult`, which `simulateLeaf` already builds one line
before storing it and which `rawLeafResult` already holds on a hit. Their bodies
lose the three `RiskResult.fromTrialOutcomes` wrappers and nothing else.
`RiskResult.fromTrialOutcomes`, `RiskResult.apply(nodeId, outcomes, provenances)`
and `RiskResult.empty` go with them. Inside `simulateLeaf` the local `trials`
holds a `Map[TrialId, Loss]` and is renamed `losses`, so that `trials` is not two
different things one line apart.

A portfolio passes `None` for `provenance`, which is ADR-003 §2 written as code:
a portfolio's "trials are sums of its children and draw nothing of their own", so
it has no record to carry. Its descendants' records are read by resolving those
leaves under their own identifiers, never by aggregating upward — the property
`PLAN-PROVENANCE-ENDPOINT.md` is built on.

**Two things disappear from this file and both are improvements worth naming.**

The **portfolio collapse** goes. Today a result-stage mitigation binding at a
portfolio cannot be expressed as a group, because `RiskResultGroup`'s
constructor pins its aggregate to the combine of its children; the code
therefore rebuilds the node as a flat `RiskResult` and discards the child
structure. Under this shape the aggregate claim sits on `PortfolioLosses`, which
the layer does not touch, so a transformed portfolio keeps its children. This is
`MITIGATION-VALUATION-EXPLAINED.md` Part 7.3's anomaly removed: a transform that
does nothing no longer changes the type of the answer.

**`descendantProvenances` goes with it.** It was called only from the collapse
branch, and it returned a flat `List[NodeProvenance]` with the node keys thrown
away, which is why the provenance endpoint could never have used it.
`LossDistribution.leafProvenances` replaces it with the keys kept.

---

## 4. Consumers that do not change, and why

`LECGenerator`, `RiskTreeKnowledgeBase`, `QueryServiceLive` and
`RiskTreeServiceLive` all read their `LossDistribution` values through members
that were `final` on the base class and are ordinary members of the case class
now — `outcomeCount`, `nTrials`, `probOfExceedance`, `maxLoss`, `minLoss`,
`outcomes`, `nodeId`. Verified by grep: none of them names a subtype, matches on
one, or reads `children`, `provenances` or `flatten`.

The `app` module references neither `LossDistribution` nor `RiskResult` at all
(`grep -rn 'LossDistribution\|RiskResult' modules/app/src` returns nothing), so
there is no Scala.js ripple. §8.18's documentation-sweep row for
`modules/app/src/main/scala/app/components/LECSpecBuilder.scala` is stale twice
over: the file is at `modules/app/src/main/scala/app/chart/LECSpecBuilder.scala`
and it names neither type.

The wire shape is unchanged, because every response carries a generated curve
derived from the figures. No codec and no Tapir schema is added to any type in
this plan.

---

## 5. Test changes

Every spec that names one of these types. The blast radius is wide and shallow:
most of them want a value with given outcomes and reached for `RiskResult` only
because it was the concrete leaf case, so they swap one constructor call. **No
test helper with back-door constructors is introduced** — one would be a hole in
the guarantee that an aggregate cannot be claimed without its children.

`withCfg(n) { LossDistribution.decorate(id, TrialOutcomes(n, outcomes), Nil, Nil, identity).toEither.toOption.get }`
is the replacement for `RiskResult(id, outcomes, Nil)`. The empty-layer
short-circuit makes the `run` argument unreachable there, so `identity` is not a
claim about anything, and the `Validation` cannot fail with an empty layer.

**`private[cache]` splits the specs, and this is decision 2's one real cost on
the test side.** A spec in package `com.risquanter.register.simulation` cannot
name `NodeLosses`, `LeafLosses` or `PortfolioLosses` at all, so the suites that
exercise the aggregate — `LossDistributionSpec`'s `RiskResultGroup.create` cases
and `PreludeOrdUsageSpec`'s `RiskResultGroup` suite — cannot stay where they are
and keep testing what they test. They move to a new spec in the family's own
package, which is the convention already in force: tests follow their subject's
package. `LossDistributionSpec` keeps everything reachable through the public
type: the curve members, `decorate`, the layer properties, the worked example.

| File | Change |
|---|---|
| `modules/server/src/test/scala/com/risquanter/register/simulation/LossDistributionSpec.scala` | Stays where it is. Every `RiskResult(id, outcomes, Nil)` and `RiskResult.empty(id)` becomes the `decorate` form above. Its `RiskResultGroup.create` sites move to `NodeLossesSpec`. The six `LossDistribution.merge` sites (lines 105, 113, 121, 130, 275, 284) are rewritten against `TrialOutcomes.combine` — the same outer-join and overflow laws, stated at the layer that owns them (decision 4). The four `Equal` assertions (lines 220, 226, 232, 254) keep summoning the instance, now `Equal[LossDistribution]`. Three hold unchanged; line 254 inverts, because `Equal.default` includes `provenance` where the deleted `Equal[RiskResult]` excluded it — that test is rewritten to assert the two readings are **not** equal, and renamed (decision 5). The `flatten` suite is already gone with the precondition landing (§1). |
| `modules/server/src/test/scala/com/risquanter/register/simulation/PreludeOrdUsageSpec.scala` | Its `RiskResult` suite swaps constructors and stays. Its `RiskResultGroup - Ord[Loss] with TreeMap` suite (lines 133–176) exercises the aggregate and moves to the new spec below, because `PortfolioLosses` is not nameable from this package. One assertion in that suite changes rather than moving unaltered: *"empty group has zero max/min"* at line 173 builds `RiskResultGroup.create(nodeId("empty-risk"))` and asserts `maxLoss == 0L` and `minLoss == 0L`. Construction is refused now, so it becomes an assertion that `PortfolioLosses.create(nodeId, Nil)` fails, renamed to say so (decision 4, Decision Triggers #5 and #8). |
| **New:** `modules/server/src/test/scala/com/risquanter/register/services/cache/NodeLossesSpec.scala`, package `com.risquanter.register.services.cache` | The aggregate's own properties, which only a spec inside this package can state: `PortfolioLosses.create` derives `trials` as the combine of exactly its children; the trial count comes from the children and not from `SimulationConfig`; children at differing counts throw; a combine overflow becomes `CONSTRAINT_VIOLATION`; the empty child list is refused with `EMPTY_COLLECTION`; and `LeafLosses.create` carries the figures and the record through without altering either. The suites arriving from `LossDistributionSpec` and `PreludeOrdUsageSpec` land here. |
| `modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala` | Its `leafProvenances` helper (lines 43–47) drops its type test and becomes a call to `LossDistribution.leafProvenances`; the child-walk at lines 336–337 is rewritten to read `provenance` off each resolved leaf's own value, keyed by node — the same records, the same keys, no traversal. It keeps every assertion it has. The spec relocates to `modules/server/src/test/scala/com/risquanter/register/simulation/ProvenanceSpec.scala` with the type it exercises; it is misfiled today independently of this change, since its subject is provenance capture through `CachedResultResolver` while its package is `domain.data`. |
| `modules/server/src/test/scala/com/risquanter/register/services/cache/CachedResultResolverSpec.scala` | No move. Its four type-name assertions are rewritten under Decision Trigger #8, and one inverts. Listed in full below this table. |
| `modules/server/src/test/scala/com/risquanter/register/simulation/LECGeneratorSpec.scala` | Constructor swap throughout; `Map.empty[String, RiskResult]` becomes `Map.empty[String, LossDistribution]`. |
| `modules/server/src/test/scala/com/risquanter/register/testutil/RiskResultTestSupport.scala` | `identityFor` returns `LossDistribution`. Nothing here builds a group, so the file neither splits nor moves. Its name no longer describes its contents; renaming it is a separate hygiene change and is not folded in. |
| `modules/server/src/test/scala/com/risquanter/register/services/helper/SimulatorSpec.scala` | Two local helpers swap the constructor. |
| `modules/server/src/test/scala/com/risquanter/register/services/QueryServiceLiveSpec.scala` | `flat` returns `LossDistribution`; the `widen` helper is deleted, because the map no longer needs widening to a base type. |
| `modules/server/src/test/scala/com/risquanter/register/foladapter/BinderIntegrationSpec.scala` | Constructor swap; the `(v: LossDistribution)` ascription at line 96 is deleted for the same reason. |
| `modules/server/src/test/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBaseSpec.scala` | Same, and its own `widen` helper (lines 149–152) is deleted. |
| `modules/server/src/test/scala/com/risquanter/register/services/pipeline/InvalidationHandlerSpec.scala` | Remove the unused `RiskResult` import at line 8. It is dead today. |
| `modules/server/src/test/scala/com/risquanter/register/mitigation/MitigationApplicationSpec.scala` | The `resultTransformFor` suite (line 183) is rewritten against `run`, and a `recordsByNode` suite is added: a parameter-stage mitigation produces no record; a node with two result-stage mitigations gets both in precedence order; each record's `resolvedScope` holds every node that mitigation reaches, not only the one whose layer it is on; `run(Nil, outcomes)` returns the same reference. The `applicationRecords` suite is untouched. |
| `modules/server/src/test/scala/com/risquanter/register/mitigation/RiskResultTransformSpec.scala` | Gains the C1 test, a required deliverable of that ruling: a scale factor that takes a loss past `Long.MaxValue` raises `ArithmeticException` instead of saturating, and the same case reaches a caller as a `CONSTRAINT_VIOLATION` through `LossDistribution.decorate`. |
| `modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala` | Comment only — line 23 names `RiskResult.outcomes`. |

**What `LossDistributionSpec` gains**, because the derived factories newly
guarantee it, and because these are the properties the prototype's suite
established:

- a portfolio's `source` is exactly the combine of its children's `trials` —
  stated here through the resolver's output, and at the construction site in
  `NodeLossesSpec`;
- an empty `applied` yields `trials eq source` — reference equality, the
  §8.3 implementation constraint;
- a non-empty `applied` leaves `source` at the combine while `trials` differs;
- a layer that overflows fails with `CONSTRAINT_VIOLATION` rather than throwing
  (the combine's overflow is `NodeLossesSpec`'s);
- a leaf's value carries exactly one provenance record and a portfolio's carries
  none;
- the `MITIGATION-VALUATION-EXPLAINED.md` §4.2 worked example, end to end: the
  inherent reading gives DiskFailure 9, PowerLoss 14, Servers 23, Fraud 3,
  Group 26; the mitigated reading gives 6, 14, 18, 3, 21; and with the §4.3
  figures Servers is 10 rather than 18, because the cap sees the capped child
  and not the raw sum;
- the inherent reading is the identity instance of the same fold — the same
  method called with `MitigationSelection.Inherent`, with every `applied` list
  empty and every node's figures literally the same object before and after the
  step (§8.5); it is not a second method.

**The rewritten `CachedResultResolverSpec` assertions** — the only shipped
assertions this change alters (Decision Trigger #8):

- line 410, an un-mitigated portfolio read: "is a `RiskResultGroup`" becomes
  an empty `applied` and `trials eq source`. The old assertion infers that no
  transform ran from an uncollapsed type; the new one states it.
- line 461–462, a portfolio read under a selection that binds a transform at the
  root: "is a flat `RiskResult`" **inverts** — the value now carries that
  mitigation's record in `applied` and its `trials` differs from its `source`,
  and the aggregate underneath keeps its children.
- line 489, the compositional-fold case: same inversion.
- New, and the case the old type-name assertions could not express at all: a
  portfolio read under a selection that binds only *below* the root carries an
  empty `applied` at the root, `trials eq source` there, and a root figure that
  differs from the un-mitigated one. This is the property that makes `source`
  distinct from the inherent figure.

---

## 6. Documentation sweep

Landed in the same pass, per the standing docs-as-current-state rule.

**Comments naming the two subtypes as though they were the consumer-facing
shape:**

- `modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala` — the header at line 17 ("Extract NodeProvenance from RiskResult.provenances") and lines 29–30 ("a leaf's record sits on its `RiskResult` … portfolio provenance is read by walking `RiskResultGroup.children`").
- `modules/common/src/main/scala/com/risquanter/register/domain/data/LEC.scala` — lines 34 and 37 name `RiskResult`.
- `modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala` — `MitigationApplicationRecord`'s header says it "sits on the valuation the mitigated fold produces"; the type it sits on now has a name.
- `modules/server/src/main/scala/com/risquanter/register/simulation/LECGenerator.scala` — lines 275 and 336 name `RiskResult`.
- `modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala` — line 10, and the pipeline description at lines 28–35, which describes the leaf transform edge and the portfolio collapse this change removes.
- `modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala` — line 126.

**ADRs.** Three carry text that this change makes wrong, two of them
uncompilable. They are amended directly (an ADR is out of scope of the
hand-over-a-block rule and is edited in the same pass).

- **ADR-009 §2** enumerates `sealed abstract class LossDistribution` with
  `RiskResult` and `RiskResultGroup` as its two subtypes and calls them the
  uniform read interface. **§3** writes
  `RiskResultGroup(portfolio.id, childResults*)` as the portfolio constructor.
  **§5** publishes
  `group.children.collect { case r: RiskResult => r.nodeId -> r.provenances }`,
  which does not compile after this change under any decision-2 option. The
  Implementation table names both subtypes with file paths. All four are
  amended.
- **ADR-003's "Mandatory Provenance in Domain Objects" code smell** — deleted
  together with the sentence in §4 that restated it. The smell's reason ("blocks
  test data … cannot construct without running a simulation") is false here:
  `NodeProvenance` has a public constructor and two tests build one directly.
  Done, not pending.
- **ADR-003 §4** publishes the same uncompilable snippet and the prose "Its
  records are read by walking its children and pairing each child's `nodeId`
  with that child's records … in child order". Amended to whatever decision 3
  rules. Its Implementation table's row *"Optional provenance capture | ✅
  Implemented (`includeProvenance` flag)"* is separately false (D3 of the ADR
  review); it is corrected in the same edit because the row is in the block
  being touched.
- **ADR-034 Decision 4** writes out `ValuationResult` as a fourth case of the
  sealed `LossDistribution` hierarchy, with `source: LossDistribution`. That
  type is not built. Decision 4 is rewritten to the shape in §3, keeping its
  reasoning — unconditional wrapping, the empty list as identity, the
  same-reference constraint — which is unaffected. Its Implementation table's
  last two rows (`MitigationApplication.resultTransformFor`; `ValuationResult`
  "ruled, not yet built") are corrected.
- **ADR-035 §1's** exhaustiveness guarantee is preserved rather than weakened,
  and the record needs no edit: `LossDistribution` stops being sealed because it
  stops having cases, while `NodeLosses` is sealed in one file with its two
  cases, so a third origin is still a compile error wherever it is matched.
  `ADR-REVIEW-2026-09-15` finding A1 — which asked for a third branch in
  `descendantProvenances` — is void: there is no third case and that function is
  deleted.

**`PLAN-RISKTRANSFORM.md`.** Four edits, all of them consequences of the
2026-09-27 ruling that this document takes over the specification.

- **§8.17 and §8.18** each gain a banner: superseded as a specification by this
  plan, retained as informative — the relationship to the M4 epic and the side
  effects identified while specifying it. Their text is otherwise left alone,
  exactly as §8.16's was when §8.17 superseded it.
- **§7.6.12's preamble** says the two decisions opened in place of 9 and 10 "are
  stated at the end of §8.18, which is the implementation-grade specification
  for that sub-slice". Both now live in §10 of this plan, as decisions 4 and 5.
- **§7.6.12 decision 9** records itself MOOT "2026-09-25 — §8.17", on the ground
  that the two internal cases "live beside the resolver as `private[cache]`
  cases of `NodeValuation`". That ground is gone with §8.17's shape. The row is
  rewritten to point at decision 2 of this plan, which is the same question
  asked of the new shape.
- **§7.6.4** describes slice 1 as gated by "the `ValuationResult` ruling of
  §8.16, whose five gating decisions are open". Four of those five are ruled and
  the fifth was answered by inspection; the sentence is corrected to name this
  plan.

**Not edited, and why.** §7.6.12 decisions 1, 2, 3, 4, 6, 7 and 8 are the
user's own rulings and stand. Decision 2 ("narrow the resolver trait's return
type") stays satisfied by the same argument §8.17 used and this plan inherits:
the return type stays `LossDistribution`, and the narrowing happens because that
type ceases to be a base class with two cases. Decision 3 ("second traversal,
two calls") is what §3.5 implements. Decision 10 stays moot for the reason it
records — there is no new type. Decision 4 is decision 6 of §10 below.

**`PLAN-PROVENANCE-ENDPOINT.md`.** Three edits, none of them to its design.

- Its **naming-distinction section** defines "**FB-c LossDistribution**" as "the
  flat valuation type designed in `docs/scratch/FB-C-DESIGN.md`. It is not
  ruled." That file does not exist in the tree, and FB-C is not the shape that
  won. The section is rewritten to name the FB-F shape and this plan, and to say
  it is ruled.
- Its **"Interaction with the valuation design"** section says that if the flat
  type lands, `leafProvenances` "stops needing a type test, because every result
  carries its own record directly: `results.flatMap { case (id, d) => d.provenance.map(id -> _) }`".
  That is now what ships, so the section stops being conditional: the
  simplification is stated as the form, with the old-`LossDistribution` version
  removed.
- Its **Phase 2 `leafProvenances` listing** loses the `case (id, r: RiskResult)`
  type test for the same reason. The route, the route's reason, the service
  method, the endpoint, the response type, the controller and every test bullet
  are **unchanged** — its thesis is that the subtree's leaves come from
  `TreeIndex` and each is resolved under its own identifier, and nothing here
  touches that.

Its two open decisions are unaffected. Decision 1 there — amending ADR-003 §4 and
ADR-009 §5 — is the same amendment §6 above already makes, so whichever way it is
ruled the edits are the ones listed above; decision 2 (whether the endpoint
accepts a mitigation selection) is a product question this plan does not touch.

**Scratch documents.** Decision 1 ruled: sweep the type out,
leave the reasoning, and point at the prototype and this plan.

- `docs/scratch/MITIGATION-VALUATION-EXPLAINED.md` — **Part 8** loses the
  `ValuationResult` declaration, its three-field gloss and §8.2's "a new subtype
  costs very little" paragraph; §8.3's construction algorithm keeps `recordsFor`,
  `run` and `decorate` but stops naming the type its `decorate` builds; §8.4's
  worked table is rewritten in terms of the shipped shape; **Part 11's** "The
  type is `ValuationResult`" line is replaced. In their place, one short passage
  records that four candidate shapes were written out and compared as running
  code in `docs/scratch/valuation-prototypes/`, that **FB-F** was evaluated as
  the best fit, and that
  [`PLAN-FBF-VALUATION-TRANSPLANT.md`](../dev/plans/PLAN-FBF-VALUATION-TRANSPLANT.md)
  is the plan for implementing it. **Parts 1 to 7, 9, 10 and 12 are not
  touched** — they are the derivation, and the derivation is what produced FB-F.
- `docs/scratch/MITIGATION-PORTFOLIO-CATEGORY-THEORY.md` — no type declaration
  to remove. Its one sentence that dates, *"So the flat `RiskResult` produced by
  `CachedResultResolverLive`'s portfolio arm is the derived and ruled outcome"*,
  names a collapse this change removes; it is corrected to say the flat value is
  the public `LossDistribution` and that the aggregate claim sits on the
  internal portfolio member. Everything else stands.
- `docs/scratch/OBSOLATE-FB-VALUATION-TYPE-DESIGNS.md` is untracked and already
  carries its own "FB-F WON" banner. Not touched.

---

## 7. ADR alignment

Every ADR in `docs/dev/decision-records/` was read for this plan. The ones that
bear on it:

| ADR | Bearing | Status |
|---|---|---|
| ADR-001 (validate once at the boundary; smart constructors) | Every construction path goes through a factory; both primary constructors that can fail are private and return `Validation`; `LeafLosses.create` is total and returns the value; no function in §3 takes a raw primitive carrying a domain value. §5 requires exactly this for aggregates and names `RiskResultGroup` as a reference. | **Compliant and strengthened.** This is also the answer to `ADR-REVIEW-2026-09-15` C2, which found the §8.16 type had no construction gate at all. |
| ADR-002 (telemetry for tracing) | The `ensureCached` / `simulateLeaf` spans, their attributes and the two metric instruments are unchanged. | Compliant, no change |
| ADR-003 (provenance and reproducibility) | §4's "provenance is captured unconditionally, on leaves only" holds — capture moves nowhere. §4's worked snippet does not survive; §3's recorded-seed claims are untouched. The "Mandatory Provenance in Domain Objects" code smell and §4's sentence restating it are already deleted. | **Amended** (§6) |
| ADR-009 (the TrialOutcomes monoid) | §1's algebra stays exactly where it is, on `TrialOutcomes`, with its two instances. §4's licence for `foreachPar` is unchanged. §2 and §3 name a hierarchy that ceases to exist and §5 publishes an uncompilable snippet. | **Amended** (§6); §1 and §4 compliant |
| ADR-010 (errors are values; failures separated by origin) | Three failure sites: mismatched trial counts stays a `require` and propagates as a defect; combine overflow and layer overflow each become a `ValidationError` with `CONSTRAINT_VIOLATION`. Each has its reason stated at the site. | Compliant |
| ADR-014 (content-addressed caching) | Only leaf content is cached, keyed by a hash of identity-free content; portfolios re-aggregate on read; nothing here reaches a cache key, a cached value, or a hash input. §5's shared tick domain is untouched. Its "❌ Caching a portfolio result" smell shows `RiskResultGroup.create(portfolio.id, childResults*)` as the good case — a name change only. | Compliant; one code-smell snippet renamed in the §6 sweep |
| ADR-015 (resolver as the single simulation entry point) | The trait's two methods keep their signatures and their default arguments. The record's own §"API Design" writes a two-generations-old signature already (`ADR-REVIEW` D5); this plan does not make that worse and does not fix it either — it is on the housekeeping list. | Compliant, no change |
| ADR-017 (tree API design) | No DTO, no endpoint, no request or response shape changes. | Not engaged |
| ADR-018 (nominal ID wrappers) | `NodeId` and `MitigationId` are carried as the wrappers throughout; no new ID type is introduced. | Compliant |
| ADR-020 (supply chain) | No dependency is added, updated, or removed. The PATCH bump in §8 follows the scheme. | Compliant |
| ADR-029 / ADR-035 / ADR-036 (injection, error leakage, confidential identifiers) | No user string reaches a parser; the two new `ValidationError` messages are `ValidationMessages.aggregatedLossOverflow` and carry a `NodeId` in `field`, which ADR-036 §4 names client-facing; no `WorkspaceId` or internal path appears. | Compliant |
| ADR-032 (two equality relations) | Neither relation changes. `Equal[LossDistribution]` (decision 5) is structural equality over an in-memory computed value, not a content relation; the record does not govern it, and nothing in production summons it. | Compliant |
| ADR-033 (narrowest sound catch) | Both catches name `ArithmeticException`, the exception `Math.addExact` and the new `scaleLosses` guard are documented to raise. §5's `require` use — private constructor plus smart constructor, guarding a state unreachable through validated entry points — is the sanctioned case. The Implementation table already lists `LossDistribution.scala`; its row's parenthetical `(RiskResultGroup.create)` is renamed in the §6 sweep. | Compliant |
| ADR-034 (mitigation valuation model) | Decisions 1, 2, 3 and 5 hold unchanged and are what this shape implements. Decision 4 writes out a type this plan does not build. | **Amended** (§6) |
| Every other ADR (004a, 007, 011, 012, 016, 019, 021–028, 030, 031, INFRA-006) | Read; none is engaged. No persistence, branching, mesh, config, frontend, capability-URL, credential, container, nginx, query-pane, authorization or infrastructure surface is touched. ADR-011's import conventions are followed. | Not engaged |

---

## 8. Version and landing

Two PATCH bumps, one per landing, because the plan lands in two commits. Shipped
code changes in both and no external API changes in either. Step 1 (§1) took
`0.10.38` → `0.10.39`. The type change (§3 onward) takes whatever `build.sbt`
holds when it starts to the next PATCH: unrelated work landed between the two, so
read `ThisBuild / version` rather than assuming `0.10.40`. Each bump is mirrored
as `APP_VERSION` into **both** `.env` and `.env.irmin`.

---

## 9. File inventory

The inventory is the separate document named in this plan's header,
`docs/dev/plans/PLAN-FBF-VALUATION-TRANSPLANT-INVENTORY.md`. It does not exist
yet. `.claude/bin/approve-inventory` creates it from `.claude/protocol/pending`,
which the user writes. This document carries no path list, so the two cannot
disagree.

The paths themselves are handed over at approval time: the agent prints the
pending block, the user reads it — that reading is the authorization — pastes it
and runs the script.

Two properties of this plan's list, so the block can be checked when it arrives.
The approval hook authorizes `modules/<X>/src/test/**` whenever the inventory
lists any `modules/<X>/src/main/**` file, so none of the `server` or `common`
unit-test paths in §5 needs a line of its own. `modules/server-it/**` is not
covered by that rule, and this plan touches no integration test, so no
integration-test path appears. One path carries the `new:` marker,
`NodeLosses.scala`, which is the agent stating the file does not exist yet.

**One approval covers both landings.** Step 1 (§1) and the type change (§3 onward)
are two commits, not two approvals: the approval script accepts only a
`docs/dev/plans/PLAN-*.md` document as the plan a token names, so a separate
fix-note could not be approved on its own. Step 1 touches
`LossDistribution.scala`, its spec and `build.sbt`, all three of which the list
already covers.

---

## 10. Decisions

Eight, all ruled. **No open decisions.**

| # | Question | State |
|---|---|---|
| — | Where this work lives, and what happens to §8.17/§8.18 | **Ruled 2026-09-27:** this document is the specification; those sections are informative (§0) |
| 1 | Whether the two scratch design documents are swept | **Ruled 2026-09-27: option B**, narrowed — sweep the type, keep the reasoning, point at the prototype and this plan (§6) |
| 2 | Where the internal family lives, and how visible it is | **Ruled 2026-09-27: option B** — `private[cache]`, plus the scaladoc asked for (§3.1) |
| 3 | How the provenance endpoint reads a leaf's records | **Settled 2026-09-27** — the public value carries `provenance: Option[NodeProvenance]` (§3.2); both plans aligned |
| 4 | What happens to `LossDistribution.merge`, and what an empty portfolio yields | **Ruled 2026-09-27: delete `merge`, fold inline, refuse the empty case.** See below |
| 5 | What `Equal[LossDistribution]` compares | **Ruled 2026-09-27: `Equal.default`** — structural, no custom law; see below |
| 6 | Whether `flatten`'s deletion lands separately and first | **Ruled 2026-09-27: yes** — the lower-risk path, and already ruled so on 2026-09-15. It is step 1 of this plan and its own commit, taken green before §3 starts (§1) |
| 7 | What the internal family is for | **Closed 2026-09-27: not a decision.** The prototype settles the shape; see below |
| 8 | What `LeafLosses.create` takes, and whether provenance is a list | **Ruled 2026-09-27: three required parameters, one record.** The ADR-003 code smell that was the only objection is deleted; see below |

### Decision 2 — what confines the family, checked against the records

Ruled B, and §3.1 is written for it. Two of the three confinement mechanisms
§3.1 relies on are existing, recorded patterns rather than new ones; the third
is settled by decision 8.

**Mechanism 1 — private primary constructors — is ADR-001 §5 verbatim**, and
that record names the type this shape replaces as one of its references:

> A domain aggregate is declared `final case class X private (...)`, with all
> construction routed through a smart constructor (`create` / `fromNodes`).
> Scala 3 makes the compiler-generated `.copy` private when the primary
> constructor is private (E173), so once the constructor is private there is no
> `apply` or `.copy` path that skips validation — the smart constructor is the
> sole gate for every construction site (builders, merges, store-loads, tests).
> This generalizes the `RiskResultGroup` private constructor (ADR-034) to every
> aggregate.
> Reference aggregates: `RiskLeaf`, `RiskPortfolio`, `Mitigation`,
> `RiskResultGroup`, `RiskTree`, `TreeIndex`.

One detail of that record does **not** transfer and does not need to.
ADR-001 §5 says the smart constructor returns `Validation[ValidationError, X]`.
`LeafLosses.create` returns the value, because it cannot fail. That matches
production exactly — `RiskResult.fromTrialOutcomes`, `RiskResult.apply` and
`RiskResult.empty` all return `RiskResult`, not a `Validation` — and `RiskResult`
is deliberately absent from ADR-001 §5's reference list, because it carries no
invariant to check. `PortfolioLosses.create` does carry one and does return
`Validation`.

**Mechanism 2 — derivation rather than acceptance — is ADR-034 Decision 3 and
ADR-009 §3.** ADR-034: *"`RiskResultGroup`'s private constructor enforces one
claim: its aggregate is the combine of its children."* ADR-009 §3: *"Portfolio
construction is a named constructor, not a monoid reduction: the parent ID comes
from the tree; the outcomes come from the algebra."* `PortfolioLosses.create` is
that constructor under a new name, with the same guarantee and the same reason.

**The one cost of B, stated once and not argued again:** a type `private[cache]`
cannot be named from `simulation`, so `LossDistribution.decorate` takes figures
rather than a family member, and the chain "these children → this aggregate →
this public value" stops being enforced by the type at the decorate step. It
stays enforced where it matters — `PortfolioLosses.create` derives the aggregate
and offers no parameter for one — and is pinned by a test in `NodeLossesSpec`.

### Decision 4 — `LossDistribution.merge`, and what an empty portfolio yields

**Ruled 2026-09-27 (user).** `merge` is deleted. `PortfolioLosses.create` folds
the children with `reduceOption(TrialOutcomes.combine)` inline, with no new named
helper. When there are no children the fold has no result and construction is
**refused**, with `EMPTY_COLLECTION`. `PortfolioLosses.create` therefore reads
nothing from `SimulationConfig` and drops its `(using cfg: SimulationConfig)`
parameter, because every figure in the result now comes from a child.

**What was settled by inspection, and stands.** `merge` implements nothing
`TrialOutcomes.combine` does not. As it stands at
`.../simulation/LossDistribution.scala:333` it *is*
`reduceOption(TrialOutcomes.combine)` with a `_.trialOutcomes` projection in and
a `_.outcomes` projection out. No arithmetic, no ordering, no alignment rule of
its own. One archived claim does not survive re-reading and is not carried
forward: `milestone-2b-cache-and-decisions.md` says the variadic form and
`reduce(combine)` differ by "a ~2× constant and allocation churn", which assumed
an n-ary single pass; there is no such pass and no such constant.

**What reopens it.** `merge`'s return type is not an accident of convenience. Its
own scaladoc gives the reason, and it is the same reason `PLAN-MONOID` §A.1 gives
for the name:

> This is a reduction, not a fold: no identity element takes part, and an empty
> argument list yields `Map.empty` rather than the zero-loss value at some
> particular trial count.

and

> **`LossDistribution.merge`** is the implementation of `TrialOutcomes.combine`.
> […] The separate name (`merge` not `combine`) accidentally signals the right
> thing: it operates on the mathematical content and returns
> `Map[TrialId, Loss]` rather than a full `LossDistribution`, because
> constructing the named result is the caller's responsibility (not the
> algebra's).

`TrialOutcomes` is a commutative monoid **on each fixed-`nTrials` slice**, not on
the whole type — its own scaladoc says so, and ADR-009 §1 marks the `Identity`
instance "config-scoped, opt-in". So returning `Map[TrialId, Loss]` is how the
n-ary reduction avoids naming a slice for the empty case, and therefore avoids
claiming an identity the type does not have. That property transfers to the new
design and my earlier framing missed it.

**Why refusing is the answer the domain already gives.** A portfolio with no
children is forbidden in four independent places, so the only value the fold could
have produced describes a node nothing else in the system will admit:

| Where | What it does |
|---|---|
| `RiskNode.scala:507` | `require(childIds != null && childIds.nonEmpty, ...)` in the class body, so it runs on every instantiation including the two `new RiskPortfolio(...)` calls in the companion |
| `RiskNode.scala:552-561` | `RiskPortfolio.create` fails with `REQUIRED_FIELD` and "childIds array must not be empty", accumulated beside the `MaxChildren = 1000` bound |
| `RiskTreeRequests.scala:340` | `requireNonEmptyPortfolios` fails with `EMPTY_COLLECTION` on any portfolio that is nobody's parent; it runs in both `validateTopology` (line 236) and `validateTopologyUpdate` (line 274), as the last step of the same topology check that enforces a single root |
| `RiskNode.scala:636` | the JSON decoder routes through `createFromStrings` → `create`, so a stored childless portfolio fails to decode rather than load |

The resolver also fails with `EMPTY_COLLECTION` before it ever combines, so the
empty fold is unreachable from production and reachable only from a test.

**What it costs, and it is the whole cost.** One assertion changes:
`PreludeOrdUsageSpec:173` builds `RiskResultGroup.create(nodeId("empty-risk"))`
today, gets a group back, and asserts `maxLoss == 0L` and `minLoss == 0L` — a
property of a value the four checks above forbid. It becomes an assertion that
construction is refused (Decision Triggers #5 and #8, authorized by this ruling).
`ValidationMessages.portfolioHasNoChildren` is added, and the resolver's existing
inline wording moves into it.

**One signature consequence.** `PortfolioLosses.create` and
`NodeLosses.portfolio` lose their `(using cfg: SimulationConfig)` parameter, as
§3.1 now shows. Nothing about how the resolver reads configuration changes: it
keeps its `config: SimulationConfig` field and keeps reading `defaultNTrials`,
`defaultTrialParallelism`, `defaultSeed3` and `defaultSeed4` from it, because leaf
simulation needs all four.

**The option not taken.** Answer the empty case with `TrialOutcomes.empty`, which
is `TrialOutcomes(cfg.defaultNTrials, Map.empty)`. That reproduces today's
behaviour exactly and changes no test, at the price of naming a trial-count slice
as though the type had one identity, and of leaving the constructor dependent on
configuration for a case no production caller can reach. A third sketch — a named
`TrialOutcomes.combineAll` helper returning `Option` — was withdrawn before the
ruling: naming a fold is not a decision, and the empty case is the only question
the fold raises.

### Decision 5 — `Equal[LossDistribution]`: ruled, `Equal.default`

**Ruled 2026-09-27 (user): `given Equal[LossDistribution] = Equal.default`.**
Structural equality over all five fields — the same relation `==` already gives a
case class, and the same form as every other `Equal` instance in the codebase.
No custom comparison law is written.

**Why there was nothing to choose.** `Equal[A]` is a zio-prelude type class: a
named equality relation other code can summon as `Equal[A].equal(a, b)`.
`Equal.default` is the structural one. The codebase declares fourteen instances;
thirteen are `Equal.default`, and `Equal[RiskResult]` at
`LossDistribution.scala:228` is the only custom one in the repository. No
production code summons any of them — every one of the thirty-four summon sites is
in a test — and no ADR requires them. So "what should the relation compare" was
never a question this code puts to anyone; the instance follows the codebase's
uniform form.

**The one behaviour that does not carry over, stated plainly.**
`Equal[RiskResult]` compares `outcomes`, `nTrials` and `nodeId` and deliberately
excludes provenance, so two results differing only in a run timestamp are equal
under it. `Equal.default` on the new type includes `provenance` and `source`, so
they are not. Nothing in production depends on either answer, because nothing in
production compares two readings.

**Consequence for `LossDistributionSpec`'s equality suite**, which is the only
caller. Three of its four assertions hold unchanged: identical readings are equal
(line 220); readings with different outcomes are not (226); readings with
different trial counts are not (232). The fourth inverts. Line 254, *"equal
outcomes with differing provenances are equal (provenance is audit metadata, not
identity)"*, asserts exactly the exclusion `Equal.default` does not make; under
this ruling the two values are **not** equal, and the test is rewritten to assert
that, with its name corrected. It is recorded here rather than swept because
reframing a test assertion is Decision Trigger #8, and this ruling is the
authorization for it.

`given Debug[LossDistribution]` stays — zio-test uses it to render values in
failure output.

### Decision 7 — closed: the prototype settles the shape

I raised this as a decision and it was not one. The reasoning that produced it —
that `PortfolioLosses.children` has no reader today and `LeafLosses` derives
nothing — is not a criterion the FB-F ruling used, and "no current consumer" is
not grounds to depart from a shape that was written out, run and compared against
three alternatives precisely so it would not be re-derived.

**The rule this plan follows: elevate the prototype's shape faithfully, and take
every algorithm from production.** §3.1 keeps `NodeLosses`, `LeafLosses` and
`PortfolioLosses` exactly as the prototype draws them, including
`PortfolioLosses.children`, which the prototype has. Its scaladoc records that
nothing reads it today and that it is the type-level statement that an aggregate
is made of the things it was derived from.

### Decision 8 — what `LeafLosses.create` takes, and whether provenance is a list

**Ruled 2026-09-27 (user).** `create(nodeId, trials, provenance)` — three required
parameters, and the record is one `NodeProvenance` rather than a list. On the
public `LossDistribution` the same field is `Option[NodeProvenance]`, because a
portfolio has no record and one concrete type carries both cases.

**Why a list is wrong.** Nothing produces two records for one node. `Simulator`
builds exactly one per leaf simulation. `LeafSimResult`, the cache value both
halves come from, already holds `provenance: NodeProvenance` — singular and
required. The prototype's `create` takes one record and wraps it, so the list has
no source at all. The only code that ever built a list longer than one is
`CachedResultResolverLive.descendantProvenances`, which existed for the collapsed
transformed-portfolio branch and which §3.5 deletes. ADR-003 §4 says the same
about the present code: a simulated leaf carries exactly one record.

**The ADR-003 objection is gone.** ADR-003 carried a code smell, "Mandatory
Provenance in Domain Objects", whose BAD example was a required single record and
whose GOOD example was `provenances: List[NodeProvenance] = Nil`. It is deleted
from ADR-003, together with the same claim restated as prose in §4. Its stated
reason does not hold in this codebase: `NodeProvenance` is a plain case class with
a public constructor and no smart constructor, and two tests build one directly
with no simulation — `LossDistributionSpec.scala:239` and `ProvenanceSpec.scala:53`.
Its second reason, intermediate aggregation, was `descendantProvenances`.

**The alternative considered and not taken.** `create(nodeId, content: LeafSimResult)`
— one parameter carrying both halves already paired, so they cannot be mismatched.
Not taken: the prototype's parameter list is the shape this plan elevates, and the
guarantee is narrow, since the only caller is one line of `CachedResultResolverLive`.

---
## 11. Verification plan

The complete run, all four tiers, green before this is reported done. The
leaked-state cleanup is a mandatory pre-step for the integration tier, not crash
recovery.

```bash
sbt compile                                  # zero warnings
sbt 'commonJVM/test; server/test'
sbt app/test
docker ps -a --filter name=register_it_ -q | xargs -r docker rm -f; \
docker network ls --filter name=register_it_ -q | xargs -r docker network rm; \
docker volume ls --filter name=register_it_ -q | xargs -r docker volume rm
sbt "serverIt/test"
```

This change alters server behaviour in one observable way — a transformed
portfolio keeps its children — so the BATS fast gate
(`run_bats tests/bats/suite-c-in-memory.bats`, invoked as the `register-dev`
skill defines it) runs as well.

Two properties are checked by hand once, because no test states them directly:

- `sbt compile` reports zero warnings;
- an unmitigated read allocates one outcome map per node rather than two, which
  the empty-layer short-circuit in `decorate` passing one reference twice is
  what guarantees. The reference-equality test in §5 pins the mechanism; this
  check confirms the consequence.

One property is checked by reading, because it is an absence (§2, S38): no
`JsonCodec`, `JsonEncoder`, `JsonDecoder` or Tapir `Schema` exists for
`LossDistribution`, `NodeLosses`, `LeafLosses` or `PortfolioLosses`, and no file
under `modules/common/src/main/scala/com/risquanter/register/http/responses/`
references any of them.

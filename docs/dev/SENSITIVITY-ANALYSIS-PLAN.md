# Plan: Tree Sensitivity Analysis (one-at-a-time / tornado)

**Status:** Draft — design exploration, not yet approved. **Requires redesign
before approval** (see notice below).
**Date:** 2026-06-18
**Tags:** simulation, sensitivity-analysis, risk-tree, aggregation, cache

---

> **⚠ Requires redesign after the TrialOutcomes refactor (2026-07-17).**
> This plan builds on primitives that no longer exist: `RiskResult.combine`,
> `given Associative/Commutative[RiskResult]`, and `RiskResult.withNodeId` were
> deleted when the monoid moved to `TrialOutcomes` (see ADR-009 and
> `PLAN-MONOID-RISKRESULT-AND-MITIGATION.md` Part A). The current replacements:
> per-trial summation is `TrialOutcomes.combine`; portfolio construction is the
> named constructor `RiskResultGroup(parentId, childResults*)`, which assigns the
> parent ID directly (no `withNodeId` relabeling step). The plan's underlying
> guarantees (trial-aligned additive results, equal-nTrials enforcement,
> path-only recompute) still hold, but every code sketch that calls the deleted
> primitives — the off-baseline recompute rule (§3), the annotated driver (§3a),
> the worked trace (§3b), correctness invariant 1, and the effort table — must be
> re-derived against `TrialOutcomes`/`RiskResultGroup` before this plan can be
> approved. No redesign has been attempted; the text below is unmodified.

---

## Goal

Given a user-built risk tree, systematically perturb the parameters of one leaf at a
time and measure the effect on a tree-level outcome metric. Rank leaves by the
magnitude of effect to produce a tornado diagram: *which input, changed by how much,
moves the result the most*.

This is classic OAT (one-at-a-time) sensitivity analysis. It needs **no** category-theory
machinery — the only genuinely useful structures (a bottom-up monoidal fold for
aggregation, and a trial-aligned additive result representation) already exist in the
codebase.

---

## Grounding: the actual structures

Verified against the live code, not assumed.

### Risk tree is flat, not recursive

[`RiskTree`](../../modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala):

```scala
final case class RiskTree(
  id: TreeId, name: SafeName,
  nodes: Seq[RiskNode],        // FLAT collection
  rootId: NodeId,
  index: TreeIndex             // parents/children/nodes maps over NodeId
)
```

`RiskPortfolio` holds `childIds: Array[NodeId]` — **ID references, not nested nodes**.
Both at the API level and in memory the tree is a flat node set plus an index. There is
no recursive structure to descend; finding/replacing a leaf is a keyed map update.

### Results preserve trial indexes; aggregation is additive per trial

[`RiskResult`](../../modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala):

```scala
case class RiskResult(nodeId: NodeId, outcomes: Map[TrialId, Loss], nTrials: Int, …)
```

Aggregation sums by trial index (`LossDistribution.merge`):

```scala
allTrialIds.map { trial =>
  trial -> distributions.foldLeft(0L)((acc, d) => acc + d.outcomeOf(trial))
}.toMap
```

`RiskResult.combine` requires equal `nTrials` and merges per trial. The derived
`outcomeCount: TreeMap[Loss, Int]` is a histogram view that has **lost** trial alignment
and must never be used for cross-node aggregation.

### Recompute already walks only the affected path

[`TreeCacheManager.invalidate`](../../modules/server/src/main/scala/com/risquanter/register/services/cache/TreeCacheManager.scala)
removes only `tree.index.ancestorPath(nodeId)`; siblings stay cached. The resolver's
portfolio branch then recomputes each ancestor as `childResults.reduce(RiskResult.combine)`
where off-path children are cache hits and only the on-path child is recomputed. This is
re-aggregation along the path — no subtraction.

### Seeds derive from stored seed identities, not params

[`Simulator.createSamplerFromLeaf`](../../modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala):
streams derive from the workspace's `seedEntityId` and the leaf's stored `seedVarId`
(`SeedDerivation.streams`; PLAN-SEED-IDENTITY). Perturbing a leaf's *params* keeps its
`seedVarId`, so the occurrence stream and uniform draws are identical to baseline. Any
change in the result reflects only the parameter change — no manual seed threading required.

---

## Core design

### 1. The perturbation seam

A "seam" (Feathers) is a single named point through which all parameter changes flow, so
the sweep loop stays ignorant of *how* a leaf is modified.

```scala
enum LeafPerturbation:
  case ScaleUpperLoss(factor: Double)
  case ScaleProbability(factor: Double)
  case SetProbability(p: OccurrenceProbability)
  // extend as needed

/** Pure, structural identity preserved (only params change). */
def perturb(tree: RiskTree, target: NodeId, p: LeafPerturbation): RiskTree
```

Because perturbation never changes parent/child wiring, the implementation is a keyed
replacement, not a traversal:

```scala
def modifyLeaf(tree: RiskTree, id: NodeId)(f: RiskLeaf => RiskLeaf): RiskTree =
  tree.index.nodes(id) match
    case leaf: RiskLeaf =>
      val updated = f(leaf)
      tree.copy(
        nodes = tree.nodes.map(n => if n.id == id then updated else n),
        index = tree.index.copy(nodes = tree.index.nodes.updated(id, updated))
      )
    case _ => tree
```

`parents` / `children` maps are untouched.

### 2. The metric

```scala
// Practical domain metric: probability of exceeding a fixed loss threshold X
def metric(root: RiskResult, X: Loss): Double = root.probOfExceedance(X)
```

Computed from the root `RiskResult` of a simulation. Any node-level metric is possible;
the root exceedance probability is the default.

### 3. Recompute off baseline values (recommended path)

Simulate the baseline tree **once** to obtain every node's result as an immutable value:

```scala
val baseline: Map[NodeId, RiskResult]   // e.g. from ensureCachedAll over all nodes
```

For each perturbation of leaf `L`, recompute only `L` and the combines along its ancestor
path, reusing baseline results for off-path siblings — without touching any shared cache:

```
metricBaseline = baseline(rootId).probOfExceedance(X)   // computed once

L'      = simulate(perturbed L)                         // ONE leaf
walk ancestorPath(L) bottom-up:
  A'    = combine(onPathChild', baselineSiblings(A))    // reuse RiskResult.combine
metric' = root'.probOfExceedance(X)
delta   = metric' - metricBaseline
```

The per-node `combine` and the `ancestorPath` walk already exist; the only new code is a
small **pure driver** that folds `combine` along `ancestorPath` against baseline sibling
values. No cache, no `Ref`, no invalidation, no restore.

> **`baseline` is not a new data structure.** `Map[NodeId, RiskResult]` is already the
> return type of
> [`RiskResultResolver.ensureCachedAll`](../../modules/server/src/main/scala/com/risquanter/register/services/cache/RiskResultResolver.scala)
> and is already consumed elsewhere (e.g.
> [`RiskTreeKnowledgeBase`](../../modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala),
> `QueryServiceLive`). The baseline map is obtained by calling `ensureCachedAll(tree,
> allNodeIds)` once.

#### 3a. The driver, annotated

```scala
def recomputeOffBaseline(
  baseline:   Map[NodeId, RiskResult], // every node's result from the ONE baseline run
  tree:       RiskTree,
  leafResult: RiskResult,              // the freshly re-simulated leaf, L'
  leafId:     NodeId                    // which leaf was perturbed
): RiskResult =

  // ancestorPath(leafId) is TOP-DOWN — the ORDER of the returned list is
  // [root, …, parent, leaf] (root first, perturbed leaf last).
  // We must rebuild BOTTOM-UP (leaf first, root last), so reverse the list.
  val bottomUp: List[NodeId] = tree.index.ancestorPath(leafId).reverse
  //   bottomUp = [leaf, …, parent, root]

  // sliding(2) yields consecutive (child, parent) adjacent pairs, e.g.
  //   [leaf, ops, root]  ->  [ [leaf, ops], [ops, root] ]
  // Each step climbs one level: we know the child we just rebuilt and the
  // parent we now need to rebuild.
  //
  // The fold's accumulator is a pair (idOfNodeJustRebuilt, itsResult).
  // It starts at the leaf itself: (leafId, leafResult) i.e. (leaf, L').
  val (_, rootResult) =
    bottomUp.sliding(2).foldLeft(leafId -> leafResult) {
      case ((childId, childRes), List(_, parentId)) =>
        // childId  : id of the node rebuilt in the previous step
        // childRes : that node's RiskResult ("child result") being carried up
        // parentId : the node we must rebuild now
        // (the window's first element = childId again; ignored as `_`)

        // The parent's OTHER children (everything except the on-path child)
        // are unchanged — pull their results straight from baseline.
        val siblings: List[RiskResult] =
          tree.index.children(parentId)   // all children of this parent
            .filterNot(_ == childId)      // drop the on-path child (we have childRes)
            .map(baseline)                // NodeId -> baseline RiskResult (immutable reuse)

        // Parent total = the rebuilt on-path child + the baseline siblings,
        // summed per trial index via RiskResult.combine.
        // combine keeps the FIRST arg's nodeId, so relabel to the parent.
        val parentRes: RiskResult =
          (childRes +: siblings).reduce(RiskResult.combine).withNodeId(parentId)

        // New accumulator: we have now rebuilt the parent; climb again.
        parentId -> parentRes
    }

  rootResult   // the final accumulator's result is root'
```

Terminology: **`childRes`** = "child result" — the `RiskResult` of the node rebuilt in the
previous step (the perturbed leaf on the first iteration), threaded up the path as the
fold accumulator. **TOP-DOWN / BOTTOM-UP** refer to the *ordering of the list*:
`ancestorPath` returns root-first/leaf-last; we reverse it to leaf-first/root-last so the
fold rebuilds from the bottom.

#### 3b. Worked trace

Tree:

```
root
├── ops
│   ├── cyber
│   └── hardware
└── market
```

Baseline values (sparse `Map[TrialId, Loss]`, nTrials = 10, showing non-zero trials only):

```
baseline.cyber    = { t3→100, t7→250 }
baseline.hardware = { t2→ 50, t7→ 80 }
baseline.ops      = combine(cyber, hardware) = { t2→50, t3→100, t7→330 }
baseline.market   = { t1→500, t7→200 }
baseline.root     = combine(ops, market)     = { t1→500, t2→50, t3→100, t7→530 }
```

`metricBaseline = root.probOfExceedance(300)` = trials with total ≥ 300 = {t1=500, t7=530}
→ **0.20**.

Perturb cyber → re-simulate just cyber (same seeds, so same trials fire; losses change):
`leafResult = cyber' = { t3→100, t7→300 }`, `leafId = cyber`.

Setup:

```
ancestorPath(cyber)  = [root, ops, cyber]            // top-down
bottomUp = .reverse  = [cyber, ops, root]
sliding(2)           = [ [cyber, ops], [ops, root] ]
initial accumulator  = (cyber, cyber')
```

Iteration 1 — window `[cyber, ops]`, acc `(cyber, cyber')`:

```
childId  = cyber ; childRes = cyber' = { t3→100, t7→300 } ; parentId = ops
children(ops).filterNot(_ == cyber)  = [hardware]
  .map(baseline)                     = [ { t2→50, t7→80 } ]
(childRes +: siblings).reduce(combine):
  combine(cyber', hardware) per trial → t2→50, t3→100, t7→300+80=380
  = { t2→50, t3→100, t7→380 }
.withNodeId(ops)                     = ops'
new acc = (ops, ops')
```

Iteration 2 — window `[ops, root]`, acc `(ops, ops')`:

```
childId  = ops ; childRes = ops' = { t2→50, t3→100, t7→380 } ; parentId = root
children(root).filterNot(_ == ops)   = [market]
  .map(baseline)                     = [ { t1→500, t7→200 } ]
(childRes +: siblings).reduce(combine):
  combine(ops', market) per trial → t1→500, t2→50, t3→100, t7→380+200=580
  = { t1→500, t2→50, t3→100, t7→580 }
.withNodeId(root)                    = root'
new acc = (root, root')
```

Result = `._2` of final acc = `root' = { t1→500, t2→50, t3→100, t7→580 }`, then
`metric' = root'.probOfExceedance(300)`.

What never happened: `hardware` and `market` were read once as baseline values and summed;
neither was re-simulated or copied. If `market` were a 10,000-leaf subtree, the trace would
be **identical** — its single baseline `RiskResult` is reused and only two `combine`s run.

Edge cases the driver handles:

- **Single-child portfolio:** `siblings` empty → `childRes +: Nil` → `reduce` returns
  `childRes`, relabeled to the parent. Correct.
- **Perturbed node is the root** (one-node tree): `ancestorPath = [root]`, `sliding(2)`
  empty → fold returns the initial accumulator → result is `leafResult`. Correct.
- **`nTrials` mismatch:** `combine` requires equal `nTrials`; all values share the baseline
  run's trial count, so this holds.

### 4. What `recomputeOffBaseline` returns, and the layering

In plain English, the driver answers one question:

> *"If I change only this one leaf's parameters, what does the **root's** loss
> distribution become?"*

It returns that new root `RiskResult` — the root's full per-trial outcomes under the
single-leaf change — by reusing every off-path node's baseline result and re-running only
the `combine`s along the changed leaf's path. It is exactly the value a full
`simulate(perturbedTree)` would produce at the root, reached cheaply.

So it is **not** the perturbation and **not** the metric. It is the *simulation* step in
the middle, optimized:

```
metric( simulate( perturb(tree, leaf, effect) ) )
        └──────────────┬──────────────────────┘
              recomputeOffBaseline replaces THIS part
              (returns the root RiskResult)
```

**Why a `RiskResult`, not `tree → tree`?** Two layers are easily conflated:

1. `perturb: RiskTree => RiskTree` — the pure tree edit (change one leaf's params). This is
   the clean `tree → tree` API.
2. `metric` — *cannot* be a function of a tree. A tree is only parameters; it has no losses
   until simulated. So "`metric(tree)`" always means `metric(simulate(tree).root)`. The loss
   distribution exists only as a `RiskResult`.

`recomputeOffBaseline` is the `simulate` in that chain. Its output is a `RiskResult` because
that is what simulation produces and what `metric` consumes. It returns the full result
(not a `Double`) so the metric stays a separate, swappable step — `probOfExceedance(X)` at
several `X`, `p95`, or the whole LEC, all from the same root result.

**The clean high-level helper** composes the three layers into the scalar function you
actually call per `(leaf, factor)`. It uses two small building blocks plus existing code:

```scala
// perturbLeaf : (RiskLeaf, LeafPerturbation) => RiskLeaf  -- NEW, pure param edit
//               (the tree-level `perturb` is modifyLeaf(tree, id)(perturbLeaf(_, effect)))
// simulateLeaf: RiskLeaf => Task[RiskResult]              -- EXISTING, thin wrapper over
//               Simulator.createSamplerFromLeaf + Simulator.performTrials

def sensitivityMetric(
  baseline: Map[NodeId, RiskResult],
  tree: RiskTree,
  leafId: NodeId,
  effect: LeafPerturbation,
  X: Loss
): Task[Double] =
  tree.index.nodes(leafId) match
    case leaf: RiskLeaf =>
      simulateLeaf(perturbLeaf(leaf, effect)).map { leaf2 =>     // re-sim the ONE leaf
        val root2 = recomputeOffBaseline(baseline, tree, leaf2, leafId)
        metric(root2, X)                                        // root2.probOfExceedance(X)
      }
    case _ => ZIO.fail(SensitivityError.NotALeaf(leafId))
```

Equivalently, in tree-centric terms, the slow and fast paths return the **same** root
result:

```scala
// Conceptual, slow — re-simulates the WHOLE tree
metric(simulate(perturb(tree, leafId, effect)).root, X)

// Optimized — identical result, only the path recomputed
metric(recomputeOffBaseline(baseline, tree, leaf2, leafId), X)
```

**Efficiency note on the signature:** `recomputeOffBaseline` never builds a full *perturbed*
`RiskTree`. It needs only the baseline results (off-path, reused), the one re-simulated leaf
(`leaf2`), and the original tree's `index` (structure is unchanged). Materializing a whole
perturbed tree just to simulate it would invite re-simulating all of it — the opposite of
the goal. The `tree → tree` `perturb` remains valid as a conceptual/clean-API layer, but the
fast evaluator deliberately bypasses building that intermediate tree and returns the root
result directly.

### 5. The sweep

```scala
ZIO.foreachPar(leaves x factors) { (leafId, factor) =>
  sensitivityMetric(baseline, tree, leafId, ScaleUpperLoss(factor), X)
    .map(m => (leafId, factor, m))
}
// rank by max_factor |m - metricBaseline| → tornado
```

Embarrassingly parallel because `baseline` is read-only and each perturbation allocates
its own path results. Nothing is shared mutably → no locks.

---

## Rejected: incremental subtraction `A' = A − L + L'`

Tempting for a root-only flat sum, but wrong-headed here:

- Requires bookkeeping the baseline `L` contribution and ancestor totals and trusting they
  stay in sync — state the `combine` recompute does not need.
- Only directly serves the flat root sum; intermediate-portfolio metrics degrade into
  per-ancestor subtraction bookkeeping.
- Marginal savings (touch fewer trial entries) versus summing already-sparse sibling maps;
  not worth the desync risk.

Path re-aggregation with `combine` produces a correct fresh total at every node on the
path and reuses existing arithmetic unchanged.

---

## Rejected/compared: deep-copy the cache and mutate the copy

See the cost analysis below. Because `RiskResult` is an immutable case class wrapping
immutable maps, a perturbation never mutates shared state — so isolating via a deep copy
buys nothing and costs `O(nodes × outcomes)` per perturbation. Deep copy is strictly
dominated by recompute-off-baseline in this immutable-data setting.

---

## Correctness invariants

1. **Equal `nTrials` across all combined results** — enforced by `RiskResult.combine`.
   Baseline and perturbed leaf must share the trial count.
2. **Trial-keyed `outcomes`, never the histogram** — aggregation sums `Map[TrialId, Loss]`;
   `outcomeCount` cannot be combined across nodes.
3. **Seed stability** — leaf id unchanged ⇒ identical occurrence/loss streams ⇒ delta
   reflects only the parameter change. Automatic, since seeds derive from `leaf.id`.
4. **Perturbation preserves structure** — only leaf params change; `parents`/`children`
   untouched, so `ancestorPath` and the baseline sibling set remain valid.
5. **Compare on `outcomes`, not full `RiskResult` equality, in the oracle test.**
   `Equal[RiskResult]` includes `provenances`, and `NodeProvenance` carries a wall-clock
   `timestamp` (and `simulationUtilVersion`). Two independent simulations therefore never
   compare equal under `==`, even with byte-identical losses. The equivalence test must
   compare `outcomes` (plus `nTrials`, `nodeId`), or strip/normalise provenance. Outcomes
   themselves are deterministic given identical leaf ids + `SimulationConfig` (seeds,
   `nTrials`), independent of parallelism.

---

## Cost analysis

Let `N` = nodes, `D` = tree depth, `B` = branching factor, `T` = nTrials,
`K` = factors per leaf, `Lf` = leaf count.

| Aspect | Recompute off baseline | Deep-copy cache + mutate |
|--------|------------------------|--------------------------|
| Extra memory / perturbation | path results only: `O(D × T)` worst case | full duplicate `O(N × T)` if truly deep; shallow ref-copy `O(N)` if not |
| Time / perturbation | 1 leaf sim + `O(D × B)` combines | copy `O(N × T or N)` + 1 leaf sim + `O(D × B)` combines |
| Parallelism | read-only shared baseline, no locks | each fiber needs its own copy → `P × memory` |
| Machinery required | pure driver + baseline map | cache + resolver + invalidate + `Ref` + restore |
| Correctness risk | low (pure) | cache-state lifecycle, restore between runs |

For large `T` (e.g. 10⁵–10⁶ trials), a genuine deep copy of every node's outcome map per
perturbation is often **not viable** at all, while recompute-off-baseline allocates only
the path's results.

---

## Implementation plan

`recomputeOffBaseline` is **not** a speculative optimization — it is asymptotically and
practically the right evaluator (≈ `Lf×` less work across a full sweep; see Cost analysis).
The naive "re-simulate the whole perturbed tree per perturbation" approach is **not
meaningfully simpler to build**: it needs its own plumbing to simulate a hypothetical tree
without polluting the shared per-`tree.id` cache, and it would be retired once the driver
lands. So build the driver from the start.

Full-tree re-simulation is still required — but as a **test oracle**, not a production phase.

1. **Production evaluator (build first).** `LeafPerturbation` + `perturbLeaf` +
   `simulateLeaf` (one leaf) + `recomputeOffBaseline` + `metric` + sweep/ranking. Fetch
   `baseline` once via `ensureCachedAll`; everything else is pure and lock-free.
2. **Validation (test scope only).** A straightforward full re-sim of the perturbed tree,
   used by a property test asserting `recomputeOffBaseline(...) == fullReSim(...).root` over
   random trees/perturbations. Never shipped in production.

Shared vs throwaway, had you done naive-first instead:

- **Shared (built once regardless):** `LeafPerturbation`, `perturbLeaf`, `metric`,
  sweep + ranking, result model.
- **Throwaway (only exists in naive-first):** the production whole-tree-re-sim evaluator and
  its ephemeral-cache plumbing.

Going straight to off-baseline avoids that throwaway; the shared scaffolding is identical, so
there is no rework penalty.

> **Optional 1-day spike (not required).** If you want a tornado on screen before the driver
> is hardened, the full re-sim oracle can double as a throwaway prototype. Skip it unless an
> early demo is needed — it is not on the critical path.

---

## Open questions

- **Severity-only shortcut.** For `ScaleUpperLoss` on an *unbounded* distribution, the
  perturbed leaf result is `c × baselineLeaf` per fired trial — no re-simulation needed.
  Worth wiring as a fast path? (Valid only for unbounded; bounded metalog requires re-sim.)
- **Metric surface.** Root exceedance at a single `X`, or a vector of thresholds / a curve
  distance? Tornado ranking needs a scalar; pick a primary metric.
- **Factor grid.** Fixed multipliers (1.1, 1.2, …) vs. symmetric (±10%, ±20%) vs.
  absolute parameter deltas. Affects interpretation of the tornado.

---

## Effort estimate

Quantitative, in **engineer-days** (1 day = focused dev time). Ranges are low–high.
Assumptions: one developer already fluent in this ZIO/Iron/Tapir codebase; estimates cover
implementation + unit tests but **exclude** code-review turnaround, ADR deliberation for the
Phase 3 endpoint, and UI design iteration. "Reuse" = building block exists and is called
as-is.

### Build — production evaluator (off-baseline from the start)

| Component | Days | New / reuse | Notes |
|-----------|------|-------------|-------|
| `LeafPerturbation` enum | 0.25 | New | Small ADT; decide v1 cases. |
| `perturbLeaf(leaf, effect): RiskLeaf` | 1.0–1.5 | New | Both distribution modes (lognormal: scale `minLoss`/`maxLoss`; expert: scale `quantiles`) + re-validate via Iron smart constructor (`RiskLeaf.create`). Main correctness surface. |
| `simulateLeaf(leaf): Task[RiskResult]` | 0.5 | Reuse + wrapper | Expose `Simulator.createSamplerFromLeaf` (`private[services]`) or add a narrow entry; then `+ performTrials`. |
| `recomputeOffBaseline` driver | 0.5–1.0 | New (pure) | The annotated fold; all primitives (`combine`, `withNodeId`, `ancestorPath`, `children`) exist. |
| `metric(root, X)` | 0.1 | Reuse | `RiskResult.probOfExceedance` exists. |
| Sweep + tornado ranking + result model | 1.0 | New | `ZIO.foreachPar` over `(leaf, factor)`; deltas; rank by `max|Δ|`; response type. |
| Baseline acquisition | 0.25 | Reuse | `ensureCachedAll(tree, allNodeIds)` once. |
| **Build subtotal** | **3.6–4.6** | | |

### Validation — test scope only

| Component | Days | New / reuse | Notes |
|-----------|------|-------------|-------|
| Full re-sim oracle (test helper) | 0.5 | New (test) | Straightforward whole-tree re-sim of `perturb(T, L, effect)` (the *same* `perturbLeaf`), so it isolates the driver from `perturbLeaf`. Not shipped. |
| Equivalence property test | 1.0–1.5 | New | `recomputeOffBaseline(...).outcomes == fullReSim(...).root.outcomes` over random trees/perturbations. Compare on `outcomes`, **not** `Equal[RiskResult]` (provenance carries a timestamp — see invariant 5). Effort is the valid-`RiskTree` generator (must satisfy `TreeIndex` invariants), not the comparison. The key safety net. |
| Unit tests | 1.0–1.5 | New | `perturbLeaf` per mode (asserts produced params directly; incl. invalid-range rejection); sweep ranking; fixture e2e. Kept separate from the driver test so a failure points at one cause. |
| **Validation subtotal** | **2.5–3.5** | | |

### Optional — API / UI (deferred; gated on ADR)

| Component | Days | New / reuse | Notes |
|-----------|------|-------------|-------|
| Tapir endpoint + request/response DTOs + codecs | 1.0–1.5 | New | Requires a **new API-shape decision to be drafted** (per the project's decision trigger for any new Tapir endpoint / OpenAPI change); should conform to or extend [ADR-017](ADR-017-tree-api-design.md). *Not* a dependency on an existing pending ADR. Validate at codec boundary. |
| Controller + service wiring | 0.5 | New | Thin glue over `sensitivityMetric` + sweep. |
| Frontend tornado chart (Laminar) | 2.0–3.0 | New | New view, signal wiring (ADR-019), chart rendering, loading/empty states. Largest single piece. |
| **Optional subtotal** | **3.5–5.0** | | |

### Totals

| Scope | Engineer-days |
|-------|---------------|
| Internal capability (Build + Validation, no ADR) | **6.0–8.0** |
| All (incl. user-facing API + chart) | **9.5–13.0** |

Add ~15–20% for integration, review cycles, and CI flakiness when planning calendar
capacity → round the internal capability to **≈ 7–9 engineer-days** and the full feature to
**≈ 11–15 engineer-days** as a planning figure.

**Where the risk concentrates** (budget care here, not on the driver): `perturbLeaf`
(correctness across two distribution modes + Iron validation) and the **equivalence
property test**. Neither is the largest line item, but both are where defects would hide.

---

## Approval checkpoint

- [ ] Approve metric definition (root `probOfExceedance(X)` as primary)
- [ ] Approve `LeafPerturbation` set (which params are perturbable in v1)
- [ ] Approve building `recomputeOffBaseline` from the start, with full re-sim kept as a
      test oracle (not a production phase)

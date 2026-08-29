# ADR-017 — Notes: When does `TreeOp` / batch operations add value?

Companion analysis to ADR-017. ADR-017 specifies the **implemented** tree API
(whole-tree PUT, identity-preserving update). These notes record the hindsight
assessment of the **unimplemented** per-node operation algebra (`TreeOp`: batch
`UpdateDistribution`, `ReparentNode`, `RenameNode`, `DeleteNode`, undo/redo,
WebSocket/SSE projections) — specifically *under what assumptions it adds value*, and
where it is redundant or equivalent given what now exists.

Two facts established during the identity-preservation work reshape the original
calculus, because most of `TreeOp`'s pitch predates both:

1. **Whole-tree PUT correctly enforces all topology invariants — but it is not the only
   mechanism that can do so.** Every invariant is also enforceable as a per-operation
   precondition against the live tree. The correctness benefit is real but the mechanism
   is not uniquely required (see "The correctness-by-construction claim, re-examined").
2. **Identity preservation makes the persistence layer a real per-node history.** An
   edited node keeps its `NodeId`, hence its content-addressed store path, hence its
   commit lineage. This both enables time travel and makes `TreeOp`'s efficiency
   advantage concrete — targeted writes against stable paths rather than churn.

---

## What still makes sense

### 1. Structural-conflict-resolving concurrent collaboration (the only strong case)

Multiple users editing the **same** tree concurrently. State-based last-write-wins PUT
silently discards a concurrent editor's work; reconciling intent needs **operations**,
not end states.

This is the one justification not subsumed by anything we built. But it is **doubly
conditional** — see "What 3-way merge vs. operation-based collaboration adds" below. It
only earns its keep if (a) multi-writer editing is an actual requirement, **and**
(b) git-native tree merge proves inadequate for structural conflicts.

### 2. Semantic-intent capture for audit (narrow, non-redundant)

An operation records *which kind* of change occurred. A before/after content diff cannot
distinguish **reparent X** from **delete X + recreate X elsewhere** — see "The subtle
history distinction" below. Genuine, but only valuable if there is a compliance /
explainability requirement to answer *"what kind of change,"* not merely *"what bytes
differ."*

---

## What is redundant (and why)

### Client-to-server bandwidth — avoiding full-tree resubmission

**Weak / redundant.** Risk trees are small JSON; uplink bytes are not a real constraint.
The original ADR framed full resubmission as a "code smell" on bandwidth grounds; that
argument was weak even then. Note: the *server-side* efficiency argument (Irmin
round-trips) is not weak — see "Efficiency at realistic tree sizes" below, which
establishes that TreeOp is genuinely more efficient via fewer persistence operations, not
via less client uplink traffic.

### Undo/redo via in-memory operation inversion

**Largely redundant.** Identity preservation + Irmin commits already provide time travel
over *persisted* state. An inverse-operation log is a **second, parallel** mechanism for
a capability the persistence layer now supplies natively. It retains marginal value only
for *pre-commit, in-session* undo of unsaved edits — a UI nicety, not an architectural
need, and achievable without a serialized `TreeOp` algebra.

### Audit trail of *what changed*

**Redundant.** Irmin commits already record author, message, and timestamp per node
path. Operations add value only for *what kind* of change (intent), not *that* a change
occurred — see audit point above.

### WebSocket/SSE "readiness"

**Mostly served already.** Real-time **read** sync is the existing SSE stale-figure
notification path (`SSEHub`; the manual `POST .../invalidate/{nodeId}` endpoint was
removed 2026-07-18 — DD-20, content addressing needs no invalidation). Real-time
**write** sync is not a
distinct benefit — it collapses back into "concurrent collaboration" (case 1). The
WebSocket angle is a transport for the collaboration decision, not an independent reason.

---

## The subtle history distinction: *update* vs. *delete + new*

This is the crux of why node **identity** matters, and where content-history and
intent-history diverge.

Two persistence outcomes can produce the *same* final tree but *different* histories:

| Scenario | Store effect | `git log nodes/{id}` | Meaning preserved? |
|----------|--------------|----------------------|--------------------|
| **Update in place** (id retained) | same path `nodes/{id}` rewritten | one continuous lineage, value X → Y | ✅ "this node's parameter changed" |
| **Delete + new** (id re-minted) | `nodes/{old}` removed, `nodes/{new}` created | two unrelated lineages: one ends, one begins | ❌ looks like an unrelated node appeared |

The persistence layer records **content history per path**. Path identity is therefore
*semantic* identity: keeping the `NodeId` is what makes "the cyber-risk leaf's
distribution evolved over six edits" a *queryable* fact rather than six disconnected
node births and deaths. This is exactly the defect the identity-preservation prerequisite
fixed: the frontend formerly discarded `NodeId` on load and resubmitted every node as
"new," collapsing every *update* into a *delete + new* and severing all per-node lineage.

What the path-history **cannot** express is the *kind* of a structural change. Consider
moving leaf X from portfolio A to portfolio B:

- As **content history**, X's path is rewritten (parent field changed) — recoverable as
  "X's parent changed."
- But **delete X under A + create X under B** would, with a *re-minted* id, be
  indistinguishable from an unrelated deletion and creation.
- Only an explicit **operation** (`ReparentNode(X, B)`) records the *intent* —
  "this was a move" — as a first-class fact.

So there are **three** fidelity tiers, increasing in cost:

1. **State snapshots** — "the tree looked like this." (Always available; whole-tree PUT.)
2. **Per-node content history** — "this node's content evolved X → Y." (Available now,
   *because* identity is preserved.)
3. **Operation/intent history** — "this was a *reparent*, not a delete+recreate."
   (Requires `TreeOp`; the only tier `TreeOp` uniquely unlocks.)

`TreeOp`'s audit value is exactly, and only, tier 3.

---

## What 3-way merge vs. operation-based collaboration adds

The real architectural fork for concurrent editing is **not** "PUT vs. batch." It is
how concurrent edits are reconciled. Because the substrate is Irmin (a git), this is a
live, non-obvious choice rather than a foregone one.

### Option A — State-based, git-native three-way merge (needs **no** `TreeOp`)

- Branch per editing session; reconcile with Irmin's content-level three-way merge over
  the per-node KV store.
- **Adds:** concurrent editing with **zero new API surface** — no operation algebra, no
  interpreter, no inverse functions. Reuses the persistence substrate directly.
- **Limit:** content merge reconciles **independent** changes well (two users edit two
  different leaves → clean auto-merge). It cannot reconcile **conflicting structural
  intent**: two users reparent the *same* node to *different* parents is a true semantic
  conflict a KV three-way merge will mishandle or flag without a resolution strategy.
- **Best when:** edits are mostly node-local (distribution tweaks, renames) and
  structural conflicts are rare or acceptably resolved by "last writer / manual."

### Option B — Operation-based (OT / CRDT), needs `TreeOp`

- Transmit operations; merge by transform/commute (per the ADR's commutativity table)
  or CRDT convergence.
- **Adds over Option A:** principled resolution of **structural** conflicts — reparent
  vs. reparent, reparent vs. delete, ordering of dependent ops (add-portfolio before
  add-leaf-into-it) — and intent-level audit (tier 3 above) as a by-product.
- **Cost:** a serialized operation algebra, an interpreter with precondition checks,
  inverse functions, and conflict-resolution semantics — substantial, and only
  justified by genuine structural-conflict frequency.
- **Best when:** real-time multi-user structural editing is a core product capability.

**Framed plainly:** the old ADR's commutativity and invertibility analyses are
*collaboration/merge* machinery wearing "efficiency" and "undo" labels. Their true home
is Option B. If collaboration is never adopted, they are dead weight; if collaboration is
adopted **state-based** (Option A), they are still unnecessary. `TreeOp` is justified
**iff** Option B is chosen over Option A — which requires both that concurrent multi-writer
editing is a requirement *and* that git-native tree merge is demonstrably insufficient
for the structural conflicts that arise.

---

## The correctness-by-construction claim, re-examined

The original reasoning was: "tree validity is a whole-graph property; it cannot be
checked from one node in isolation; therefore whole-tree submission is required." The
first two parts are true; the conclusion does not follow.

### What the code actually checks

`validateTopologyUpdate` runs seven guards over the submitted node set:

| Guard | What it reads | Truly whole-tree? |
|---|---|---|
| `requireNoReservedNames` | names of submitted nodes only | No |
| `requireUniqueNames` | names of submitted nodes only | No |
| `requireSingleRoot` | count of root-less submitted nodes | No |
| `requireLeafParents` | leaf's parentName ∈ portfolio name set | No |
| `requirePortfolioParents` | portfolio's parentName ∈ portfolio name set | No |
| `requireNoCycles` | parent-pointer chain, upward traversal | O(depth), not O(n) |
| `requireNonEmptyPortfolios` | every portfolio name has ≥1 child in submitted set | See below |

None of these reads from the database. They run entirely over the submitted payload.
The statement "whole-graph invariants need the full submitted tree" is true — but the
question is whether submitting the full tree is the *only way* to enforce them, or just
the way whole-tree PUT happens to do it.

### The same invariants as TreeOp preconditions

Each guard reduces to an O(1)–O(depth) check against the **live tree** when the
operation is constrained:

| Operation | Loads needed | Invariants checked |
|---|---|---|
| `UpdateDistribution(nodeId, params)` | 1 node read | node exists ∧ is leaf — no topology change |
| `RenameNode(nodeId, newName)` | name-index read (1) | newName not in name set |
| `AddLeaf(parentId, params)` | 1 parent read + name index | parent is portfolio ∧ name unique |
| `DeleteNode(nodeId)` | 1 target + 1 parent read | not root ∧ parent has >1 child |
| `ReparentNode(nodeId, newParentId)` | ancestor path (O(depth)) | newParent ∉ descendants(node) ∧ old parent keeps ≥1 child |

The non-empty-portfolio invariant — the most "whole-tree looking" guard — reduces to
*"does the target node's parent still have ≥1 child after this op?"* — a O(1) check.
The cycle check reduces to *"is newParent in ancestors(node)?"* — O(depth) upward walk.

**Conclusion:** every invariant whole-tree PUT enforces is achievable by TreeOp as a
per-operation precondition against the live tree. The correctness guarantee is identical;
the mechanism differs. PUT validates the submitted payload as a graph; TreeOp validates
the live tree + one operation. The set of forbidden states that can be reached is the
same in both cases.

The one genuine advantage of whole-tree PUT is **atomic complex restructuring**: a
dependent multi-step change (insert a portfolio tier, reparent ten leaves under it,
remove the old tier) arrives as a single target state, validated in one pass. With
sequential TreeOps the server validates each operation against the intermediate state;
the final state is only holistically validated if you add an end-state check — which is
exactly the batch model with a final-topology verification step.

---

## Efficiency at realistic tree sizes

The I/O unit is one sequential Irmin HTTP round-trip per node (code confirms:
`writeNodes` and `readNodes` both use `ZIO.foreachDiscard`/`ZIO.foreach` — sequential,
not parallel). Each is a GraphQL mutation over an HTTP connection.

**Per whole-tree PUT on a tree of n nodes:**

```
Load tree:    n sequential GETs
Write nodes:  n sequential SETs  (incl. all unchanged nodes)
Total:        2n round-trips
```

**Per TreeOp:**

| Operation | Load cost | Write cost | Total |
|---|---|---|---|
| `UpdateDistribution` | 1 | 1 | **2** |
| `RenameNode` (with name index) | 1 | 1 | **2** |
| `AddLeaf` | 1 (parent) + 1 (name index) | 1 | **3** |
| `DeleteNode` | 2 (target + parent) | 1 | **3** |
| `ReparentNode` | O(depth) (ancestor path) | 1 | depth + 1 |

Break-even for `UpdateDistribution` (the commonest edit in practice):

```
2n = 2   →   n = 1
```

TreeOp is more efficient for **any tree with more than one node**. At a round-trip
latency of ~10ms (conservative local Docker network):

| n (nodes) | PUT (2n × 10ms) | UpdateDistribution TreeOp (20ms) | Factor |
|---|---|---|---|
| 10 | 200ms | 20ms | 10× |
| 30 | 600ms | 20ms | 30× |
| 100 | 2s | 20ms | 100× |
| 200 | 4s | 20ms | 200× |

### What is a realistic tree size?

A risk model for a mid-sized organization with 3–5 risk categories, 5–15 subcategories
each, and 2–5 specific risks (leaves) per subcategory: **50–300 nodes**. The 2-second
mark is reached around 100 nodes. A heavily modelled enterprise tree with cross-domain
coverage: 300–1000 nodes (4–10 seconds for a single distribution tweak).

The efficiency gap is not a micro-optimization concern at these scales — it is the
difference between a responsive interaction and a perceptibly slow one for the most
common edit type.

---

## Updated summary

| Claim | Verdict |
|---|---|
| Whole-tree PUT enforces all topology invariants | ✅ True |
| It is the *only* mechanism that can do so | ❌ False — every invariant is achievable as a TreeOp precondition |
| `TreeOp` is redundant on **correctness** grounds | ❌ False — equivalent correctness, different mechanism |
| `TreeOp` is redundant on **bandwidth** grounds | ✅ True (validation cost is CPU not bandwidth) |
| `TreeOp` is redundant on **undo** grounds | ✅ True (Irmin commit history subsumes in-memory inversion) |
| `TreeOp` is redundant on **audit-of-what** grounds | ✅ True (commits record that changes happened) |
| `TreeOp` adds value for **efficiency** at realistic tree sizes | ✅ True — 10–200× fewer round-trips for single-node ops |
| `TreeOp` adds value for **intent-level audit** (what *kind* of change) | ✅ True (non-redundant with commit history) |
| `TreeOp` adds value for **structural-conflict collaboration** | ✅ True (conditional on multi-writer being a requirement) |
| Whole-tree PUT has an advantage for **atomic complex restructurings** | ✅ True (target-state semantics; multi-step validated as one) |

**The honest framing:** whole-tree PUT is correct and the right foundation. TreeOp is
not a correctness improvement — it is an efficiency and expressiveness improvement.
Whether to implement it is a product-priority and complexity-budget decision, not a
correctness or capability question. At tree sizes of 50–300 nodes, the efficiency
argument becomes hard to dismiss for frequent single-node edits.


# Appendix — Tree write shape: whole-tree PUT vs. apply-then-validate

Analysis appendix to `PLAN-TREE-WRITE-CONCURRENCY.md`. It records why the shape
of the tree write is a decision in its own right, what correctness guarantee
each candidate shape gives, and the four options considered.

**Ruling: Option A now, Option B next.** Recorded in
`PLAN-TREE-WRITE-CONCURRENCY.md` §2.0. Detailed design of Option B is not
attempted here and is not yet scheduled.

This appendix confers no G3 coverage and is not implementation-grade.

---

## 1. Why the write shape is a decision

`RiskTreeServiceLive.update` computes a complete replacement tree from the
request body and writes it. The values written therefore come from the client's
read, which may be arbitrarily old, and ADR-017's whole-tree PUT means every
write is a full replacement where omission deletes. Two consequences follow, and
the second one is what makes this a design decision rather than a bug fix:

1. A writer holding stale state overwrites everything the request enumerates.
2. Because the write is tree-wide, the version token that would guard it must
   also be tree-wide — obtained on a read, carried through the client, submitted
   on the write. **The plumbing cost of the concurrency fix is a consequence of
   the write shape, not of concurrency control itself.** Under a shape where the
   client submits a change rather than a state, the precondition rides inside
   the change and needs no separate transport.

The question this appendix answers is whether a different shape keeps the
correctness guarantee ADR-017 was designed to provide.

---

## 2. Correctness under apply-then-validate

**The shape in question.** The client sends an intent; the server loads current
state, applies the intent, validates the result, and persists only if valid.
This is the command-handler shape — the write half of CQRS (Command Query
Responsibility Segregation). Full CQRS, with separate read models, projections
and an event store, is not required to get it.

**The guarantee is preserved by construction.** `RiskTree.fromNodes` and
`validateTopologyUpdate` form a pure predicate over a candidate node set: all
seven topology guards read only the submitted nodes, and none of them reads from
the store. The write path's guarantee is therefore

> for every persisted tree T: `valid(nodes(T))`

which says nothing about where `nodes(T)` came from. Today it is
`parse(client payload)`; under apply-then-validate it is
`apply(delta, currentTree)`. Both are ways of producing a candidate node set, and
the same predicate runs on the result either way. There is nothing new to prove.

**This is a cheaper guarantee than the per-operation-precondition analysis.**
`ADR-017-NOTES.md` ("The correctness-by-construction claim, re-examined") proves
a harder statement: that each invariant reduces to an O(1)–O(depth) precondition
checkable against the live tree before applying an operation. That reduction is
needed only to reject early without building the result, and every invariant
needs its own argument. Building the result and validating it wholesale skips
the reduction entirely, at the cost of one O(n) in-memory validation per write —
negligible at the 50–300 node sizes the notes establish as realistic.

**Two caveats, both real:**

- What weakens is client awareness, not tree validity. An accepted write under
  the current shape means "the client's intended end state is exactly this, and
  it is valid". Under apply-then-validate it means "the result of applying the
  client's intent to current state is valid" — possibly a state the client never
  saw. The stored tree is equally valid either way. This is the trade every merge
  makes.
- ADR-017's atomic-complex-restructuring property survives only if the unit of
  work is a batch applied atomically and validated once. One operation per
  request loses it: a dependent multi-step change (insert a tier, reparent leaves
  under it, drop the old tier) passes through intermediate states that the
  validator would reject even though the end state is valid.

---

## 3. What a change of shape does and does not fix

```
current:  written = f(client_read_at_C0)   // absolute state, wholly from a stale read
delta:    written = apply(delta, head)     // change applied to fresh state
```

Under the delta form the client's stale knowledge enters the write only through
the delta's own operands. So:

- Concurrent edits to **disjoint** parts of the tree merge automatically. One
  client adding a leaf and another editing a probability both survive.
- Concurrent edits to the **same field** still resolve last-writer-wins.

A delta shape therefore shrinks the blast radius from the whole tree to the
fields the request names. It does not remove the need for a version token; it
makes the token per-field, and self-describing inside the payload
(`SetProbability(nodeId, expected = 0.05, to = 0.06)`), which is what removes the
header and read-endpoint plumbing.

**Not available client-side:** per-node content hashes. `TreeIndex` in `common`
carries structure only (`nodes`, `parents`, `children`); the sha256 content
hashing is `ContentHashIndex` under `modules/server/`, and `RiskTree`'s codec
omits the index and rebuilds it on decode. A per-field expected *value* avoids
this and is simpler than transporting hashes.

---

## 4. The options

### Option A — whole-tree PUT plus a tree-wide version token

The body of `PLAN-TREE-WRITE-CONCURRENCY.md`.

*Pros:* smallest change; ADR-017 unchanged; reuses `VersionConflict`, its 409
mapping and the SPA handler, all of which exist and are unused; semantics are
trivially explainable.
*Cons:* every concurrent edit is a conflict, including edits to unrelated parts
of the tree; needs a read-side change to get the token to the client plus a
write-side transport decision; the user-visible outcome is a refusal.

*Example:* one client adds a leaf at 09:40 while another renamed a different leaf
at 09:10. Nothing overlaps. The add is still rejected.

### Option B — whole-tree submission plus a base revision; server diffs and merges

The client keeps submitting a complete tree and also sends the revision it was
derived from. The server computes `delta = diff(base, submitted)`, applies it to
head, and validates the result with the existing validator.

*Pros:* correctness identical, per §2; disjoint edits auto-merge and only
genuinely overlapping fields conflict, reportable per field; no client rewrite —
`TreeBuilderState.loadedSnapshotVar` already holds the base state and the drafts
already carry `id: Option[NodeId]`; the wire change is one field, and it is the
**same** field Option A needs, so Option A is the strict-mode subset of Option B
and shipping A first is not wasted; no operation algebra, interpreter or inverse
functions.
*Cons:* the diff-and-merge function and its conflict policy are new logic that
must be correct — contained and testable, but real; and the client can be shown a
result it did not compose.

*Example:* same scenario as above. The added leaf touches nothing the rename
touched, applies to head, and both survive. An edit to the same probability
returns a 409 naming that field.

### Option C — an operation algebra (`TreeOp`), batched and validated once

*Pros:* everything Option B gives, plus minimal payloads and intent history —
`ADR-017-NOTES.md`'s fidelity tier 3, where a reparent is recorded as a move
rather than inferred from a content diff, which is the only tier `TreeOp`
uniquely unlocks; the notes establish a 10–200x round-trip advantage for
single-node edits at realistic tree sizes.
*Cons:* by a wide margin the largest change — op vocabulary, server-side
interpreter, and a client that emits operations instead of submitting drafts,
which is a rewrite of `TreeBuilderState`'s submission model. Per the notes, this
is an efficiency and expressiveness case, not a correctness one.

### Option D — Irmin-native three-way merge, branch per editing session

*Pros:* the machinery exists and is in production — `IrminClientLive.mergeWithBranch`,
typed `IrminMergeConflict`, consumed by `ScenarioMergeService`.
*Cons:* a key-value content merge reconciles independent changes but mishandles
conflicting structural intent (two clients reparenting the same node to different
parents). Decisively for this question, Irmin creates the merge commit itself, so
validating the merged result happens after the write — which forces the
scratch-branch-then-fast-forward machinery that `PLAN-TREE-WRITE-CONCURRENCY.md`
Decision 2 Option B already identifies as landing in DD-5's branch-lifecycle
invariants.

---

## 5. Relationship to Phase F

`PLAN-PHASE-F-SEMANTIC-MERGE.md` needs a **domain-level** merge over `RiskLeaf`
parameters, as distinct from the byte-level Irmin merge Phase D shipped. Option B
needs a domain-level diff and merge over two node sets. The two differ in what
they reconcile — Phase F reconciles scenario branches, Option B reconciles
concurrent edits on one branch — but they want the same underlying capability.
Whichever is designed first should be designed so the other can reuse it.

---

## 6. What is deliberately not settled here

- The diff and merge functions, their signatures, and the conflict policy for
  Option B.
- Field-level versus node-level conflict granularity.
- What the client is shown when a merge succeeds but changed something the user
  did not submit.
- Whether Option C's intent history is ever wanted; it is not addressed by this
  ruling in either direction.

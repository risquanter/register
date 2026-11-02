# PLAN — Seed allocation per workspace, and a seed entity id per tree

**Status: DRAFT — open decisions SA-D-4 to SA-D-9 are unruled. This document
confers no G3 plan coverage and authorizes no source edit.** It becomes
implementation-grade once those decisions are ruled, §4 is rewritten against the
rulings, and the user has written `PLAN-SEED-ALLOCATION-INVENTORY.md`.

**Date:** 2026-11-02.

**Origin:** the complex-tier review of `PLAN-ERROR-WIRE-BOUNDARY.md` §17
(coverage entry in `docs/dev/reviews/REVIEW-main.md`, finding 1), and the
discussion of that finding on 2026-11-02.

**Inventory:** `PLAN-SEED-ALLOCATION-INVENTORY.md`, beside this file. It does not
exist yet. The user creates it.

**Paired plan:** `PLAN-TREE-WRITE-CONCURRENCY.md`. Its compare-and-set save lands
before this plan, and its §9 is the single statement of the order.

---

## 1. Problem

### 1.1 Terms

A **seed** here is a leaf's `seedVarId`: an integer in `[1, 50000000)` that
selects the leaf's two random-number streams. ADR-003 §1 derives them as
`2 * seedVarId` for occurrence and `2 * seedVarId + 1` for loss.

A **seed entity id** is `seedEntityId`: an integer in `[1, 100000000)` that ADR-003
§1 passes to HDR as the entity coordinate. Today it belongs to the workspace, so
every tree in a workspace draws with the same value.

The **high-water mark** is `seedVarHighWater`, stored in each tree's `meta`
value. It is the largest seed the tree has ever handed out.

HDR (Hash-based Deterministic Random) is the generator in the `hdr-rng` library.
It maps a trial number and four integer coordinates to a uniform draw. ADR-003's
context states that its identifier axes are bounded at 10⁸.

### 1.2 Two branches hand out the same seed

A save assigns each new leaf without a provided seed the next value above the
tree's high-water mark (`SeedVarIdAssigner.assign`). The mark is read from the
`meta` value on the branch being saved. A scenario is a fork of main, so after the
fork each branch holds its own copy of `meta`, and each copy counts upward on its
own.

Example. A tree's high-water mark is 5 when scenario S is forked. Main adds the
leaf "Fire" and gives it seed 6. S adds the leaf "Flood" and gives it seed 6. Both
copies of `meta` now hold 6. The merge's field rule sees two equal values and
reports no conflict, so the preview answers `Clean`. The guard after the merge then
finds seed 6 twice and refuses the merge with `InvariantBroken`. A seed never
changes once assigned (ADR-003 §2), so the user cannot resolve the refusal from the
dialog.

The collision is created when the seed is handed out. A merge can only detect it
or renumber it, so the fix belongs to allocation.

### 1.3 A tree cannot be recreated exactly in another existing workspace

Reproduction through the API works by reading a tree, keeping its seeds, and
creating it again with every seed provided. `RiskLeafDefinitionRequest.seedVarId`
accepts them on create and on new leaves in an update. The get-tree endpoint
returns them.

The figures also depend on the seed entity id. `WorkspaceStore.create` accepts a
provided `seedEntityId`, and the bootstrap endpoint takes it as the query
parameter `seedEntityId`. A second workspace can therefore reproduce a first one's
figures only when it is created with the first one's value, and the store refuses a
value that another workspace in the same deployment holds (`seedEntityIdInUse`).
An existing workspace cannot take a copied tree at all, because all its trees draw
with its own value.

### 1.4 The save this plan writes through

Two concurrent saves of one tree resolve last-writer-wins today. Irmin's
`set_tree` retries when the branch head moves, but it writes the same subtree on
top of the new head, so the second save replaces the first save's tree.
`PLAN-TREE-WRITE-CONCURRENCY.md` TWC-D-2 rules that the save becomes a
compare-and-set through a staging branch, and the user ruled on 2026-11-02 that
this save is a prerequisite of the present plan. `PLAN-TREE-WRITE-CONCURRENCY.md`
§9 is the single statement of the order: its step 1, the compare-and-set save, lands
first; this plan lands second; its client token lands third.

Inside one save the order is: reserve the seeds (SA-D-2), build the tree, then
the compare-and-set write. A save refused by the compare-and-set has already
moved the counter, so its reserved seeds become a gap. SA-D-2 states that a gap is
harmless.

---

## 2. Ruled decisions

### SA-D-1 — The seed counter belongs to the workspace

Ruled 2026-11-02. Seeds are handed out from one counter per workspace. No branch
holds a copy of it, so a fork does not copy it and every branch of every tree in
the workspace draws new seeds from the same sequence.

Consequence for §1.2: main's "Fire" gets 6 and the scenario's "Flood" gets 7, so
the merge has nothing to refuse.

### SA-D-2 — A save reserves its seeds in one atomic step

Derived from SA-D-1 and the existing assignment rule, and explained to the user on
2026-11-02. A save that needs k new seeds reserves them from the counter in
one atomic step, where k is the number of new leaves without a provided seed. The
step reads the counter value c, assigns the leaves the values above
`max(c, every provided seed in the save)` in sorted-name order, and moves the
counter to the largest value now in use. One atomic step per save, not one per
leaf.

A provided seed raises the counter in that same step, so a later save can never be
handed a seed that a provided leaf already holds.

A save that reserves seeds and then fails leaves a gap in the sequence. A gap is
harmless, because the rule is only that a seed is never handed out twice.

### SA-D-3 — A tree carries its own seed entity id

Ruled in direction 2026-11-02. Each tree stores a `seedEntityId`. A create request
may provide one. Without it, the tree takes the workspace's `seedEntityId` at the
moment of creation and keeps that value. It never changes afterwards, for the same
reason a seed never changes: changing it changes every figure the tree produces.

Every simulation of the tree draws with the tree's value instead of the
workspace's. This closes §1.3: a tree read from one workspace can be created in
another existing workspace with its seeds and its seed entity id provided, and it
produces the same figures.

---

## 3. Open decisions

### SA-D-4 — Where the workspace counter is stored

**What has to be decided, and why now.** SA-D-1 says the counter belongs to the
workspace and that no branch copies it. The counter must survive exactly as long
as the trees whose seeds it guards, or a fresh counter could hand out a seed a
stored tree still holds. Every signature in §4 that touches the counter depends on
where it lives.

**Option A — a value in Irmin on a branch only the server writes, changed with
Irmin's `test_and_set`.** Irmin GraphQL exposes
`test_and_set(branch, path, test, set, info)`, which writes `set` at `path` only if
the value there is still `test`, and retries internally while other paths change
(`irmin-graphql` `server.ml` 676-696, `irmin` `store.ml` 907-911 and 971-1001,
read from the `local/irmin-builder:3.11-p1` image). The counter would live at one
path per workspace on a dedicated branch, so no scenario fork copies it.
- Pro: the counter lives in the same store as the trees, so a restore of the Irmin
  store restores both together and they cannot disagree.
- Pro: it works with either workspace store, in memory or Postgres.
- Con: a failed test comes back as a GraphQL error whose text is
  "Test-and-set failed: got …". Telling it apart from other failures without
  matching that text needs a re-read of the counter after any failure: a value
  different from `test` means another save moved it, and the reservation retries.
- Con: a new branch kind. ADR-036 says both existing branch-name types embed the
  `WorkspaceId`. A third type joins `StoreBranch`, and ADR-004a's storage layout
  and ADR-007's branch rules need an entry for it.
- What you would see: nothing in the browser. A store dump shows one extra branch
  with one small value per workspace.

**Option B — a field of the workspace in the workspace store.**
`WorkspaceStoreLive` changes it inside its existing `Ref.modify`, which already
hands out `seedEntityId` atomically. `WorkspaceStorePostgres` changes it with one
`UPDATE workspaces SET … RETURNING …`, which Postgres applies atomically per row.
- Pro: atomic primitives with typed results in both backends, and no error text to
  classify.
- Pro: no Irmin change and no new branch kind.
- Con: the counter and the trees live in two stores. If the Postgres database is
  restored to a point older than the Irmin store, the counter is lower than seeds
  that trees already hold, and the next save can hand out a repeated seed. The
  per-tree distinctness check would still refuse a repeat inside one tree, and a
  repeat across two branches of one tree would again surface only at the merge.
- Con: a schema migration (`V3__…`) adds the column.
- What you would see: nothing in the browser. Two backends each gain one field.

**Option C — a value in Irmin on main, changed with `test_and_set`.**
- Pro: no new branch kind.
- Con: a scenario fork copies main, so every scenario carries a copy that nothing
  reads. The merge would merge that path too.
- Con: every reservation is a commit on main. That moves main's head, so a merge
  that is being assembled fails its publish with `MergeTargetMoved` whenever any
  save added a leaf in the meantime, and main's commit list gains one commit per
  such save.
- What you would see: more "main changed, re-run the preview" refusals on merges.

**My recommendation: A.** It keeps the counter and the trees in one store, so no
restore order can make them disagree. B's typed primitives are its real advantage,
and A's re-read after a failure gives the same answer without matching error text.
C pays A's costs and adds merge refusals.

### SA-D-5 — What happens to the per-tree high-water mark

**What has to be decided, and why now.** After SA-D-1 the per-tree
`seedVarHighWater` no longer decides any assignment. It is stored in `meta`, it is
a field of `RiskTree`, `RiskTree.fromNodes` checks that it is at least the largest
seed in the tree, and the merge compares it as a field
(`FieldName.SeedVarHighWater`, `TreeMetadataFields.seedVarHighWater`, the browser
sentence in `MergeMessages`).

**Option A — keep it, as the largest seed this tree has ever held.**
- Use it gains: one stored number answers "has this tree ever held seed n" without
  reading history. Nothing in the server or the browser asks that question today.
- Con: the merge keeps comparing a field that two branches now raise to different
  values whenever both added leaves. The merge would report a conflict for that
  case, where after SA-D-1 there is nothing wrong.
- What you would see: a merge of two branches that each added a leaf shows a
  conflict on "seed high-water" that no edit can resolve.

**Option B — remove it.** `meta` loses the field, `RiskTree` loses the field and
its check, and the merge loses the field and its browser sentence.
- Pro: removes a merge conflict that no longer means anything.
- Con: a persisted-format change (`meta` version 3) and a wire change
  (`TreeMetadataFields`, `FieldName`).
- What you would see: the merge dialog no longer mentions seeds.

No consumer reads the value for any purpose besides allocation and its own check.

**My recommendation: B.** Option A's remaining use has no caller, and keeping the
field keeps a merge conflict that SA-D-1 made meaningless.

### SA-D-6 — The distinctness scope of a provided seed

**What has to be decided, and why now.** Today `SeedVarId.requireDistinct` refuses
two leaves with the same seed inside one tree. After SA-D-1 server-assigned seeds
are distinct across the whole workspace. A provided seed is not: a create can
provide seed 6 while another tree in the workspace, or another branch of the same
tree, already holds 6.

**Option A — keep the check per tree.** ADR-003 §2 states it as "distinct per
tree".
- Pro: recreating a tree in the same workspace while the original still exists
  works, with every seed provided.
- Con: two branches of one tree can still each be given the same provided seed,
  and the merge refuses that (SA-D-8 covers the preview side).
- What you would see: a copy of a tree in the same workspace works. A user who
  types the same seed on two branches is refused at the merge.

**Option B — check against every tree and every branch in the workspace.**
- Pro: a seed means one risk across the whole workspace.
- Con: the check reads every tree on every branch of the workspace on every save
  that provides a seed.
- Con: a recreation in the same workspace is refused while the original exists.
- What you would see: a copy in the same workspace is refused until the original
  is deleted.

**My recommendation: A.** Distinctness exists so that two leaves aggregated into
one portfolio never draw the same stream, which would make them perfectly
correlated. Every simulation and every query takes one `treeId`, so two trees are
never aggregated together, and a seed shared by two trees creates no correlation. The one harmful repeat, two branches of one tree,
is caught by SA-D-8 before the merge.

### SA-D-7 — The persisted `meta` format

**What has to be decided, and why now.** SA-D-3 adds `seedEntityId` to `meta`, and
SA-D-5 Option B removes `seedVarHighWater`. Either change makes the format version
3. `TreeMetadata.CurrentSchemaVersion`'s scaladoc states the existing practice:
"There is no decode path for version 1: such a store is wiped rather than
migrated."

**Option A — no decode path for version 2. A store holding version 2 is wiped.**
- Pro: one decoder. It follows the existing practice for version 1.
- Pro: the workspace counter in SA-D-4 then starts at 0 for every workspace, with
  no starting value to derive.
- Con: any tree stored today is lost on upgrade.

**Option B — decode version 2 and fill the new field.** The seed entity id comes
from the workspace, and the counter starts at the largest high-water mark of any
tree on any branch of the workspace.
- Pro: stored trees survive.
- Con: a second decoder, and a start-up or first-use step that reads every branch
  of a workspace to seed its counter.

**My recommendation: A**, under the condition that no store you keep holds trees
you need. The repository cannot settle that condition, and it is the deciding fact.

### SA-D-8 — Whether the merge preview reports a repeated seed

**What has to be decided, and why now.** After SA-D-1 two branches can still hold
the same seed for two different leaves only through provided seeds. The merge
guard refuses that case with `InvariantBroken`, which names no field. This is the
remaining part of the complex review's finding 1.

**Option A — the scan reports it.** The pass that reports repeated node names
(`duplicateNodeNames`) already decodes every merged node. It also collects the
merged leaves' seeds and reports a repeat as a conflict on the tree, with a new
browser sentence saying which two risks share a seed.
- Pro: the preview predicts every refusal the guard can give.
- Con: more code in the scan, and a new wire case or field name for the conflict.
- What you would see: the preview names the two risks and tells you to remove one
  side's risk and add it again after the merge.

**Option B — leave it to the guard.**
- Pro: no change.
- Con: the preview can say `Clean` and the merge refuse, with no named cause.
- What you would see: the same dead end as §1.2, now only for seeds you typed
  yourself.

**My recommendation: A.**

### SA-D-9 — How the create request carries a tree's seed entity id

**What has to be decided, and why now.** SA-D-3 lets a create provide the tree's
seed entity id. That is a wire change to the create endpoint and to the bootstrap
endpoint, which creates a workspace and its first tree in one call.

**Option A — an optional field on `RiskTreeDefinitionRequest`:**
`seedEntityId: Option[Long]`, refined at the codec boundary like
`RiskLeafDefinitionRequest.seedVarId`.
- Pro: it travels with the tree it describes, and the bootstrap endpoint gains it
  without change, because it already takes `RiskTreeDefinitionRequest` as its body.
- Con: the bootstrap endpoint then has two seed entity ids in one call: the query
  parameter for the workspace and the body field for its first tree.

**Option B — a query parameter on the create endpoint**, like the bootstrap
endpoint's `seedEntityId`.
- Pro: matches the bootstrap endpoint's existing spelling.
- Con: the value describes the tree in the body, yet travels outside it. The
  bootstrap endpoint would need a second, differently named parameter.

**My recommendation: A.**

---

## 4. Signatures

Draft. They are written against the recommendations: SA-D-4 A, SA-D-5 B,
SA-D-6 A, SA-D-7 A, SA-D-8 A, SA-D-9 A. §4 is rewritten after the rulings and
presented as the Signature Echo.

### 4.1 The workspace counter

Family: a server service trait with a live implementation and a layer, the family
of `WorkspaceStore` in
`modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStore.scala`.
The pure assignment it wraps is the existing `SeedVarIdAssigner.assign`, which it
reuses unchanged. No existing type denotes a per-workspace seed sequence.

```scala
// modules/server/src/main/scala/com/risquanter/register/services/workspace/SeedVarIdCounter.scala
package com.risquanter.register.services.workspace

import zio.*
import com.risquanter.register.domain.data.iron.{SafeName, SeedVarId, WorkspaceId}
import com.risquanter.register.domain.errors.AppError

/** Hands out seeds from one sequence per workspace. A seed handed out once is
  * never handed out again in that workspace, on any branch. */
trait SeedVarIdCounter:
  /** Assigns a seed to every leaf in `leafNames` that `fixed` does not name, and
    * moves the workspace's counter past every value in the result, in one atomic
    * step. `fixed` holds the seeds that must not move: carried over from kept
    * leaves, and provided by the caller for new ones. */
  def assign(
    wsId: WorkspaceId,
    leafNames: Seq[SafeName.SafeName],
    fixed: Map[SafeName.SafeName, SeedVarId.SeedVarId]
  ): IO[AppError, Map[SafeName.SafeName, SeedVarId.SeedVarId]]
```

The step under SA-D-4 A is: read the counter value c at the workspace's path, run
`SeedVarIdAssigner.assign(leafNames, fixed, c.value)`, then `test_and_set` the path
from c to the returned high-water mark. On failure, read the path again. A value
other than c means another save moved it, and the step repeats. A value equal to c
means the failure was something else, and it is returned. The number of repeats is
bounded by `SeedVarIdCounter.MaxAttempts = 8`, a value chosen here and not
measured.

The Irmin client gains the value compare-and-set:

```scala
// modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala
def testAndSet(path: IrminPath, test: Option[String], set: Option[String], message: String, branch: SeedCounterRef): IO[IrminError, Unit]
```

`SeedCounterRef` joins the sealed `StoreBranch` in `OpaqueTypes.scala`, beside
`BranchRef`, `MergeStagingRef` and `PLAN-TREE-WRITE-CONCURRENCY.md` §3.2's
`SaveStagingRef`, which lands before it. Its name embeds the `WorkspaceId`, the
same as `MergeStagingRef.prefix`, so ADR-036 §2's containment applies to it
unchanged. The records' amendment for server-only branches is made once, by the
tree-write plan's step 1, and this plan adds its row. Its exact spelling is
written in the echo.

### 4.2 The tree's seed entity id

```scala
// modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala
final case class RiskTree private (
  id: TreeId,
  name: SafeName.SafeName,
  nodes: Seq[RiskNode],
  rootId: NodeId,
  index: TreeIndex,
  seedEntityId: SeedEntityId.SeedEntityId,
  mitigations: Seq[Mitigation]
)

// modules/server/src/main/scala/com/risquanter/register/repositories/model/TreeMetadata.scala
final case class TreeMetadata(
  id: TreeId,
  name: SafeName.SafeName,
  rootId: NodeId,
  seedEntityId: SeedEntityId.SeedEntityId,
  schemaVersion: Int,
  createdAt: Instant,
  updatedAt: Instant
)
// CurrentSchemaVersion = 3

// modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala
final case class RiskTreeDefinitionRequest(
  name: String,
  portfolios: Seq[RiskPortfolioDefinitionRequest],
  leaves: Seq[RiskLeafDefinitionRequest],
  seedEntityId: Option[Long]
)
```

`RiskTree.fromNodes` replaces its `seedVarHighWater: Option[SeedVarId.SeedVarId]`
parameter with `seedEntityId: SeedEntityId.SeedEntityId`. The merge copies the
tree's `seedEntityId` from main, since both branches hold the same value and it
never changes. It is not a merge field.

### 4.3 Service signatures that change

```scala
// modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala
def create(wsId: WorkspaceId, workspaceSeedEntityId: SeedEntityId.SeedEntityId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Permission]): IO[AppError, RiskTree]
def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, includeProvenance: Boolean, rev: Revision)(using Checked[Permission]): IO[AppError, Double]
def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], includeProvenance: Boolean, rev: Revision, omitAbsent: Boolean)(using Checked[Permission]): IO[AppError, Map[NodeId, LECNodeCurve]]

// modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala
def evaluate(wsId: WorkspaceId, treeId: TreeId, parsed: ParsedQuery, branch: BranchRef)(using Checked[Permission]): IO[AppError, QueryResponse]

// modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolver.scala
// ensureCached and ensureCachedAll lose their seedEntityId parameter and read tree.seedEntityId.
```

`getLECCurvesMulti`'s `omitAbsent: Boolean = false` loses its default under the
boyscout rule against defaulted parameters on a signature already being changed.

### 4.4 The cache registry key

`ContentCacheRegistry` keys its caches by `SeedEntityId` today, and ADR-014 §4
states "One cache per workspace". After SA-D-3 two workspaces can hold trees with
one seed entity id. Sharing a cache entry between them is correct, because equal
content and equal seed entity id give equal figures. A cache keyed by seed entity
id alone can no longer be released per workspace, though. A workspace's trees may
draw with another workspace's value, and releasing that value would drop the other
workspace's cache.

The key becomes the pair:

```scala
// modules/server/src/main/scala/com/risquanter/register/services/cache/ContentCacheRegistry.scala
def forTree(wsId: WorkspaceId, seedEntityId: SeedEntityId.SeedEntityId): UIO[ContentCache]
```

This changes the signatures of `PLAN-WORKSPACE-CACHE-RELEASE.md`, which is
approved in direction and not implemented. Its `ContentCacheRegistry.release`
would take the `WorkspaceId` and drop every key carrying it. That plan is not
edited by this one, and the change is presented to the user for a ruling.

---

## 5. File inventory

The inventory is the user's document `PLAN-SEED-ALLOCATION-INVENTORY.md`. Until
it exists, nothing here is authorized. The path list depends on SA-D-4, SA-D-5
and SA-D-8, so it is derived by search after they are ruled and handed to the user
as the two-part block.

Not touched: `IrminQueries`' merge queries and `ScenarioMergeService`'s
compare-and-set publish. The save's own compare-and-set is
`PLAN-TREE-WRITE-CONCURRENCY.md` step 1 and is in place before this plan starts;
this plan's inventory names the shared files (`RiskTreeRepositoryIrmin.scala`,
`RiskTreeServiceLive.scala`, `OpaqueTypes.scala`, `IrminClient.scala`,
`IrminClientLive.scala`, `IrminQueries.scala`) for its own additions to them.

---

## 6. ADR alignment

| ADR | Bearing | Alignment |
|---|---|---|
| ADR-003 | Seed assignment, seed entity id | **Amended by this plan.** §1's `entityId = workspace.seedEntityId` becomes the tree's value. §2's assignment bullet ("numbering them consecutively from `RiskTree.seedVarHighWater + 1`") becomes the workspace counter. Its high-water bullet and "distinct per tree" bullet follow SA-D-5 and SA-D-6. The Reproduction table's row "The same model in a second workspace or deployment with seed IDs provided" gains the seed entity id. The two rows for rebuilding were amended on 2026-11-02 ahead of this plan. |
| ADR-014 | Cache scope | **Amended by this plan.** §4 "One cache per workspace" becomes one cache per workspace and seed entity id (§4.4). |
| ADR-032 | Hashed projection | Compliant. `seedVarId` stays in the projection. The seed entity id stays out of it and stays a cache scope. |
| ADR-004a | Storage layout | **Amended under SA-D-4 A**: the counter branch and its path are added to the layout. Compliant under B. |
| ADR-007 | Branch kinds | **Amended under SA-D-4 A**: a server-only branch that is neither a scenario nor a merge staging branch. |
| ADR-036 | Branch-name types | Compliant under SA-D-4 A: `SeedCounterRef` embeds the `WorkspaceId` and joins the sealed `StoreBranch`, so it cannot reach a wire type. |
| ADR-017 | Create DTO | **Amended under SA-D-9 A**: the create request gains an optional field. |
| ADR-038 | Response shape | The get-tree response gains `seedEntityId` through `RiskTree`'s derived codec. Compliant: a refined type, no raw primitive. |
| ADR-001 | Validation | Compliant: the new request field is refined by `ValidationUtil.refineSeedEntityId` at the codec boundary. |
| ADR-039 | Stored values read back | Compliant: the counter value read from Irmin is refined to `SeedVarId` on arrival, and a value that fails refinement is a refused peer content failure, not an unreachable store. |
| ADR-010 | Errors | Compliant: counter exhaustion reuses `SeedVarIdAssigner`'s `ValidationError`. Repeats exhausted under contention fail with an `IrminError`. |
| ADR-011, ADR-037 | Every edited file | Compliant: `MaxAttempts` is a named constant in the companion of its owner. |

---

## 7. Verification plan

1. **Unit, `SeedVarIdCounterSpec`** (new). Two concurrent `assign` calls on one
   workspace return disjoint seeds. A provided seed above the counter moves the
   counter past it. A gap left by a failed save is never filled.
1a. **Integration, `TreeWriteConcurrencyItSpec`** (owned by
   `PLAN-TREE-WRITE-CONCURRENCY.md` §6 item 4). This plan adds one assertion to
   its parallel-saves test: the two new leaves, on whichever side won, carry
   distinct seeds, and the counter stands at or above both.
2. **Unit, `RiskTreeServiceLiveSpec`.** A create without a seed entity id stores
   the workspace's. A create with one stores it. An update never changes it.
3. **Unit, `ScenarioMergeServiceSpec`.** The §1.2 example: main and the scenario
   each add a leaf from the same fork, and the preview answers `Clean` and the
   merge succeeds. Under SA-D-8 A: two provided equal seeds on the two branches
   make the preview report the conflict.
4. **Unit, `CachedResultResolverSpec`.** Two trees in one workspace with different
   seed entity ids and identical content get different figures.
5. **Integration, `SeedReproducibilityItSpec`.** A tree built over several saves,
   read back, and created in a second existing workspace with its seeds and seed
   entity id provided gives the same figures.
6. **Full suite**, after the `register_it_` cleanup:
   `sbt 'commonJVM/test; server/test'`, `sbt app/test`, `sbt serverIt/test`.
   Expected: every tier passes.

---

## 8. Terminology impact

None. `docs/user/TERMINOLOGY.md` has no entry for seed, seed entity id or
high-water mark. Its sentences about a leaf's random-number stream stay true.

---

## 9. Relation to other plans

- `PLAN-ERROR-WIRE-BOUNDARY.md` §17.2 names the merge field `SeedVarHighWater`.
  Under SA-D-5 B that field is removed, and §17.2 is swept when this plan is
  implemented. The complex review's finding 1 is closed by SA-D-1 for
  server-assigned seeds and by SA-D-8 for provided ones.
- `PLAN-TREE-WRITE-CONCURRENCY.md` owns the compare-and-set save this plan writes
  through, and its §9 sequences the two plans (§1.4 here).
- `PLAN-WORKSPACE-CACHE-RELEASE.md` is affected by §4.4.

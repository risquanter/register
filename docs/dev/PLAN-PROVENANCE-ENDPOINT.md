# Plan: Provenance Endpoint (Option 1)

**Status:** Approved — awaiting implementation. **Response shape needs revision
before implementing (DD-19, closed 2026-07-18 → (c)+(d) + A′):** `NodeProvenance`
loses `riskId` and provenance moves to `RiskResult` only, so the response becomes
an attributed `Map[NodeId, NodeProvenance]` assembled at the resolver edge
(structural walk over `RiskResultGroup.children`) — not the flat list keyed by
`riskId` this plan sketches. See `docs/scratch/milestone-2b-cache-and-decisions.md`
DD-19 (Closed table).  
**Date:** 2026-05-12  
**ADR reference:** ADR-003 (Provenance & Reproducibility)

---

## Objective

Surface `NodeProvenance` data through a dedicated, first-class HTTP endpoint, and remove the dead
`includeProvenance: Boolean` parameter that threads through 6 files without observable effect.

---

## Codebase Architecture Context

### Module structure

```
modules/
  common/   — shared domain types, HTTP endpoint definitions, Iron opaque types
  server/   — service implementations, controllers, simulation, cache, tests
```

### Key domain types (all in `modules/common`)

**`NodeProvenance`** — `com.risquanter.register.domain.data.NodeProvenance`  
`modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala`

Captures everything needed to reproduce a single leaf's simulation:
```scala
case class NodeProvenance(
  riskId: NodeId,
  entityId: Long,          // leaf.id.value.hashCode.toLong
  occurrenceVarId: Long,   // entityId.hashCode + 1000L
  lossVarId: Long,         // entityId.hashCode + 2000L
  globalSeed3: Long,       // from SimulationConfig (env: REGISTER_SEED3)
  globalSeed4: Long,       // from SimulationConfig (env: REGISTER_SEED4)
  distributionType: String,         // "lognormal" | "expert"
  distributionParams: DistributionParams,
  timestamp: Instant,
  simulationUtilVersion: String
)
// JsonCodec already derived — no new codec needed
```

`DistributionParams` is a sealed trait with two subtypes, also in `Provenance.scala`:
- `LognormalDistributionParams(minLoss, maxLoss, confidenceInterval)`
- `ExpertDistributionParams(percentiles, quantiles, terms)`

**`RiskResult`** — `com.risquanter.register.domain.data.RiskResult`  
`modules/common/src/main/scala/com/risquanter/register/domain/data/LossDistribution.scala`

```scala
case class RiskResult private (
  nodeId: NodeId,
  trialOutcomes: TrialOutcomes,   // nTrials + sparse Map[TrialId, Loss]
  provenances: List[NodeProvenance] = Nil
)
```

Portfolio aggregation is `RiskResultGroup(parentId, childResults*)`, which sums losses per-trial
via the `TrialOutcomes` monoid. Provenance is not accumulated during aggregation:
`RiskResultGroup.provenances` reads its children's lists at access time
(`children.flatMap(_.provenances)`). A portfolio result's `provenances` is therefore still the
union of all leaf provenances in its subtree — the guarantee this plan relies on is unchanged.

### Simulation and provenance capture

The Monte Carlo engine lives in `modules/server`:
- `Simulator.scala` — `performTrials`, `createSamplerFromLeaf` (returns `(RiskSampler, NodeProvenance)`)
- `RiskResultResolverLive.scala` — `simulateLeaf` always does:
  ```scala
  (sampler, provenance) <- Simulator.createSamplerFromLeaf(leaf, seed3, seed4)
  trials <- Simulator.performTrials(sampler, nTrials, parallelism)
  result = RiskResult(leaf.id, trials, List(provenance))  // always populated
  cache.put(leaf.id, result)
  ```

**There is no code path that stores a `RiskResult` in cache with `provenances = Nil`** for a real
leaf. `Nil` only appears in test data and the `withOutcomes` helper.

### `ensureCached` semantics

`RiskResultResolver.ensureCached(tree, nodeId)` is a **synchronous cache-aside read**:
- Cache hit → return cached `RiskResult` immediately (provenance already inside)
- Cache miss → simulate subtree inline → write to cache → return result (provenance inside)

Cache invalidation is handled separately by `InvalidationHandler`, which evicts a node and all
ancestors when the tree is mutated (PUT/PATCH). `ensureCached` has no staleness concept.

**The provenance endpoint calls `ensureCached` and reads `.provenances`.** Whether the cache was
warm from a prior `lec-multi` call or cold, the provenances are always present after `ensureCached`
returns.

### HTTP layer structure

```
WorkspaceAnalysisEndpoints (trait, modules/common)
  ↑ mixed in by
WorkspaceAnalysisController (class, modules/server)
  ↑ registered in
HttpApi.makeControllers → HttpApi.endpointsZIO
  ↑ consumed by
Application.scala
```

`WorkspaceAnalysisController` is **already** wired in `HttpApi.makeControllers`. No changes to
`HttpApi.scala` or `Application.scala` are needed to add a new route to this controller.

### Authorization pattern

Every analysis endpoint follows this exact pattern in the controller:
```scala
val someRoute: ServerEndpoint[Any, Task] = someEndpoint.serverLogic {
  case (maybeUserId, key, treeId, ...) =>
    (for
      userId <- userCtx.extract(maybeUserId)
      _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
      ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
      result <- riskTreeService.someMethod(ws.id, treeId, ...)
    yield result).either
}
```

The `.either` at the boundary converts `Task[A]` to `Task[Either[AppError, A]]`, following
ADR-010's hybrid error channel pattern.

### Service helpers available in `RiskTreeServiceLive`

`lookupNodeInTree(wsId, treeId, nodeId): Task[(RiskTree, RiskNode)]` — fetches the tree scoped to
the workspace and verifies the nodeId exists within it. Fails with `ValidationFailed(NOT_FOUND)`
on either miss. This is the correct helper to use; no new helpers are needed.

`traced(name)(body)` — wraps the body in an OTel span, records success/failure metrics, logs
unexpected errors. All service methods use this wrapper.

### Telemetry convention (ADR-002)

`tracing.setAttribute` calls go inside `traced(name) { ... }` immediately after the span opens.
Naming convention for attributes: snake_case, `_count` suffix for counts, `_id` suffix for ids.

### The dead `includeProvenance` parameter (current state)

The parameter exists in 6 files and is threaded through but does nothing:

| Layer | Location | Current dead code |
|---|---|---|
| Tapir endpoint definition | `WorkspaceAnalysisEndpoints.scala` | `query[Boolean]("includeProvenance").default(false)` on both endpoints |
| Service trait | `RiskTreeService.scala` | default param on `probOfExceedance` and `getLECCurvesMulti` |
| Service impl | `RiskTreeServiceLive.scala` | param on both overrides + one `tracing.setAttribute("include_provenance", ...)` per method |
| Controller | `WorkspaceAnalysisController.scala` | in destructure pattern + forwarded to service |
| Resolver trait | `RiskResultResolver.scala` | default param on `ensureCached`, `ensureCachedAll`, and their accessor objects |
| Resolver impl | `RiskResultResolverLive.scala` | param on both overrides + `simulateSubtree` + one `tracing.setAttribute("include_provenance", ...)` |

The parameter also appears in **tests** that will need updating (see Phase 1 test section).

---

## ADR-003 status

ADR-003 Section 3 has already been updated (before this plan) to describe the always-captured
reality. No further ADR update is needed as part of this plan.

---

## Phase 1 — Remove dead `includeProvenance` parameter

Pure cleanup. No behavioural change. All existing tests must remain green after this phase.

### Files to change

**`modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceAnalysisEndpoints.scala`**

Remove `query[Boolean]("includeProvenance").default(false)` from both endpoint definitions.

Current `getWorkspaceProbOfExceedanceEndpoint`:
```scala
.in(query[Long]("threshold"))
.in(query[Boolean]("includeProvenance").default(false))
.out(jsonBody[Double])
```
Becomes:
```scala
.in(query[Long]("threshold"))
.out(jsonBody[Double])
```

Current `getWorkspaceLECCurvesMultiEndpoint`:
```scala
.post
.in(query[Boolean]("includeProvenance").default(false))
.in(jsonBody[List[NodeId]].description("Array of node IDs"))
```
Becomes:
```scala
.post
.in(jsonBody[List[NodeId]].description("Array of node IDs"))
```

---

**`modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`**

Remove `includeProvenance: Boolean = false` from both method signatures and remove the
`(currently unused for this endpoint)` / `for reproducibility` docstring notes that referenced it.

---

**`modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`**

For `probOfExceedance`: remove `includeProvenance: Boolean = false` from the override signature,
remove `_ <- tracing.setAttribute("include_provenance", includeProvenance)`, remove the parameter
from the `resolver.ensureCached(tree, nodeId, includeProvenance)` call (becomes `resolver.ensureCached(tree, nodeId)`).

For `getLECCurvesMulti`: same removals.

---

**`modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisController.scala`**

`probOfExceedance` serverLogic destructure changes from:
```scala
case (maybeUserId, key, treeId, nodeId, threshold, includeProvenance) =>
  ...
  result <- riskTreeService.probOfExceedance(ws.id, treeId, nodeId, threshold, includeProvenance)
```
to:
```scala
case (maybeUserId, key, treeId, nodeId, threshold) =>
  ...
  result <- riskTreeService.probOfExceedance(ws.id, treeId, nodeId, threshold)
```

`getLECCurvesMulti` serverLogic destructure changes from:
```scala
case (maybeUserId, key, treeId, includeProvenance, nodeIds) =>
  ...
  result <- riskTreeService.getLECCurvesMulti(ws.id, treeId, nodeIds.toSet, includeProvenance)
```
to:
```scala
case (maybeUserId, key, treeId, nodeIds) =>
  ...
  result <- riskTreeService.getLECCurvesMulti(ws.id, treeId, nodeIds.toSet)
```

---

**`modules/server/src/main/scala/com/risquanter/register/services/cache/RiskResultResolver.scala`**

Remove `includeProvenance: Boolean = false` from `ensureCached`, `ensureCachedAll`, and their
companion accessor methods in `object RiskResultResolver`.

---

**`modules/server/src/main/scala/com/risquanter/register/services/cache/RiskResultResolverLive.scala`**

Remove `includeProvenance: Boolean = false` from `ensureCached` and `ensureCachedAll` overrides.
Remove `includeProvenance` from the `simulateSubtree(tree, nodeId, includeProvenance)` call and
`simulateSubtree` signature itself. Remove the `tracing.setAttribute("include_provenance", ...)` line.

---

### Tests to update (Phase 1)

**`modules/server/src/test/scala/com/risquanter/register/domain/data/ProvenanceSpec.scala`**

Two tests pass `includeProvenance` as a named argument to `resolver.ensureCached`:

1. Test `"ensureCached with includeProvenance=true captures metadata"` — remove the named argument;
   the test still verifies provenance is populated.

2. Test `"resolver always captures provenance regardless of includeProvenance flag"` — this test
   tested the now-deleted behaviour. The comment above it reads:
   > "Filtering (based on includeProvenance flag) happens at the service layer."
   
   Remove the test and the comment. The always-captured property is already implicit from every
   other provenance test. If the test body is worth keeping for cache-consistency documentation,
   rename it to `"resolver always captures provenance"` and remove the named argument.

---

### ADR compliance (Phase 1)

- ADR-001 (Iron types): No refined types involved. ✅
- ADR-002 (Logging): Removes one dead OTel attribute per method. ✅
- ADR-003 (Provenance): ADR-003 updated before this plan. ✅
- ADR-009 (Identity aggregation): No aggregation changes. ✅
- ADR-010 (Error handling): No error handling changes. ✅
- ADR-011 (Import conventions): No new imports. ✅

### Validation checklist (Phase 1)

- [ ] Compliant with ADR-001 through ADR-011 (see above)
- [ ] Code compiles
- [ ] All existing tests pass (zero failures)
- [ ] No `includeProvenance` parameter remains anywhere in main source
- [ ] ProvenanceSpec tests updated (no named `includeProvenance =` argument)

---

## Phase 2 — Dedicated provenance endpoint

### New HTTP endpoint

```
GET /w/{key}/risk-trees/{treeId}/nodes/{nodeId}/provenance
Authorization: Bearer token (same auth stack as existing analysis endpoints)
→ 200 OK   application/json   List[NodeProvenance]
→ 401/403  (same workspace capability-URL auth as probOfExceedance)
→ 404      ValidationFailed NOT_FOUND if treeId or nodeId not found in workspace
```

Response is `List[NodeProvenance]`:
- For a **leaf** nodeId: list of one entry
- For a **portfolio** nodeId: one entry per leaf descendant (full subtree audit record)
- `NodeProvenance` already has `JsonCodec` derivation in `Provenance.scala`; no new codec needed

### New Tapir endpoint definition

File: `modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceAnalysisEndpoints.scala`

Add import: `import com.risquanter.register.domain.data.NodeProvenance`

Add to the trait:
```scala
val getWorkspaceNodeProvenanceEndpoint =
  authedBaseEndpoint
    .tag("workspaces")
    .name("getWorkspaceNodeProvenance")
    .description("Get provenance metadata for a node (HDR seeds + distribution params for audit/reproducibility)")
    .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "provenance")
    .get
    .out(jsonBody[List[NodeProvenance]])
```

### New service method

File: `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala`

Add to the trait (in the LEC Query APIs section):
```scala
/** Get provenance metadata for a node.
  *
  * Returns one NodeProvenance per leaf in the subtree rooted at nodeId.
  * A leaf node returns a list of one entry; a portfolio node returns all leaf provenances.
  *
  * @param wsId   Workspace that owns the tree
  * @param treeId Risk tree identifier
  * @param nodeId Node identifier (leaf or portfolio)
  * @return List of NodeProvenance, one per simulated leaf in the subtree
  */
def getProvenance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId): Task[List[NodeProvenance]]
```

Add import at top: `import com.risquanter.register.domain.data.NodeProvenance`

### New service implementation

File: `modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala`

Add import: `import com.risquanter.register.domain.data.NodeProvenance`

Add override in the LEC Query APIs section:
```scala
override def getProvenance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId): Task[List[NodeProvenance]] =
  traced("getProvenance") {
    for
      _         <- tracing.setAttribute("tree_id", treeId.value)
      _         <- tracing.setAttribute("node_id", nodeId.value)
      (tree, _) <- lookupNodeInTree(wsId, treeId, nodeId)
      result    <- resolver.ensureCached(tree, nodeId)
      _         <- tracing.setAttribute("provenance_count", result.provenances.length.toLong)
    yield result.provenances
  }
```

`lookupNodeInTree` is an existing private helper in the same class. `traced` is an existing private
helper in the same class. `resolver.ensureCached` signature after Phase 1: `(RiskTree, NodeId): Task[LossDistribution]`
(`provenances` is a member of the `LossDistribution` base class, so the read below compiles for
both leaf and portfolio results).

### New controller route

File: `modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisController.scala`

Add import: `import com.risquanter.register.domain.data.NodeProvenance`

Add route (following the same auth pattern as the existing routes):
```scala
val nodeProvenance: ServerEndpoint[Any, Task] = getWorkspaceNodeProvenanceEndpoint.serverLogic {
  case (maybeUserId, key, treeId, nodeId) =>
    (for
      userId <- userCtx.extract(maybeUserId)
      _      <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
      ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
      result <- riskTreeService.getProvenance(ws.id, treeId, nodeId)
    yield result).either
}
```

Add `nodeProvenance` to `override val routes`:
```scala
override val routes: List[ServerEndpoint[Any, Task]] =
  List(
    probOfExceedance,
    getLECCurvesMulti,
    nodeProvenance
  )
```

### Wiring check

`WorkspaceAnalysisController` is already registered in `HttpApi.makeControllers`
(`modules/server/src/main/scala/com/risquanter/register/http/HttpApi.scala`). No changes to
`HttpApi.scala` or `Application.scala` are required.

### Tests (Phase 2)

**Unit — `modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala`**

This spec uses a shared `provide` block at suite level. The test layer already includes
`RiskResultResolverLive.layer` and `TreeCacheManager.layer` — simulation runs live in these tests.
The same `validRequest` / `hierarchicalRequest` pattern used by existing tests applies here.

Replace the orphaned `// Provenance Filtering (Service Layer)` comment block (lines ~223-225) with
a `// Provenance` section and add these tests:

```scala
// ========================================
// Provenance
// ========================================

test("getProvenance returns one entry for a leaf node") {
  val program = for
    tree       <- service(_.create(stubWsId, validRequest))  // single-leaf tree
    rootId     = tree.rootId
    provenances <- service(_.getProvenance(stubWsId, tree.id, rootId))
  yield provenances

  program.assert { ps =>
    ps.size == 1 && ps.head.riskId == tree.rootId  // riskId matches the leaf
  }
},

test("getProvenance returns one entry per leaf for a portfolio node") {
  val program = for
    tree       <- service(_.create(stubWsId, twoLeafRequest))  // two-leaf tree
    rootId     = tree.rootId
    provenances <- service(_.getProvenance(stubWsId, tree.id, rootId))
  yield provenances

  program.assert(_.size == 2)
},

test("getProvenance fails NOT_FOUND for unknown treeId") {
  service(_.getProvenance(stubWsId, treeId("no-such-tree"), nodeId("x"))).exit
    .map(exit => assertTrue(exit.isFailure))  // ValidationFailed NOT_FOUND
},

test("getProvenance fails NOT_FOUND for nodeId not in tree") {
  val program = for
    tree <- service(_.create(stubWsId, validRequest))
    exit <- service(_.getProvenance(stubWsId, tree.id, nodeId("not-in-tree"))).exit
  yield exit
  program.map(exit => assertTrue(exit.isFailure))
}
```

`twoLeafRequest` is a private val analogous to the existing `hierarchicalRequest` in the
`getLECCurvesMulti` tests — define it locally in the same suite.

The `treeId` and `nodeId` helpers are already imported from
`com.risquanter.register.testutil.TestHelpers`.

**Note:** The `tree.rootId` is the portfolio root in a hierarchical tree; `validRequest` creates a
single-leaf tree where the root IS the leaf. Verify assertions match the tree shape used.

### ADR compliance (Phase 2)

- ADR-001 (Iron types): `WorkspaceKeySecret`, `TreeId`, `NodeId` path params are all refined. ✅
- ADR-002 (Logging): `traced("getProvenance")` span; `tree_id`, `node_id`, `provenance_count` OTel attributes. ✅
- ADR-003 (Provenance): This phase is the primary implementation surface for ADR-003's audit intent. ✅
- ADR-009 (TrialOutcomes monoid): Provenances read from children by `RiskResultGroup.provenances` — untouched. ✅
- ADR-010 (Error handling): `.either` at controller boundary; `lookupNodeInTree` uses `ValidationFailed`. ✅
- ADR-011 (Import conventions): All new imports at top-level; no FQNs in logic. ✅

### Validation checklist (Phase 2)

- [ ] Compliant with ADR-001 through ADR-011 (see above)
- [ ] Code compiles
- [ ] All tests pass (existing + new)
- [ ] `getProvenance` has exactly one call site (`WorkspaceAnalysisController.nodeProvenance`)
- [ ] `getWorkspaceNodeProvenanceEndpoint` has exactly one call site (`WorkspaceAnalysisController`)
- [ ] Endpoint visible in Swagger at `/docs`
- [ ] At least one test exercises the HTTP layer (not only the service unit test) — confirmed by integration checklist
- [ ] `WorkspaceAnalysisController` wired in `HttpApi.makeControllers` — confirmed, no change needed

---

## Out of scope

- Provenance for `lec-multi` via `LECNodeCurve` enrichment — not in this plan
- Per-trial outcome replay — provenance captures parameters to reconstruct; it does not replay draws
- Provenance for portfolio nodes beyond their leaves' records — portfolios have no own
  provenance; their list is derived from children at read time (`RiskResultGroup.provenances`)

---

## Working instructions reminder (WORKING-INSTRUCTIONS.md)

- **Signature Echo Protocol** must be executed before writing any function body
- **Mandatory Review Halt** applies after every planning/review presentation
- **Comment style**: comments describe current state only — no references to migration history or plan phases
- **Functional composition**: no manual ADT unwrap/re-wrap; no multi-line inline lambdas; no public methods without call sites
- Tests accompany each phase (not deferred)

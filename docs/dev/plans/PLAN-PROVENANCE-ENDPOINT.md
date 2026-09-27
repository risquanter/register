# Plan: Provenance Endpoint

**Status:** Approved — awaiting implementation  
**ADR reference:** ADR-003 (Provenance and Reproducibility)  
**Inventory:** `docs/dev/plans/PLAN-PROVENANCE-ENDPOINT-INVENTORY.md`

---

## A naming distinction used throughout

Two different types are called `LossDistribution`, and this plan names both.

**old-LossDistribution** is the sealed class in
`modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala`,
with `RiskResult` for a simulated leaf and `RiskResultGroup` for an aggregated
portfolio. Phase 1 is written against it, and an unqualified mention in that
phase means this one.

**FB-F LossDistribution** is the flat valuation type ruled 2026-09-27 and
specified by
[`PLAN-FBF-VALUATION-TRANSPLANT.md`](PLAN-FBF-VALUATION-TRANSPLANT.md): one
concrete type carrying `nodeId`, `trials`, `source`, `applied` and
`provenance`, with no subtypes. It was chosen from four candidates written out
and run as code in `docs/scratch/valuation-prototypes/`.

Phase 1 is independent of which one is in the tree. **Phase 2 is written against
FB-F** and lands after it, because a leaf's record is read off the value's own
`provenance` field. The two plans are aligned on that single point and on
nothing else: this plan owns the endpoint, that one owns the field.

---

## Objective

Give provenance a first-class read path, and remove the `includeProvenance`
parameter, which is accepted at the HTTP boundary and threaded through four
layers without affecting any response.

ADR-003 Decision 4 states that provenance is surfaced through a dedicated audit
endpoint rather than embedded in curve or exceedance responses. This plan builds
that endpoint and deletes the parameter that stands in its place.

---

## Current state

### Provenance is captured on every leaf and never returned

`CachedResultResolverLive` builds a `NodeProvenance` for every leaf it simulates
and stores it beside the outcomes in the cache value:

```scala
final case class LeafSimResult(
  outcomes: TrialOutcomes,
  provenance: NodeProvenance
)
```

`NodeProvenance` carries no node identity:

```scala
case class NodeProvenance(
  entityId: Long,
  occurrenceVarId: Long,
  lossVarId: Long,
  globalSeed3: Long,
  globalSeed4: Long,
  distributionType: String,
  distributionParams: DistributionParams,
  timestamp: Instant,
  metalogDistributionVersion: String
)
```

Identity is attached at the read edge instead. Whether the cache hits or misses,
the resolver labels the record with the requested node:

```scala
// hit
RiskResult.fromTrialOutcomes(leaf.id, content.outcomes, List(content.provenance))
// miss
RiskResult.fromTrialOutcomes(leaf.id, outcomes, List(provenance))
```

A portfolio holds no record of its own. No response type in
`modules/common/.../http/responses` contains provenance, and no route serves it.

### `includeProvenance` affects nothing

The parameter is declared on both analysis endpoints
(`WorkspaceAnalysisEndpoints`), destructured in `WorkspaceAnalysisController`,
passed to `RiskTreeService.probOfExceedance` and
`RiskTreeService.getLECCurvesMulti`, and forwarded to
`CachedResultResolver.ensureCached` / `ensureCachedAll`. Its only use at any of
those sites is:

```scala
_ <- tracing.setAttribute("include_provenance", includeProvenance)
```

There is no branch on it and no filtering anywhere. `ProvenanceSpec` carries a
comment stating that filtering happens at the service layer; no such filtering
exists.

### What the tree already supplies

`RiskTree` carries a `TreeIndex`, rebuilt from the flat node list whenever a
tree is decoded. It answers both structural questions this endpoint needs:

```scala
def descendants(nodeId: NodeId): Set[NodeId]   // the node and everything beneath it
def leafIds: Set[NodeId]                       // every node with no children
```

`leafIds` is exact rather than approximate here, because a portfolio cannot be
childless. `RiskPortfolio` enforces `require(childIds != null && childIds.nonEmpty, …)`,
and `RiskTreeRequests` rejects an empty one at the boundary with
`EMPTY_COLLECTION`.

`CachedResultResolver.ensureCachedAll` already takes an arbitrary set of node
identifiers and resolves each one independently, returning
`Map[NodeId, LossDistribution]`.

---

## Phase 1 — Delete the `includeProvenance` parameter

Behaviour-preserving. No response changes.

### Signatures after the change

```scala
// modules/common/.../http/endpoints/WorkspaceAnalysisEndpoints.scala
// drop `.in(query[Boolean]("includeProvenance").default(false))` from both

// modules/server/.../services/RiskTreeService.scala
def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long,
                     seedEntityId: SeedEntityId.SeedEntityId, rev: Revision): Task[Double]

def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId],
                      seedEntityId: SeedEntityId.SeedEntityId, rev: Revision,
                      omitAbsent: Boolean): Task[Map[NodeId, LECNodeCurve]]

// modules/server/.../services/cache/CachedResultResolver.scala (trait + accessors)
def ensureCached(tree: RiskTree, nodeId: NodeId,
                 seedEntityId: SeedEntityId.SeedEntityId,
                 selection: MitigationSelection = MitigationSelection.Inherent,
                 resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty): Task[LossDistribution]

def ensureCachedAll(tree: RiskTree, nodeIds: Set[NodeId],
                    seedEntityId: SeedEntityId.SeedEntityId,
                    selection: MitigationSelection = MitigationSelection.Inherent,
                    resolvedScopes: Map[MitigationId, Set[NodeId]] = Map.empty): Task[Map[NodeId, LossDistribution]]
```

Also removed: the two `tracing.setAttribute("include_provenance", …)` calls in
`RiskTreeServiceLive`, the one in `CachedResultResolverLive`, and the
`@param includeProvenance` scaladoc lines on all four methods.

### Callers to update

`WorkspaceAnalysisController` — both `serverLogic` destructuring patterns lose
one element and both service calls lose one argument. The surrounding
`ActiveBranch.resolve` / `Revision` derivation is unchanged.

### Tests to update

- `CascadeTestStubs` — two stub signatures.
- `ProvenanceSpec` — five `includeProvenance = …` named arguments. The test
  `"resolver always captures provenance regardless of includeProvenance flag"`
  becomes `"resolver always captures provenance"`; its preceding comment about
  service-layer filtering is deleted, being untrue.

---

## Phase 2 — The provenance endpoint

### Route

```
GET /w/{key}/risk-trees/{treeId}/nodes/{nodeId}/provenance
X-Branch: <branch name>
?at=<commit hash>                       optional point-in-time pin
→ 200  application/json  Map[NodeId, NodeProvenance]
→ 404  treeId or nodeId absent at this revision
```

A leaf returns one entry. A portfolio returns one entry per leaf descendant,
keyed by that leaf's id. Both `JsonFieldEncoder[NodeId]` and
`JsonFieldDecoder[NodeId]` already exist in `OpaqueTypes`, and
`Map[NodeId, LECNodeCurve]` is the existing precedent for this response shape.

The response is a map and therefore unordered. Node identity is the only
ordering a client needs, since each key names the leaf the record belongs to.

### Tapir endpoint

```scala
val getWorkspaceNodeProvenanceEndpoint =
  authedBaseEndpoint
    .tag("workspaces")
    .name("getWorkspaceNodeProvenance")
    .description("Provenance for every simulated leaf in a node's subtree: HDR stream identities and distribution parameters; optional `at` commit pin")
    .in("w" / path[WorkspaceKeySecret]("key") / "risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "provenance")
    .get
    .in(branchHeader)
    .in(query[Option[CommitHash]]("at").description("Commit pin for point-in-time read — absent = branch head."))
    .out(jsonBody[Map[NodeId, NodeProvenance]])
```

Requires `import com.risquanter.register.domain.data.NodeProvenance`.

### Service method

```scala
// RiskTreeService
def getProvenance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId,
                  seedEntityId: SeedEntityId.SeedEntityId,
                  rev: Revision): Task[Map[NodeId, NodeProvenance]]
```

```scala
// RiskTreeServiceLive
override def getProvenance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId,
                           seedEntityId: SeedEntityId.SeedEntityId,
                           rev: Revision): Task[Map[NodeId, NodeProvenance]] =
  traced("getProvenance") {
    for
      _            <- tracing.setAttribute("tree_id", treeId.value)
      _            <- tracing.setAttribute("node_id", nodeId.value)
      (tree, _, _) <- lookupNodeInTree(wsId, treeId, nodeId, rev)
      leaves        = tree.index.descendants(nodeId).intersect(tree.index.leafIds)
      results      <- resolver.ensureCachedAll(tree, leaves, seedEntityId)
      attributed    = LossDistribution.leafProvenances(results)
      _            <- tracing.setAttribute("provenance_count", attributed.size.toLong)
    yield attributed
  }
```

The subtree's leaves come from the tree index, and each one is resolved on its
own. Resolving the leaves directly rather than their common ancestor avoids
combining figures at every intermediate portfolio, which this endpoint has no
use for.

### Reading the records off the results

`descendantProvenances` in `CachedResultResolverLive` was private, served the
portfolio-collapse path, and discarded the node id; the FB-F transplant deletes
it along with that path. So the endpoint needs its own function, and it lives on
the `LossDistribution` companion:

```scala
/** Each simulated leaf's provenance record, keyed by the node that carries it.
  *
  * A leaf resolution always carries one record; a portfolio carries none, so a
  * portfolio entry contributes nothing. The caller supplies the leaf
  * set from `TreeIndex` and resolves each leaf under its own identifier, so
  * nothing here walks a value structure.
  */
def leafProvenances(results: Map[NodeId, LossDistribution]): Map[NodeId, NodeProvenance] =
  results.iterator.flatMap { case (id, d) => d.provenance.map(id -> _) }.toMap
```

No type test: under FB-F every value carries its own record directly, and a
portfolio's is absent. One record per key holds because the resolver's leaf
path always produces `List(provenance)`, on both the cache hit and the cache
miss.

### Controller route

```scala
val nodeProvenance: ServerEndpoint[Any, Task] = getWorkspaceNodeProvenanceEndpoint.serverLogic {
  case (maybeUserId, key, treeId, nodeId, activeBranch, at) =>
    (for
      userId <- userCtx.requireAuthenticated(maybeUserId)
      given Checked[Permission] <- authzService.check(userId, Permission.AnalyzeRun, ResourceRef(ResourceType.RiskTree, treeId.toSafeId))
      ws     <- workspaceStore.resolveTreeWorkspace(key, treeId)
      branch <- ActiveBranch.resolve(ws.id, activeBranch)
      rev     = at.fold[Revision](Revision.Head(branch))(Revision.At(_))
      result <- riskTreeService.getProvenance(ws.id, treeId, nodeId, ws.seedEntityId, rev)
    yield result).either
}
```

Added to `override val routes`. `WorkspaceAnalysisController` is already
registered in `HttpApi.makeControllers`; neither `HttpApi` nor `Application`
changes.

---

## Interaction with the valuation design

[`PLAN-FBF-VALUATION-TRANSPLANT.md`](PLAN-FBF-VALUATION-TRANSPLANT.md) replaces
old-LossDistribution with a flat type carrying `provenance` as an ordinary
field. **Ruled 2026-09-27, and Phase 2 above is written for it** — that is why
`leafProvenances` has no type test.

**What the two plans share, and it is one sentence.** The public
`LossDistribution` carries `provenance: Option[NodeProvenance]`: one record for a
simulated leaf, absent for a portfolio, which draws nothing of its own
(ADR-003 §2). That field is the transplant plan's to define and this plan's
to read. Nothing else crosses between them.

**What this plan's design does NOT depend on**, stated so it is not reopened
during implementation:

- **The route is `TreeIndex`, not traversal.** The subtree's leaves come from
  `tree.index.descendants(nodeId).intersect(tree.index.leafIds)` and each leaf is
  resolved under its own identifier. No code walks from one resolved value to
  another. This held under old-LossDistribution, it holds under FB-F, and it
  would hold under any of the four candidate shapes.
- **Nothing aggregates provenance upward.** A portfolio's records are its
  descendants' records read individually, never a merge. Resolving the leaves
  rather than their common ancestor also avoids combining figures at every
  intermediate portfolio, which this endpoint has no use for.
- **The wire drops it.** No curve or exceedance response carries provenance;
  those are built from the figures. This endpoint is the only reader, which is
  ADR-003 Decision 4's "dedicated audit endpoint".

The route, the endpoint definition, the response type, the service method
signature, the controller and every test bullet are unaffected by the
transplant.

**Sequencing.** Phase 1 can land before or after the transplant. Phase 2 lands
after it.

---

## ADR alignment

| ADR | Bearing | Status |
|---|---|---|
| ADR-003 | Decision 4 (§4) names a dedicated audit endpoint as the surface for provenance; this plan builds it | Compliant on the decision — see the flagged deviation below on §4's worked example |
| ADR-001 | `WorkspaceKeySecret`, `TreeId`, `NodeId`, `CommitHash` path and query params are refined; no raw primitive carries a domain value | Compliant |
| ADR-002 | One span per call; `tree_id`, `node_id`, `provenance_count` attributes; net removal of three dead attributes | Compliant |
| ADR-009 | §5 requires that a record carry no node identity of its own, that the supertype expose no flat provenance list, and that nothing be merged onto an aggregate. All three hold: `NodeProvenance` is unchanged, no flat list is added, and a portfolio's own entry contributes nothing | Compliant on the rule — see the flagged deviation below on §5's worked example |
| ADR-010 | `.either` at the controller boundary; `lookupNodeInTree` fails with `ValidationFailed(NOT_FOUND)` | Compliant |
| ADR-014 | Reads go through the resolver, so a warm cache serves them | Compliant |
| ADR-030 | The controller binds `given Checked[Permission]` before the service call, matching both existing routes | Compliant |
| ADR-036 | The response carries `NodeId` keys only, which §4 names client-facing; no `WorkspaceId` | Compliant |

### Flagged deviation — two ADRs publish an example this plan does not follow

ADR-003 §4 and ADR-009 §5 both give the same worked example for how a record is
attributed to a node:

```scala
group.children.collect { case r: RiskResult => r.nodeId -> r.provenances }
```

ADR-003 §4 states it as prose as well: "Its records are read by walking its
children and pairing each child's `nodeId` with that child's records, giving the
union of all leaf provenances in the subtree, in child order."

This plan attributes records by resolving each leaf under its own identifier,
taking the set of leaves from `TreeIndex`. It produces the same records with the
same node keys. It differs in two respects: it does not descend through
`RiskResultGroup.children`, and the result is a map, so there is no child order.
Both ADR passages need amending to describe attribution by node identifier and
to drop the ordering claim.

Two things are worth separating here. The **rule** in ADR-009 §5 — that
`NodeProvenance` carries no identity of its own, that the sealed supertype
exposes no flat provenance list, and that nothing is merged onto an aggregate —
is satisfied exactly. Only the **example** of how identity is supplied differs.

The ordering claim is inaccurate about the endpoint independently of this, since
the response type is an unordered map.

**Decision required before implementation:** open decision 1.

---

## Open decisions

1. **Amending ADR-003 §4 and ADR-009 §5.** Both publish the child-walk example
   and ADR-003 §4 adds the "in child order" claim. Either amend both to describe
   attribution by node identifier and drop the ordering claim, or change this
   plan to descend through `RiskResultGroup.children` instead. The first keeps
   the endpoint resolving only the leaves it needs; the second keeps the ADRs
   untouched at the cost of combining figures at every intermediate portfolio
   for a result that discards them.

2. **Whether the endpoint accepts a mitigation selection.** It currently takes
   none and resolves the inherent valuation. Accepting one is technically
   unobstructed: only leaves are resolved, a leaf never collapses, and a
   parameter-stage mitigation changes the leaf's content hash, so the record
   returned would describe the simulation that actually ran under that
   selection. Whether the audit surface should offer that reading at all is a
   product question, not a technical one.

---

## Verification plan

Commands that must be green:

```bash
sbt 'commonJVM/test; server/test'
sbt app/test
sbt "serverIt/test"
```

Tests to add:

- `RiskTreeServiceLiveSpec` — a leaf node returns one entry keyed by that leaf;
  a portfolio root returns one entry per leaf descendant; a mid-tree portfolio
  returns its own leaf descendants and no others; an unknown `treeId` fails; a
  `nodeId` absent from the tree fails.
- `ProvenanceSpec` — `leafProvenances` pairs each record with the node that
  holds it, and returns nothing for a portfolio entry.
- `TreeIndexSpec` — `descendants(nodeId).intersect(leafIds)` is exactly the leaf
  set beneath a node, for a leaf, for a mid-tree portfolio, and for the root.
- An integration test exercising the route over HTTP, matching how the other
  analysis endpoints are covered.

Checks:

- No `includeProvenance` remains in `src/main`.
- `getProvenance` and `getWorkspaceNodeProvenanceEndpoint` each have exactly one
  call site.
- The endpoint appears in the generated OpenAPI at `/docs`.

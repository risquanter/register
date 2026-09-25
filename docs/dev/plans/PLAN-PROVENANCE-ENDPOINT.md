# Plan: Provenance Endpoint

**Status:** Approved — awaiting implementation  
**ADR reference:** ADR-003 (Provenance and Reproducibility)  
**Inventory:** `docs/dev/plans/PLAN-PROVENANCE-ENDPOINT-INVENTORY.md`

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

### Provenance is always captured, and never returned

`CachedResultResolverLive` builds a `NodeProvenance` for every leaf it
simulates and stores it beside the outcomes in the cache value:

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

Attribution is therefore structural: a record belongs to the `RiskResult` that
holds it, and that result carries the `nodeId`. No response type in
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

### Where the live derivation sits

`CachedResultResolverLive.descendantProvenances` walks a `LossDistribution` and
returns every descendant leaf's records as a flat `List[NodeProvenance]`, so
attribution is discarded. It is private and serves the portfolio-collapse path,
not a read API.

`LossDistribution` exposes only `nodeId` and `trialOutcomes`; `provenances` is a
member of `RiskResult` alone. A walk that keeps attribution must match on the
subtype.

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
      _              <- tracing.setAttribute("tree_id", treeId.value)
      _              <- tracing.setAttribute("node_id", nodeId.value)
      (tree, _, _)   <- lookupNodeInTree(wsId, treeId, nodeId, rev)
      dist           <- resolver.ensureCached(tree, nodeId, seedEntityId)
      attributed      = LossDistribution.attributedProvenances(dist)
      _              <- tracing.setAttribute("provenance_count", attributed.size.toLong)
    yield attributed
  }
```

### Attributed walk

`descendantProvenances` discards the node id, so the walk the endpoint needs is
a new named function on the `LossDistribution` companion, where both subtypes
are visible:

```scala
/** Every simulated leaf's provenance in this distribution, keyed by the node
  * that carries it. */
def attributedProvenances(dist: LossDistribution): Map[NodeId, NodeProvenance] =
  dist match
    case r: RiskResult      => r.provenances.map(r.nodeId -> _).toMap
    case g: RiskResultGroup => g.children.map(attributedProvenances).reduceOption(_ ++ _).getOrElse(Map.empty)
```

Reading the inherent valuation is what makes the `Map` shape total: with
`MitigationSelection.Inherent` no portfolio collapses into a `RiskResult`, so
each key is a distinct leaf holding exactly one record.

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

## ADR alignment

| ADR | Bearing | Status |
|---|---|---|
| ADR-003 | The endpoint is §3's dedicated audit surface | Compliant — this plan implements it |
| ADR-001 | `WorkspaceKeySecret`, `TreeId`, `NodeId`, `CommitHash` path and query params are refined; no raw primitive carries a domain value | Compliant |
| ADR-002 | One span per call; `tree_id`, `node_id`, `provenance_count` attributes; net removal of three dead attributes | Compliant |
| ADR-009 | Attribution is read through the structure; no flat list on the supertype, nothing merged onto the aggregate | Compliant |
| ADR-010 | `.either` at the controller boundary; `lookupNodeInTree` fails with `ValidationFailed(NOT_FOUND)` | Compliant |
| ADR-014 | Reads go through the resolver, so a warm cache serves them | Compliant |
| ADR-030 | The controller binds `given Checked[Permission]` before the service call, matching both existing routes | Compliant |
| ADR-036 | The response carries `NodeId` keys only, which §4 names client-facing; no `WorkspaceId` | Compliant |

---

## Open decisions

1. **Mitigated provenance.** The endpoint reads the inherent valuation and takes
   no `selection`. Under a mitigated selection a collapsed transformed portfolio
   carries its descendants' records under its own id, so `Map[NodeId, NodeProvenance]`
   stops being one-record-per-key. Whether the endpoint should ever accept a
   selection — and what shape it would return — is not decided here.

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
  a portfolio root returns one entry per leaf descendant; an unknown `treeId`
  fails; a `nodeId` absent from the tree fails.
- `ProvenanceSpec` — `attributedProvenances` pairs each record with the node
  that holds it, and a portfolio's key set equals its leaf-descendant set.
- An integration test exercising the route over HTTP, matching how the other
  analysis endpoints are covered.

Checks:

- No `includeProvenance` remains in `src/main`.
- `getProvenance` and `getWorkspaceNodeProvenanceEndpoint` each have exactly one
  call site.
- The endpoint appears in the generated OpenAPI at `/docs`.

# ADR-004a: Persistence Architecture (SSE Variant)

**Status:** Accepted
**Date:** 2026-07-20
**Tags:** persistence, irmin, graphql, sse, architecture

> Proposed 2026-01-16; accepted 2026-07-20 with text aligned to the implemented
> system. Where implementation refined a proposed mechanism without changing the
> decision, the refinement is marked **[Refined → DD-n]**, pointing into the
> milestone-2b decision log (`docs/scratch/milestone-2b-cache-and-decisions.md`).
> Tree terminology and the exact domain-to-storage mapping:
> [ADR-004a-appendix](ADR-004a-appendix.md). ADR-004b (WebSocket variant) is
> unadopted; its trigger would be multi-user collaborative editing.

---

## Context

- Versioned persistence (branches, history, time travel) is **non-negotiable** for scenario analysis
- Computation and persistence must be **separated** (ZIO/Scala computes; Irmin/OCaml stores)
- Two communication channels are required: Irmin↔ZIO (internal) and ZIO↔Browser (external)
- Server→client push needs only **unidirectional** streaming; bidirectional adds cost without a collaborative-editing requirement

---

## Decision

### 1. Irmin as Versioned Persistence Layer

Irmin stores tree structure with Git-like semantics; one whole-node JSON blob
per path (`RiskTreeRepositoryIrmin`; mapping details in the appendix §2):

```
workspaces/<wsId>/risk-trees/<treeId>/nodes/<nodeId>  → RiskLeaf | RiskPortfolio JSON
```

Scenarios are **Irmin branches**, not paths: `scenarios.<wsId>.<name-slug>`
**[Refined → DD-5, DD-21]** (Irmin rejects `/` in branch names). Absent branch
selector = `main` **[Refined → DD-8]**.

### 2. GraphQL for Irmin↔ZIO (Channel A) — Single Writer

`IrminClient` / `IrminQueries`: queries for reads, `set_tree` mutations for
writes, branch ops (create/delete/CAS) for scenarios. Each user action produces
exactly **one** commit — Irmin's log *is* the user-visible history, saves are
atomic **[Refined → DD-7]**.

ZIO is the **only writer** to Irmin, so change detection happens at the
mutation path, not via watch subscriptions **[Refined]**:

```scala
// InvalidationHandler, in the same request that writes:
// diff(oldTree, newTree) → affected nodeIds → SSE publish
// (a watch channel would only echo ZIO's own writes back to it)
```

### 3. SSE for ZIO→Browser Push (Channel B)

Per-tree fan-out (`SSEHub` ZIO Hub) behind a Tapir stream endpoint
(`SSEController`):

```
GET /w/{key}/events/tree/{treeId}        text/event-stream, workspace-scoped

cache_invalidated   nodes whose figures changed — re-fetch   (live publisher)
node_changed        tree structure modified                  (defined)
lec_updated         recomputed quantiles push                (defined, unpublished)
connection_status   connect / heartbeat lifecycle
```

SSE is a **notification** channel, not a data channel: clients re-fetch over
HTTP, which carries auth and `X-Active-Branch`. Events will carry a branch tag
in the payload using the wire encoding — `Option[ScenarioName]`, absent = main
(DD-8 symmetry) — not `BranchRef`, which embeds `WorkspaceId` and never
crosses the client boundary; subscription stays tree-scoped — one stream hears
all branches, as the compare view requires, and `EventSource` cannot set
headers **[Refined → DD-22; decided 2026-07-19, payload tag not yet
implemented as of 2026-07-23]**.

### 4. Content-Addressed ZIO-Side Result Cache

Computed results live in ZIO, never in Irmin. One `ContentCache` per workspace
**[Refined → DD-15…DD-19]**:

```scala
// key:   ContentHash of the simulation-relevant leaf projection (DD-16)
// value: TrialOutcomes + content-only provenance, no node identity (DD-18/19)
// no invalidation: a changed leaf IS a different key; stale entries
//   become orphans for the EvictionStrategy
// portfolios re-aggregate from child results on read — never cached (DD-15)
```

### 5. Data Flow

```
Browser edit → REST mutation (X-Active-Branch)
  → ZIO writes ONE Irmin commit (set_tree)
  → InvalidationHandler diffs old vs new tree in-request
  → SSE cache_invalidated {nodeIds, treeId, branch}
  → Browser re-fetches affected figures over HTTP
      (ContentCache hit = replay, miss = re-simulate)
  → chart updates
```

---

## Code Smells

### ❌ A Second Writer to Irmin

```scala
// BAD: any component writing to Irmin outside the repository layer
//      (breaks §2's in-request change detection — SSE goes silent)
irminGraphQL.mutate(...)             // from anywhere else

// GOOD: all writes flow through the single path
RiskTreeRepositoryIrmin → IrminClient.setTree(...)
```

### ❌ Data-Carrying Push as Source of Truth

```scala
// BAD: client renders figures from the SSE payload (bypasses auth + branch header)
es.onmessage = e => renderChart(e.data.quantiles)

// GOOD: notification only; re-fetch over HTTP
es.onmessage = e => refetch(e.data.nodeIds)   // request carries X-Active-Branch
```

### ❌ Computation in Irmin Layer

```scala
// BAD: OCaml computes; Irmin stores results
//   workspaces/.../nodes/<id>/lec_cache

// GOOD: Irmin stores parameters only; ZIO computes and caches
```

---

## Implementation

| Location | Pattern |
|----------|---------|
| `IrminClient` / `IrminClientLive` / `IrminQueries` | GraphQL queries, `set_tree` mutations, branch ops |
| `RiskTreeRepositoryIrmin` | Domain-to-storage mapping, branch-parameterized methods |
| `InvalidationHandler` | In-request tree diff → SSE publish |
| `ContentCache` | Per-workspace content-addressed result cache |
| `SSEHub` | Per-tree ZIO Hub fan-out |
| `SSEController` / `SSEEndpoints` | Tapir `streamBody(ZioStreams)` as `text/event-stream` |

---

## References

- [Irmin Documentation](https://irmin.org/)
- [Tapir Streaming Support](https://tapir.softwaremill.com/en/latest/endpoint/streaming.html)
- [ADR-004a-appendix](ADR-004a-appendix.md) — tree terminology, domain-to-storage mapping
- `docs/scratch/milestone-2b-cache-and-decisions.md` — decision log (DD-5, DD-7, DD-8, DD-15…DD-19, DD-21, DD-22)

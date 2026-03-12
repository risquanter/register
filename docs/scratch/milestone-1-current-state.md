# Milestone 1: Current State Assessment — Scenario Branching & Time Travel

> Throwaway document. Establishes baseline understanding before design work.

## 1. What Exists Today (Code Review)

### 1.1 Irmin Layer

**IrminClient trait** — 7 operations:
| Method | Branch-aware? | Notes |
|--------|--------------|-------|
| `get(path)` | ❌ hardcoded `main` | Response: `response.data.flatMap(_.main).flatMap(_.tree.get)` |
| `set(path, value, message)` | ❌ no branch param | Mutation omits `branch:` arg → Irmin defaults to `main` |
| `remove(path, message)` | ❌ no branch param | Same |
| `list(prefix)` | ❌ hardcoded `main` | `response.data.flatMap(_.main).flatMap(_.tree.get_tree)` |
| `branches` | ✅ | Lists all branch names |
| `mainBranch` | ✅ | Gets head commit of main |
| `healthCheck` | N/A | Uses `listBranches` |

**IrminQueries** — already has `getValueFromBranch(branch, path)` but it's **unused** by IrminClientLive.

**IrminConfig** — has `branch: String = "main"` field but it's **unused** in actual queries. All queries hardcode `main`.

**Response types** — `GetValueResponse` hardcodes to `MainBranchData` structure (`.main.tree.get`). A branch-aware read needs a different response type that uses `.branch(name:).tree.get` instead.

### 1.2 Irmin GraphQL Schema (available operations)

From `dev/irmin-schema.graphql`, these are the operations available but NOT yet wrapped by our client:

| GraphQL Operation | Type | Purpose for Us |
|-------------------|------|----------------|
| `query { branch(name:) }` | query | Read from specific branch |
| `query { commit(hash:) }` | query | Read tree at specific commit (time travel) |
| `Branch.head` | field | Get head commit of any branch |
| `Branch.last_modified(n, depth, path)` | field | Commit log for a path (time travel UI) |
| `Branch.lcas(commit:)` | field | Lowest common ancestor (3-way merge) |
| `mutation { set(..., branch:) }` | mutation | Write to specific branch |
| `mutation { remove(..., branch:) }` | mutation | Remove on specific branch |
| `mutation { merge_with_branch(from:, branch:) }` | mutation | Merge scenario→main |
| `mutation { revert(commit:, branch:) }` | mutation | Revert to historical state |
| `mutation { test_and_set(...) }` | mutation | CAS for optimistic concurrency |
| `subscription { watch(path, branch:) }` | subscription | Watch for changes (needs WebSocket) |

### 1.3 Repository Layer

**RiskTreeRepositoryIrmin** — all operations use `IrminClient.get/set/remove/list` directly. No branch concept. Path convention: `risk-trees/{treeId}/nodes/{nodeId}` and `risk-trees/{treeId}/meta`.

**Key observation:** The repository constructs `IrminPath` values directly and calls `irmin.get(path)`. To support branches, either:
- (a) The repository gains a branch parameter, or
- (b) The `IrminClient.get` method gains a branch parameter, or
- (c) We introduce a branch-aware client wrapper that delegates

### 1.4 Cache Layer

**RiskResultCache** — keyed by `NodeId` only. No branch dimension. Per-tree isolation via `TreeCacheManager`.

**TreeCacheManager** — keyed by `TreeId`. Creates one cache per tree. Invalidation uses `TreeIndex` ancestor walk.

**RiskResultResolver** — takes `RiskTree` parameter → uses `tree.id` to get the right cache. Simulation uses `SimulationConfig` (service-wide).

**Key observation:** Cache key is `(TreeId, NodeId)`. With branches, the same `TreeId` exists on multiple branches but the tree **content** may differ. Options:
1. **Per-branch caches** — key becomes `(BranchRef, TreeId, NodeId)`. Memory cost multiplied by active branches.
2. **Content-addressed keys** — key becomes `(ContentHash, NodeId)`. Unchanged subtrees share cache. Elegant but complex.
3. **Clear-on-switch** — single cache per tree, cleared when switching branches. Simple but loses cached results on back-and-forth.

### 1.5 Service Layer

**RiskTreeService** — CRUD + LEC query APIs. All operations take `TreeId`. No branch concept. `RiskResultResolver.ensureCached(tree, nodeId)` is the core primitive.

**InvalidationHandler** — bridges mutations → cache invalidation → SSE. Triggered by `RiskTreeServiceLive.update` and `.delete`. No branch awareness.

### 1.6 HTTP Layer

**WorkspaceController** — all tree operations under `/w/{key}/...`. Authorization via `AuthorizationService`. No scenario/branch endpoints exist.

**API surface today:**
- `POST /workspaces` (bootstrap)
- `GET/POST/PUT/DELETE /w/{key}/risk-trees/...` (CRUD)
- `GET /w/{key}/risk-trees/{id}/lec/...` (LEC queries)
- `POST /w/{key}/risk-trees/{id}/invalidate/{nodeId}` (cache)
- `GET /sse/{treeId}` (server-sent events)

### 1.7 Frontend

**NavigationState** — two sections: `Design` and `Analyze`. No "Scenarios" section.

**TreeViewState** — owns `selectedTreeId`, `selectedTree`, `expandedNodes`, `selectedNodeId`, `chartState`. No branch concept.

**WorkspaceState** — owns workspace key lifecycle. No branch/scenario awareness.

**Views:** `DesignView`, `AnalyzeView`, `TreeBuilderView`, `TreeListView`, `TreeDetailView`, `LECChartView`. All operate on a single tree version.

---

## 2. Irmin Schema Capabilities vs Our Client Coverage

```
What Irmin offers        What we use         Gap
─────────────────        ───────────         ───
branch(name:)            ✗                   Read from named branch
commit(hash:)            ✗                   Read from historical commit
set(branch:)             ✗ (main only)       Write to named branch
remove(branch:)          ✗ (main only)       Remove on named branch
merge_with_branch        ✗                   Merge branches
revert(commit:, branch:) ✗                   Revert to historical state
test_and_set             ✗                   CAS (optimistic concurrency)
Branch.last_modified     ✗                   Commit history/log
Branch.lcas              ✗                   Common ancestor (3-way merge)
watch(branch:)           ✗                   Real-time notifications
branches                 ✅ used             List branches
main                     ✅ used             Read from main
```

---

## 3. ADR Constraints Summary (Relevant to These Features)

1. **Iron validation at boundary** (ADR-001): New types `BranchRef`, `ScenarioId`, `ScenarioName`, `CommitHash` need Iron-refined wrappers with Tapir codecs.

2. **Deterministic simulation** (ADR-003): Same tree config + same `SimulationConfig` = identical results. Time travel is free — any historical commit reproduces exactly.

3. **Irmin stores params only, never results** (ADR-004a, ADR-015): Cache is ephemeral. Branch switch = load tree from new branch + re-populate cache on demand.

4. **O(depth) invalidation** (ADR-005, ADR-014): Branch switch invalidation should not be O(n). Smart cache strategies needed.

5. **Content-addressable cache sharing** (ADR-005): Unchanged subtrees across branches can share cached results. This is the ideal but needs `CacheKey(contentHash, nodeId)`.

6. **`nTrials` compatibility** (ADR-009): Cross-branch comparison requires matching `SimulationConfig`. Since config is service-wide, this is satisfied automatically today.

7. **Fork point tracking** (ADR-007): `createdFrom: CommitHash` mandatory for 3-way merge.

8. **TreeOp invertibility** (ADR-017): Operations have computable inverses → undo/redo within a branch is possible via the same TreeOp algebra.

9. **No retry logic in application** (ADR-012): Irmin retries handled by service mesh.

10. **Error hierarchy** (ADR-010): New error types needed: `BranchNotFound`, `MergeConflict`, `BranchAlreadyExists`.

---

## 4. What the Current Plan Says vs What We Now Know

### Plan says (IMPLEMENTATION-PLAN.md §Tier 3):
- Phase 6 (EventHub/ConflictDetector) and Phase 7 (Scenario Branching) are bundled in Tier 3
- **"Prerequisites: Tier 2 (cache invalidation pipeline) must be complete for reactive updates"**
- Scenario endpoints are unscoped (not under `/w/{key}/...`)

### What we now know:
| Claim | Reality |
|-------|---------|
| "Tier 2 required for scenarios" | **Partially wrong.** Only Phase 6 (EventHub, ConflictDetector) needs Tier 2's watch pipeline. Scenario branching (Phase 7) only needs `IrminClient` branch extension. |
| "Phase 6 before Phase 7" | **Not required.** They can be developed in parallel. Scenarios work without real-time collaboration. |
| Scenario endpoints at `/scenarios` | **Needs revision.** Must be workspace-scoped: `/w/{key}/scenarios/...` to maintain security model (ADR-021, ADR-024). |
| Time travel feature | **Not in the plan at all.** Irmin supports it natively via `commit(hash:)` and `Branch.last_modified`. Needs explicit design. |
| Cache strategy for branches | **Underspecified.** Plan mentions "use cached RiskResult for fast comparison" but doesn't address cache isolation between branches. |

---

## 5. Frontend Gap Analysis

| Feature | Current UI Support | What's Needed |
|---------|-------------------|---------------|
| Scenario list/switcher | ❌ None | Dropdown or sidebar panel showing branches |
| Create scenario | ❌ None | "New Scenario" button + name input |
| Branch indicator | ❌ None | Badge showing current branch (e.g., "main" / "scenario: high-cyber") |
| Scenario comparison | ❌ None | Side-by-side LEC overlay or diff view |
| Merge UI | ❌ None | Merge preview + conflict resolution |
| Time travel / history | ❌ None | Commit log panel + "restore" action |
| Navigation section | ❌ Only Design/Analyze | Third section "Scenarios" or integrated into Design |

---

## 6. Confirmed: Strict Prerequisites for Scenario Branching

Only these are truly required:

1. **IrminClient branch extension** — parameterize get/set/remove/list with optional `branch: Option[BranchRef]`
2. **IrminQueries for merge/revert/history** — new GraphQL query strings + response types
3. **Branch-aware cache strategy** — decide per-branch vs content-addressed vs clear-on-switch
4. **Nominal wrapper types** — `BranchRef`, `ScenarioId`, `CommitHash` per ADR-018
5. **Workspace-scoped scenario endpoints** — `POST/GET/DELETE /w/{key}/scenarios/...`

NOT required:
- ❌ Tier 2 watch pipeline (only needed for real-time collaboration)
- ❌ EventHub / ConflictDetector (only needed for multi-user editing)
- ❌ WebSocket transport (only needed for bidirectional comms)

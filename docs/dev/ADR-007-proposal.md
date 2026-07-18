# ADR-007-proposal: Scenario Branching

**Status:** Proposed  
**Date:** 2026-01-16  
**Tags:** branching, versioning, scenarios, irmin

> **Note:** Code examples in this ADR are conceptual patterns, not actual codebase types.
> GraphQL queries shown are illustrative of the expected Irmin API.

---

## Context

- Users want to explore **"what-if" scenarios** without affecting main tree
- Irmin provides **native git-like branching** and merge
- Branches enable **comparison** of different risk configurations
- Need clear UX for branch management

---

## Decision

### 1. Branch as Named Scenario

> **DD-5 closed 2026-07-18 → Option A (milestone-2b doc, Closed table):
> a scenario is (workspace, name) — nothing more.** No `Scenario` record
> type, no `ScenarioId`, no metadata store (Irmin or Postgres). The branch
> name is the complete domain model; everything else is derived from Irmin
> on demand (fork point via `lca`, creation time via `getHistory`). The
> earlier sketch below is superseded.

Each scenario is an Irmin branch, and the branch name is its identity:

```scala
// ScenarioName: user input accepting only slug-mappable characters —
// letters (folded to lowercase), digits, space (mapped to '-'), '-', '_'.
// Anything else is a 400 at the Tapir boundary; no lossy slugification.
type ScenarioName = String :| (Not[Blank] & Match["^[a-zA-Z0-9 _-]+$"])
```

### 2. Branch Naming Convention

**TWO segments after the prefix** (DD-5; `BranchRefConstraint` changes from
three to two segments when Phase B ships):

```
scenarios.{workspaceId}.{name-slug}

Examples (workspaceId = lowercased ULID; lowercasing is bijective for ULIDs):
- scenarios.01j8zq3fkwp2x9m4v7rtbnd6ea.high-cyber
- scenarios.01j8zq3fkwp2x9m4v7rtbnd6ea.recession-impact
- main  (the canonical tree)

> **Separator pinned 2026-07-18:** `.`, not `/` — Irmin rejects `/` in branch
> names (verified live against local/irmin-prod:3.11; `BranchRefConstraint`
> in OpaqueTypes.scala is the source of truth).
```

This ensures:
- Workspace is the ownership boundary (DD-11: listing, authorization, and
  reaper cleanup are prefix operations on the first segment)
- Name collisions within a workspace are rejected by the store itself
  (`test_and_set_branch` CAS create fails if the ref exists) — duplicate
  display names are impossible by the filename rule
- **Create** must fork explicitly: `test_and_set_branch(test: null,
  set: <main head>)` — a branch created by a bare first write starts EMPTY
  (live-verified; milestone-2b A9 fact 3)
- **Rename = recreate**: new branch at the old head + CAS-delete the old
  ref. Content, history, and `lca` merge bases survive; deleting a branch
  removes only the pointer, never commits (A9 facts 2+5, live-verified).
  Open tabs holding the old ref get a clean `BranchNotFound` and re-select.

### 3. Scenario Operations

```scala
// Conceptual: Scenario service
trait ScenarioService:
  def create(name: ScenarioName, description: Option[String]): Task[Scenario]
  def list(userId: UserId): Task[List[Scenario]]
  def switch(scenarioId: ScenarioId): Task[Unit]
  def merge(source: ScenarioId, target: ScenarioId): Task[MergeResult]
  def compare(a: ScenarioId, b: ScenarioId): Task[ScenarioDiff]
  def delete(scenarioId: ScenarioId): Task[Unit]
```

### 4. Branch Creation (Conceptual GraphQL)

```graphql
# Conceptual: Irmin GraphQL mutation for branching
mutation CreateBranch($name: String!, $from: String!) {
  createBranch(name: $name, from: $from) {
    name
    head {
      hash
      info { date, author, message }
    }
  }
}
```

### 5. Merge Strategy

```scala
// Conceptual: Merge workflow
enum MergeResult:
  case Success(newCommit: CommitHash)
  case Conflict(conflicts: List[ConflictInfo])
  case NoChanges

case class ConflictInfo(
  nodeId: NodeId,
  baseValue: RiskNode,
  oursValue: RiskNode,
  theirsValue: RiskNode
)

def mergeScenario(source: ScenarioId, target: ScenarioId): Task[MergeResult] =
  for
    sourceRef <- getScenario(source).map(_.branchRef)
    targetRef <- getScenario(target).map(_.branchRef)
    result    <- irminClient.merge(sourceRef, targetRef)
  yield result
```

### 6. Scenario Comparison

```scala
// Conceptual: Diff between scenarios
case class ScenarioDiff(
  added: List[NodeId],
  removed: List[NodeId],
  modified: List[(NodeId, NodeDiff)],
  lecImpact: LECComparison  // Compare aggregate LEC curves
)

case class LECComparison(
  scenarioA: LECSummary,
  scenarioB: LECSummary,
  deltaAt95thPercentile: Money,
  deltaExpectedLoss: Money
)
```

---

## Outstanding Issues

### Branch Name Validation

**Resolved by DD-5 (2026-07-18).** `ScenarioName` accepts
`^[a-zA-Z0-9 _-]+$` (uppercase folds to lowercase, space maps to `-`);
anything unmappable is a 400 at the boundary — no lossy slugification.
Max slug length 64 (the `BranchRefConstraint` segment cap); case-insensitive
by construction (the slug is the canonical lowercase form).

### Orphan Branch Cleanup

**Issue:** If user creates many scenarios and abandons them, branches accumulate.

**Options:**
1. Manual cleanup only
2. Soft-delete with grace period
3. Auto-archive after inactivity (e.g., 90 days)
4. Quota per user

**Action required:** Define branch lifecycle policy.

---

## Diagrams

### Branching Model

```
main ─────●─────●─────●─────●─────────●───────────────►
          │           │               ▲
          │           │               │ merge
          │           ▼               │
          │     optimistic-growth ────●───────────────►
          │           ●───────●───────┘
          │
          ▼
    recession-impact
          ●───────●───────●───────────────────────────►
```

### Scenario Lifecycle

```
┌─────────────────────────────────────────────────────────┐
│                    Scenario Lifecycle                   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────┐    ┌───────────┐    ┌─────────────────┐  │
│  │  Create  │───►│  Active   │───►│  Merged/Deleted │  │
│  │          │    │           │    │                 │  │
│  └──────────┘    └───────────┘    └─────────────────┘  │
│       │               │                    ▲           │
│       │               │                    │           │
│       │               ▼                    │           │
│       │         ┌───────────┐              │           │
│       │         │  Compare  │──────────────┘           │
│       │         │  w/ main  │                          │
│       │         └───────────┘                          │
│       │                                                │
│       └─────► Fork point saved for merge base          │
│                                                        │
└─────────────────────────────────────────────────────────┘
```

---

## Code Smells

### ❌ No Fork Point Tracking

```scala
// Superseded by DD-5 (2026-07-18): no Scenario record exists at all.
// The fork point is never stored — it is computed on demand from Irmin:
//   lca(branchRef, mainHead)   // 3-way merge base, live-verified (A9)
// Storing createdFrom would duplicate a derivable fact.
```

### ❌ Branch Name Collisions

```scala
// BAD: Simple names collide
def createBranch(name: String): Task[BranchRef] =
  irminClient.createBranch(name)  // "optimistic" already exists!

// GOOD (DD-5): workspace-namespaced; collision rejected by the CAS create
def createScenario(name: ScenarioName, wsId: WorkspaceId): Task[BranchRef] =
  val branchName = s"scenarios.${wsId.lowercased}.${slug(name)}"
  // test_and_set_branch(branch = branchName, test = null, set = mainHead)
  // fails (returns false) if the branch already exists
  irminClient.createBranchAt(branchName, mainHead)
```

### ❌ No Merge Conflict Handling

```scala
// BAD: Assume merge always succeeds
def mergeScenario(source: ScenarioId): Task[Unit] =
  irminClient.merge(source, "main")  // What if conflicts?

// GOOD: Handle conflicts explicitly
def mergeScenario(source: ScenarioId): Task[MergeResult] =
  irminClient.merge(source, "main").flatMap {
    case MergeResult.Success(commit) => 
      notifySuccess(commit)
    case MergeResult.Conflict(conflicts) => 
      presentConflictResolutionUI(conflicts)
    case MergeResult.NoChanges => 
      ZIO.succeed(MergeResult.NoChanges)
  }
```

---

## UI Considerations

### Scenario Switcher

- Dropdown or sidebar showing available scenarios
- Visual indicator of current scenario
- Quick action to create new scenario from current state
- Comparison view showing diff with main

### Merge UI

- Side-by-side comparison before merge
- Conflict resolution interface (if conflicts detected)
- Preview of LEC impact after merge

---

## Implementation

| Component | Location | Purpose |
|-----------|----------|---------|
| `Scenario` | `domain/Scenario.scala` | Scenario model |
| `ScenarioService` | `service/ScenarioService.scala` | Branch operations |
| `ScenarioDiff` | `domain/ScenarioDiff.scala` | Comparison model |
| `IrminBranchClient` | `infra/IrminBranchClient.scala` | GraphQL branch ops |

---

## References

- Irmin branching: https://irmin.org/tutorial/command-line
- ADR-005: Cached aggregates enable fast scenario comparison
- ADR-006: Real-time events when switching scenarios
- Tree Zippers Conversation: "Branches for what-if scenarios"

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

Each scenario is an Irmin branch:

```scala
// Conceptual: Scenario model
case class Scenario(
  id: ScenarioId,          // Unique identifier (e.g., UUID)
  name: ScenarioName,      // User-friendly name (validated with Iron)
  branchRef: BranchRef,    // Irmin branch reference
  createdFrom: CommitHash, // Fork point
  createdBy: UserId,
  createdAt: Instant,
  description: Option[String]
)

// ScenarioName validated with Iron (non-empty, valid characters)
type ScenarioName = String :| (Not[Blank] & Match["^[a-zA-Z0-9_-]+$"])
```

### 2. Branch Naming Convention

Irmin branch names follow pattern for uniqueness:

```
scenarios/{userId}/{scenarioId}/{name}

Examples:
- scenarios/user-123/abc-456/optimistic-growth
- scenarios/user-123/def-789/recession-impact
- main  (the canonical tree)
```

This ensures:
- No collisions between users
- Multiple scenarios with same display name allowed
- Easy filtering by user

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

**Issue:** Need to decide exact validation rules for scenario names.

**Current approach:** Iron type with regex `^[a-zA-Z0-9_-]+$`

**Open questions:**
- Maximum length?
- Allow spaces (URL-encode in branch path)?
- Case sensitivity?

**Action required:** Finalize naming constraints.

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
// BAD: Lose track of where branch started
case class Scenario(
  name: String,
  branchRef: BranchRef
)
// Can't compute proper 3-way merge without base

// GOOD: Track fork point
case class Scenario(
  name: ScenarioName,
  branchRef: BranchRef,
  createdFrom: CommitHash  // Fork point for merge base
)
```

### ❌ Branch Name Collisions

```scala
// BAD: Simple names collide
def createBranch(name: String): Task[BranchRef] =
  irminClient.createBranch(name)  // "optimistic" already exists!

// GOOD: Namespaced unique names
def createBranch(name: ScenarioName, userId: UserId): Task[BranchRef] =
  val scenarioId = ScenarioId.generate()
  val branchName = s"scenarios/${userId}/${scenarioId}/${name}"
  irminClient.createBranch(branchName)
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

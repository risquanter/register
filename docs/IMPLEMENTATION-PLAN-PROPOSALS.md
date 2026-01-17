# Implementation Plan: ADR Proposals

This plan implements the ADR proposals in logical dependency order, with explicit checkpoints for user approval.

---

## Overview

### Proposals to Implement

| ADR | Title | Dependencies |
|-----|-------|--------------|
| ADR-004a | Persistence Architecture (SSE) | None |
| ADR-005 | Cached Subtree Aggregates | ADR-004a (Irmin notifications) |
| ADR-008 | Error Handling & Resilience | ADR-004a (network layer) |
| ADR-006 | Real-Time Collaboration | ADR-004a, ADR-005, ADR-008 |
| ADR-007 | Scenario Branching | ADR-004a (Irmin branches) |
| ADR-004b | WebSocket Enhancement | ADR-006 (collaboration patterns) |

### Implementation Sequence

```
Phase 1: Error Domain Model (ADR-008 foundation) ✅ COMPLETE
    ↓
Phase 1.5: Irmin Dev Environment Setup (prerequisite)
    ↓
Phase 2: Irmin GraphQL Client (ADR-004a foundation)
    ↓
Phase 3: Tree Index & Cache Structure (ADR-005 foundation)
    ↓
Phase 4: SSE Infrastructure (ADR-004a completion)
    ↓
Phase 5: Cache Invalidation Pipeline (ADR-005 completion)
    ↓
  ══════════════════════════════════════════════
  CHECKPOINT: Accept ADR-004a, ADR-005, ADR-008
  ══════════════════════════════════════════════
    ↓
Phase 6: Event Hub & Collaboration (ADR-006)
    ↓
Phase 7: Scenario Branching (ADR-007)
    ↓
  ══════════════════════════════════════════════
  CHECKPOINT: Accept ADR-006, ADR-007
  ══════════════════════════════════════════════
    ↓
Phase 8: WebSocket Enhancement (ADR-004b) [Optional]
```

---

## Phase 1: Infrastructure Error Extensions

### Status: COMPLETE

### Objective
Extend the existing `SimulationError` hierarchy with infrastructure-specific error cases for service mesh integration.

**Note:** Retry and circuit breaker logic has been **removed** from this phase. These concerns are delegated to the service mesh (Istio/Linkerd). See ADR-012-proposal for the service mesh strategy decision.

### ADR Reference
- ADR-008-proposal: Error Handling & Resilience (scope reduced)
- ADR-010: Error Handling Strategy (existing foundation to extend)
- ADR-012: Service Mesh Strategy (Istio Ambient Mode) — ACCEPTED

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- Extends existing `SimulationError` sealed trait (ADR-010 compliant)
- Does NOT create parallel error hierarchy
- Uses existing `ErrorResponse.encode` pattern matching (add new cases)
- **Resilience (retries, circuit breaker) delegated to service mesh**

### Validation Checklist
- [x] Compliant with ADR-001 (Iron types for validated error fields)
- [x] Compliant with ADR-002 (errors integrate with logging)
- [x] Compliant with ADR-009 (no impact on aggregation)
- [x] Compliant with ADR-010 (extends SimulationError, not parallel hierarchy)
- [x] Compliant with ADR-011 (top-level imports, no FQNs)

### Completed Tasks ✅

1. **Extended `SimulationError` with infrastructure cases**
   ```
   domain/errors/SimulationError.scala (updated)
   ```
   - ✅ Added: `case class IrminUnavailable(reason: String) extends SimulationError`
   - ✅ Added: `case class NetworkTimeout(operation: String, duration: Duration) extends SimulationError`
   - ✅ Added: `case class VersionConflict(nodeId: String, expected: String, actual: String) extends SimulationError`
   - ✅ Added: `case class MergeConflict(branch: String, details: String) extends SimulationError`

2. **Updated `ErrorResponse.encode` pattern matching**
   ```
   domain/errors/ErrorResponse.scala (updated)
   ```
   - ✅ Added cases for new error types
   - ✅ IrminUnavailable → 503 Service Unavailable
   - ✅ NetworkTimeout → 504 Gateway Timeout
   - ✅ VersionConflict → 409 Conflict
   - ✅ MergeConflict → 409 Conflict

3. **Updated tests**
   ```
   test/.../ErrorResponseSpec.scala (updated)
   ```
   - ✅ Tests for all new infrastructure error → HTTP mappings

### Removed Tasks ❌ (Delegated to Service Mesh)

~~3. Create retry schedule utilities~~ → **Service mesh handles retries**
~~4. Create circuit breaker configuration~~ → **Service mesh handles circuit breaking**

### Remaining Tasks

4. **Run tests and verify compilation**

5. **Create ADR-012-proposal: Service Mesh Strategy**
   - Istio vs Linkerd comparison
   - Integration with Keycloak + OPA for auth
   - Must be accepted before proceeding to Phase 2

### Deliverables
- [x] New error cases added to existing SimulationError
- [x] ErrorResponse.encode handles all new cases
- [ ] Tests pass
- [ ] ADR-012-proposal created

### Blocking Dependency

**✅ ADR-012 (Service Mesh Strategy) ACCEPTED — Istio Ambient Mode**

Decisions made:
- Istio Ambient Mode selected (ztunnel + waypoint proxies)
- Auth: Keycloak + OPA via ext_authz
- Irmin client: no application-level resilience (mesh handles retries/circuit breaking)
- Development: let it fail (no mesh locally)

### Questions for User
- None — awaiting ADR-012 analysis
- None anticipated

### Approval Checkpoint
- [ ] ADR compliance verified at planning stage
- [ ] Code compiles
- [ ] Tests pass
- [ ] User approves Phase 1

---

## Phase 1.5: Irmin Development Environment Setup

### Status: NOT STARTED

### Objective
Set up a minimal Irmin instance with GraphQL endpoint for local development. This is a prerequisite for Phase 2 (Irmin GraphQL Client).

### Why This Phase Exists
- Irmin is an OCaml library, not a standalone service — requires OCaml toolchain
- No official Docker image available (existing images are 7+ years old)
- Phase 2 needs a running Irmin GraphQL endpoint to develop against
- We need to document the GraphQL schema for client code generation

### Approach: Multi-Stage Distroless Build (Recommended)

Following the project's distroless approach for Scala, we create a minimal Irmin image with only the static binary.

**Strategy:**
1. **Builder stage** — Use `ocaml/opam:alpine-ocaml-5.2` with musl libc for static linking
2. **Runtime stage** — Copy only the `irmin` binary to `gcr.io/distroless/static` or scratch

#### Dockerfile (Two-Stage Distroless)

```dockerfile
# ============================================================================
# Stage 1: Build static Irmin binary
# ============================================================================
FROM ocaml/opam:alpine-ocaml-5.2 AS builder

# Switch to static musl compilation
# Alpine uses musl libc which enables fully static binaries
USER opam
WORKDIR /home/opam

# Install build dependencies
RUN opam update && \
    opam install -y \
      irmin-cli \
      irmin-graphql \
      irmin-git \
      irmin-pack

# Find and verify the irmin binary is statically linked
RUN opam exec -- which irmin && \
    opam exec -- irmin --version && \
    file $(opam exec -- which irmin) && \
    ldd $(opam exec -- which irmin) 2>&1 | grep -q "statically linked" || echo "Note: may need +static switch"

# Copy binary to known location
RUN cp $(opam exec -- which irmin) /home/opam/irmin-static

# ============================================================================
# Stage 2: Minimal runtime image
# ============================================================================
FROM gcr.io/distroless/static:nonroot AS runtime

# Copy only the static binary
COPY --from=builder /home/opam/irmin-static /irmin

# Create data directory (distroless has /home/nonroot)
WORKDIR /data

# Expose GraphQL port
EXPOSE 8080

# Run as nonroot user (distroless convention)
USER nonroot:nonroot

# Start GraphQL server
ENTRYPOINT ["/irmin"]
CMD ["graphql", "--port", "8080", "--root", "/data"]
```

#### Alternative: Scratch-based (Even Smaller)

```dockerfile
# Runtime stage using scratch (absolute minimum)
FROM scratch AS runtime-scratch

COPY --from=builder /home/opam/irmin-static /irmin

# Need CA certificates for HTTPS (if using git remote)
# COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/

EXPOSE 8080
ENTRYPOINT ["/irmin"]
CMD ["graphql", "--port", "8080", "--root", "/data"]
```

#### Static Binary Compilation Notes

OCaml on Alpine (musl) can produce fully static binaries. If the default compiler doesn't produce static output, use a static switch:

```bash
# In builder stage, create static-capable switch
opam switch create static ocaml-variants.5.2.0+options ocaml-option-static ocaml-option-musl
opam switch static
opam install irmin-cli irmin-graphql irmin-git
```

Expected image sizes:
- **Builder stage**: ~2-3 GB (full OCaml toolchain)
- **Runtime (distroless/static)**: ~20-50 MB (irmin binary + distroless base)
- **Runtime (scratch)**: ~15-40 MB (irmin binary only)

### Docker Compose Integration

```yaml
# docker-compose.yml
services:
  irmin:
    build:
      context: ./dev
      dockerfile: Dockerfile.irmin
      target: runtime  # or runtime-scratch
    ports:
      - "8080:8080"
    volumes:
      - irmin-data:/data
    healthcheck:
      test: ["CMD", "wget", "-q", "--spider", "http://localhost:8080/graphql"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 5s

volumes:
  irmin-data:
```

**Note:** For healthcheck in distroless, we may need a different approach since wget isn't available. Options:
- Use `grpc_health_probe` style binary
- Check from docker host: `docker compose exec irmin /irmin --version`
- Skip healthcheck for dev environment

### Alternative: Development Image (Faster Iteration)

For active development where you need the full toolchain:

```dockerfile
# Dockerfile.irmin-dev (non-distroless, for debugging)
FROM ocaml/opam:alpine-ocaml-5.2

USER opam
WORKDIR /home/opam

RUN opam update && \
    opam install -y irmin-cli irmin-graphql irmin-git irmin-pack

EXPOSE 8080

CMD ["opam", "exec", "--", "irmin", "graphql", "--port", "8080", "--root", "/data"]
```

Use this during development, switch to distroless for staging/production.

### Tasks

1. **Create Dockerfile with multi-stage build**
   ```
   dev/Dockerfile.irmin
   ```

2. **Verify static linking works**
   - Build the image
   - Check binary with `file` and `ldd`
   - If not static, adjust opam switch to include `+static+musl`

3. **Create docker-compose.yml**
   ```
   dev/docker-compose.yml
   ```

4. **Extract GraphQL schema**
   ```bash
   # With server running
   npx get-graphql-schema http://localhost:8080/graphql > dev/irmin-schema.graphql
   ```
   Or use GraphiQL at `http://localhost:8080/graphql`

5. **Verify GraphQL endpoint**
   ```bash
   curl -X POST http://localhost:8080/graphql \
     -H "Content-Type: application/json" \
     -d '{"query": "{ main { head { hash } } }"}'
   ```

6. **Document in DEV-ENVIRONMENT.md**
   - How to start/stop Irmin
   - GraphQL endpoint URL
   - Key operations

### Deliverables
- [ ] Multi-stage Dockerfile producing distroless image
- [ ] Irmin GraphQL server running locally (port 8080)
- [ ] Image size < 50 MB (distroless target)
- [ ] GraphQL schema extracted to `dev/irmin-schema.graphql`
- [ ] Docker Compose file
- [ ] DEV-ENVIRONMENT.md with setup instructions

### GraphQL Schema Reference

Based on Irmin documentation, key types:

```graphql
type Query {
  main: Branch                    # Main branch
  branch(name: String!): Branch   # Named branch
  branches: [Branch!]!            # All branches
}

type Branch {
  name: String!
  head: Commit
  tree: Tree!
}

type Commit {
  hash: String!
  info: Info!
  parents: [String!]!
  tree: Tree!
}

type Tree {
  get(path: String!): String              # Get value at path
  get_contents(path: String!): Contents   # Get with metadata
  get_tree(path: String!): Tree           # Get subtree
  list: [TreeItem!]!                      # List children
  list_contents_recursively: [TreeItem!]! # All descendants
}

type TreeItem {
  path: String!
  value: String
  metadata: String
}

type Mutation {
  set(path: String!, value: String!, info: InfoInput): Commit!
  set_tree(path: String!, tree: [TreeItemInput!]!, info: InfoInput): Commit!
  remove(path: String!, info: InfoInput): Commit!
}

# Subscriptions for watch (to be verified)
type Subscription {
  watch(branch: String): WatchEvent
}
```

### Storage Backend Decision

**Recommended: `irmin-pack`** (default for `irmin-cli`)

| Backend | Pros | Cons |
|---------|------|------|
| `irmin-mem` | Fast, no disk I/O | Data lost on restart |
| `irmin-pack` | Persistent, optimized for Irmin | Cannot inspect with git CLI |
| `irmin-git` | Git-compatible, inspectable | Slower, larger footprint |

For development, `irmin-pack` (default) is recommended — persistent and fast.
For debugging tree state, can switch to `irmin-git` and use `git log`/`git diff`.

### Approval Checkpoint
- [x] Approach chosen: Multi-stage distroless Docker build
- [ ] Dockerfile created and builds successfully
- [ ] Irmin running with GraphQL endpoint
- [ ] Static binary confirmed (< 50 MB image)
- [ ] Schema documented
- [ ] User approves Phase 1.5

---

## Phase 2: Irmin GraphQL Client

### Objective
Create the ZIO client that communicates with Irmin via GraphQL for reads, writes, and subscriptions.

### ADR Reference
- ADR-004a-proposal: Persistence Architecture (Channel A: Irmin ↔ ZIO)

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- Uses Iron types for NodeId, TreeId
- Logs GraphQL operations at service layer (ADR-002)
- Uses extended SimulationError hierarchy (ADR-010)
- Top-level imports throughout (ADR-011)

### Validation Checklist
- [ ] Compliant with ADR-001 (Iron types for node IDs)
- [ ] Compliant with ADR-002 (log GraphQL operations at service layer)
- [ ] Compliant with ADR-009 (no impact on aggregation)
- [ ] Compliant with ADR-010 (use SimulationError subtypes, retry policy)
- [ ] Compliant with ADR-011 (top-level imports, no FQNs)

### Prerequisites
- Irmin running with GraphQL endpoint (external service)
- Known GraphQL schema for tree operations

### Tasks

1. **Define Irmin data models**
   ```
   infra/irmin/model/IrminNode.scala
   infra/irmin/model/IrminCommit.scala
   ```
   - Node structure matching Irmin schema
   - Commit metadata (hash, timestamp, author)

2. **Create GraphQL queries/mutations**
   ```
   infra/irmin/IrminQueries.scala
   ```
   - `getNode(path)` query
   - `setNode(path, value)` mutation
   - `getTree(path)` query
   - `createBranch(name, from)` mutation

3. **Create GraphQL subscription handler**
   ```
   infra/irmin/IrminSubscription.scala
   ```
   - Subscribe to tree changes
   - Parse watch events into domain events

4. **Create `IrminClient` service**
   ```
   infra/irmin/IrminClient.scala
   ```
   - ZIO service with sttp GraphQL backend
   - Integrates retry policy and circuit breaker
   - Methods: `getNode`, `setNode`, `watchChanges`, etc.

5. **Create `IrminClient` ZLayer**
   ```
   infra/irmin/IrminClientLive.scala
   ```
   - Configuration from environment
   - Proper resource management for subscriptions

6. **Add integration tests** (requires Irmin running)
   ```
   test/.../IrminClientIntegrationSpec.scala
   ```

### Deliverables
- [ ] Client connects to Irmin GraphQL endpoint
- [ ] Can read/write nodes
- [ ] Subscription receives change events
- [ ] Errors wrapped in `SimulationError` subtypes

### Questions for User
- What is the Irmin GraphQL endpoint URL for development?
- Do we need to set up Irmin locally first? (May require OCaml setup)

### Approval Checkpoint
- [ ] Code compiles
- [ ] Integration tests pass (or manual verification)
- [ ] User approves Phase 2

---

## Phase 3: Tree Index & Cache Structure

### Objective
Implement the parent-pointer index and LEC cache data structures that enable O(depth) invalidation.

### ADR Reference
- ADR-005-proposal: Cached Subtree Aggregates
- ADR-009: Compositional Risk Aggregation (RiskResult type)

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- Uses existing RiskResult and LECCurveData types (ADR-009)
- Iron-refined NodeId types (ADR-001)
- Cache operations logged at service layer (ADR-002)

### Validation Checklist
- [ ] Compliant with ADR-001 (Iron types for NodeId)
- [ ] Compliant with ADR-002 (cache operations logged)
- [ ] Compliant with ADR-009 (uses existing RiskResult, LECCurveData)
- [ ] Compliant with ADR-010 (cache errors use SimulationError)
- [ ] Compliant with ADR-011 (top-level imports)

### Tasks

1. **Create `TreeIndex` data structure**
   ```
   domain/tree/TreeIndex.scala
   ```
   - `nodes: Map[NodeId, RiskNode]` — O(1) lookup
   - `parents: Map[NodeId, NodeId]` — child → parent
   - `children: Map[NodeId, List[NodeId]]` — parent → children
   - `ancestorPath(nodeId): List[NodeId]` — walk to root

2. **Create `LECCache` service**
   ```
   service/cache/LECCache.scala
   ```
   - `Ref[Map[NodeId, LECCurveData]]` for cache storage
   - `get(nodeId): UIO[Option[LECCurveData]]`
   - `set(nodeId, lec): UIO[Unit]`
   - `invalidate(nodeId): UIO[List[NodeId]]` — returns invalidated ancestors

3. **Create `TreeIndexService`**
   ```
   service/tree/TreeIndexService.scala
   ```
   - Build index from tree structure
   - Update index on node changes
   - Provide ancestor lookup for invalidation

4. **Add tests**
   ```
   test/.../TreeIndexSpec.scala
   test/.../LECCacheSpec.scala
   ```
   - Verify O(depth) ancestor lookup
   - Verify invalidation returns correct path

### Deliverables
- [ ] TreeIndex correctly tracks parent pointers
- [ ] ancestorPath returns root-to-node path
- [ ] LECCache invalidation clears ancestor entries
- [ ] Tests cover edge cases (root node, leaf node, deep tree)

### Questions for User
- None anticipated

### Approval Checkpoint
- [ ] Code compiles
- [ ] Tests pass
- [ ] User approves Phase 3

---

## Phase 4: SSE Infrastructure

### Objective
Implement Server-Sent Events endpoint for pushing LEC updates and tree changes to browsers.

### ADR Reference
- ADR-004a-proposal: Persistence Architecture (Channel B: ZIO → Browser)

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- SSE connection lifecycle logged (ADR-002)
- Connection errors use SimulationError subtypes (ADR-010)
- Event payloads may include RiskResult data (ADR-009)

### Validation Checklist
- [ ] Compliant with ADR-002 (log SSE connections at service layer)
- [ ] Compliant with ADR-009 (event payloads compatible with RiskResult)
- [ ] Compliant with ADR-010 (connection errors use SimulationError)
- [ ] Compliant with ADR-011 (top-level imports)

### Tasks

1. **Define SSE event types**
   ```
   api/sse/SSEEvent.scala
   ```
   - `LECUpdated(nodeId, lecData)`
   - `NodeChanged(nodeId, changeType)`
   - `ConnectionStatus(status)`
   - JSON encoding for each

2. **Create SSE subscriber registry**
   ```
   service/sse/SSEHub.scala
   ```
   - `subscribers: Ref[Map[TreeId, Set[Queue[SSEEvent]]]]`
   - `subscribe(treeId): ZStream[Any, Nothing, SSEEvent]`
   - `broadcast(treeId, event): UIO[Unit]`

3. **Create SSE endpoint**
   ```
   api/sse/SSEEndpoint.scala
   ```
   - Tapir SSE endpoint: `GET /events/tree/{treeId}`
   - Returns `ZStream` of SSE events
   - Handles client disconnection cleanup

4. **Wire to existing routes**
   ```
   api/Routes.scala (update)
   ```
   - Add SSE endpoint to server

5. **Add tests**
   ```
   test/.../SSEHubSpec.scala
   ```
   - Verify broadcast reaches subscribers
   - Verify cleanup on disconnect

### Deliverables
- [ ] SSE endpoint accessible at `/events/tree/{treeId}`
- [ ] Events stream to connected clients
- [ ] Multiple clients receive broadcasts
- [ ] Client disconnect cleans up resources

### Questions for User
- None anticipated

### Approval Checkpoint
- [ ] Code compiles
- [ ] Tests pass
- [ ] Manual test: connect with curl/browser, see events
- [ ] User approves Phase 4

---

## Phase 5: Cache Invalidation Pipeline

### Objective
Connect Irmin watch notifications to cache invalidation and SSE broadcast.

### ADR Reference
- ADR-004a-proposal: Complete data flow (steps 5-10)
- ADR-005-proposal: Invalidation on node change

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- Recomputation preserves HDR seed provenance (ADR-003)
- Uses Identity[RiskResult].combine for aggregation (ADR-009)
- Pipeline errors use SimulationError, logged before boundary (ADR-010, ADR-002)

### Validation Checklist
- [ ] Compliant with ADR-001 (Iron types for node references)
- [ ] Compliant with ADR-002 (pipeline errors logged at service layer)
- [ ] Compliant with ADR-003 (provenance preserved in recomputation)
- [ ] Compliant with ADR-009 (uses Identity[RiskResult].combine)
- [ ] Compliant with ADR-010 (errors in pipeline use SimulationError)
- [ ] Compliant with ADR-011 (top-level imports)

### Tasks

1. **Create invalidation handler**
   ```
   service/pipeline/InvalidationHandler.scala
   ```
   - Receives Irmin watch events
   - Calls `LECCache.invalidate(nodeId)`
   - Triggers recomputation for affected path

2. **Create recomputation service**
   ```
   service/pipeline/LECRecomputer.scala
   ```
   - Lazy recomputation (compute on demand)
   - Uses existing `Simulator` for LEC computation
   - Updates cache after computation
   - Broadcasts `LECUpdated` via SSEHub

3. **Create pipeline orchestrator**
   ```
   service/pipeline/TreeUpdatePipeline.scala
   ```
   - Subscribes to `IrminClient.watchChanges`
   - Routes events to InvalidationHandler
   - Manages pipeline lifecycle

4. **Integrate with application startup**
   ```
   Main.scala (update)
   ```
   - Start pipeline as background fiber
   - Graceful shutdown on app termination

5. **Add integration tests**
   ```
   test/.../TreeUpdatePipelineSpec.scala
   ```
   - Simulate Irmin change → verify SSE event emitted
   - Verify cache invalidated for correct path

### Deliverables
- [ ] Irmin change triggers cache invalidation
- [ ] Recomputation uses O(depth) path, not full tree
- [ ] SSE clients receive `LECUpdated` events
- [ ] Pipeline handles errors without crashing

### Questions for User
- Should recomputation be eager (immediate) or lazy (on next read)?
  - ADR-005 suggests lazy, but eager may be better UX for visible nodes

### Approval Checkpoint
- [ ] Code compiles
- [ ] Integration tests pass
- [ ] End-to-end: edit node → see LEC update in browser
- [ ] User approves Phase 5

---

## Major Checkpoint: Accept ADR-004a, ADR-005, ADR-008

### Review Criteria

**ADR-004a (Persistence Architecture - SSE):**
- [ ] Irmin GraphQL client implemented
- [ ] SSE endpoint working
- [ ] Full data flow demonstrated

**ADR-005 (Cached Subtree Aggregates):**
- [ ] TreeIndex with parent pointers
- [ ] LECCache with O(depth) invalidation
- [ ] Recomputation pipeline working

**ADR-008 (Error Handling & Resilience):**
- [ ] SimulationError extended with infrastructure cases
- [ ] Retry policies applied to Irmin calls
- [ ] Circuit breaker prevents cascade failures

### Actions on Approval
1. Rename `ADR-004a-proposal.md` → `ADR-004a.md`
2. Rename `ADR-005-proposal.md` → `ADR-005.md`
3. Rename `ADR-008-proposal.md` → `ADR-008.md`
4. Update status to "Accepted" in each
5. Add to validation set for subsequent phases

---

## Phase 6: Event Hub & Collaboration

### Objective
Implement multi-user event distribution and conflict detection.

### ADR Reference
- ADR-006-proposal: Real-Time Collaboration

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- Builds on SSE infrastructure from Phase 4
- Conflict errors extend SimulationError (VersionConflict, MergeConflict from Phase 1)
- Event distribution logged at service layer (ADR-002)

### Validation Checklist
- [ ] Compliant with ADR-001 (Iron types for user/session IDs)
- [ ] Compliant with ADR-002 (collaboration events logged)
- [ ] Compliant with ADR-004a (uses SSE infrastructure)
- [ ] Compliant with ADR-005 (cache invalidation triggers events)
- [ ] Compliant with ADR-010 (conflict errors use SimulationError subtypes)
- [ ] Compliant with ADR-011 (top-level imports)

### Tasks

1. **Extend event types for collaboration**
   ```
   domain/event/RiskEvent.scala
   ```
   - `NodeCreated`, `NodeUpdated`, `NodeDeleted`
   - `UserJoined`, `UserLeft`
   - `ConflictDetected`

2. **Create EventHub service**
   ```
   service/collaboration/EventHub.scala
   ```
   - Per-user event queues
   - `broadcast(event)`, `broadcastExcept(event, userId)`
   - Bounded queues with backpressure policy

3. **Create ConflictDetector**
   ```
   service/collaboration/ConflictDetector.scala
   ```
   - Track `baseVersion` on edit requests
   - Compare with current Irmin version
   - Return `EditResult.Success` or `EditResult.Conflict`

4. **Update mutation endpoints**
   ```
   api/RiskTreeEndpoints.scala (update)
   ```
   - Accept `baseVersion` in edit requests
   - Check for conflicts before applying
   - Broadcast events after successful mutation

5. **Add tests**
   ```
   test/.../EventHubSpec.scala
   test/.../ConflictDetectorSpec.scala
   ```

### Deliverables
- [ ] Multiple users see each other's changes via SSE
- [ ] Conflict detected when editing stale version
- [ ] Conflict event sent to affected user
- [ ] Events exclude originator (no self-echo)

### Questions for User
- Backpressure strategy for slow clients:
  - A) Drop oldest events
  - B) Disconnect slow client
  - C) Coalesce rapid updates

### Approval Checkpoint
- [ ] Code compiles
- [ ] Tests pass
- [ ] Two browser tabs see each other's changes
- [ ] User approves Phase 6

---

## Phase 7: Scenario Branching

### Objective
Implement what-if scenario management via Irmin branches.

### ADR Reference
- ADR-007-proposal: Scenario Branching

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- ScenarioId, ScenarioName use Iron refinement (ADR-001)
- Branch operations via Irmin GraphQL client (ADR-004a)
- Merge conflicts use MergeConflict from SimulationError (ADR-010)
- Scenario comparison uses cached LECCurveData (ADR-005, ADR-009)

### Validation Checklist
- [ ] Compliant with ADR-001 (ScenarioName, ScenarioId validated with Iron)
- [ ] Compliant with ADR-002 (branch operations logged)
- [ ] Compliant with ADR-004a (uses Irmin branch operations)
- [ ] Compliant with ADR-005 (cached aggregates enable fast comparison)
- [ ] Compliant with ADR-009 (comparison uses RiskResult structures)
- [ ] Compliant with ADR-010 (merge conflicts use SimulationError)
- [ ] Compliant with ADR-011 (top-level imports)

### Tasks

1. **Create Scenario domain model**
   ```
   domain/scenario/Scenario.scala
   ```
   - `ScenarioId`, `ScenarioName` (Iron refined)
   - `branchRef`, `createdFrom`, `createdBy`, `createdAt`

2. **Create ScenarioService**
   ```
   service/scenario/ScenarioService.scala
   ```
   - `create(name, description)` → creates Irmin branch
   - `list(userId)` → list user's scenarios
   - `switch(scenarioId)` → change active branch
   - `delete(scenarioId)` → remove branch

3. **Create merge functionality**
   ```
   service/scenario/ScenarioMerger.scala
   ```
   - `merge(source, target)` → Irmin merge
   - Handle `MergeResult.Conflict` with conflict info
   - Compute `ScenarioDiff` for preview

4. **Create comparison service**
   ```
   service/scenario/ScenarioComparator.scala
   ```
   - `compare(a, b)` → diff nodes and LEC impact
   - Use cached LECCurveData for fast comparison

5. **Create API endpoints**
   ```
   api/ScenarioEndpoints.scala
   ```
   - `POST /scenarios` — create
   - `GET /scenarios` — list
   - `POST /scenarios/{id}/switch` — switch
   - `POST /scenarios/{id}/merge` — merge to target
   - `GET /scenarios/{a}/compare/{b}` — compare

6. **Add tests**
   ```
   test/.../ScenarioServiceSpec.scala
   ```

### Deliverables
- [ ] Can create scenario from current tree state
- [ ] Can switch between scenarios
- [ ] Edits in scenario don't affect main
- [ ] Can merge scenario back to main
- [ ] Can compare two scenarios

### Questions for User
- Branch naming: Should scenario name be unique per user, or globally unique?
- Orphan cleanup: Manual only, or auto-archive after inactivity?

### Approval Checkpoint
- [ ] Code compiles
- [ ] Tests pass
- [ ] End-to-end scenario workflow demonstrated
- [ ] User approves Phase 7

---

## Major Checkpoint: Accept ADR-006, ADR-007

### Review Criteria

**ADR-006 (Real-Time Collaboration):**
- [ ] EventHub distributes events to multiple users
- [ ] Conflict detection prevents lost updates
- [ ] Backpressure handled

**ADR-007 (Scenario Branching):**
- [ ] Scenarios map to Irmin branches
- [ ] CRUD operations work
- [ ] Merge with conflict handling

### Actions on Approval
1. Rename `ADR-006-proposal.md` → `ADR-006.md`
2. Rename `ADR-007-proposal.md` → `ADR-007.md`
3. Update status to "Accepted" in each

---

## Phase 8: WebSocket Enhancement (Optional)

### Objective
Replace SSE with WebSocket for bidirectional communication and enhanced collaboration.

### ADR Reference
- ADR-004b-proposal: Persistence Architecture (WebSocket Enhancement)

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- WebSocket connection errors use SimulationError (ADR-010)
- Connection lifecycle logged (ADR-002)
- Extends collaboration patterns from Phase 6 (ADR-006)

### Validation Checklist
- [ ] Compliant with ADR-002 (WebSocket lifecycle logged)
- [ ] Compliant with ADR-006 (extends collaboration patterns)
- [ ] Compliant with ADR-010 (reconnection errors use SimulationError)
- [ ] Compliant with ADR-011 (top-level imports)

### Prerequisites
- Phases 1-7 complete
- User decision: Is WebSocket enhancement needed now?

### Tasks

1. **Create WebSocket message types**
   ```
   api/ws/WSMessage.scala
   ```
   - Client→Server: `EditNode`, `CursorMove`, `PresenceUpdate`
   - Server→Client: `LECUpdated`, `NodeChanged`, `UserCursor`

2. **Create WebSocket hub**
   ```
   service/ws/WebSocketHub.scala
   ```
   - Replace/extend SSEHub
   - Handle bidirectional messages
   - Track user presence and cursors

3. **Create WebSocket endpoint**
   ```
   api/ws/WebSocketEndpoint.scala
   ```
   - ZIO HTTP WebSocket handler
   - Message routing

4. **Update frontend** (if in scope)
   - Replace EventSource with WebSocket
   - Send cursor/presence updates

5. **Add tests**
   ```
   test/.../WebSocketHubSpec.scala
   ```

### Deliverables
- [ ] WebSocket connection established
- [ ] Bidirectional message flow
- [ ] Presence tracking (who's online)
- [ ] Cursor sharing (optional)

### Questions for User
- Is this phase needed for initial release?
- Should we implement cursor sharing?

### Approval Checkpoint
- [ ] Code compiles
- [ ] Tests pass
- [ ] User approves Phase 8

---

## Summary

| Phase | ADR(s) | Key Deliverable | Accept ADR? |
|-------|--------|-----------------|-------------|
| 1 | 008 | Error types, retry, circuit breaker | — |
| 2 | 004a | Irmin GraphQL client | — |
| 3 | 005 | TreeIndex, LECCache | — |
| 4 | 004a | SSE endpoint | — |
| 5 | 004a, 005 | Invalidation pipeline | ✓ 004a, 005, 008 |
| 6 | 006 | EventHub, conflict detection | — |
| 7 | 007 | Scenario branching | ✓ 006, 007 |
| 8 | 004b | WebSocket (optional) | ✓ 004b |

---

## Open Questions Before Starting

1. **Irmin Setup:** Do we have an Irmin instance running with GraphQL? If not, we may need to set one up first (potential OCaml work).

2. **Recomputation Strategy:** Eager (immediate) or lazy (on-demand) for LEC recomputation?

3. **Backpressure Policy:** Which strategy for slow SSE clients?

4. **Scenario Naming:** Unique per user or globally unique?

5. **Phase 8 Scope:** Include WebSocket in initial implementation or defer?

---

*Document created: 2026-01-17*  
*Status: Awaiting user approval before starting Phase 1*

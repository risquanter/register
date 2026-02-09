# Implementation Plan: ADR Proposals

This plan implements the ADR proposals in logical dependency order, with explicit checkpoints for user approval.

---

## Working Instructions for Agent

### Design Decision Protocol

Before implementing any phase, the agent MUST:

1. **Read relevant ADRs** - Identify which ADRs apply to the phase
2. **Extract technology choices** - Note specific libraries/patterns mandated by ADRs
3. **Present design decisions** - Before writing code, summarize:
   - What the ADR says about implementation approach
   - Any gaps or ambiguities that need user input
   - Alternative approaches if ADR is unclear
4. **Wait for approval** - Do not proceed until user confirms design

### Technology Decision Checkpoints

When a phase introduces **new technology** (SSE, WebSocket, new library), stop and ask:
- Does the ADR specify a technology? If so, quote it.
- Is this technology compatible with existing codebase patterns?
- Are there alternatives worth considering?

### ADR Compliance Verification

For each phase:
1. Run ADR-001 executable checklist (grep commands)
2. Verify logging at service layer (ADR-002)
3. Check error types extend SimulationError (ADR-010)
4. Verify top-level imports (ADR-011)

### Red Flags (Stop and Ask)

- Adding a new dependency not mentioned in ADRs
- Creating a parallel pattern to existing code (e.g., raw HTTP vs Tapir)
- Making infrastructure decisions with multiple valid approaches

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
Phase 1.5: Irmin Dev Environment Setup (prerequisite) ✅ COMPLETE
    ↓
Phase 2: Irmin GraphQL Client (ADR-004a foundation) ✅ COMPLETE
    ↓
Phase 3: Tree Index & Cache Structure (ADR-005 foundation) ✅ COMPLETE
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
- [ ] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [ ] All accepted ADRs validated (ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011)
  - [ ] No deviations introduced during implementation
  - [ ] Completion report follows prescribed format
- [ ] User approves Phase 1

---

## Phase 1.5: Irmin Development Environment Setup

### Status: ✅ COMPLETE

### Objective
Set up a minimal Irmin instance with GraphQL endpoint for local development. This is a prerequisite for Phase 2 (Irmin GraphQL Client).

### Why This Phase Exists
- Irmin is an OCaml library, not a standalone service — requires OCaml toolchain
- No official Docker image available (existing images are 7+ years old)
- Phase 2 needs a running Irmin GraphQL endpoint to develop against
- We need to document the GraphQL schema for client code generation

### Approach Implemented: Alpine Development Image

Used Alpine-based development image for initial setup. Multi-stage distroless migration prepared but deferred until more experience gained with Irmin.

**Implementation:**
- `dev/Dockerfile.irmin` — Multi-stage build with Alpine runtime
- `docker-compose.yml` — Added Irmin service with `--profile persistence`
- `dev/irmin-schema.graphql` — Extracted GraphQL schema (180 lines)

### Completed Tasks ✅

1. **Created Dockerfile.irmin**
   ```
   dev/Dockerfile.irmin
   ```
   - Base: `ocaml/opam:alpine-ocaml-5.2`
   - Installs: irmin-cli, irmin-graphql, irmin-pack, irmin-git
   - System deps: gmp-dev, libffi-dev
   - Binds to 0.0.0.0:8080 for container access

2. **Updated docker-compose.yml**
   - Added `irmin` service on port 9080 (host) → 8080 (container)
   - Volume: `irmin-data` for persistence
   - Profile: `persistence` (start with `--profile persistence`)
   - Healthcheck: GraphQL query with IPv4 address (Alpine resolves localhost to IPv6)

3. **Extracted GraphQL schema**
   ```
   dev/irmin-schema.graphql (180 lines)
   ```
   Key types: Query, Mutation, Subscription, Branch, Commit, Tree, Contents

4. **Verified GraphQL endpoint**
   - Write mutation: ✅ Returns commit hash
   - Read query: ✅ Returns stored values
   - Version tracking: ✅ Parents, timestamps, authors

5. **Created documentation**
   - `docs/DOCKER-DEVELOPMENT.md` — Unified Docker & Development guide
   - `docs/test/TESTING.md` — Consolidated testing guide with Irmin section

### Deliverables
- [x] Dockerfile producing working Irmin image (~650 MB dev image)
- [x] Irmin GraphQL server running locally (port 9080)
- [x] GraphQL schema extracted to `dev/irmin-schema.graphql`
- [x] Docker Compose updated with Irmin service
- [x] Documentation in DOCKER-DEVELOPMENT.md and TESTING.md

### Image Details

| Metric | Value |
|--------|-------|
| Base Image | ocaml/opam:alpine-ocaml-5.2 |
| Final Image Size | ~650 MB (dev with toolchain) |
| Startup Time | ~500ms |
| Memory (idle) | ~100-150 MB |
| Port | 9080 (host) → 8080 (container) |

### Future: Distroless Migration

The Dockerfile is structured for future distroless migration:
- Static binary compilation with musl libc prepared
- Target: < 50 MB runtime image
- Will implement when production deployment is needed

### Approval Checkpoint
- [x] Approach chosen: Alpine development image (distroless deferred)
- [x] Dockerfile created and builds successfully
- [x] Irmin running with GraphQL endpoint on port 9080
- [x] GraphQL schema extracted to `dev/irmin-schema.graphql`
- [x] Docker Compose updated with Irmin service
- [x] Documentation updated (DOCKER-DEVELOPMENT.md, TESTING.md)
- [x] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [x] All accepted ADRs validated
  - [x] No deviations introduced during implementation
  - [x] Completion report follows prescribed format
- [x] User approves Phase 1.5

---

## Phase 2: Irmin GraphQL Client

### Status: ✅ COMPLETE

### Objective
Create the ZIO client that communicates with Irmin via GraphQL for reads and writes.

### ADR Reference
- ADR-004a-proposal: Persistence Architecture (Channel A: Irmin ↔ ZIO)

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- Uses Iron types for paths (validated path format)
- Logs GraphQL operations at service layer (ADR-002)
- Uses extended SimulationError hierarchy: `IrminUnavailable`, `NetworkTimeout` (ADR-010)
- Top-level imports throughout (ADR-011)
- **No retry logic** - delegated to service mesh per ADR-012

### Validation Checklist
- [x] Compliant with ADR-001 (Iron types for paths)
- [x] Compliant with ADR-002 (log GraphQL operations at service layer)
- [x] Compliant with ADR-009 (no impact on aggregation)
- [x] Compliant with ADR-010 (use SimulationError subtypes)
- [x] Compliant with ADR-011 (top-level imports, no FQNs)
- [x] Compliant with ADR-012 (no retry logic, mesh handles resilience)

### Prerequisites
- ✅ Irmin running with GraphQL endpoint on port 9080
- ✅ GraphQL schema extracted to `dev/irmin-schema.graphql`

### Scope: Queries & Mutations Only

This phase implements **queries and mutations** using sttp HTTP client.

> ⚠️ **Subscription Dependency Note:**
> GraphQL subscriptions require WebSocket transport (graphql-ws protocol).
> STTP HTTP client handles request-response but not persistent WebSocket subscriptions.
> 
> **Subscriptions are deferred to Phase 5, Task 0** (`IrminClient.watch`) where the
> WebSocket client dependency decision is made in context of the full cache invalidation
> pipeline. Transport options: Caliban client, sttp-ws, or HTTP polling fallback.
> See Phase 5 Task 0 for detailed rationale and decision criteria.

### Completed Tasks ✅

1. **Created IrminConfig**
   ```
   configs/IrminConfig.scala
   ```
   - ✅ Endpoint URL, branch name, timeout settings
   - ✅ ZLayer factory from application.conf

2. **Defined Irmin data models**
   ```
   infra/irmin/model/IrminPath.scala
   infra/irmin/model/IrminCommit.scala
   infra/irmin/model/IrminResponses.scala
   ```
   - ✅ Iron-refined path type with validation
   - ✅ Commit metadata (hash, timestamp, author, message)
   - ✅ JSON codecs for all GraphQL responses

3. **Created GraphQL queries/mutations**
   ```
   infra/irmin/IrminQueries.scala
   ```
   - ✅ `getValue(path)` query
   - ✅ `setValue(path, value, info)` mutation
   - ✅ `removeValue(path, info)` mutation
   - ✅ `listBranches`, `getMainBranch` queries

4. **Created `IrminClient` service trait**
   ```
   infra/irmin/IrminClient.scala
   ```
   - ✅ ZIO service interface
   - ✅ Methods: `get`, `set`, `remove`, `branches`, `mainBranch`, `healthCheck`

5. **Created `IrminClientLive` implementation**
   ```
   infra/irmin/IrminClientLive.scala
   ```
   - ✅ sttp HTTP client with ZIO backend
   - ✅ Configuration from IrminConfig layer
   - ✅ Errors mapped to SimulationError subtypes (IrminUnavailable, NetworkTimeout)
   - ✅ No retry logic (delegated to service mesh per ADR-012)

6. **Added integration tests**
   ```
   test/.../IrminClientIntegrationSpec.scala
   ```
   - ✅ 8 tests covering all operations
   - ✅ Tests use unique paths to avoid content-addressing collisions
   - ✅ All tests passing

### Deliverables
- [x] Client connects to Irmin GraphQL endpoint
- [x] Can read values (`get`)
- [x] Can write values (`set`) with commit metadata
- [x] Can remove values (`remove`)
- [x] Can list branches and get main branch
- [x] Health check functionality
- [x] Errors wrapped in `IrminUnavailable` / `NetworkTimeout`

### ADR Compliance Review (Post-Implementation)
**Re-validated ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011, ADR-012
**Compliance status:** ✅ All ADRs compliant
**Key validations:**
- ADR-001: IrminPath uses Iron Match constraint for validation
- ADR-002: All GraphQL operations logged at INFO/DEBUG level
- ADR-010: Network errors mapped to IrminUnavailable (503), NetworkTimeout (504)
- ADR-011: All imports are top-level (no FQNs)
- ADR-012: No retry logic in client (delegated to service mesh)

### Approval Checkpoint
- [x] Code compiles
- [x] Integration tests pass (8/8 tests)
- [x] ADR compliance verified post-implementation
- [x] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [x] All accepted ADRs validated (ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011)
  - [x] No deviations introduced during implementation
  - [x] Completion report follows prescribed format
- [x] User approves Phase 2

---

## Phase 3: Tree Index & Cache Structure

### Status: COMPLETE

### Objective
Implement the parent-pointer index and LEC cache data structures that enable O(depth) invalidation.

### ADR Reference
- ADR-005-proposal: Cached Subtree Aggregates
- ADR-009: Compositional Risk Aggregation (RiskResult type)

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- Uses existing RiskResult and LECCurveResponse types (ADR-009)
- Iron-refined NodeId types (ADR-001)
- Cache operations logged at service layer (ADR-002)

### Validation Checklist
- [x] Compliant with ADR-001 (Iron types for NodeId — uses `type NodeId = SafeId.SafeId`)
- [x] Compliant with ADR-002 (cache operations logged)
- [x] Compliant with ADR-009 (uses existing RiskResult, LECCurveResponse)
- [x] Compliant with ADR-010 (cache errors use SimulationError)
- [x] Compliant with ADR-011 (top-level imports)

### Completed Tasks ✅

1. **Created `TreeIndex` data structure**
   ```
   domain/tree/TreeIndex.scala
   ```
   - ✅ `NodeId` — nominal case class wrapper around `SafeId.SafeId` (ADR-018)
   - ✅ `nodes: Map[NodeId, RiskNode]` — O(1) lookup
   - ✅ `parents: Map[NodeId, NodeId]` — child → parent
   - ✅ `children: Map[NodeId, List[NodeId]]` — parent → children
   - ✅ `ancestorPath(nodeId): List[NodeId]` — walk to root
   - ✅ `descendants(nodeId): Set[NodeId]` — subtree nodes
   - ✅ `fromTree(root: RiskNode)` — factory method

2. **Created `RiskResultCache` service** (per ADR-014)
   ```
   services/cache/RiskResultCache.scala
   ```
   - ✅ `Ref[Map[NodeId, RiskResult]]` for cache storage
   - ✅ `get(nodeId): UIO[Option[RiskResult]]`
   - ✅ `set(nodeId, result): UIO[Unit]`
   - ✅ Per-tree cache managed by `TreeCacheManager`

3. **Created `TreeIndexService`**
   ```
   services/tree/TreeIndexService.scala
   ```
   - ✅ `buildFromTree(root: RiskNode): UIO[TreeIndex]`
   - ✅ `ancestorPath(nodeId): UIO[List[NodeId]]`
   - ✅ `descendants(nodeId): UIO[Set[NodeId]]`
   - ✅ `updateTree(newRoot: RiskNode): UIO[TreeIndex]`

4. **Added tests**
   ```
   test/.../TreeIndexSpec.scala (14 tests)
   test/.../RiskResultCacheSpec.scala (11 tests)
   ```
   - ✅ 25 tests all passing
   - ✅ Tests use `SafeId.fromString(...).getOrElse(throw...)` pattern

### Deliverables ✅
- [x] TreeIndex correctly tracks parent pointers
- [x] ancestorPath returns correct leaf-to-root path
- [x] RiskResultCache invalidation clears ancestor entries
- [x] Tests cover edge cases (root node, leaf node, deep tree)
- [x] All internal structures use `NodeId` (SafeId.SafeId) per ADR-001

### ADR-001 Compliance Verification
Ran executable validation checklist:
- ✅ Check 1: No raw String domain IDs in cache/index structures
- ✅ Check 2: No String keys in cache/index structures
- ✅ Check 3: No raw String IDs in service methods
- ✅ Check 4: refineUnsafe only in tests and known-valid literals

### Approval Checkpoint
- [x] Code compiles
- [x] Tests pass (25/25)
- [ ] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [ ] All accepted ADRs validated (ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011)
  - [ ] No deviations introduced during implementation
  - [ ] Completion report follows prescribed format
- [ ] User approves Phase 3

---

## Phase 4: SSE Infrastructure

### Status: COMPLETE

### Objective
Implement Server-Sent Events endpoint for pushing LEC updates and tree changes to browsers.

### ADR Reference
- ADR-004a-proposal: Persistence Architecture (Channel B: ZIO → Browser)

### ADR Compliance Review (Planning Phase)
**Reviewed ADRs:** ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011
**Deviations detected:** None
**Alignment notes:**
- SSE connection lifecycle logged (ADR-002) ✅
- Connection errors use SimulationError subtypes (ADR-010)
- Event payloads may include RiskResult data (ADR-009)
- **Technology clarification:** Tapir uses `streamBody(ZioStreams)` with `text/event-stream` content type (not dedicated SSE output type)

### Validation Checklist
- [x] Compliant with ADR-002 (log SSE connections at service layer)
- [x] Compliant with ADR-009 (event payloads compatible with RiskResult)
- [x] Compliant with ADR-010 (connection errors use SimulationError)
- [x] Compliant with ADR-011 (top-level imports)

### Completed Tasks ✅

1. **Defined SSE event types**
   ```
   http/sse/SSEEvent.scala
   ```
   - ✅ `LECUpdated(nodeId, treeId, quantiles)`
   - ✅ `NodeChanged(nodeId, treeId, changeType)`
   - ✅ `CacheInvalidated(nodeIds, treeId)`
   - ✅ `ConnectionStatus(status, message)`
   - ✅ JSON encoders for all event types

2. **Created SSE Hub service**
   ```
   services/sse/SSEHub.scala
   ```
   - ✅ `subscribe(treeId): UIO[ZStream[Any, Nothing, SSEEvent]]`
   - ✅ `publish(treeId, event): UIO[Int]` (returns subscriber count)
   - ✅ `subscriberCount(treeId): UIO[Int]`
   - ✅ `broadcastAll(event): UIO[Int]`
   - ✅ ZIO Hub for fan-out broadcasting
   - ✅ Explicit subscriber tracking (Hub.size is message count, not subscribers)

3. **Created SSE endpoint (Tapir streaming)**
   ```
   http/sse/SSEEndpoints.scala
   ```
   - ✅ `GET /events/tree/{treeId}` endpoint
   - ✅ Uses `streamBody(ZioStreams)` with `CodecFormat.TextEventStream()`
   - ✅ Returns `Stream[Throwable, Byte]` for SSE wire format
   - ✅ Appears in Swagger documentation

4. **Created SSE controller**
   ```
   http/sse/SSEController.scala
   ```
   - ✅ Wires endpoint to SSEHub
   - ✅ Formats events as SSE wire format (`event: <type>\ndata: <json>\n\n`)
   - ✅ Includes heartbeat stream (30s interval)
   - ✅ Sends connection status on subscribe

5. **Added tests**
   ```
   test/.../SSEHubSpec.scala (7 tests)
   ```
   - ✅ Subscribe returns stream
   - ✅ Publish with no subscribers returns 0
   - ✅ Subscriber receives published event
   - ✅ Multiple subscribers receive same event
   - ✅ SubscriberCount reflects active subscribers
   - ✅ Events for different trees are isolated
   - ✅ BroadcastAll reaches all trees

### Deliverables ✅
- [x] SSE endpoint defined at `/events/tree/{treeId}`
- [x] Events stream to connected clients
- [x] Multiple clients receive broadcasts (fan-out via ZIO Hub)
- [x] Client disconnect tracked in subscriber count
- [x] 7 tests passing

### Technical Notes
- **Tapir SSE:** No dedicated `serverSentEventsBody` - use `streamBody(ZioStreams)` with `text/event-stream`
- **Subscriber tracking:** Hub.size returns pending messages, not subscribers - track separately with Ref
- **Test timing:** Use `@@ TestAspect.withLiveClock` and `Live.live(ZIO.sleep(...))` for timing-based tests

### Approval Checkpoint
- [x] Code compiles
- [x] Tests pass (7/7)
- [ ] Manual test: connect with curl/browser, see events (pending wiring to HttpApi)
- [x] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [x] All accepted ADRs validated (ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011)
  - [x] No deviations introduced during implementation
  - [x] Completion report follows prescribed format
- [x] User approves Phase 4

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

0. **Add `IrminClient.watch` — GraphQL subscription for tree changes** 🔒
   ```
   infra/irmin/IrminClient.scala (trait + companion accessor)
   infra/irmin/IrminClientLive.scala (implementation)
   infra/irmin/IrminQueries.scala (subscription query string)
   infra/irmin/model/IrminResponses.scala (WatchResponse / Diff types)
   ```
   - Extends `IrminClient` trait with: `def watch(path: Option[IrminPath]): ZStream[Any, IrminError, IrminCommit]`
   - Irmin schema: `subscription { watch(path: Path, branch: BranchName): Diff! }` where `Diff { commit: Commit! }`
   - **Requires WebSocket client dependency** — transport decision made here:
     - Option A: Caliban client (built-in ZIO subscription + graphql-ws protocol)
     - Option B: sttp-ws (raw WebSocket, manual graphql-ws framing)
     - Option C: HTTP polling fallback (simplest, no new dep, higher latency)
   - Decision criteria: Does Phase 8 (WebSocket Enhancement / ADR-004b) also need the same dep?
     If yes → choose a dep that serves both. If Phase 8 is distant → polling fallback is fine.
   - **Why not implemented earlier:** No consumer exists until `TreeUpdatePipeline` (Task 3);
     implementing in isolation would create dead code and force a premature transport decision.
   - Add `WatchResponse` / `DiffData` response types to `IrminResponses.scala`
   - Add `watchSubscription(path)` query string to `IrminQueries.scala`
   - Integration test requires live Irmin with persistent WebSocket connection

1. **Create invalidation handler**
   ```
   service/pipeline/InvalidationHandler.scala
   ```
   - Receives Irmin watch events (from Task 0 `ZStream`)
   - Calls `TreeCacheManager.onTreeStructureChanged(treeId)` (invalidates cached results)
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
   - Subscribes to `IrminClient.watch` (Task 0)
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
- [ ] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [ ] All accepted ADRs validated (ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011)
  - [ ] No deviations introduced during implementation
  - [ ] Completion report follows prescribed format
- [ ] User approves Phase 5

---

## Major Checkpoint: Accept ADR-004a, ADR-005, ADR-008

### Review Criteria

**ADR-004a (Persistence Architecture - SSE):**
- [ ] Irmin GraphQL client implemented
- [ ] SSE endpoint working
- [ ] Full data flow demonstrated

**ADR-005 (Cached Subtree Aggregates):**
- [x] TreeIndex with node lookup
- [x] RiskResultCache with per-tree caching (via TreeCacheManager)
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
- [ ] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [ ] All accepted ADRs validated (ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011)
  - [ ] No deviations introduced during implementation
  - [ ] Completion report follows prescribed format
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
- Scenario comparison uses cached RiskResult (ADR-005, ADR-009)

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
   - Use cached RiskResult for fast comparison

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
- [ ] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [ ] All accepted ADRs validated (ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011)
  - [ ] No deviations introduced during implementation
  - [ ] Completion report follows prescribed format
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
- [ ] **Final Review** — Re-read `docs/WORKING-INSTRUCTIONS.md` and verify:
  - [ ] All accepted ADRs validated (ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011)
  - [ ] No deviations introduced during implementation
  - [ ] Completion report follows prescribed format
- [ ] User approves Phase 8

---

## Summary

| Phase | ADR(s) | Key Deliverable | Accept ADR? |
|-------|--------|-----------------|-------------|
| 1 | 008 | Error types, retry, circuit breaker | — |
| 2 | 004a | Irmin GraphQL client | — |
| 3 | 005 | TreeIndex, RiskResultCache | — |
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

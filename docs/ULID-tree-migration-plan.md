# ULID Tree ID Migration Plan (Cutover)

**Status:** Nearly Complete — Phases 1–5 and 7 done, Phase 6 partial  
**Date:** 2026-02-06  
**Last Updated:** 2026-02-09  
**Goal:** Move tree IDs from `NonNegativeLong` to ULID across domain, repository, services, endpoints, tests, and docs. Cutover = no dual-mode; numeric tree IDs are removed in favor of ULID everywhere.

## Constraints & Policies
- Tree names must be unique; node names must be unique within a tree.
- Tree IDs and node IDs must be globally unique (ULID ensures this; add regression tests to guard future ID changes).
- No existing clients/data → no compatibility window needed.

## Phases (sequential, each gated by approval)

### 1. Type & Codec Foundation ✅

- ✅ `TreeId` case class wrapping `SafeId.SafeId` (ULID, 26-char Crockford base32, uppercase).
- ✅ JSON codecs (`JsonEncoder[TreeId]`, `JsonDecoder[TreeId]`) in `TreeId` companion.
- ✅ Tapir codecs (`Codec[String, TreeId, TextPlain]`, `Schema[TreeId]`) in `TapirCodecs`.

### 2. Domain & Service Surface ✅

- ✅ `RiskTreeService` and `RiskTreeRepository` methods all take `TreeId`.
- ✅ Server generates tree ULID via `IdGenerators.nextTreeId` — `RiskTreeDefinitionRequest` has no `id` field, making client-supplied IDs structurally impossible.
- ✅ `ID_NOT_ALLOWED_ON_CREATE` error code defined (never triggered at runtime because DTO excludes `id`).
- ✅ `ensureUniqueTree` enforces tree name uniqueness on create and update.

### 3. Endpoint Layer (API Break) ✅

- ✅ All endpoints use `path[TreeId]("treeId")`.
- ✅ Tapir schema is `Schema.string` for `TreeId`.
- ✅ Cache, SSE, and tree endpoints all use ULID paths.

### 4. Repository & Persistence ✅

- ✅ Irmin paths: `risk-trees/${treeId.value}/nodes/${nodeId.value}` (ULID strings).
- ✅ Listing parses IDs via `TreeId.fromString`.
- ⬜ `test_and_set` coverage: Irmin schema defines it, but not yet exercised in code or tests.

### 5. SSE / Cache / Invalidation ✅

- ✅ `SSEHub` methods take `TreeId`; internal `Ref` maps keyed by `TreeId`.
- ✅ `TreeCacheManager.cacheFor(treeId: TreeId)` — internal maps are `Map[TreeId, ...]`.
- ✅ `SSEEvent` variants carry `treeId: TreeId`.

### 6. Tests & Fixtures ⚠️ Partially Done

- ✅ All tests use `TestHelpers.treeId("label")` which wraps deterministic ULID.
- ✅ Controller spec create-flow exercises server-generated `TreeId`.
- ⬜ No **dedicated** test asserting created trees receive a ULID.
- ⬜ No duplicate-create coverage test (production code `ensureUniqueTree` exists but untested).
- ⬜ No `test_and_set` test coverage.

### 7. Docs & Testing Guides ✅

- ✅ ADR-001, ADR-002, ADR-003, ADR-005, ADR-009, ADR-010, ADR-014, ADR-015, ADR-017 updated.
- ✅ ARCHITECTURE.md, NOTES.md, IRMIN-INTEGRATION.md, RISKTREE-REPOSITORY-IRMIN-PLAN.md updated.
- ✅ IMPLEMENTATION-PLAN-LEC-CACHING.md, IMPLEMENTATION-PLAN-PROPOSALS.md, PLAN-SPLIT-PANE-LEC-UI.md updated.
- ⬜ Remaining `NonNegativeLong` in docs refers only to legitimate `minLoss`/`maxLoss` usage.

## ADR Compliance (planning)
- Reviewed: ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011.
- Planned deviations: none.

## Approval & Stop Points
- Cutover (no dual-mode) approved by user.
- Follow WORKING-INSTRUCTIONS: seek approval before schema/API changes are applied; tests must pass to mark a phase complete.

## Outstanding Items
1. **`test_and_set` coverage** — Irmin schema defines it; no code or tests exercise it yet.
2. **Dedicated server-generated ID test** — create-flow works but lacks explicit assertion.
3. **Duplicate-create test** — `ensureUniqueTree` production code exists; needs test coverage.

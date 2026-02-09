# Irmin ULID Adoption Plan

**Purpose:** Introduce ULIDs (via `com.bilal-fazlani` %% `zio-ulid` % `1.3.1`) for risk tree IDs and node IDs, with a rollout that is safe for multiple service instances sharing the same Irmin backend.

**Status:** Nearly Complete — both node IDs (`NodeId`) and tree IDs (`TreeId`) are ULID throughout domain, services, endpoints, repository, cache, and SSE. Remaining gaps: `test_and_set` coverage, dedicated duplicate-create tests, and doc cleanup.

**Last Updated:** 2026-02-09

## ADR Compliance (planning phase)
- ADR-001 (Iron types): ensure ULIDs are validated/normalized before use.
- ADR-002 (Logging): log IDs safely; no PII embedded; prefer structured logs.
- ADR-009 (Identity aggregation): preserve stable IDs for aggregation semantics.
- ADR-010 (Error handling): keep typed errors; map ID validation errors to domain errors.
- ADR-011 (Import conventions): follow existing Scala import style.
- ADR-012 (Service mesh, retries): no client-side retries beyond existing guidance.

## Scope
- Generate ULIDs for risk trees and node IDs.
- Update persistence paths, DTOs, validation, and repository logic to use ULIDs consistently.
- Maintain compatibility and prevent collisions when multiple service nodes write to the same Irmin backend.

## TODOs (sequenced)
1) Dependencies & wiring
   - ✅ Add `zio-ulid` dependency to common/server/server-it.
   - ✅ Provide a ULID service wrapper for node IDs and tree IDs (`IdGenerators.nextNodeId`, `IdGenerators.nextTreeId`).

2) Domain types
   - ✅ Node IDs: `NodeId(toSafeId: SafeId.SafeId)` — case class wrapping ULID.
   - ✅ Tree IDs: `TreeId(toSafeId: SafeId.SafeId)` — case class wrapping ULID. Clean cutover, no dual-mode.

3) Validation & DTOs
   - ✅ `ValidationUtil.refineId` enforces ULID; `SafeId` spec covers canonical/normalization.
   - ✅ DTO tests updated to use deterministic ULIDs (`safeId`/`idStr`).
   - ✅ `RiskTreeDefinitionRequest` has no `id` field — client-supplied tree IDs are structurally impossible. `ID_NOT_ALLOWED_ON_CREATE` error code defined.
   - ✅ Node IDs on create are server-generated via `IdGenerators.nextNodeId`.

4) Repository & path conventions
   - ✅ Tree and node paths use ULID strings: `risk-trees/{treeUlid}/nodes/{nodeUlid}`.
   - ✅ Repository parsing uses `TreeId.fromString` and `NodeId` (via `SafeId`).
   - ⬜ `test_and_set` Irmin feature: schema defines it, but not yet exercised in code.

5) Service & business logic
   - ✅ All services consume `TreeId` and `NodeId` — ULID throughout.
   - ✅ Server-side ULID generation for trees via `IdGenerators.nextTreeId` (called in `RiskTreeServiceLive.create`).
   - ✅ `ensureUniqueTree` enforces tree name uniqueness on create and update.
   - ⬜ Duplicate-create test coverage: production code exists, test coverage missing.

6) Concurrency & multi-instance safety
   - ✅ Client-side ULID generation for nodes; deterministic helper for tests/fixtures.
   - ⬜ Duplicate protection test (`test_and_set`) not written yet.

7) Migration strategy
   - ✅ Clean cutover for tree IDs to ULID completed. No dual-mode.
   - ✅ All tests and fixtures use ULID tree IDs.
   - ⬜ Communication/versioning plan for the breaking API change not yet written (no downstream consumers currently).

8) Observability & testing
   - ✅ SafeId unit/opaque type specs cover canonicalization and invalid formats.
   - ✅ Integration tests updated to ULID node IDs and tree IDs.
   - ⬜ GraphQL `test_and_set` duplicate-prevention IT; multi-instance collision test still open.
   - ⬜ Dedicated test for server-generated tree ULIDs (create-flow exercises it but no explicit assertion).
   - ⬜ Dedicated duplicate-check coverage on create paths.

9) Documentation
   - ✅ ADR-001, ADR-002, ADR-003, ADR-005, ADR-009, ADR-010, ADR-014, ADR-015, ADR-017, ADR-018 updated.
   - ✅ ARCHITECTURE.md, NOTES.md, IRMIN-INTEGRATION.md, RISKTREE-REPOSITORY-IRMIN-PLAN.md updated.
   - ✅ IMPLEMENTATION-PLAN-LEC-CACHING.md, IMPLEMENTATION-PLAN-PROPOSALS.md, PLAN-SPLIT-PANE-LEC-UI.md updated.

## Open questions
- Should we expose idempotency keys at HTTP create to aid clients, or rely on ULID uniqueness + duplicate checks?
- (Resolved) No downstream systems assume numeric tree IDs — no existing clients/data.

## Notes on error handling and versioning
- Error handling: `ID_NOT_ALLOWED_ON_CREATE` defined but never triggered at runtime (DTO shape prevents it). When duplicates are detected by `ensureUniqueTree`, a domain error with 409 is returned. ULID format errors are `INVALID_FORMAT`.
- Versioning: API change is breaking (tree IDs became ULID, create forbids client IDs). No existing clients, so no migration window needed.

## Next steps
- Write `test_and_set` coverage (Irmin duplicate protection).
- Add dedicated duplicate-create test for `ensureUniqueTree`.
- Complete Phase 7 doc updates (ADR-017, ARCHITECTURE.md, NOTES.md).
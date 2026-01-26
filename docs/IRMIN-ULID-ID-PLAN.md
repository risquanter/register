# Irmin ULID Adoption Plan

**Purpose:** Introduce ULIDs (via `com.bilal-fazlani` %% `zio-ulid` % `1.3.1`) for risk tree IDs and node IDs, with a rollout that is safe for multiple service instances sharing the same Irmin backend.

**Status:** In progress — node IDs now ULID (`SafeId`); tree IDs will migrate to ULID (decision made). Validation + tests already cover ULID nodes; tree-ID migration, server-side generation, and duplicate protections are next.

## ADR Compliance (planning phase)
- ADR-001 (Iron types): ensure ULIDs are validated/normalized before use.
- ADR-002 (Logging): log IDs safely; no PII embedded; prefer structured logs.
- ADR-009 (Identity aggregation): preserve stable IDs for aggregation semantics.
- ADR-010 (Error handling): keep typed errors; map ID validation errors to domain errors.
- ADR-011 (Import conventions): follow existing Scala import style.
- ADR-012 (Service mesh, retries): no client-side retries beyond existing guidance.

## Scope
- Generate ULIDs for risk trees and node IDs (currently `NonNegativeLong` for tree IDs and `SafeId` for node IDs).
- Update persistence paths, DTOs, validation, and repository logic to use ULIDs consistently.
- Maintain compatibility and prevent collisions when multiple service nodes write to the same Irmin backend.

## TODOs (sequenced)
1) Dependencies & wiring
   - ✅ Add `zio-ulid` dependency to common/server/server-it.
   - ✅ Provide a ULID service wrapper for node IDs; extend it to tree IDs during migration.

2) Domain types
   - ✅ Node IDs: `SafeId` now ULID with canonical uppercase constraint.
   - 🔜 Tree IDs: migrate from `NonNegativeLong` to ULID (new refined type, canonical uppercase). No dual-mode period planned unless blockers arise.

3) Validation & DTOs
   - ✅ `ValidationUtil.refineId` enforces ULID; `SafeId` spec covers canonical/normalization.
   - ✅ DTO tests updated to use deterministic ULIDs (`safeId`/`idStr`).
   - 🔜 Public API must reject client-supplied IDs on create (trees/nodes) and generate ULIDs server-side after payload validation; document the contract and error responses.

4) Repository & path conventions
   - ✅ Node paths use ULID strings (`risk-trees/{treeId}/nodes/{ulid}`).
   - 🔜 Tree IDs become ULID: paths shift to `risk-trees/{treeUlid}/nodes/{nodeUlid}`; repository parsing/generation to be updated accordingly.

5) Service & business logic
   - ✅ Services/tests consume ULID node IDs via helpers; provenance/cache specs updated accordingly.
   - 🔜 TreeId ULID generation/wiring: introduce server-side ULID generation for trees; API create endpoints accept missing IDs and must ignore/reject client-supplied IDs to avoid bypassing duplicate checks; generation happens after payload validation.
   - 🔜 Duplicate checks on all create paths (tree and node): enforce uniqueness at the repository/service layer and surface clear 4xx errors when duplicates are attempted.

6) Concurrency & multi-instance safety
   - ✅ Client-side ULID generation for nodes; deterministic helper for tests/fixtures.
   - ⏳ Duplicate protection test (`test_and_set`) not written yet.

7) Migration strategy
   - 🔜 Clean cutover for tree IDs to ULID (no dual-mode). No operational data to migrate (only tests), but update fixtures and test data.
   - 🔜 Communication/versioning plan for the breaking API change (tree IDs become ULID, client-supplied IDs on create are rejected).

8) Observability & testing
   - ✅ SafeId unit/opaque type specs cover canonicalization and invalid formats.
   - ✅ Integration tests updated to ULID node IDs (server-it repository/API happy path).
   - 🔜 Add GraphQL `test_and_set` duplicate-prevention IT; multi-instance collision test still open.
   - 🔜 Add tests for server-generated tree ULIDs and REST create-without-ID contract (client-provided IDs rejected).
   - 🔜 Add duplicate-check coverage on all POST create paths (tree/node).

9) Documentation
   - ✅ This plan reflects ULID node rollout status.
   - ⏳ Update public API docs once tree ID decision is made.

## Open questions
- Should we expose idempotency keys at HTTP create to aid clients, or rely on ULID uniqueness + duplicate checks?
- Any downstream systems (reports/exports) that assume numeric tree IDs?

## Notes on error handling and versioning
- Error handling: when a client supplies an ID on create, return a clear 400 with a domain-specific code (e.g., INVALID_INPUT / ID_NOT_ALLOWED_ON_CREATE). When duplicates are detected by the repository/service, return 409 or domain error signaling duplicate resource. Keep ULID format errors as INVALID_FORMAT.
- Versioning: API change is breaking (tree IDs become ULID, and create forbids client IDs). Communicate via release notes and, if needed, bump API version or provide a migration window with a separate endpoint.

## Next step (pending approval)
- Align on migration approach (dual-mode vs cutover) and update plan accordingly before implementation.
# Irmin ULID Adoption Plan

**Purpose:** Introduce ULIDs (via `com.bilal-fazlani` %% `zio-ulid` % `1.3.1`) for risk tree IDs and node IDs, with a rollout that is safe for multiple service instances sharing the same Irmin backend.

**Status:** Planning

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
   - Add `libraryDependencies += "com.bilal-fazlani" %% "zio-ulid" % "1.3.1"` to `server` and `server-it` where generation/validation is needed.
   - Provide a small ULID service wrapper (pure, no global state) for generation and parsing.

2) Domain types
   - Introduce ULID-refined types for tree IDs and node IDs (Iron-compatible). Decide whether to replace `NonNegativeLong` tree IDs or support dual mode during migration.
   - Ensure casing/format normalization (e.g., uppercase Crockford) to avoid duplicate representations.

3) Validation & DTOs
   - Update `RiskTreeDefinitionRequest` (and any request/response DTOs) to accept/emit ULIDs for node IDs; ensure clear validation errors.
   - Document ID format in API docs (if present) and error messages.

4) Repository & path conventions
   - Update Irmin paths to use ULID strings for tree IDs and node IDs (`risk-trees/{ulid}/nodes/{ulid}`).
   - Adjust parsing/validation where tree IDs were numeric (`NonNegativeLong`).
   - Keep per-node storage model intact (ADR-004a alignment).

5) Service & business logic
   - Propagate ULID types through service signatures (creation returns ULID tree ID; node references use ULIDs).
   - Preserve existing semantics (no retries added; honor ADR-012).

6) Concurrency & multi-instance safety
   - Generation is client-side; ULID randomness + timestamp reduce collision risk—no central coordinator required.
   - Add repository-level duplicate protection (fail fast on existing IDs) to guard concurrent writes.
   - Consider idempotent create semantics in HTTP layer (optional) to mitigate retried requests.
      - Add integration test covering Irmin GraphQL `test_and_set` create-if-absent to ensure duplicate writes are rejected without OCaml changes.

7) Migration strategy
   - Decide on cutover: numeric → ULID for tree IDs (breaking) or dual-mode period (support both, mark numeric deprecated).
   - Backfill existing Irmin data if needed (optional: map old IDs to ULIDs via migration tool and redirect map).

8) Observability & testing
   - Add structured logging for generated IDs (non-sensitive).
   - Tests: unit tests for ULID validation/normalization; integration tests for repo paths and HTTP roundtrips; integration test for GraphQL `test_and_set` duplicate-prevention path; multi-instance simulation (two repos writing to same Irmin) to assert no collisions and proper duplicate detection.

9) Documentation
   - Update `RISKTREE-REPOSITORY-IRMIN-PLAN.md` outstanding tasks with ULID adoption link.
   - Note API contract change (breaking if tree IDs shift to ULID) and versioning/communication strategy.

## Open questions
- Do we need a dual-ID migration window or a clean cutover for tree IDs?
- Should we expose idempotency keys at HTTP create to aid clients, or rely on ULID uniqueness + duplicate checks?
- Any downstream systems (reports/exports) that assume numeric tree IDs?

## Next step (pending approval)
- Align on migration approach (dual-mode vs cutover) and update plan accordingly before implementation.
# ULID Tree ID Migration Plan (Cutover)

**Status:** Draft
**Date:** 2026-02-06
**Goal:** Move tree IDs from `NonNegativeLong` to ULID across domain, repository, services, endpoints, tests, and docs. Cutover = no dual-mode; numeric tree IDs are removed in favor of ULID everywhere.

## Constraints & Policies
- Tree names must be unique; node names must be unique within a tree.
- Tree IDs and node IDs must be globally unique (ULID ensures this; add regression tests to guard future ID changes).
- No existing clients/data → no compatibility window needed.

## Phases (sequential, each gated by approval)

1. **Type & Codec Foundation**
   - Introduce `TreeId` ULID Iron type (canonical uppercase).
   - JSON and Tapir codecs for `TreeId`.
   - No wiring changes yet.

2. **Domain & Service Surface**
   - Switch service/domain signatures to `TreeId` for trees.
   - Create: server generates tree ULID; reject client-supplied tree IDs with 400 (`ID_NOT_ALLOWED_ON_CREATE`).
   - Enforce duplicate checks: tree name uniqueness, global ID uniqueness (tests).

3. **Endpoint Layer (API Break)**
   - Path params use `TreeId` ULID; OpenAPI reflects ULID.
   - Verify swagger/docs output; ensure routes still registered.

4. **Repository & Persistence**
   - Irmin paths: `risk-trees/{treeUlid}/nodes/{nodeUlid}`.
   - Adjust metadata handling and parsing to ULID.
   - Add duplicate-protection/test-and-set coverage if available.

5. **SSE / Cache / Invalidation**
   - Update SSE hub/cache/invalidation surfaces to `TreeId`.
   - Ensure publish/subscribe keys and cache maps use ULID.

6. **Tests & Fixtures**
   - Update unit + integration (server-it) tests to ULID tree IDs.
   - Add tests for server-generated tree IDs and rejection of client IDs on create.
   - Add duplicate-create coverage (tree name / ID uniqueness); guardrails for future ID changes.

7. **Docs & Testing Guides (Final Step)**
   - Update ADR-017, API docs, and any other ADRs referencing numeric tree IDs.
   - Update testing documentation and test plans to reflect ULID tree IDs and new error codes.
   - Note breaking change and release notes (no compatibility period).

## ADR Compliance (planning)
- Reviewed: ADR-001, ADR-002, ADR-003, ADR-009, ADR-010, ADR-011.
- Planned deviations: none.

## Approval & Stop Points
- Cutover (no dual-mode) approved by user.
- Follow WORKING-INSTRUCTIONS: seek approval before schema/API changes are applied; tests must pass to mark a phase complete.

## Outstanding Questions
- None. All policies confirmed: unique names, global ULID IDs, no compatibility window.

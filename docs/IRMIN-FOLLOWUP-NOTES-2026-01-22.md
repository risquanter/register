# Irmin Follow-Up & Hardening Notes

**Date:** 2026-01-22
**Purpose:** Quick-reference plan for improving Irmin-backed RiskTree storage. Use this file to resume work in new sessions. All items align with existing ADRs:
- ADR-004a: per-node storage model and path conventions
- ADR-010: typed error channels (`IO[IrminError, *]`), mapped at boundaries
- ADR-012: no client-side retries; rely on mesh/backoff elsewhere

## Current State (code)
- `RiskTreeRepositoryIrmin` (per-node storage) lives in modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala
- Meta stored at `risk-trees/{treeId}/meta` (fields: name, rootId); nodes stored under `risk-trees/{treeId}/nodes/{nodeId}`
- Irmin client has `list` and uses typed `IrminError`; repository maps to `RepositoryFailure`
- `update` deletes then rewrites (potentially leaves partial state if mid-sequence failure)

## Planned Improvements (ordered)
1) **Safer update ordering (ADR-004a/010/012 compliant)**
   - Overwrite new/updated nodes + meta first, then prune obsolete nodes.
   - If any write fails, leave previous commit state intact (no partial new state).
   - Standardize commit messages/tags: `risk-tree:{id}:update:{txnId}` to correlate history for diffs/time-travel.

2) **Metadata hardening**
   - Extend meta payload with `id`, `createdAt`, `updatedAt` (persisted alongside `name`, `rootId`). Derive timestamps from commit clock or wall-clock; store explicitly for API use without extra Irmin lookups.
   - Fail fast if `rootId` is not present in the node set before writing (preflight validation).
   - Optionally add schema/version field for forward migrations.

3) **Shared type (when needed)**
   - Promote private `Meta` to public `TreeMetadata` only if other layers (DTOs/tests) need to read/write meta. Keep `zio-json` codec co-located.

4) **Error clarity**
   - Distinguish “meta missing” vs “tree absent” vs “node decode failure” in repository errors to aid diagnostics.

5) **Testing & wiring**
   - Add `RiskTreeRepositoryIrminSpec` under server-it (requires Irmin container):
     - CRUD roundtrips, partial failure scenarios, meta presence, root-in-node-set preflight, commit message/tag expectations.
   - Wire Application to allow opting into Irmin repository via config flag (keep in-memory default).
   - Extend HTTP integration specs to run with Irmin wiring once repository spec is green.

## Run References
- Start Irmin: `docker compose --profile persistence up -d`
- Existing Irmin client tests: `sbt "serverIt/testOnly *IrminClientIntegrationSpec"`
- Planned repo tests (once added): `sbt "serverIt/testOnly *RiskTreeRepositoryIrminSpec"`

## Notes
- Time-travel/history remains intact: even deletes are new commits; ordering changes are about consistent HEAD state, not history loss.
- Keep per-node layout (ADR-004a) and typed error channel (ADR-010). Respect ADR-012 by not adding retries in the client layer.

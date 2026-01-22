# Irmin Follow-Up & Hardening Notes

**Date:** 2026-01-22
**Purpose:** Quick-reference plan for improving Irmin-backed RiskTree storage. Use this file to resume work in new sessions. All items align with existing ADRs:
- ADR-004a: per-node storage model and path conventions
- ADR-010: typed error channels (`IO[IrminError, *]`), mapped at boundaries
- ADR-012: no client-side retries; rely on mesh/backoff elsewhere

## Current State (code)
- `RiskTreeRepositoryIrmin` (per-node storage) lives in modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala
- Meta stored at `risk-trees/{treeId}/meta` with `TreeMetadata` (id, name, rootId, schemaVersion, createdAt, updatedAt) using wall-clock millis; nodes stored under `risk-trees/{treeId}/nodes/{nodeId}`
- Irmin client has `list` and uses typed `IrminError`; repository maps to `RepositoryFailure`
- `update` now overwrites nodes + meta first, then prunes obsolete nodes; preflight ensures root exists in node set

## Planned Improvements (ordered)
1) **Safer update ordering (ADR-004a/010/012 compliant)** — ✅ DONE
   - Overwrite new/updated nodes + meta first, then prune obsolete nodes.
   - Standardized commit messages/tags: `risk-tree:{id}:update:{txnId}`.

2) **Metadata hardening** — ✅ DONE
   - Meta now includes `id`, `createdAt`, `updatedAt`, `schemaVersion` (wall-clock millis persisted alongside `name`, `rootId`).
   - Preflight root-in-node-set validation before writes.

3) **Shared type (when needed)** — ✅ DONE
   - Public `TreeMetadata` with `zio-json` codec.

4) **Error clarity** — ✅ PARTIAL
   - Distinguish meta-missing with existing nodes (fail fast) vs absent tree; node decode failures surfaced per-node.

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

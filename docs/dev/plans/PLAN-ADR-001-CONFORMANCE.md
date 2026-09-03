# PLAN — ADR-001 conformance citation sweep (TODO #36, Option C)

**Scope:** the source-comment half of TODO #36's ADR-001 conformance work. The
ADR-001 restructure and the new appendix (both docs, ungated) are already landed.
This document exists only to carry the `## File inventory` the enforcement hook
needs to authorize the comment-only citation edits in `modules/**`; it is a bare
inventory, not a full implementation-grade plan (per the user's waiver for #36).

All edits below are **comment-only** — no signature, type, endpoint, DTO, or
behaviour changes. They re-point ADR-001 `§`-anchored citations to the new
four-Decision numbering and to `ADR-001-appendix.md`.

## Citation mapping

- `services/QueryService.scala:16` — `ADR-001 §4` → `ADR-001 §2` (no validation in service methods)
- `domain/data/iron/OpaqueTypes.scala:604,625` — `ADR-001 §4` → `ADR-001 §3` (Iron map-key codecs)
- `domain/data/iron/OpaqueTypes.scala:34,42` — `ADR-001 §8` → `ADR-001-appendix.md, "External-System Output Boundary Constraints"`
- `domain/data/iron/ValidationUtil.scala:112,129` — `ADR-001 §8` → appendix, same anchor
- `configs/SpiceDbConfig.scala:19,76` — `ADR-001 §8` → appendix, same anchor
- `app/state/LECChartState.scala:65` — `ADR-001 §4` → `ADR-001 §3`
- `app/state/TreeViewState.scala:130` — `ADR-001 §7` → `ADR-001 §3`
- `http/codecs/IronTapirCodecs.scala:80` — named anchor "JSON Bodies with Iron Types" → `ADR-001 §2`

`http/requests/QueryRequest.scala:20` cites `ADR-001 §1` (parse-don't-validate),
which remains §1 — no edit.

## File inventory

- modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala
- modules/server/src/main/scala/com/risquanter/register/configs/SpiceDbConfig.scala
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/main/scala/app/state/TreeViewState.scala
- modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala

## Verification

- `grep -rn 'ADR-001 §' modules --include='*.scala'` shows only §1/§2/§3 (no §4/§5/§6/§7/§8).
- `sbt 'commonJVM/test; server/test'` and `sbt app/test` green (comment-only, so a
  clean compile is the substantive check; full suite confirms no accidental change).

# File inventory — PLAN-ADR-001-CONFORMANCE.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


- modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala
- modules/server/src/main/scala/com/risquanter/register/configs/SpiceDbConfig.scala
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/main/scala/app/state/TreeViewState.scala
- modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala

# File inventory — PLAN-IRMIN-GRAPHQL-VARIABLES.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala
- modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala
- modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminResponses.scala
- modules/server/src/test/scala/com/risquanter/register/infra/irmin/IrminQueriesSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminClientIntegrationSpec.scala

# File inventory — PLAN-IRMIN-RECURSIVE-READ.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClient.scala` — one trait method plus its companion accessor
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminClientLive.scala` — the implementation and its private extractor
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/IrminQueries.scala` — the GraphQL query builder
- `modules/server/src/main/scala/com/risquanter/register/infra/irmin/model/IrminResponses.scala` — four response case classes and their JSON decoders
- `modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala` — `readNodesAt` and `readMitigationsAt` bodies
- `modules/server-it/src/test/scala/com/risquanter/register/http/support/CountingIrminClient.scala` — new — the call-counting wrapper
- `modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala` — the call-count test

Any in-memory or stub implementation of `IrminClient` gains the new method.
Adding a method to a trait is a compile error at every implementation, so the
compiler produces this list exhaustively; implementations found that way are
added to this inventory before being edited, not edited under this sentence.

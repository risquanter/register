# File inventory — PLAN-ADR-008-CITATION-SWEEP.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala
- modules/common/src/test/scala/com/risquanter/register/domain/errors/ErrorResponseSpec.scala
- modules/app/src/main/scala/app/state/GlobalError.scala
- modules/app/src/main/scala/app/views/ErrorBanner.scala

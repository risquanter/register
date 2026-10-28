# File inventory — PLAN-SSE-EVENT-ENUMS.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


The enforcement hook authorizes gated edits only from bullet lines in this H2
section (up to the next `## ` heading). Approving the plan (token → this
document) authorizes every file below.

- modules/server/src/main/scala/com/risquanter/register/http/sse/SSEEvent.scala
- modules/server/src/main/scala/com/risquanter/register/http/sse/SSEController.scala
- modules/server/src/test/scala/com/risquanter/register/services/sse/SSEHubSpec.scala

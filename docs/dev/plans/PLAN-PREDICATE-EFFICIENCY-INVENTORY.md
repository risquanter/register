# File inventory — PLAN-PREDICATE-EFFICIENCY.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.


- modules/server/src/main/scala/com/risquanter/register/foladapter/RiskTreeKnowledgeBase.scala
- build.sbt

`build.sbt` is listed only for the PATCH version bump. `.env` and `.env.irmin`
are mirrored from it (not hook-gated, handled in the same landing).
`TreeIndex.descendants` is **not** touched — it stays the right tool wherever the
whole subtree set is genuinely needed (change fan-out). No test file changes: the
existing `RiskTreeKnowledgeBaseSpec` truth-table and irreflexivity suites plus
`BinderIntegrationSpec` are the oracle that the rewrite preserves behaviour.

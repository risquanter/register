# File inventory — PLAN-MITIGATION-TREE-ROWS.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/app/src/main/scala/app/views/TreeDetailView.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/views/TreeListView.scala
- modules/app/src/main/scala/app/components/TreeNodeRow.scala
- modules/app/src/main/scala/app/components/Icons.scala
- modules/app/src/main/scala/app/state/SelectionEdit.scala
- modules/app/styles/app.css
- modules/app/src/test/scala/app/state/SelectionEditSpec.scala
- modules/app/src/test/scala/app/components/TreeNodeRowSpec.scala
- modules/app/src/main/scala/app/components/BranchCard.scala

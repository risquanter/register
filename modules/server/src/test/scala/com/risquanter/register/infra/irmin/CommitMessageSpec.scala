package com.risquanter.register.infra.irmin

import zio.test.*
import com.risquanter.register.domain.data.iron.{ScenarioName, WorkspaceId}
import com.risquanter.register.http.responses.{HistoryOperation, TreeWriteKind}
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId}

/** The commit-message convention: one owner renders and parses every message
  * this server writes, under the law `parse(render(m)) == Some(m)`. */
object CommitMessageSpec extends ZIOSpecDefault:

  private val ws       = WorkspaceId(safeId("commit-ws"))
  private val tree     = treeId("commit-tree")
  private val scenario = ScenarioName.fromString("draft-a").toOption.get

  private val every: List[CommitMessage] =
    TreeWriteKind.values.toList.map(CommitMessage.TreeWritten(ws, tree, _)) ++
      List(CommitMessage.MergePrepared(ws, scenario), CommitMessage.ScenarioMerged(ws, scenario))

  def spec = suite("CommitMessage")(
    test("parse(render(m)) == Some(m) for every case and every tree write") {
      assertTrue(every.forall(m => CommitMessage.parse(CommitMessage.render(m)).contains(m)))
    },
    test("render produces the spellings already in the store") {
      assertTrue(
        CommitMessage.render(CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Create)) == s"workspace:${ws.value}:risk-tree:${tree.value}:create",
        CommitMessage.render(CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Update)) == s"workspace:${ws.value}:risk-tree:${tree.value}:update",
        CommitMessage.render(CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Delete)) == s"workspace:${ws.value}:risk-tree:${tree.value}:delete",
        CommitMessage.render(CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Revert)) == s"workspace:${ws.value}:risk-tree:${tree.value}:revert",
        CommitMessage.render(CommitMessage.ScenarioMerged(ws, scenario)) == s"workspace:${ws.value}:merge-scenario:draft-a",
        CommitMessage.render(CommitMessage.MergePrepared(ws, scenario)) == s"workspace:${ws.value}:merge-prepare:draft-a"
      )
    },
    test("a message this server did not write parses to None and shows as Other") {
      assertTrue(
        CommitMessage.parse("some external commit").isEmpty,
        CommitMessage.parse("").isEmpty,
        CommitMessage.historyOperation(CommitMessage.parse("some external commit")).contains(HistoryOperation.Other())
      )
    },
    test("a message whose identifier segments do not refine parses to None") {
      assertTrue(
        CommitMessage.parse(s"workspace:not-a-ulid:risk-tree:${tree.value}:update").isEmpty,
        CommitMessage.parse(s"workspace:${ws.value}:risk-tree:${tree.value}:rename").isEmpty,
        CommitMessage.parse(s"workspace:${ws.value}:merge-scenario:Not A Name").isEmpty
      )
    },
    test("the history shows a tree write and a merge, and not a merge's preparation write") {
      assertTrue(
        CommitMessage.historyOperation(Some(CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Revert)))
          .contains(HistoryOperation.TreeWrite(TreeWriteKind.Revert)),
        CommitMessage.historyOperation(Some(CommitMessage.ScenarioMerged(ws, scenario))).contains(HistoryOperation.Merge()),
        CommitMessage.historyOperation(Some(CommitMessage.MergePrepared(ws, scenario))).isEmpty
      )
    }
  )

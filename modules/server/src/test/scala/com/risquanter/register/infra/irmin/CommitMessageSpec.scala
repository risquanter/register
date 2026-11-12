package com.risquanter.register.infra.irmin

import zio.NonEmptyChunk
import zio.test.*
import com.risquanter.register.domain.data.iron.{ScenarioName, WorkspaceId}
import com.risquanter.register.domain.errors.{ErrorLocation, ExpectedInput, FieldProblem, ValidationError, ValueFormat}
import com.risquanter.register.http.responses.{HistoryOperation, TreeWriteKind}
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId}

/** The commit-message convention: one owner renders and parses every message
  * this server writes, under the law `parse(render(m)) == Right(m)`. */
object CommitMessageSpec extends ZIOSpecDefault:

  private val ws       = WorkspaceId(safeId("commit-ws"))
  private val tree     = treeId("commit-tree")
  private val scenario = ScenarioName.fromString("draft-a").toOption.get

  private val every: List[CommitMessage] =
    TreeWriteKind.values.toList.map(CommitMessage.TreeWritten(ws, tree, _)) ++
      List(CommitMessage.MergePrepared(ws, scenario), CommitMessage.ScenarioMerged(ws, scenario))

  /** The whole message, or a segment, did not read as the convention declares. */
  private val unreadable: ValidationError =
    ValidationError(ErrorLocation.Whole, FieldProblem.Unreadable(ExpectedInput.Declared))

  def spec = suite("CommitMessage")(
    test("parse(render(m)) == Right(m) for every case and every tree write") {
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
    test("a message this server did not write is refused as the message the reader declares") {
      assertTrue(
        CommitMessage.parse("some external commit") == Left(NonEmptyChunk(unreadable)),
        CommitMessage.parse("") == Left(NonEmptyChunk(unreadable))
      )
    },
    test("an identifier segment that does not refine carries the identifier's own refusal") {
      assertTrue(
        CommitMessage.parse(s"workspace:not-a-ulid:risk-tree:${tree.value}:update").left.map(_.map(_.problem)) ==
          Left(NonEmptyChunk(FieldProblem.WrongFormat(ValueFormat.Ulid)))
      )
    },
    test("a write kind outside the set, and a segment spelled otherwise than render spells it, are refused as declared") {
      assertTrue(
        CommitMessage.parse(s"workspace:${ws.value}:risk-tree:${tree.value}:rename") == Left(NonEmptyChunk(unreadable)),
        CommitMessage.parse(s"workspace:${ws.value}:merge-scenario:Draft-A") == Left(NonEmptyChunk(unreadable))
      )
    },
    test("the refusals of several segments accumulate") {
      assertTrue(
        CommitMessage.parse("workspace:not-a-ulid:risk-tree:also-not:rename").left.map(_.length) == Left(3)
      )
    },
    test("the history shows a tree write and a merge, and not a merge's preparation write") {
      assertTrue(
        CommitMessage.historyOperation(CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Revert))
          .contains(HistoryOperation.TreeWrite(TreeWriteKind.Revert)),
        CommitMessage.historyOperation(CommitMessage.ScenarioMerged(ws, scenario)).contains(HistoryOperation.Merge()),
        CommitMessage.historyOperation(CommitMessage.MergePrepared(ws, scenario)).isEmpty
      )
    }
  )

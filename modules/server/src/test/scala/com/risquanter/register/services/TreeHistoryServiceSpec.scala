package com.risquanter.register.services

import zio.*
import zio.test.*
import java.time.Instant
import io.github.iltotore.iron.*
import com.risquanter.register.auth.{Authority, Checked, TestChecked}
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, MergeStagingRef, PositiveInt, ScenarioName, StoreBranch, WorkspaceId}
import com.risquanter.register.domain.errors.{Core, ErrorLocation, Failure, OutageMode, PeerZone}
import com.risquanter.register.http.responses.{HistoryOperation, TreeWriteKind}
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient}
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminCommit, IrminInfo, IrminPath, IrminTreeEntry}
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId}

/** Tests for `TreeHistoryServiceLive`: which commits a history shows and how
  * many it asks Irmin for. */
object TreeHistoryServiceSpec extends ZIOSpecDefault:

  private given Checked[Authority] = TestChecked.value

  private val ws       = WorkspaceId(safeId("history-ws"))
  private val tree     = treeId("history-tree")
  private val scenario = ScenarioName.fromString("draft-a").toOption.get

  private def commitWith(fill: Char, message: CommitMessage): IrminCommit =
    val hash = CommitHash.fromString(fill.toString * 40).toOption.get
    IrminCommit(hash, hash, Nil, IrminInfo(Instant.EPOCH, message))

  private def limit(n: Int): PositiveInt = n.refineUnsafe

  /** Irmin for the history read only: `getHistory` answers the newest `n`
    * commits of `log`, oldest first, and records the `n` it was asked for, or
    * fails with `outage` when one is given. */
  private final class FakeHistoryIrmin(log: List[IrminCommit], asked: Ref[List[Int]], outage: Option[Failure]) extends IrminClient:
    override def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main): IO[Failure, List[IrminCommit]] =
      outage.fold(asked.update(_ :+ (n: Int)).as(log.takeRight(n)))(ZIO.fail(_))

    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def set(path: IrminPath, value: String, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def set(path: IrminPath, value: String, message: CommitMessage, staging: MergeStagingRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def remove(path: IrminPath, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def branches = ZIO.die(new NotImplementedError("unused by this spec"))
    override def mainBranch: IO[Failure, Option[IrminBranch]] = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getBranch(branch: StoreBranch) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def revert(commit: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def createBranchAt(branch: StoreBranch, at: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def deleteBranch(branch: StoreBranch, currentHead: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getCommit(hash: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getAtCommit(commit: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def listAtCommit(commit: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def lca(branch: BranchRef, commit: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def healthCheck = ZIO.die(new NotImplementedError("unused by this spec"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))

  // Oldest first, as Irmin's `last_modified` returns them: a create, a save,
  // then one merge (its preparation write, then its final write). The create
  // is listed twice, as Irmin lists a commit reached through both parents of
  // a merge commit.
  private val log = List(
    commitWith('d', CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Create)),
    commitWith('d', CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Create)),
    commitWith('c', CommitMessage.TreeWritten(ws, tree, TreeWriteKind.Update)),
    commitWith('b', CommitMessage.MergePrepared(ws, scenario)),
    commitWith('a', CommitMessage.ScenarioMerged(ws, scenario))
  )

  private def history(n: Int): IO[Failure, (List[HistoryOperation], List[Int])] =
    for
      asked   <- Ref.make(List.empty[Int])
      entries <- TreeHistoryServiceLive(FakeHistoryIrmin(log, asked, None))
                   .history(ws, tree, BranchRef.Main, limit(n))
      seen    <- asked.get
    yield (entries.map(_.operation), seen)

  def spec = suite("TreeHistoryServiceLive")(

    suite("history")(
      test("a merge's preparation commit is not shown, a commit listed twice is shown once, oldest first") {
        history(10).map { case (operations, _) =>
          assertTrue(operations == List(
            HistoryOperation.TreeWrite(TreeWriteKind.Create),
            HistoryOperation.TreeWrite(TreeWriteKind.Update),
            HistoryOperation.Merge()
          ))
        }
      },
      test("limit counts the entries shown and keeps the newest, and Irmin is asked for twice as many commits") {
        history(2).map { case (operations, asked) =>
          assertTrue(
            operations == List(HistoryOperation.TreeWrite(TreeWriteKind.Update), HistoryOperation.Merge()),
            asked == List(4)
          )
        }
      },
      test("a store that does not answer fails the read with that value, not with a repository failure") {
        val outage: Failure =
          Failure.Unanswered(Core(PeerZone.Store, NonEmptyChunk((ErrorLocation.Whole, OutageMode.ConnectionRefused)), Nil))
        for
          asked <- Ref.make(List.empty[Int])
          exit  <- TreeHistoryServiceLive(FakeHistoryIrmin(log, asked, Some(outage)))
                     .history(ws, tree, BranchRef.Main, limit(10)).exit
        yield assertTrue(exit == Exit.fail(outage))
      },
      test("the number of commits asked for saturates instead of overflowing") {
        assertTrue(TreeHistoryServiceLive.requested(limit(Int.MaxValue)) == limit(Int.MaxValue))
      },
      test("an entry's timestamp is the commit's instant in ISO-8601") {
        for
          asked   <- Ref.make(List.empty[Int])
          entries <- TreeHistoryServiceLive(FakeHistoryIrmin(log, asked, None))
                       .history(ws, tree, BranchRef.Main, limit(10))
        yield assertTrue(entries.map(_.at).forall(_ == Instant.EPOCH.toString))
      }
    )
  )

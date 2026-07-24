package com.risquanter.register.services

import zio.*
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, PositiveInt}
import com.risquanter.register.domain.errors.{IrminError, IrminGraphQLError, IrminMergeConflict, MergeConflict}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry, IrminPath}
import com.risquanter.register.testutil.TestHelpers.safeId

/** Unit tests for `ScenarioMergeServiceLive`'s error mapping around
  * `IrminClient.mergeBranch` — no Docker/live Irmin required. The patched
  * Irmin backend refuses a conflicting merge as `IrminMergeConflict`
  * (pinned live by `IrminMergeSemanticsSpec`); this spec pins the service's
  * translation of that failure and that unrelated Irmin errors pass through
  * untranslated.
  */
object ScenarioMergeServiceSpec extends ZIOSpecDefault:

  private given Checked[Permission] = TestChecked.value

  private def ws(label: String): WorkspaceId = WorkspaceId(safeId(label))
  private def name(s: String): ScenarioName.ScenarioName = ScenarioName.fromString(s).toOption.get
  private def hash(fill: Char): CommitHash = CommitHash.fromString(fill.toString * 40).toOption.get

  private def commit(h: CommitHash): IrminCommit =
    IrminCommit(h.value, "", Nil, IrminInfo("2026-01-01T00:00:00Z", "test", "test"))

  /** Fake for the merge path only. Main and the scenario share the LCA with
    * main's head (main has not moved since the fork), so the pre-check scan
    * short-circuits to "no conflicts" without touching `list`/`get`, and
    * `merge` proceeds straight to `mergeBranch`, whose outcome is injected.
    */
  private final class FakeMergeIrmin(
    mainHead: CommitHash,
    scenarioHead: CommitHash,
    mergeResult: IO[IrminError, IrminCommit]
  ) extends IrminClient:
    private def branchOf(n: String, h: CommitHash): IrminBranch = IrminBranch(n, Some(commit(h)))

    override def mainBranch: IO[IrminError, Option[IrminBranch]] =
      ZIO.succeed(Some(branchOf("main", mainHead)))
    override def getBranch(branch: BranchRef): IO[IrminError, Option[IrminBranch]] =
      ZIO.succeed(Some(branchOf(branch.toBranchRef, scenarioHead)))
    override def lca(branch: BranchRef, c: CommitHash): IO[IrminError, List[IrminCommit]] =
      ZIO.succeed(List(commit(mainHead)))
    override def mergeBranch(from: BranchRef, into: BranchRef, message: String): IO[IrminError, IrminCommit] =
      mergeResult

    override def branches = ZIO.die(new NotImplementedError("unused by this spec"))
    override def createBranchAt(branch: BranchRef, at: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def deleteBranch(branch: BranchRef, currentHead: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def set(path: IrminPath, value: String, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def remove(path: IrminPath, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def revert(c: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getCommit(commitHash: CommitHash) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getAtCommit(c: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))
    override def healthCheck = ZIO.die(new NotImplementedError("unused by this spec"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by this spec"))

  private def service(mergeResult: IO[IrminError, IrminCommit]): ScenarioMergeService =
    ScenarioMergeServiceLive(FakeMergeIrmin(hash('a'), hash('b'), mergeResult))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ScenarioMergeServiceSpec")(
      test("a refused merge (IrminMergeConflict) maps to the domain MergeConflict") {
        val svc = service(ZIO.fail(IrminMergeConflict("Recursive merging of common ancestors: default")))
        svc.merge(ws("ws-conflict"), name("draft-v1")).exit
          .map(exit => assert(exit)(fails(isSubtype[MergeConflict](anything))))
      },
      test("an unrelated Irmin failure passes through untranslated") {
        val svc = service(ZIO.fail(IrminGraphQLError(List("boom"), None)))
        svc.merge(ws("ws-other-error"), name("draft-v1")).exit
          .map(exit => assert(exit)(fails(isSubtype[IrminGraphQLError](anything))))
      },
      test("a successful merge returns the merge commit's head") {
        val svc = service(ZIO.succeed(commit(hash('c'))))
        svc.merge(ws("ws-clean"), name("draft-v1"))
          .map(newHead => assertTrue(newHead == hash('c')))
      }
    )

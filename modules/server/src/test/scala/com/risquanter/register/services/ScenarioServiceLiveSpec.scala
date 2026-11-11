package com.risquanter.register.services

import zio.*
import zio.prelude.NonEmptyList
import zio.test.*

import java.time.Instant
import com.risquanter.register.auth.{Authority, Checked, TestChecked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, MergeStagingRef, PositiveInt, StoreBranch}
import com.risquanter.register.domain.errors.{
  Core, Diagnostic, ErrorLocation, Failure, FieldName, FieldProblem, RequestHeaderName, TrustZone, ValidationError
}
import com.risquanter.register.http.errors.ConflictKind
import com.risquanter.register.http.responses.TreeWriteKind
import com.risquanter.register.infra.irmin.{CommitMessage, IrminClient}
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry, IrminPath, IrminWriteOutcome}
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId}

/** Unit tests for `ScenarioServiceLive` against an in-memory `IrminClient` fake —
  * no Docker/live Irmin required. Covers create/list/delete happy paths, both
  * refused compare-and-set outcomes as the caller's `Conflict`, the ForkOf-not-found
  * and empty-main paths, and that `list` excludes both `main` and other workspaces.
  */
object ScenarioServiceLiveSpec extends ZIOSpecDefault:

  private given Checked[Authority] = TestChecked.value

  private def ws(label: String): WorkspaceId = WorkspaceId(safeId(label))
  private def name(s: String): ScenarioName.ScenarioName = ScenarioName.fromString(s).toOption.get
  private def hash(fill: Char): CommitHash = CommitHash.fromString(fill.toString * 40).toOption.get

  /** The information every fixture commit carries; no assertion here reads it. */
  private val info: IrminInfo =
    IrminInfo(Instant.EPOCH, CommitMessage.TreeWritten(ws("fixture-ws"), treeId("fixture-tree"), TreeWriteKind.Update))

  /** The branch a fixture's raw name spells, as the live client refines it. */
  private def storeBranch(name: String): StoreBranch =
    StoreBranch.fromString(name, ErrorLocation.Whole).fold(e => throw new IllegalArgumentException(e.mkString(";")), identity)

  /** Minimal in-memory `IrminClient`: only `branches`/`getBranch`/`mainBranch`/
    * `createBranchAt`/`deleteBranch` are exercised by `ScenarioServiceLive` — every
    * other method is unused by it and dies loudly if a test accidentally hits it.
    */
  private final class FakeIrminClient(state: Ref[Map[String, CommitHash]]) extends IrminClient:
    private def toBranch(branch: StoreBranch, headOpt: Option[CommitHash]): IrminBranch =
      IrminBranch(branch, headOpt.map(h => IrminCommit(h, h, Nil, info)))

    override def branches: IO[Failure, List[StoreBranch]] = state.get.map(_.keys.toList.map(storeBranch))

    override def mainBranch: IO[Failure, Option[IrminBranch]] =
      state.get.map(m => Some(toBranch(BranchRef.Main, m.get("main"))))

    override def getBranch(branch: StoreBranch): IO[Failure, Option[IrminBranch]] =
      state.get.map(m => m.get(branch.name).map(h => toBranch(branch, Some(h))))

    override def createBranchAt(branch: StoreBranch, at: CommitHash): IO[Failure, IrminWriteOutcome] =
      state.modify { m =>
        m.get(branch.name) match
          case Some(head) => (IrminWriteOutcome.Refused(Some(head)), m)
          case None       => (IrminWriteOutcome.Applied, m + (branch.name -> at))
      }

    override def deleteBranch(branch: StoreBranch, currentHead: CommitHash): IO[Failure, IrminWriteOutcome] =
      state.modify { m =>
        m.get(branch.name) match
          case Some(head) if head == currentHead => (IrminWriteOutcome.Applied, m - branch.name)
          case head                              => (IrminWriteOutcome.Refused(head), m)
      }

    override def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): IO[Failure, IrminWriteOutcome] =
      state.modify { m =>
        m.get(branch.name) match
          case Some(head) if head == expectedHead => (IrminWriteOutcome.Applied, m + (branch.name -> to))
          case head                               => (IrminWriteOutcome.Refused(head), m)
      }

    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def set(path: IrminPath, value: String, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def set(path: IrminPath, value: String, message: CommitMessage, staging: MergeStagingRef) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def remove(path: IrminPath, message: CommitMessage, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def mergeCommit(from: CommitHash, into: StoreBranch, message: CommitMessage) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def revert(commit: CommitHash, branch: BranchRef) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def getCommit(commitHash: CommitHash) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def getAtCommit(commit: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def listAtCommit(commit: CommitHash, path: IrminPath) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def getHistory(path: IrminPath, n: PositiveInt, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    /** Ancestry in this fake is one level deep: a commit counts as reachable
      * from a branch when it is that branch's head. `ScenarioServiceLive` reads
      * only whether the commit appears among the ancestors, so one level is
      * enough to exercise the gate, and a deeper model would add a commit graph
      * no assertion here reads. */
    override def lca(branch: BranchRef, commit: CommitHash) =
      state.get.map(_.get(branch.name).filter(_ == commit).map(c => IrminCommit(c, c, Nil, info)).toList)
    override def healthCheck = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))

  /** `initial` maps raw branch names (e.g. "main", "scenarios.<ws>.draft-v1") to heads. */
  private def makeService(initial: Map[String, CommitHash] = Map.empty): UIO[ScenarioService] =
    Ref.make(initial).map(state => new ScenarioServiceLive(FakeIrminClient(state)))

  /** The conflict a refused compare-and-set answers the caller with. */
  private def conflict(location: ErrorLocation, kind: ConflictKind, diagnostics: List[Diagnostic]): Failure =
    Failure.Conflict(Core(TrustZone.Client, NonEmptyList(location -> kind), diagnostics))

  /** The caller's refusal of the request's `source`. */
  private def sourceRefused(problem: FieldProblem): Failure =
    Failure.rejected(TrustZone.Client, NonEmptyList(ValidationError(ErrorLocation.requestField(FieldName.Source), problem)), Nil)

  /** The stale-head conflict a delete expecting `expected` answers when the head is `actual`. */
  private def staleHead(scenario: String, expected: CommitHash, actual: Option[CommitHash]): Failure =
    conflict(
      ErrorLocation.Header(RequestHeaderName.IfMatch),
      ConflictKind.ScenarioHeadStale(name(scenario)),
      List(Diagnostic.ExpectedHead(expected), Diagnostic.ActualHead(actual)))

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ScenarioServiceLive")(
      test("create from main forks main's current head") {
        val wsId = ws("ws-alpha")
        for
          svc    <- makeService(Map("main" -> hash('a')))
          branch <- svc.create(wsId, name("draft-v1"), ScenarioSource.Main)
          fetched <- svc.list(wsId)
        yield assertTrue(
          branch.toBranchRef == s"scenarios.${wsId.value.toLowerCase}.draft-v1",
          fetched == List(ScenarioSummary(name("draft-v1"), hash('a')))
        )
      },

      test("create fails when the workspace has no main content to fork from") {
        val wsId = ws("ws-empty")
        for
          svc  <- makeService(Map.empty)
          exit <- svc.create(wsId, name("draft-v1"), ScenarioSource.Main).exit
        yield assertTrue(exit.causeOption.flatMap(_.failureOption) ==
          Some(sourceRefused(FieldProblem.WorkspaceHasNoContent)))
      },

      test("create ForkOf an existing scenario forks that scenario's head, not main's") {
        val wsId = ws("ws-fork")
        val existingBranch = s"scenarios.${wsId.value.toLowerCase}.draft-v1"
        for
          svc    <- makeService(Map("main" -> hash('a'), existingBranch -> hash('b')))
          branch <- svc.create(wsId, name("draft-v2"), ScenarioSource.ForkOf(name("draft-v1")))
          fetched <- svc.list(wsId)
        yield assertTrue(
          fetched.toSet == Set(ScenarioSummary(name("draft-v1"), hash('b')), ScenarioSummary(name("draft-v2"), hash('b')))
        )
      },

      test("create ForkOf a nonexistent scenario fails with a typed not-found error") {
        val wsId = ws("ws-fork-missing")
        for
          svc  <- makeService(Map("main" -> hash('a')))
          exit <- svc.create(wsId, name("draft-v2"), ScenarioSource.ForkOf(name("ghost"))).exit
        yield assertTrue(exit.causeOption.flatMap(_.failureOption) ==
          Some(sourceRefused(FieldProblem.ScenarioNotFound(name("ghost")))))
      },

      test("create fails with the caller's Conflict at the name on a name collision") {
        val wsId = ws("ws-collide")
        val existingBranch = s"scenarios.${wsId.value.toLowerCase}.draft-v1"
        for
          svc  <- makeService(Map("main" -> hash('a'), existingBranch -> hash('a')))
          exit <- svc.create(wsId, name("draft-v1"), ScenarioSource.Main).exit
        yield assertTrue(exit.causeOption.flatMap(_.failureOption) ==
          Some(conflict(ErrorLocation.requestField(FieldName.Name), ConflictKind.Rejected(), Nil)))
      },

      test("list excludes main and other workspaces' scenarios") {
        val wsId = ws("ws-list")
        val other = ws("ws-other")
        for
          svc <- makeService(Map(
                   "main"                                                  -> hash('a'),
                   s"scenarios.${wsId.value.toLowerCase}.draft-v1"         -> hash('b'),
                   s"scenarios.${other.value.toLowerCase}.someone-elses"   -> hash('c')
                 ))
          fetched <- svc.list(wsId)
        yield assertTrue(fetched == List(ScenarioSummary(name("draft-v1"), hash('b'))))
      },

      test("list returns empty for a workspace with no scenarios") {
        val wsId = ws("ws-none")
        for
          svc     <- makeService(Map("main" -> hash('a')))
          fetched <- svc.list(wsId)
        yield assertTrue(fetched.isEmpty)
      },

      test("delete succeeds and removes the branch when expectedHead matches") {
        val wsId = ws("ws-delete")
        val branch = s"scenarios.${wsId.value.toLowerCase}.draft-v1"
        for
          svc     <- makeService(Map("main" -> hash('a'), branch -> hash('b')))
          _       <- svc.delete(wsId, name("draft-v1"), hash('b'))
          fetched <- svc.list(wsId)
        yield assertTrue(fetched.isEmpty)
      },

      test("delete fails with the stale-head Conflict carrying both heads when the head moved") {
        val wsId = ws("ws-stale")
        val branch = s"scenarios.${wsId.value.toLowerCase}.draft-v1"
        for
          svc  <- makeService(Map("main" -> hash('a'), branch -> hash('c')))
          exit <- svc.delete(wsId, name("draft-v1"), hash('b')).exit
        yield assertTrue(exit.causeOption.flatMap(_.failureOption) ==
          Some(staleHead("draft-v1", hash('b'), Some(hash('c')))))
      },

      test("delete fails with the stale-head Conflict and no actual head when the scenario no longer exists") {
        val wsId = ws("ws-gone")
        for
          svc  <- makeService(Map("main" -> hash('a')))
          exit <- svc.delete(wsId, name("draft-v1"), hash('b')).exit
        yield assertTrue(exit.causeOption.flatMap(_.failureOption) ==
          Some(staleHead("draft-v1", hash('b'), None)))
      },

      test("create AtCommit forks a commit reachable from main") {
        val wsId = ws("ws-alpha")
        for
          svc     <- makeService(Map("main" -> hash('a')))
          branch  <- svc.create(wsId, name("draft-v1"), ScenarioSource.AtCommit(hash('a')))
          fetched <- svc.list(wsId)
        yield assertTrue(
          branch.toBranchRef == s"scenarios.${wsId.value.toLowerCase}.draft-v1",
          fetched == List(ScenarioSummary(name("draft-v1"), hash('a')))
        )
      },

      test("create AtCommit forks a commit reachable from one of this workspace's own scenarios") {
        val wsId     = ws("ws-alpha")
        val ownDraft = s"scenarios.${wsId.value.toLowerCase}.draft-v1"
        for
          svc     <- makeService(Map("main" -> hash('a'), ownDraft -> hash('b')))
          branch  <- svc.create(wsId, name("draft-v2"), ScenarioSource.AtCommit(hash('b')))
          fetched <- svc.list(wsId)
        yield assertTrue(
          branch.toBranchRef == s"scenarios.${wsId.value.toLowerCase}.draft-v2",
          fetched.contains(ScenarioSummary(name("draft-v2"), hash('b')))
        )
      },

      /** The commit exists in the store and belongs to another workspace's
        * scenario branch. Forking at it would point this workspace's branch at
        * a snapshot of the whole repository, so a later merge would carry the
        * other workspace's unpublished edits onto main. The answer is the same
        * not-found a hash that was never issued gets, so the two cases are not
        * distinguishable from outside. */
      test("create AtCommit refuses a commit that exists only in another workspace's history") {
        val wsId        = ws("ws-alpha")
        val foreignDraft = s"scenarios.${ws("ws-beta").value.toLowerCase}.secret-plan"
        for
          svc  <- makeService(Map("main" -> hash('a'), foreignDraft -> hash('e')))
          exit <- svc.create(wsId, name("draft-v1"), ScenarioSource.AtCommit(hash('e'))).exit
          list <- svc.list(wsId)
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption) == Some(sourceRefused(FieldProblem.CommitNotFound(hash('e')))),
          list.isEmpty
        )
      }
    ) @@ TestAspect.sequential

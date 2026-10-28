package com.risquanter.register.services

import zio.*
import zio.test.*
import zio.test.Assertion.*

import com.risquanter.register.auth.{Checked, Permission, TestChecked}
import com.risquanter.register.domain.data.iron.{WorkspaceId, ScenarioName, BranchRef, CommitHash, PositiveInt, StoreBranch}
import com.risquanter.register.domain.errors.{DataConflict, ErrorLocation, FieldName, ScenarioHeadStale, ValidationFailed, ValidationErrorCode, IrminError}
import com.risquanter.register.infra.irmin.IrminClient
import com.risquanter.register.infra.irmin.model.{IrminBranch, IrminCommit, IrminInfo, IrminTreeEntry, IrminPath}
import com.risquanter.register.testutil.TestHelpers.safeId

/** Unit tests for `ScenarioServiceLive` against an in-memory `IrminClient` fake —
  * no Docker/live Irmin required. Covers create/list/delete happy paths, both
  * CAS-error translations (DataConflict, ScenarioHeadStale), the ForkOf-not-found
  * and empty-main paths, and that `list` excludes both `main` and other workspaces.
  */
object ScenarioServiceLiveSpec extends ZIOSpecDefault:

  private given Checked[Permission] = TestChecked.value

  private def ws(label: String): WorkspaceId = WorkspaceId(safeId(label))
  private def name(s: String): ScenarioName.ScenarioName = ScenarioName.fromString(s).toOption.get
  private def hash(fill: Char): CommitHash = CommitHash.fromString(fill.toString * 40).toOption.get

  /** Minimal in-memory `IrminClient`: only `branches`/`getBranch`/`mainBranch`/
    * `createBranchAt`/`deleteBranch` are exercised by `ScenarioServiceLive` — every
    * other method is unused by it and dies loudly if a test accidentally hits it.
    */
  private final class FakeIrminClient(state: Ref[Map[String, CommitHash]]) extends IrminClient:
    private def toBranch(name: String, headOpt: Option[CommitHash]): IrminBranch =
      IrminBranch(name, headOpt.map(h => IrminCommit(h.value, "", Nil, IrminInfo("2026-01-01T00:00:00Z", "test", "test"))))

    override def branches: IO[IrminError, List[String]] = state.get.map(_.keys.toList)

    override def mainBranch: IO[IrminError, Option[IrminBranch]] =
      state.get.map(m => Some(toBranch("main", m.get("main"))))

    override def getBranch(branch: StoreBranch): IO[IrminError, Option[IrminBranch]] =
      state.get.map(m => m.get(branch.name).map(h => toBranch(branch.name, Some(h))))

    override def createBranchAt(branch: StoreBranch, at: CommitHash): IO[IrminError, Unit] =
      state.modify { m =>
        if m.contains(branch.name) then (false, m)
        else (true, m + (branch.name -> at))
      }.flatMap(applied => if applied then ZIO.unit else ZIO.fail(com.risquanter.register.domain.errors.BranchAlreadyExists(branch)))

    override def deleteBranch(branch: StoreBranch, currentHead: CommitHash): IO[IrminError, Unit] =
      state.modify { m =>
        if m.get(branch.name).contains(currentHead) then (true, m - branch.name)
        else (false, m)
      }.flatMap(applied => if applied then ZIO.unit else ZIO.fail(com.risquanter.register.domain.errors.BranchHeadStale(branch, currentHead)))

    override def moveBranchTo(branch: BranchRef, expectedHead: CommitHash, to: CommitHash): IO[IrminError, Unit] =
      state.modify { m =>
        if m.get(branch.name).contains(expectedHead) then (true, m + (branch.name -> to))
        else (false, m)
      }.flatMap(applied => if applied then ZIO.unit else ZIO.fail(com.risquanter.register.domain.errors.BranchHeadStale(branch, expectedHead)))

    override def get(path: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def set(path: IrminPath, value: String, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def setTree(path: IrminPath, entries: List[IrminTreeEntry], message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def remove(path: IrminPath, message: String, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def mergeBranch(from: BranchRef, into: StoreBranch, message: String) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
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
      state.get.map { m =>
        if m.get(branch.name).contains(commit) then
          List(IrminCommit(commit.value, "", Nil, IrminInfo("2026-01-01T00:00:00Z", "test", "test")))
        else Nil
      }
    override def healthCheck = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))
    override def list(prefix: IrminPath, branch: BranchRef = BranchRef.Main) = ZIO.die(new NotImplementedError("unused by ScenarioServiceLive"))

  /** `initial` maps raw branch names (e.g. "main", "scenarios.<ws>.draft-v1") to heads. */
  private def makeService(initial: Map[String, CommitHash] = Map.empty): UIO[ScenarioService] =
    Ref.make(initial).map(state => new ScenarioServiceLive(FakeIrminClient(state)))

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
        yield assert(exit)(fails(isSubtype[ValidationFailed](anything)))
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
        yield assert(exit)(fails(isSubtype[ValidationFailed](anything)))
      },

      test("create fails with DataConflict on a name collision") {
        val wsId = ws("ws-collide")
        val existingBranch = s"scenarios.${wsId.value.toLowerCase}.draft-v1"
        for
          svc  <- makeService(Map("main" -> hash('a'), existingBranch -> hash('a')))
          exit <- svc.create(wsId, name("draft-v1"), ScenarioSource.Main).exit
        yield assert(exit)(fails(isSubtype[DataConflict](anything)))
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

      test("delete fails with ScenarioHeadStale(actual = Some) when the head moved") {
        val wsId = ws("ws-stale")
        val branch = s"scenarios.${wsId.value.toLowerCase}.draft-v1"
        for
          svc  <- makeService(Map("main" -> hash('a'), branch -> hash('c')))
          exit <- svc.delete(wsId, name("draft-v1"), hash('b')).exit
        yield assert(exit)(fails(isSubtype[ScenarioHeadStale](anything))) &&
          assertTrue(exit.causeOption.flatMap(_.failureOption) match
            case Some(ScenarioHeadStale(_, expected, actual)) => expected == hash('b') && actual == Some(hash('c'))
            case _                                             => false
          )
      },

      test("delete fails with ScenarioHeadStale(actual = None) when the scenario no longer exists") {
        val wsId = ws("ws-gone")
        for
          svc  <- makeService(Map("main" -> hash('a')))
          exit <- svc.delete(wsId, name("draft-v1"), hash('b')).exit
        yield assert(exit)(fails(isSubtype[ScenarioHeadStale](anything))) &&
          assertTrue(exit.causeOption.flatMap(_.failureOption) match
            case Some(ScenarioHeadStale(_, _, actual)) => actual == None
            case _                                      => false
          )
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
        yield assert(exit)(fails(isSubtype[ValidationFailed](anything))) &&
          assertTrue(
            exit.causeOption.flatMap(_.failureOption) match
              case Some(ValidationFailed(errs)) =>
                errs.exists(e => e.code == ValidationErrorCode.NOT_FOUND &&
                  e.location == ErrorLocation.requestField(FieldName.Source))
              case _ => false,
            list.isEmpty
          )
      }
    ) @@ TestAspect.sequential

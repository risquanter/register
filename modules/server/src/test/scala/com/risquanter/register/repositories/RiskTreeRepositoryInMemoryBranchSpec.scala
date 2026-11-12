package com.risquanter.register.repositories

import zio.*
import zio.test.*
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.data.iron.{WorkspaceId, BranchRef, CommitHash, Revision, SafeName, SeedVarId, TreeId}
import com.risquanter.register.domain.errors.{
  Core, ErrorLocation, ErrorSubject, Failure, FieldName, FieldProblem, RequestHeaderName, RequestQueryParam, TrustZone,
  ValidationError
}
import com.risquanter.register.http.errors.Capability
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId, nodeId, lognormalShape}

/**
  * Branch-threading contract of the in-memory backend: branches are an Irmin
  * capability, so the in-memory repository must reject a non-main branch
  * request with a typed failure instead of silently answering with
  * main-branch data. History is an Irmin capability too, so a read pinned to a
  * commit and a revert fail the same way. Its create refuses an identifier the
  * workspace holds.
  */
object RiskTreeRepositoryInMemoryBranchSpec extends ZIOSpecDefault {

  private val wsId: WorkspaceId  = WorkspaceId(safeId("branch-spec-ws"))
  private val someTree: TreeId   = treeId("branch-spec-tree")
  private val commit: CommitHash = CommitHash.fromString("a" * 40).toOption.get

  /** The failure a read or revert pinned to a commit produces, at `location`. */
  private def pinnedReadsUnavailable(location: ErrorLocation): Failure =
    Failure.NotSupported(Core(TrustZone.Environment, NonEmptyChunk(location -> Capability.PinnedReads), Nil))

  /** A root portfolio holding one leaf. */
  private def minimalTree(tid: TreeId): RiskTree =
    val rootId = nodeId("root")
    val leafId = nodeId("leaf-1")
    val root = RiskPortfolio.create(rootId.value, "Root", Vector(leafId), None, ErrorSubject.Request).toEither.toOption.get
    val leaf = RiskLeaf.create(subject = ErrorSubject.Request, id = leafId.value, name = "Leaf 1", probability = 0.1,
      shape = lognormalShape(1000L, 2000L), parentId = Some(rootId), seedVarId = 1L).toEither.toOption.get
    RiskTree.fromNodesUnsafe(tid, SafeName.fromString("Branch Spec Tree").toOption.get, Seq(root, leaf), rootId,
      Some(SeedVarId.fromLong(1L).toOption.get), mitigations = Nil)

  private val scenarioBranch: BranchRef =
    BranchRef.fromString("scenarios.ws.alt").toOption.get

  def spec = suite("RiskTreeRepositoryInMemory branch handling")(

    test("main head is served") {
      for {
        repo  <- ZIO.service[RiskTreeRepository]
        rMain <- repo.getById(wsId, someTree, Revision.Head(BranchRef.Main)).map(_.map(_._1))
      } yield assertTrue(rMain.isEmpty)
    },

    test("a non-main branch request fails as NotSupported for scenarios, at the branch header") {
      for {
        repo <- ZIO.service[RiskTreeRepository]
        exit <- repo.getById(wsId, someTree, Revision.Head(scenarioBranch)).exit
      } yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).contains(Failure.NotSupported(Core(
          TrustZone.Environment,
          NonEmptyChunk(ErrorLocation.Header(RequestHeaderName.Branch) -> Capability.Scenarios),
          Nil
        )))
      )
    },

    test("a read pinned to a commit fails as NotSupported for pinned reads, at the at parameter") {
      for {
        repo <- ZIO.service[RiskTreeRepository]
        exit <- repo.getById(wsId, someTree, Revision.At(commit)).exit
      } yield assertTrue(
        exit.causeOption.flatMap(_.failureOption)
          .contains(pinnedReadsUnavailable(ErrorLocation.QueryParameter(RequestQueryParam.At)))
      )
    },

    test("a revert fails as NotSupported for pinned reads, at the request's target commit") {
      for {
        repo <- ZIO.service[RiskTreeRepository]
        exit <- repo.revert(wsId, someTree, commit, BranchRef.Main).exit
      } yield assertTrue(
        exit.causeOption.flatMap(_.failureOption)
          .contains(pinnedReadsUnavailable(ErrorLocation.requestField(FieldName.ToCommit)))
      )
    },

    test("creating a tree whose identifier the workspace already holds is the caller's refusal") {
      for {
        repo  <- ZIO.service[RiskTreeRepository]
        tree   = minimalTree(someTree)
        _     <- repo.create(wsId, tree, BranchRef.Main)
        exit  <- repo.create(wsId, tree, BranchRef.Main).exit
      } yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).contains(Failure.rejected(
          TrustZone.Client,
          NonEmptyChunk(ValidationError(ErrorLocation.requestField(FieldName.Id), FieldProblem.TreeIdAlreadyExists(someTree))),
          Nil
        ))
      )
    }
  ).provide(RiskTreeRepositoryInMemory.layer)
}

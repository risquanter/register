package com.risquanter.register.repositories

import zio.*
import zio.prelude.NonEmptyList
import zio.test.*
import com.risquanter.register.domain.data.{RiskLeaf, RiskPortfolio, RiskTree}
import com.risquanter.register.domain.data.iron.{WorkspaceId, BranchRef, Revision, SafeName, SeedVarId, TreeId}
import com.risquanter.register.domain.errors.{
  Core, ErrorLocation, ErrorSubject, Failure, FieldName, FieldProblem, RequestHeaderName, TrustZone, ValidationError
}
import com.risquanter.register.http.errors.Capability
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId, nodeId, lognormalShape}

/**
  * Branch-threading contract of the in-memory backend: branches are an Irmin
  * capability, so the in-memory repository must reject a non-main branch
  * request with a typed failure instead of silently answering with
  * main-branch data. Its create refuses an identifier the workspace holds.
  */
object RiskTreeRepositoryInMemoryBranchSpec extends ZIOSpecDefault {

  private val wsId: WorkspaceId = WorkspaceId(safeId("branch-spec-ws"))
  private val someTree = treeId("branch-spec-tree")

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
          NonEmptyList(ErrorLocation.Header(RequestHeaderName.Branch) -> Capability.Scenarios),
          Nil
        )))
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
          NonEmptyList(ValidationError(ErrorLocation.requestField(FieldName.Id), FieldProblem.TreeIdAlreadyExists(someTree))),
          Nil
        ))
      )
    }
  ).provide(RiskTreeRepositoryInMemory.layer)
}

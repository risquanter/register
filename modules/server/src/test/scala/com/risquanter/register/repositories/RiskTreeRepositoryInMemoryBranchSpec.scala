package com.risquanter.register.repositories

import zio.*
import zio.test.*
import com.risquanter.register.domain.data.iron.{WorkspaceId, BranchRef, Revision}
import com.risquanter.register.domain.errors.RepositoryFailure
import com.risquanter.register.testutil.TestHelpers.{safeId, treeId}

/**
  * Branch-threading contract of the in-memory backend (milestone 2b, DD-4):
  * branches are an Irmin capability, so the in-memory repository must reject
  * a non-main branch request with a typed failure instead of silently
  * answering with main-branch data.
  */
object RiskTreeRepositoryInMemoryBranchSpec extends ZIOSpecDefault {

  private val wsId: WorkspaceId = WorkspaceId(safeId("branch-spec-ws"))
  private val someTree = treeId("branch-spec-tree")

  private val scenarioBranch: BranchRef =
    BranchRef.fromString("scenarios.ws.alt").toOption.get

  def spec = suite("RiskTreeRepositoryInMemory branch handling")(

    test("main head is served") {
      for {
        repo  <- ZIO.service[RiskTreeRepository]
        rMain <- repo.getById(wsId, someTree, Revision.Head(BranchRef.Main))
      } yield assertTrue(rMain.isEmpty)
    },

    test("a non-main branch request fails with a typed RepositoryFailure") {
      for {
        repo <- ZIO.service[RiskTreeRepository]
        exit <- repo.getById(wsId, someTree, Revision.Head(scenarioBranch)).exit
      } yield assertTrue(
        exit.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[RepositoryFailure])
      )
    }
  ).provide(RiskTreeRepositoryInMemory.layer)
}

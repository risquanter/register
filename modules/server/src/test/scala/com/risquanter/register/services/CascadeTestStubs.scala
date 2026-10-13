package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, NodeId, ScenarioName, SeedEntityId, TreeId, WorkspaceId, Revision}
import com.risquanter.register.domain.data.{LECNodeCurve, RiskTree}
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure}
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}

/** Minimal `RiskTreeService`/`ScenarioService` test doubles shared by every spec
  * that exercises cascade-delete (`WorkspaceReaperSpec`,
  * `WorkspaceLifecycleControllerCascadeSpec`). Only the method each cascade
  * actually calls (`delete` for trees; `list`/`delete` for scenarios) is
  * customizable — every other method dies immediately, so a test that
  * accidentally exercises an unexpected call path fails loudly instead of
  * silently returning a default.
  */
object CascadeTestStubs:

  /** Stand-in revision paired with every `getById` hit by an in-memory test
    * double. Cascade tests do not assert on it, which keeps the `onGetById`
    * hook returning a bare `Option[RiskTree]`; specs that do assert the
    * service passes the repository's commit through compare against it. */
  val stubCommit: CommitHash = CommitHash.fromString("0" * 40).toOption.get

  def riskTreeService(
    onDelete: (WorkspaceId, TreeId) => IO[AppError, RiskTree],
    onGetById: (WorkspaceId, TreeId, Revision) => IO[AppError, Option[RiskTree]] = (_, _, _) => ZIO.die(new UnsupportedOperationException)
  ): RiskTreeService = new RiskTreeService:
    def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Permission]): IO[AppError, RiskTree] = ZIO.die(new UnsupportedOperationException)
    def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using Checked[Permission]): IO[AppError, RiskTree] = ZIO.die(new UnsupportedOperationException)
    def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Permission]): IO[AppError, RiskTree] = onDelete(wsId, id)
    def revertTree(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef)(using Checked[Permission]): IO[AppError, RiskTree] = ZIO.die(new UnsupportedOperationException)
    def getById(wsId: WorkspaceId, id: TreeId, rev: Revision)(using Checked[Permission]): IO[AppError, Option[(RiskTree, CommitHash)]] = onGetById(wsId, id, rev).map(_.map((_, stubCommit)))
    def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision): IO[AppError, Double] = ZIO.die(new UnsupportedOperationException)
    def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, rev: Revision, omitAbsent: Boolean): IO[AppError, Map[NodeId, LECNodeCurve]] = ZIO.die(new UnsupportedOperationException)

  /** `delete` always fails — simulates a tree that was already manually deleted.
    *
    * The failure is `RepositoryFailure`, which is what both repository backends
    * report for a tree that is not there, so the cascade sees the same value it
    * would see in production.
    */
  def noOpRiskTreeService: RiskTreeService =
    riskTreeService((_, id) => ZIO.fail(RepositoryFailure(s"RiskTree ${id.toSafeId} not found")))

  def scenarioService(
    onList: WorkspaceId => IO[AppError, List[ScenarioSummary]],
    onDelete: (WorkspaceId, ScenarioName.ScenarioName, CommitHash) => IO[AppError, Unit]
  ): ScenarioService = new ScenarioService:
    def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)(using Checked[Permission]): IO[AppError, BranchRef] = ZIO.die(new UnsupportedOperationException)
    def list(wsId: WorkspaceId)(using Checked[Permission]): IO[AppError, List[ScenarioSummary]] = onList(wsId)
    def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)(using Checked[Permission]): IO[AppError, Unit] = onDelete(wsId, name, expectedHead)

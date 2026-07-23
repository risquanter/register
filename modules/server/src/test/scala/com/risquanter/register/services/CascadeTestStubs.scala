package com.risquanter.register.services

import zio.*
import com.risquanter.register.auth.{Checked, Permission}
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, NodeId, ScenarioName, SeedEntityId, TreeId, WorkspaceId}
import com.risquanter.register.domain.data.{LECNodeCurve, RiskTree}
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

  def riskTreeService(
    onDelete: (WorkspaceId, TreeId) => Task[RiskTree],
    onGetById: (WorkspaceId, TreeId, BranchRef) => Task[Option[RiskTree]] = (_, _, _) => ZIO.die(new UnsupportedOperationException)
  ): RiskTreeService = new RiskTreeService:
    def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Permission]): Task[RiskTree] = ZIO.die(new UnsupportedOperationException)
    def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using Checked[Permission]): Task[RiskTree] = ZIO.die(new UnsupportedOperationException)
    def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Permission]): Task[RiskTree] = onDelete(wsId, id)
    def getById(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Permission]): Task[Option[RiskTree]] = onGetById(wsId, id, branch)
    def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, branch: BranchRef): Task[Double] = ZIO.die(new UnsupportedOperationException)
    def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, branch: BranchRef): Task[Map[NodeId, LECNodeCurve]] = ZIO.die(new UnsupportedOperationException)

  /** `delete` always fails — simulates a tree that was already manually deleted. */
  def noOpRiskTreeService: RiskTreeService =
    riskTreeService((_, _) => ZIO.fail(new NoSuchElementException("Tree not found")))

  def scenarioService(
    onList: WorkspaceId => Task[List[ScenarioSummary]],
    onDelete: (WorkspaceId, ScenarioName.ScenarioName, CommitHash) => Task[Unit]
  ): ScenarioService = new ScenarioService:
    def create(wsId: WorkspaceId, name: ScenarioName.ScenarioName, source: ScenarioSource)(using Checked[Permission]): Task[BranchRef] = ZIO.die(new UnsupportedOperationException)
    def list(wsId: WorkspaceId)(using Checked[Permission]): Task[List[ScenarioSummary]] = onList(wsId)
    def delete(wsId: WorkspaceId, name: ScenarioName.ScenarioName, expectedHead: CommitHash)(using Checked[Permission]): Task[Unit] = onDelete(wsId, name, expectedHead)

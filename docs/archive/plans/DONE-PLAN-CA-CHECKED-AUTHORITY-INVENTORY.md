# File inventory — PLAN-CA-CHECKED-AUTHORITY.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/server/src/main/scala/com/risquanter/register/auth/AuthorizationService.scala
- modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisioner.scala
- modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisionerNoOp.scala
- modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisionerSpiceDB.scala
- modules/server/src/main/scala/com/risquanter/register/auth/BootstrapProvisionerStub.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala
- modules/server/src/main/scala/com/risquanter/register/services/CascadeDelete.scala
- modules/server/src/main/scala/com/risquanter/register/services/ChangedNodesService.scala
- modules/server/src/main/scala/com/risquanter/register/services/QueryService.scala
- modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeServiceNotSupported.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioService.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceNotSupported.scala
- modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceReaper.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStore.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStoreLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala
- modules/server/src/test/scala/com/risquanter/register/auth/TestChecked.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/RiskTreeControllerSpec.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/ScenarioControllerSpec.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleControllerCascadeSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/AggregateFreshnessAfterLeafMoveSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala
- modules/server/src/test/scala/com/risquanter/register/services/ChangedNodesServiceSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/ScenarioServiceCascadeSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/ScenarioServiceLiveSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/SeedStabilitySpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/TreeHistoryServiceSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/workspace/WorkspaceReaperSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgresSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/workspace/WorkspaceStoreSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/services/TreeRevertItSpec.scala

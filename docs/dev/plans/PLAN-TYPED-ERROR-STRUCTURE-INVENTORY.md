# File inventory — PLAN-TYPED-ERROR-STRUCTURE.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/common/src/main/scala/com/risquanter/register/domain/errors/AppError.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorDetail.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorResponse.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/JsonHttpError.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ValidationErrorCode.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ValidationExtensions.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Distribution.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskTree.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/TargetingPredicate.scala
- modules/common/src/main/scala/com/risquanter/register/domain/tree/TreeIndex.scala
- modules/common/src/main/scala/com/risquanter/register/frontend/TreeBuilderLogic.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala
- modules/common/src/main/scala/com/risquanter/register/http/responses/ScenarioMergeResponse.scala
- modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala
- modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepository.scala
- modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrmin.scala
- modules/server/src/main/scala/com/risquanter/register/repositories/RiskTreeRepositoryInMemory.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioMergeService.scala
- modules/server/src/main/scala/com/risquanter/register/services/ScenarioServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/TreeHistoryService.scala
- modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala
- modules/server/src/main/scala/com/risquanter/register/services/QueryServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/NodeLosses.scala
- modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala
- modules/server/src/main/scala/com/risquanter/register/services/helper/SeedVarIdAssigner.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStoreLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/workspace/WorkspaceStorePostgres.scala
- modules/server/src/main/scala/com/risquanter/register/simulation/LognormalDistribution.scala
- modules/server/src/main/scala/com/risquanter/register/simulation/LognormalHelper.scala
- modules/server/src/main/scala/com/risquanter/register/simulation/LossDistribution.scala
- modules/server/src/main/scala/com/risquanter/register/simulation/MetalogDistribution.scala
- modules/server/src/main/scala/com/risquanter/register/mitigation/RiskResultTransform.scala
- modules/server/src/main/scala/com/risquanter/register/configs/SpiceDbConfig.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/ScenarioController.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceLifecycleController.scala
- modules/app/src/main/scala/app/state/GlobalError.scala
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/main/scala/app/state/PortfolioFormState.scala
- modules/app/src/main/scala/app/views/FormSubmitUtil.scala
- modules/app/src/main/scala/app/views/PortfolioFormView.scala
- modules/app/src/main/scala/app/views/RiskLeafFormView.scala
- modules/app/src/main/scala/app/components/MergeModal.scala
- modules/server-it/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala
- build.sbt
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorValues.scala
- modules/app/src/main/scala/app/state/ErrorTemplates.scala

# File inventory — PLAN-LOSS-SHAPE-ALGEBRA.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/common/src/main/scala/com/risquanter/register/domain/data/DistributionShape.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Distribution.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskNode.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/RiskLeafTransform.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/LeafSimContent.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Mitigation.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/ResultTransformSpec.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/Provenance.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/OpaqueTypes.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationUtil.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/DistributionShapeRequest.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeRequests.scala
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/DistributionPreviewService.scala
- modules/server/src/main/scala/com/risquanter/register/services/helper/Simulator.scala
- modules/server/src/main/scala/com/risquanter/register/mitigation/MitigationApplication.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationStaleness.scala
- modules/app/src/main/scala/app/state/RiskLeafFormState.scala
- modules/app/src/main/scala/app/state/TreeBuilderState.scala
- modules/app/src/main/scala/app/chart/DistributionSpecBuilder.scala
- modules/app/src/main/scala/app/state/DistributionChartState.scala
- modules/app/src/main/scala/app/views/DistributionChartView.scala
- modules/app/src/main/scala/app/views/TreeDetailView.scala
- modules/app/src/main/scala/app/views/TreePreview.scala
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/DistributionPreviewEndpoints.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeUpdateRequest.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/DistributionPreviewController.scala
- docs/dev/plans/PLAN-LOSS-SHAPE-ALGEBRA.md
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/DistributionPreviewEndpoints.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeUpdateRequest.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/RiskTreeDefinitionRequest.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/DistributionPreviewController.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/MitigationScopeResolverLive.scala
- modules/server/src/main/scala/com/risquanter/register/services/cache/CachedResultResolverLive.scala
- modules/app/src/main/scala/app/state/FormMode.scala
- modules/app/src/main/scala/app/components/TreeNodeRow.scala
- modules/server/src/main/scala/com/risquanter/register/simulation/MetalogDistribution.scala
- modules/server/src/main/scala/com/risquanter/register/mitigation/RiskResultTransform.scala
- modules/server/src/main/scala/com/risquanter/register/mitigation/ResultTransformInterpreter.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/DemoEnterpriseScriptSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/DemoSimpleScriptSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/LognormalBoundPositivityItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/QueryEndpointSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/RequestBodyLimitItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/infra/irmin/IrminRevertSemanticsSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/repositories/RiskTreeRepositoryIrminSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/services/PinnedReadAuthorizationItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/services/ScenarioMergeServiceItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/services/TreeRevertItSpec.scala

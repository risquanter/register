# File inventory — PLAN-MITIGATION-TREE-ROWS.md

The approval hook reads this file to decide whether a gated edit is
permitted. A gated edit is allowed only when the edited file appears on a
bullet line below, written as a full repo-relative path. Text that is not a
bullet line authorizes nothing.

Only the user writes this file, through `.claude/bin/approve-inventory`.

- modules/app/src/main/scala/app/views/TreeDetailView.scala
- modules/app/src/main/scala/app/views/AnalyzeView.scala
- modules/app/src/main/scala/app/views/TreeListView.scala
- modules/app/src/main/scala/app/components/TreeNodeRow.scala
- modules/app/src/main/scala/app/components/Icons.scala
- modules/app/src/main/scala/app/state/SelectionEdit.scala
- modules/app/styles/app.css
- modules/app/src/test/scala/app/state/SelectionEditSpec.scala
- modules/app/src/test/scala/app/components/TreeNodeRowSpec.scala
- modules/app/src/main/scala/app/components/BranchCard.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/MitigationSelection.scala
- modules/common/src/main/scala/com/risquanter/register/domain/data/iron/ValidationMessages.scala
- modules/common/src/main/scala/com/risquanter/register/domain/errors/ErrorLocation.scala
- modules/common/src/main/scala/com/risquanter/register/http/codecs/IronTapirCodecs.scala
- modules/common/src/main/scala/com/risquanter/register/http/endpoints/WorkspaceAnalysisEndpoints.scala
- modules/common/src/main/scala/com/risquanter/register/http/requests/AnalysisRequests.scala
- modules/common/src/main/scala/com/risquanter/register/http/responses/AnalysisResponses.scala
- modules/common/src/main/scala/com/risquanter/register/http/responses/LECCurvesResponse.scala
- modules/common/src/test/scala/com/risquanter/register/domain/data/MitigationSelectionSpec.scala
- modules/common/src/test/scala/com/risquanter/register/http/requests/AnalysisRequestsSpec.scala
- modules/server/src/main/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisController.scala
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeService.scala
- modules/server/src/main/scala/com/risquanter/register/services/RiskTreeServiceLive.scala
- modules/server/src/test/scala/com/risquanter/register/http/controllers/WorkspaceAnalysisControllerSpec.scala
- modules/server/src/test/scala/com/risquanter/register/mitigation/MitigationApplicationSpec.scala
- modules/server/src/test/scala/com/risquanter/register/services/CascadeTestStubs.scala
- modules/server/src/test/scala/com/risquanter/register/services/RiskTreeServiceLiveSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/HttpApiIntegrationSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/http/SeedReproducibilityItSpec.scala
- modules/server-it/src/test/scala/com/risquanter/register/services/MitigationPersistenceItSpec.scala
- modules/app/src/main/scala/app/state/LECChartState.scala
- modules/app/src/test/scala/app/state/LECChartStateSpec.scala
- modules/app/src/main/scala/app/state/PortfolioFormState.scala
- modules/app/src/main/scala/app/state/RiskLeafFormState.scala
- modules/app/src/test/scala/app/views/FormErrorRoutingSpec.scala
- modules/app/src/main/scala/app/state/CurveGrid.scala
- modules/app/src/test/scala/app/state/CurveGridSpec.scala
- modules/app/src/main/scala/app/state/TreeViewState.scala
- modules/app/src/main/scala/app/state/CompareState.scala
- modules/app/src/main/scala/app/Main.scala
- modules/app/src/main/scala/app/chart/LECSpecBuilder.scala
- modules/app/src/main/scala/app/chart/ColorAssigner.scala
- modules/app/src/main/scala/app/chart/CompareColorAssigner.scala
- modules/app/src/main/scala/app/components/MitigationPanel.scala
- modules/app/src/main/scala/app/views/BlockedMessages.scala
- modules/app/src/main/scala/app/views/NoticeMessages.scala
- modules/app/src/test/scala/app/state/CompareStateSpec.scala
- modules/app/src/test/scala/app/chart/CompareColorAssignerSpec.scala
- modules/app/src/test/scala/app/views/AnalyzeViewSeedSpec.scala
- modules/app/src/test/scala/app/state/TreeHistoryStateSpec.scala

package com.risquanter.register.http.controllers

import zio.*
import zio.json.*
import zio.test.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.server.stub.*
import sttp.tapir.ztapir.RIOMonadError

import com.risquanter.register.auth.{Authority, AuthorizationServiceNoOp, Checked, TestChecked, UserContextExtractor}
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.common.FolSymbols.QuerySort
import com.risquanter.register.domain.data.{
  Mitigation, MitigationPrecedence, MitigationSelection, MitigationSpec, MitigationTarget,
  RelativeLikelihood, RelativeSeverity, RiskLeaf, RiskTree, ScopeOutcome, TargetingPredicate
}
import com.risquanter.register.domain.data.iron.{
  BranchRef, CommitHash, MitigationId, NodeId, Probability, QueryLiteral, Revision, SafeName, SeedEntityId, SeedVarId,
  TreeId, WorkspaceId, WorkspaceKeySecret
}
import com.risquanter.register.domain.errors.{ErrorSubject, Failure}
import com.risquanter.register.http.errors.BindProblem
import com.risquanter.register.http.requests.{ProbOfExceedanceRequest, RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.http.responses.{ExceedanceSeries, LECNodeSeries, TreeStructureResponse}
import com.risquanter.register.services.{ChangedNodesServiceLive, RiskTreeService, TreeHistoryService}
import com.risquanter.register.services.cache.ResolvedScopes
import com.risquanter.register.services.workspace.{WorkspaceStore, WorkspaceStoreLive}
import com.risquanter.register.telemetry.TelemetryLive
import com.risquanter.register.testutil.TestHelpers.{lognormalShape, mitigationId, nodeId, retentionFactor, treeId, unsafeGet}
import io.github.iltotore.iron.*

/** The exceedance route and the structure route over HTTP. The exceedance route
  * answers a POST carrying a selection, and a selection beyond its bound is
  * refused by the decoder before any handler runs. The structure route answers
  * the tree beside each mitigation's resolved scope and the stale override ids.
  * The service is a stub that records the selection it was handed, so the
  * second case can show the handler was never reached, and that answers every
  * structure read with fixed values, so the third case shows what the handler
  * puts on the wire.
  */
object WorkspaceAnalysisControllerSpec extends ZIOSpecDefault:

  private given MonadError[Task] = new RIOMonadError[Any]
  private given Checked[Authority] = TestChecked.value

  private val tree: TreeId = treeId("analysis-tree")
  private val node: NodeId = nodeId("analysis-node")
  private val answer: Probability = 0.25

  private def scopedMitigation(label: String): Mitigation =
    unsafeGet(
      Mitigation.create(
        id = mitigationId(label),
        name = SafeName.fromString(label).toOption.get,
        target = MitigationTarget.Predicate(TargetingPredicate.create("leaf(x)").toEither.toOption.get),
        spec = MitigationSpec.LeafRelative(RelativeLikelihood.Scale(retentionFactor(0.5)), RelativeSeverity.Keep),
        precedence = MitigationPrecedence.default
      ),
      "mitigation"
    )

  private val resolvedMitigation: Mitigation = scopedMitigation("m-resolved")
  private val failedMitigation: Mitigation   = scopedMitigation("m-failed")

  /** A one-leaf tree holding both mitigations. */
  private val structureTree: RiskTree =
    val leaf = unsafeGet(RiskLeaf.create(subject = ErrorSubject.Request,
      id = node.value, name = "Analysis Leaf", probability = 0.2,
      shape = lognormalShape(1000L, 50000L), parentId = None, seedVarId = 1L), "leaf")
    RiskTree.fromNodesUnsafe(
      id = tree,
      name = SafeName.fromString("Analysis Tree").toOption.get,
      nodes = Seq(leaf),
      rootId = node,
      seedVarHighWater = Some(SeedVarId.fromLong(1L).toOption.get),
      mitigations = List(resolvedMitigation, failedMitigation)
    )

  private val structureScopes: ResolvedScopes = ResolvedScopes(Map(
    resolvedMitigation.id -> ScopeOutcome.Resolved(Set(node)),
    failedMitigation.id   -> ScopeOutcome.Failed(NonEmptyChunk(
      BindProblem.UnreadableLiteral(QueryLiteral.fromString("Gone").toOption.get, QuerySort.NodeNameLiteral)))
  ))

  private val structureStale: Set[MitigationId] = Set(resolvedMitigation.id)

  /** Answers every exceedance read with `answer` and records the selection, and
    * answers every structure read with the fixed structure above. */
  private def recordingService(seen: Ref[List[MitigationSelection]]): RiskTreeService = new RiskTreeService:
    private def unused[A]: IO[Failure, A] = ZIO.die(new UnsupportedOperationException("not exercised"))
    def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree] = unused
    def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree] = unused
    def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree] = unused
    def revertTree(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree] = unused
    def getById(wsId: WorkspaceId, id: TreeId, rev: Revision)(using Checked[Authority]): IO[Failure, Option[(RiskTree, CommitHash)]] = unused
    def getStructure(wsId: WorkspaceId, id: TreeId, branch: BranchRef, at: Option[CommitHash])(using Checked[Authority]): IO[Failure, (RiskTree, ResolvedScopes, Set[MitigationId])] =
      ZIO.succeed((structureTree, structureScopes, structureStale))
    def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, branch: BranchRef, at: Option[CommitHash], selection: MitigationSelection)(using Checked[Authority]): IO[Failure, ExceedanceSeries] =
      seen.update(_ :+ selection).as(ExceedanceSeries(answer, Nil))
    def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, branch: BranchRef, at: Option[CommitHash], omitAbsent: Boolean, selection: MitigationSelection)(using Checked[Authority]): IO[Failure, Map[NodeId, List[LECNodeSeries]]] = unused

  /** A stub backend over the analysis and tree controllers, and a key whose
    * workspace holds `tree`. */
  private def harness(seen: Ref[List[MitigationSelection]]): ZIO[Any, Throwable, (SttpBackend[Task, Any], WorkspaceKeySecret)] =
    (for
      store          <- ZIO.service[WorkspaceStore]
      key            <- store.create(None)
      _              <- store.addTree(key, tree)
      analysis       <- WorkspaceAnalysisController.makeZIO
      treeController <- WorkspaceTreeController.makeZIO
    yield (
      TapirStubInterpreter(SttpBackendStub[Task, Any](summon[MonadError[Task]]))
        .whenServerEndpointsRunLogic(analysis.routes ++ treeController.routes)
        .backend(),
      key
    )).mapError(failure => new IllegalStateException(failure.toString)).provide(
      ZLayer.succeed(recordingService(seen)),
      ChangedNodesServiceLive.layer,
      ZLayer.succeed(TreeHistoryService.empty),
      TestConfigs.workspaceLayer >>> WorkspaceStoreLive.layer,
      AuthorizationServiceNoOp.layer,
      ZLayer.succeed(UserContextExtractor.noOp),
      TestConfigs.telemetryLayer >>> TelemetryLive.configured
    )

  private def exceedance(backend: SttpBackend[Task, Any], key: WorkspaceKeySecret, body: String): Task[Response[Either[String, String]]] =
    basicRequest
      .post(uri"http://localhost/w/${key.reveal}/risk-trees/${tree.value}/nodes/${node.value}/prob-of-exceedance?threshold=1000")
      .header("X-Branch", "main")
      .contentType("application/json")
      .body(body)
      .send(backend)

  private def structure(backend: SttpBackend[Task, Any], key: WorkspaceKeySecret): Task[Response[Either[String, String]]] =
    basicRequest
      .get(uri"http://localhost/w/${key.reveal}/risk-trees/${tree.value}/structure")
      .header("X-Branch", "main")
      .send(backend)

  def spec = suite("Workspace read routes — exceedance and structure")(
    test("a POST carrying a selection is answered with one reading, and the selection reaches the service") {
      val selection = MitigationSelection.single(mitigationId("m-one"))
      for
        seen            <- Ref.make(List.empty[MitigationSelection])
        (backend, key)  <- harness(seen)
        response        <- exceedance(backend, key, ProbOfExceedanceRequest(selection).toJson)
        recorded        <- seen.get
      yield assertTrue(
        response.code == StatusCode.Ok,
        response.body.toOption.flatMap(_.fromJson[ExceedanceSeries].toOption).contains(ExceedanceSeries(answer, Nil)),
        recorded == List(selection)
      )
    },

    test("a selection naming more mitigations than a tree can hold is a 400 before the handler runs") {
      val ids = (0 to RiskTree.MaxMitigations).map(i => s"\"${mitigationId(s"m-$i").value}\"").mkString(",")
      for
        seen            <- Ref.make(List.empty[MitigationSelection])
        (backend, key)  <- harness(seen)
        response        <- exceedance(backend, key, s"""{"selection":{"fullScope":[$ids]}}""")
        recorded        <- seen.get
      yield assertTrue(response.code == StatusCode.BadRequest, recorded.isEmpty)
    },

    test("the structure route answers the tree, each mitigation's resolved scope in id order, and the stale ids") {
      for
        seen           <- Ref.make(List.empty[MitigationSelection])
        (backend, key) <- harness(seen)
        response       <- structure(backend, key)
        decoded         = response.body.toOption.flatMap(_.fromJson[TreeStructureResponse].toOption)
      yield assertTrue(
        response.code == StatusCode.Ok,
        decoded.map(_.tree.id).contains(tree),
        decoded.map(_.mitigationScopes).contains(structureScopes.toWire),
        decoded.map(_.staleMitigationIds).contains(structureStale)
      )
    }
  )

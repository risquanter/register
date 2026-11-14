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
import com.risquanter.register.domain.data.{MitigationSelection, RiskTree}
import com.risquanter.register.domain.data.iron.{
  BranchRef, CommitHash, NodeId, Probability, Revision, SeedEntityId, TreeId, WorkspaceId, WorkspaceKeySecret
}
import com.risquanter.register.domain.errors.Failure
import com.risquanter.register.http.requests.{ProbOfExceedanceRequest, RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.http.responses.{ExceedanceSeries, LECNodeSeries}
import com.risquanter.register.services.RiskTreeService
import com.risquanter.register.services.workspace.{WorkspaceStore, WorkspaceStoreLive}
import com.risquanter.register.telemetry.TelemetryLive
import com.risquanter.register.testutil.TestHelpers.{mitigationId, nodeId, treeId}
import io.github.iltotore.iron.*

/** The exceedance route over HTTP: it answers a POST carrying a selection, and a
  * selection beyond its bound is refused by the decoder before any handler runs.
  * The service is a stub that records the selection it was handed, so the second
  * case can show the handler was never reached.
  */
object WorkspaceAnalysisControllerSpec extends ZIOSpecDefault:

  private given MonadError[Task] = new RIOMonadError[Any]
  private given Checked[Authority] = TestChecked.value

  private val tree: TreeId = treeId("analysis-tree")
  private val node: NodeId = nodeId("analysis-node")
  private val answer: Probability = 0.25

  /** Answers every exceedance read with `answer` and records the selection. */
  private def recordingService(seen: Ref[List[MitigationSelection]]): RiskTreeService = new RiskTreeService:
    private def unused[A]: IO[Failure, A] = ZIO.die(new UnsupportedOperationException("not exercised"))
    def create(wsId: WorkspaceId, req: RiskTreeDefinitionRequest, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree] = unused
    def update(wsId: WorkspaceId, id: TreeId, req: RiskTreeUpdateRequest, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree] = unused
    def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree] = unused
    def revertTree(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef)(using Checked[Authority]): IO[Failure, RiskTree] = unused
    def getById(wsId: WorkspaceId, id: TreeId, rev: Revision)(using Checked[Authority]): IO[Failure, Option[(RiskTree, CommitHash)]] = unused
    def probOfExceedance(wsId: WorkspaceId, treeId: TreeId, nodeId: NodeId, threshold: Long, seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, branch: BranchRef, at: Option[CommitHash], selection: MitigationSelection)(using Checked[Authority]): IO[Failure, ExceedanceSeries] =
      seen.update(_ :+ selection).as(ExceedanceSeries(answer, Nil))
    def getLECCurvesMulti(wsId: WorkspaceId, treeId: TreeId, nodeIds: Set[NodeId], seedEntityId: SeedEntityId.SeedEntityId, includeProvenance: Boolean, branch: BranchRef, at: Option[CommitHash], omitAbsent: Boolean, selection: MitigationSelection)(using Checked[Authority]): IO[Failure, Map[NodeId, List[LECNodeSeries]]] = unused

  /** A stub backend over the controller, and a key whose workspace holds `tree`. */
  private def harness(seen: Ref[List[MitigationSelection]]): ZIO[Any, Throwable, (SttpBackend[Task, Any], WorkspaceKeySecret)] =
    (for
      store      <- ZIO.service[WorkspaceStore]
      key        <- store.create(None)
      _          <- store.addTree(key, tree)
      controller <- WorkspaceAnalysisController.makeZIO
    yield (
      TapirStubInterpreter(SttpBackendStub[Task, Any](summon[MonadError[Task]]))
        .whenServerEndpointsRunLogic(controller.routes)
        .backend(),
      key
    )).mapError(failure => new IllegalStateException(failure.toString)).provide(
      ZLayer.succeed(recordingService(seen)),
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

  def spec = suite("WorkspaceAnalysisController — the exceedance route")(
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
    }
  )

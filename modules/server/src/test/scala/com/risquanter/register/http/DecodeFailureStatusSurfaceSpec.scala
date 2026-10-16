package com.risquanter.register.http

import zio.*
import zio.test.*

import sttp.model.{HeaderNames, StatusCode}
import sttp.tapir.{auth, endpoint, header, EndpointIO, EndpointInput}
import sttp.tapir.internal.*
import sttp.tapir.server.model.MaxContentLength
import sttp.tapir.server.model.EndpointExtensions.*

import com.risquanter.register.auth.{AuthorizationServiceNoOp, BootstrapProvisionerNoOp, UserContextExtractor}
import com.risquanter.register.configs.TestConfigs
import com.risquanter.register.domain.data.RiskTree
import com.risquanter.register.domain.data.iron.{BranchRef, CommitHash, Revision, TreeId, WorkspaceId}
import com.risquanter.register.domain.errors.{AppError, RepositoryFailure, TreeLoadFailure}
import com.risquanter.register.http.controllers.{
  DistributionPreviewController,
  QueryController,
  ScenarioController,
  SystemController,
  WorkspaceAnalysisController,
  WorkspaceLifecycleController,
  WorkspaceTreeController
}
import com.risquanter.register.http.sse.SSEController
import com.risquanter.register.repositories.RiskTreeRepository
import com.risquanter.register.services.{
  ChangedNodesServiceLive,
  DistributionPreviewService,
  QueryServiceLive,
  RiskTreeServiceLive,
  ScenarioMergeServiceNotSupported,
  ScenarioServiceNotSupported,
  TreeHistoryService
}
import com.risquanter.register.services.cache.{CachedResultResolverLive, ContentCacheRegistry, MitigationScopeResolverRegistry}
import com.risquanter.register.services.pipeline.InvalidationHandler
import com.risquanter.register.services.sse.SSEHub
import com.risquanter.register.services.workspace.WorkspaceStoreLive
import com.risquanter.register.telemetry.TelemetryLive

/** Guards the one assumption the rejection answer rests on: every decode
  * failure this API can produce answers `400`.
  *
  * A rejected input is answered with the status Tapir computed for it, and five
  * declarations make that status something other than `400`. A `Content-Type`
  * header input or a `oneOfBody` make it `415`, a stream body makes it `413`,
  * an authentication input makes it `401` with a challenge header, and a
  * `maxRequestBodyLength` on any endpoint makes an oversized plain body `413`
  * too: `ServerInterpreter.decodeBody` turns the length exception into a decode
  * error, and `respond` matches that error before it matches the body input.
  * The list below is the same one `DefaultDecodeFailureHandler.respond` matches
  * on.
  *
  * Why that matters: the browser decodes a failure through the endpoint's
  * declared error output, where the rejection body is declared at `400` alone.
  * The first endpoint to declare one of these four makes the server answer a
  * status no declared variant matches, and the user sees "the reply could not
  * be read" instead of the rejection. This fails at that moment, which is the
  * only moment the question can be answered with a real endpoint in hand.
  *
  * An authentication input is a second, separate problem. This service
  * validates no credential — a token is checked at the mesh and its claims
  * arrive as ordinary headers (ADR-012) — so an endpoint declaring one would be
  * advertising a challenge nothing here will read.
  *
  * The endpoint list comes from `HttpApi.endpointsZIO`, the same collector the
  * server itself uses, so an endpoint cannot be missed: one that is not
  * gathered there is not served at all. A hand-written list would drift, and
  * the endpoint left off it would be exactly the one this exists to catch.
  */
object DecodeFailureStatusSurfaceSpec extends ZIOSpecDefault:

  /** Minimal repository — no route's logic runs, only its declaration is read. */
  private val stubRepo = new RiskTreeRepository:
    override def create(wsId: WorkspaceId, t: RiskTree, branch: BranchRef): IO[AppError, RiskTree] =
      ZIO.succeed(t)
    override def update(wsId: WorkspaceId, id: TreeId, op: RiskTree => RiskTree, branch: BranchRef): IO[AppError, RiskTree] =
      ZIO.fail(RepositoryFailure("stub"))
    override def delete(wsId: WorkspaceId, id: TreeId, branch: BranchRef): IO[AppError, RiskTree] =
      ZIO.fail(RepositoryFailure("stub"))
    override def revert(wsId: WorkspaceId, id: TreeId, toCommit: CommitHash, branch: BranchRef): IO[AppError, RiskTree] =
      ZIO.fail(RepositoryFailure("stub"))
    override def getById(wsId: WorkspaceId, id: TreeId, rev: Revision): IO[AppError, Option[(RiskTree, CommitHash)]] =
      ZIO.succeed(None)
    override def getAllForWorkspace(wsId: WorkspaceId, rev: Revision): IO[AppError, List[Either[TreeLoadFailure, RiskTree]]] =
      ZIO.succeed(Nil)

  private val controllersLayer = ZLayer.make[
    SystemController & WorkspaceLifecycleController & WorkspaceTreeController & WorkspaceAnalysisController &
      SSEController & QueryController & DistributionPreviewController & ScenarioController
  ](
    ZLayer.succeed(stubRepo),
    TestConfigs.simulationLayer,
    TestConfigs.workspaceLayer,
    TestConfigs.telemetryLayer >>> TelemetryLive.configured,
    ContentCacheRegistry.layer,
    CachedResultResolverLive.layer,
    MitigationScopeResolverRegistry.layer,
    RiskTreeServiceLive.layer,
    ChangedNodesServiceLive.layer,
    ZLayer.succeed(TreeHistoryService.empty),
    SSEHub.live,
    InvalidationHandler.live,
    WorkspaceStoreLive.layer,
    AuthorizationServiceNoOp.layer,
    ZLayer.succeed(UserContextExtractor.noOp),
    ZLayer.succeed(BootstrapProvisionerNoOp),
    ScenarioServiceNotSupported.layer,
    ScenarioMergeServiceNotSupported.layer,
    QueryServiceLive.layer,
    DistributionPreviewService.layer,
    ZLayer.fromZIO(SystemController.makeZIO),
    ZLayer.fromZIO(WorkspaceLifecycleController.makeZIO),
    ZLayer.fromZIO(WorkspaceTreeController.makeZIO),
    ZLayer.fromZIO(WorkspaceAnalysisController.makeZIO),
    ZLayer.fromZIO(ScenarioController.makeZIO),
    SSEController.layer,
    ZLayer.fromZIO(QueryController.makeZIO),
    ZLayer.fromZIO(DistributionPreviewController.makeZIO)
  )

  /** The status Tapir would answer with if this input failed to decode, for the
    * inputs that answer with something other than `400`.
    *
    * Mirrors the arms of `DefaultDecodeFailureHandler.respond` that do not
    * reach `respondBadRequest`, except the authentication arm: an
    * `EndpointInput.Auth` is not a basic input, and the traversal below unwraps
    * one into the header it wraps, so it is detected through the endpoint's own
    * `auths` accessor instead.
    */
  private def statusIfRejected(input: EndpointInput.Basic[?]): Option[StatusCode] =
    input match
      case h: EndpointIO.Header[?] if h.name == HeaderNames.ContentType =>
        Some(StatusCode.UnsupportedMediaType)
      case fh: EndpointIO.FixedHeader[?] if fh.h.name == HeaderNames.ContentType =>
        Some(StatusCode.UnsupportedMediaType)
      case _: EndpointIO.OneOfBody[?, ?]         => Some(StatusCode.UnsupportedMediaType)
      case _: EndpointIO.StreamBodyWrapper[?, ?] => Some(StatusCode.PayloadTooLarge)
      case _                                     => None

  /** Whether the endpoint carries the attribute `maxRequestBodyLength` sets.
    * It is an endpoint attribute rather than an input, so the input traversal
    * above cannot see it and it is read from the endpoint's info instead.
    */
  private def declaresBodyLimit(e: sttp.tapir.AnyEndpoint): Boolean =
    e.info.attribute(MaxContentLength.attributeKey).isDefined

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("the statuses a decode failure can answer with")(
      // Without this the case below could pass because the detector matches
      // nothing rather than because no endpoint declares one of these.
      test("the detector recognises the inputs it is looking for") {
        assertTrue(
          statusIfRejected(header[String](HeaderNames.ContentType)).contains(StatusCode.UnsupportedMediaType),
          statusIfRejected(header[String]("X-Branch")).isEmpty,
          endpoint.in(auth.bearer[String]()).auths.nonEmpty,
          endpoint.in(header[String]("X-Branch")).auths.isEmpty,
          declaresBodyLimit(endpoint.in(header[String]("X-Branch")).maxRequestBodyLength(1L)),
          !declaresBodyLimit(endpoint.in(header[String]("X-Branch")))
        )
      },
      test("no endpoint declares an input whose rejection answers other than 400") {
        for
          endpoints <- HttpApi.endpointsZIO.provideLayer(controllersLayer)
          byInput = for
            route  <- endpoints
            input  <- route.endpoint.asVectorOfBasicInputs()
            status <- statusIfRejected(input).toList
          yield s"${route.endpoint.showShort} declares ${input.show}, whose rejection answers $status"
          byAuth = for
            route <- endpoints
            if route.endpoint.auths.nonEmpty
          yield s"${route.endpoint.showShort} declares an authentication input, whose rejection answers " +
            s"${StatusCode.Unauthorized} with a challenge header"
          byLength = for
            route <- endpoints
            if declaresBodyLimit(route.endpoint)
          yield s"${route.endpoint.showShort} declares a request body limit, whose rejection answers " +
            s"${StatusCode.PayloadTooLarge}"
          offenders = byInput ++ byAuth ++ byLength
          // The traversal reaching real inputs is the third thing that could
          // make this pass for the wrong reason: `X-Branch` is declared on
          // several endpoints, so finding it proves inputs are being read.
          branchHeaderSeen = endpoints
            .flatMap(_.endpoint.asVectorOfBasicInputs())
            .exists:
              case h: EndpointIO.Header[?] => h.name == "X-Branch"
              case _                       => false
        yield assertTrue(
          endpoints.nonEmpty,
          branchHeaderSeen,
          offenders.isEmpty
        ) ?? (
          "An endpoint now declares an input whose decode failure answers a status other than 400:\n" +
            offenders.mkString("\n") +
            "\n\nThe rejection body is declared at 400 only, so the browser cannot match this answer and " +
            "reports it as an unreadable reply. Add a variant for that status to the error output in " +
            "BaseEndpoint: a client picks a variant by its fixed status code and takes the first that " +
            "decodes, so two variants sharing one body type are told apart by status and their order " +
            "does not matter. If the input is an authentication input, stop instead — credential " +
            "validation belongs to the mesh (ADR-012), not to this service."
        )
      }
    )

end DecodeFailureStatusSurfaceSpec

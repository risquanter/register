package com.risquanter.register.auth

import zio.*
import zio.prelude.NonEmptyList
import zio.test.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.ztapir.RIOMonadError
import com.risquanter.register.configs.{SpiceDbConfig, SpiceDbConsistency, SpiceDbToken, TestConfigs}
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId, IronConstants, MeshServiceUrl}
import com.risquanter.register.domain.errors.{Core, DenialKind, Diagnostic, ErrorLocation, ExpectedInput, Failure, FieldProblem, OutageMode, PeerZone, TrustZone}
import com.risquanter.register.telemetry.TelemetryLive

/** Unit tests for AuthorizationServiceSpiceDB HTTP adapter (T-U1 through T-U11).
  *
  * No live SpiceDB required. Uses SttpBackendStub to simulate SpiceDB HTTP
  * responses in isolation — tests the response-mapping logic only.
  *
  */
object AuthorizationServiceSpiceDBSpec extends ZIOSpecDefault:

  // ─── Fixtures ───────────────────────────────────────────────────────────────

  private val aliceUuid: String           = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf"
  private val alice: UserId.Authenticated = UserId.fromString(aliceUuid).toOption.get

  private val ws1: WorkspaceId = WorkspaceId.fromString("01ARZ3NDEKTSV4RRFFQ69G5FAV").toOption.get
  // Construct directly — asResource extension is in ResourceRef companion but requires explicit import
  private val ws1Ref: ResourceRef = ResourceRef(ResourceType.Workspace, ws1.toSafeId)

  /** The denial SpiceDB's refusal of alice's `view_workspace` on ws1 produces:
    * at the authorization zone, naming the principal for the log. */
  private val aliceDenied: Failure =
    Failure.AccessDenied(Core(TrustZone.Peer(PeerZone.Authorization),
      NonEmptyList(ErrorLocation.Whole -> DenialKind.PermissionNotHeld),
      List(Diagnostic.Principal(alice, Permission.ViewWorkspace, ws1Ref))))

  private val testConfig: SpiceDbConfig = SpiceDbConfig(
    url         = MeshServiceUrl.fromString("http://spicedb-test:50051").toOption.get,
    token       = SpiceDbToken.fromString("test-bearer-token", ErrorLocation.Whole).toOption.get,
    consistency = SpiceDbConsistency.MinimizeLatency,
    timeoutSeconds = IronConstants.Ten
  )

  // ─── Stub backend factory ────────────────────────────────────────────────────

  private given MonadError[Task] = new RIOMonadError[Any]

  private def stubWith(body: String, code: Int = 200): SttpBackend[Task, Any] =
    SttpBackendStub[Task, Any](summon[MonadError[Task]])
      .whenAnyRequest
      .thenRespond(Response(body, StatusCode(code)))

  private def stubFailing(e: Throwable): SttpBackend[Task, Any] =
    SttpBackendStub[Task, Any](summon[MonadError[Task]])
      .whenAnyRequest
      .thenRespondF(ZIO.fail(e))

  /** How SpiceDB failed to answer, when the failure is an outage at the
    * authorization zone; `None` for anything else. */
  private def outageModeOf(error: Failure): Option[OutageMode] = error match
    case Failure.Unanswered(Core(PeerZone.Authorization, errors, _)) => Some(errors.head._2)
    case _                                                          => None

  private def failsAsOutage[A](outcome: Either[Failure, A], mode: OutageMode): TestResult =
    assertTrue(outcome.left.toOption.flatMap(outageModeOf).contains(mode))

  /** What this service refused in SpiceDB's answer, when the failure is a
    * refusal at the authorization zone; `None` for anything else. */
  private def refusalOf(error: Failure): Option[FieldProblem] = error match
    case Failure.Rejected(Core(TrustZone.Peer(PeerZone.Authorization), errors, _)) => Some(errors.head._2)
    case _                                                                          => None

  private def failsAsRefusal[A](outcome: Either[Failure, A], expected: ExpectedInput): TestResult =
    assertTrue(outcome.left.toOption.flatMap(refusalOf).contains(FieldProblem.Unreadable(expected)))

  private def serviceLayer(stub: SttpBackend[Task, Any]): ZLayer[Any, Throwable, AuthorizationService] =
    ZLayer.make[AuthorizationService](
      AuthorizationServiceSpiceDB.layer,
      ZLayer.succeed(testConfig),
      ZLayer.succeed(stub),
      TestConfigs.telemetryLayer >>> TelemetryLive.configured
    )

  // ─── Test suite ──────────────────────────────────────────────────────────────

  def spec = suite("AuthorizationServiceSpiceDB")(

    test("T-U1 — PERMISSIONSHIP_HAS_PERMISSION maps to ZIO.succeed(Checked[P]())") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).map(_ => assertCompletes)
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_HAS_PERMISSION"}"""))
    ),

    test("T-U2 — PERMISSIONSHIP_NO_PERMISSION is AccessDenied at the authorization zone, naming the principal") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map(outcome => assertTrue(outcome == Left(aliceDenied)))
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_NO_PERMISSION"}"""))
    ),

    test("T-U3 — PERMISSIONSHIP_UNSPECIFIED treated as deny (fail-closed)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map(outcome => assertTrue(outcome == Left(aliceDenied)))
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_UNSPECIFIED"}"""))
    ),

    test("T-U4 — HTTP 4xx (bad token) is an outage with that status") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map(failsAsOutage(_, OutageMode.Status(StatusCode.Unauthorized)))
    }.provide(
      serviceLayer(stubWith("""{"code":16,"message":"UNAUTHENTICATED"}""", code = 401))
    ),

    test("T-U5 — HTTP 5xx is an outage with that status (fail-closed, not a denial)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map(failsAsOutage(_, OutageMode.Status(StatusCode.ServiceUnavailable)))
    }.provide(
      serviceLayer(stubWith("""{"code":13,"message":"Internal server error"}""", code = 503))
    ),

    test("T-U6 — OTel counter and histogram record on every check() call without errors") {
      // Verifies that the OTel instrumentation does not throw or abort the effect.
      // Exact counter values require an InMemoryMetricReader, which is
      // integration-test scope; the full assertion lives there.
      for
        _ <- ZIO.serviceWithZIO[AuthorizationService](
               _.check(alice, Permission.ViewWorkspace, ws1Ref)
             )
        _ <- ZIO.serviceWithZIO[AuthorizationService](
               _.check(alice, Permission.DesignWrite, ws1Ref)
             )
        _ <- ZIO.serviceWithZIO[AuthorizationService](
               _.check(alice, Permission.ViewWorkspace, ws1Ref).either
             )
      yield assertCompletes
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_HAS_PERMISSION"}"""))
    ),

    test("T-U7 — audit log uses user.value (raw UUID), not user.toString (redacted)") {
      for
        _ <- ZIO.serviceWithZIO[AuthorizationService](
               _.check(alice, Permission.ViewWorkspace, ws1Ref)
             )
        output <- ZTestLogger.logOutput
        // ZTestLogger.LogEntry.message is a () => String thunk
        authzLogEntry = output.find(e => e.message().contains("authz.check"))
      yield assertTrue(
        authzLogEntry.isDefined,
        // user.value emits the raw UUID in the audit log
        authzLogEntry.get.message().contains(aliceUuid),
        // user.toString would produce UserId(***) — must NOT appear
        !authzLogEntry.get.message().contains("UserId(***)")
      )
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_HAS_PERMISSION"}"""))
    ),

    test("T-U8 — check() transport failure is a refused connection") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map(failsAsOutage(_, OutageMode.ConnectionRefused))
    }.provide(
      serviceLayer(stubFailing(new RuntimeException("Connection refused")))
    ),

    test("T-U9 — check() malformed response body is refused as JSON at the authorization zone") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map(failsAsRefusal(_, ExpectedInput.Json))
    }.provide(
      serviceLayer(stubWith("not-json"))
    ),

    test("T-U10 — a permissionship outside the API's set is refused as the answer the decoder declares") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map(failsAsRefusal(_, ExpectedInput.Declared))
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_MAYBE"}"""))
    ),

    test("T-U11 — PERMISSIONSHIP_CONDITIONAL_PERMISSION treated as deny (no caveats are evaluated)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map(outcome => assertTrue(outcome == Left(aliceDenied)))
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_CONDITIONAL_PERMISSION"}"""))
    )
  )

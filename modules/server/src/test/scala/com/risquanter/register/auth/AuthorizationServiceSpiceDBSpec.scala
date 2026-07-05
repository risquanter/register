package com.risquanter.register.auth

import zio.*
import zio.test.*
import zio.test.Assertion.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.ztapir.RIOMonadError
import com.risquanter.register.configs.{SpiceDbConfig, SpiceDbConsistency, SpiceDbToken, TestConfigs}
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId, IronConstants, MeshServiceUrl}
import com.risquanter.register.domain.errors.{AuthError, AuthForbidden, AuthServiceUnavailable}
import com.risquanter.register.telemetry.{TracingLive, MetricsLive}

/** Unit tests for AuthorizationServiceSpiceDB HTTP adapter (T-U1 through T-U7).
  *
  * No live SpiceDB required. Uses SttpBackendStub to simulate SpiceDB HTTP
  * responses in isolation — tests the response-mapping logic only.
  *
  * @see AUTH-TESTING-PLAN.md §W3 — Wave 3 prerequisite unit tests
  * @see AUTHORIZATION-PLAN.md §L2.2 — SpiceDB HTTP API mapping table
  */
object AuthorizationServiceSpiceDBSpec extends ZIOSpecDefault:

  // ─── Fixtures ───────────────────────────────────────────────────────────────

  private val aliceUuid = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf"
  private val alice: UserId.Authenticated = UserId.fromString(aliceUuid).toOption.get

  private val ws1Id = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
  private val ws2Id = "01BX5ZZKBKACTAV9WEVGEMMVRZ"
  private val ws1: WorkspaceId = WorkspaceId.fromString(ws1Id).toOption.get
  // Construct directly — asResource extension is in ResourceRef companion but requires explicit import
  private val ws1Ref: ResourceRef = ResourceRef(ResourceType.Workspace, ws1.toSafeId)

  private val testConfig: SpiceDbConfig = SpiceDbConfig(
    url         = MeshServiceUrl.fromString("http://spicedb-test:50051").toOption.get,
    token       = SpiceDbToken.fromString("test-bearer-token").toOption.get,
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

  private def serviceLayer(stub: SttpBackend[Task, Any]): ZLayer[Any, Throwable, AuthorizationService] =
    ZLayer.make[AuthorizationService](
      AuthorizationServiceSpiceDB.layer,
      ZLayer.succeed(testConfig),
      ZLayer.succeed(stub),
      TestConfigs.telemetryLayer >>> TracingLive.console,
      TestConfigs.telemetryLayer >>> MetricsLive.console
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

    test("T-U2 — PERMISSIONSHIP_NO_PERMISSION maps to AuthForbidden") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map {
        case Left(err: AuthForbidden) =>
          assertTrue(
            err.userId       == aliceUuid,
            err.permission   == "view_workspace",
            err.resourceType == "workspace",
            err.resourceId   == ws1Id
          )
        case other =>
          assertTrue(false)
      }
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_NO_PERMISSION"}"""))
    ),

    test("T-U3 — PERMISSIONSHIP_UNSPECIFIED treated as deny (fail-closed)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map {
        case Left(_: AuthForbidden) => assertCompletes
        case other                  => assertTrue(false)
      }
    }.provide(
      serviceLayer(stubWith("""{"permissionship":"PERMISSIONSHIP_UNSPECIFIED"}"""))
    ),

    test("T-U4 — HTTP 4xx (bad token) maps to AuthServiceUnavailable") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case Left(_: AuthForbidden)          => assertTrue(false)
        case Right(_)                        => assertTrue(false)
      }
    }.provide(
      serviceLayer(stubWith("""{"code":16,"message":"UNAUTHENTICATED"}""", code = 401))
    ),

    test("T-U5 — HTTP 5xx maps to AuthServiceUnavailable (fail-closed, not AuthForbidden)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map {
        case Left(err: AuthServiceUnavailable) =>
          assertTrue(!err.isInstanceOf[AuthForbidden])
        case Left(_: AuthForbidden) =>
          assertTrue(false)
        case Right(_) =>
          assertTrue(false)
      }
    }.provide(
      serviceLayer(stubWith("""{"code":13,"message":"Internal server error"}""", code = 503))
    ),

    test("T-U6 — OTel counter and histogram record on every check() call without errors") {
      // Verifies that the OTel instrumentation does not throw or abort the effect.
      // Exact counter values require an InMemoryMetricReader (integration test scope).
      // See AUTH-TESTING-PLAN §W3 T-U6 for the full assertion at integration level.
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

    test("T-U8 — check() transport failure maps to AuthServiceUnavailable") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    }.provide(
      serviceLayer(stubFailing(new RuntimeException("Connection refused")))
    ),

    test("T-U9 — check() malformed response body maps to AuthServiceUnavailable") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.check(alice, Permission.ViewWorkspace, ws1Ref)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    }.provide(
      serviceLayer(stubWith("not-json"))
    ),

    test("T-U10 — listAccessible parses NDJSON result lines in order") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.listAccessible(alice, ResourceType.Workspace, Permission.ViewWorkspace)
      ).map { ids =>
        assertTrue(ids.map(_.value.toString) == List(ws1Id, ws2Id))
      }
    }.provide(
      serviceLayer(stubWith(
        s"""{"result":{"resourceObjectId":"$ws1Id"}}
           |{"result":{"resourceObjectId":"$ws2Id"}}
           |""".stripMargin
      ))
    ),

    test("T-U11 — listAccessible empty body yields empty list") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.listAccessible(alice, ResourceType.Workspace, Permission.ViewWorkspace)
      ).map(ids => assertTrue(ids.isEmpty))
    }.provide(
      serviceLayer(stubWith(""))
    ),

    test("T-U12 — listAccessible mid-stream error object fails the whole lookup (no partial results)") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.listAccessible(alice, ResourceType.Workspace, Permission.ViewWorkspace)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    }.provide(
      serviceLayer(stubWith(
        s"""{"result":{"resourceObjectId":"$ws1Id"}}
           |{"error":{"code":4,"message":"DEADLINE_EXCEEDED"}}
           |""".stripMargin
      ))
    ),

    test("T-U13 — listAccessible unparseable stream line maps to AuthServiceUnavailable") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.listAccessible(alice, ResourceType.Workspace, Permission.ViewWorkspace)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    }.provide(
      serviceLayer(stubWith("not-json\n"))
    ),

    test("T-U14 — listAccessible HTTP 5xx maps to AuthServiceUnavailable") {
      ZIO.serviceWithZIO[AuthorizationService](
        _.listAccessible(alice, ResourceType.Workspace, Permission.ViewWorkspace)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    }.provide(
      serviceLayer(stubWith("""{"code":13,"message":"Internal server error"}""", code = 503))
    )
  )

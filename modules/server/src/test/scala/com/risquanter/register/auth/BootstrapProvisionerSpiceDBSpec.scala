package com.risquanter.register.auth

import zio.*
import zio.test.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.ztapir.RIOMonadError
import com.risquanter.register.configs.{SpiceDbConfig, SpiceDbConsistency, SpiceDbToken}
import com.risquanter.register.domain.data.iron.{IronConstants, MeshServiceUrl, UserId, WorkspaceId}
import com.risquanter.register.domain.errors.AuthServiceUnavailable

/** Unit tests for the BootstrapProvisionerSpiceDB WriteRelationships adapter.
  *
  * No live SpiceDB required. Uses SttpBackendStub to simulate SpiceDB HTTP
  * responses in isolation — tests request construction and response-mapping
  * logic only. Mirrors the AuthorizationServiceSpiceDBSpec conventions.
  *
  * @see AUTHORIZATION-IMPLEMENTATION-PLAN.md §D — BootstrapProvisioner design
  * @see AUTHORIZATION-IMPLEMENTATION-PLAN.md §T-S10 — server-it read-back counterpart
  */
object BootstrapProvisionerSpiceDBSpec extends ZIOSpecDefault:

  // ─── Fixtures ───────────────────────────────────────────────────────────────

  private val aliceUuid = "8f14e45f-ceea-4a0e-8f09-bcb3d2c2f6cf"
  private val alice: UserId.Authenticated = UserId.fromString(aliceUuid).toOption.get

  private val ws1Id = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
  private val ws1: WorkspaceId = WorkspaceId.fromString(ws1Id).toOption.get

  private val testConfig: SpiceDbConfig = SpiceDbConfig(
    url            = MeshServiceUrl.fromString("http://spicedb-test:50051").toOption.get,
    token          = SpiceDbToken.fromString("test-bearer-token").toOption.get,
    consistency    = SpiceDbConsistency.MinimizeLatency,
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

  // Responds 200 only to a request that carries the exact expected shape:
  // write URI, bearer auth, TOUCH operation, owner_user tuple for (ws1, alice).
  // Any other request gets the stub's default 404 — failing the test.
  private def matchingStub: SttpBackend[Task, Any] =
    SttpBackendStub[Task, Any](summon[MonadError[Task]])
      .whenRequestMatches { req =>
        val bodyStr = req.body match
          case StringBody(s, _, _) => s
          case _                   => ""
        req.uri.path.endsWith(List("v1", "relationships", "write")) &&
        req.headers.exists(h =>
          h.name.equalsIgnoreCase("Authorization") && h.value == "Bearer test-bearer-token"
        ) &&
        bodyStr.contains("\"OPERATION_TOUCH\"") &&
        bodyStr.contains("\"owner_user\"") &&
        bodyStr.contains("\"workspace\"") &&
        bodyStr.contains(ws1Id) &&
        bodyStr.contains(aliceUuid)
      }
      .thenRespond(Response("""{"writtenAt":{"token":"test-zed-token"}}""", StatusCode.Ok))

  private def provisionerLayer(stub: SttpBackend[Task, Any]): ZLayer[Any, Nothing, BootstrapProvisioner] =
    ZLayer.make[BootstrapProvisioner](
      BootstrapProvisionerSpiceDB.layer,
      ZLayer.succeed(testConfig),
      ZLayer.succeed(stub)
    )

  // ─── Test suite ──────────────────────────────────────────────────────────────

  def spec = suite("BootstrapProvisionerSpiceDB")(

    test("recordOwnership sends TOUCH owner_user tuple with bearer auth and succeeds on 2xx") {
      ZIO.serviceWithZIO[BootstrapProvisioner](
        _.recordOwnership(alice, ws1)
      ).map(_ => assertCompletes)
    }.provide(provisionerLayer(matchingStub)),

    test("HTTP 4xx (bad token / schema mismatch) maps to AuthServiceUnavailable (fail-closed)") {
      ZIO.serviceWithZIO[BootstrapProvisioner](
        _.recordOwnership(alice, ws1)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    }.provide(provisionerLayer(stubWith("""{"code":16,"message":"UNAUTHENTICATED"}""", code = 401))),

    test("HTTP 5xx maps to AuthServiceUnavailable (fail-closed)") {
      ZIO.serviceWithZIO[BootstrapProvisioner](
        _.recordOwnership(alice, ws1)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    }.provide(provisionerLayer(stubWith("""upstream connect error""", code = 502))),

    test("network failure maps to AuthServiceUnavailable (fail-closed)") {
      ZIO.serviceWithZIO[BootstrapProvisioner](
        _.recordOwnership(alice, ws1)
      ).either.map {
        case Left(_: AuthServiceUnavailable) => assertCompletes
        case other                           => assertTrue(false)
      }
    }.provide(provisionerLayer(stubFailing(new RuntimeException("connection refused")))),

    test("bootstrapToken and systemMaintenanceToken make no SpiceDB call") {
      // Backend fails on ANY request — the token methods can only succeed
      // if they never touch HTTP (lifecycle markers only, per trait contract).
      ZIO.serviceWithZIO[BootstrapProvisioner] { provisioner =>
        for
          _ <- provisioner.bootstrapToken()
          _ <- provisioner.systemMaintenanceToken()
        yield assertCompletes
      }
    }.provide(provisionerLayer(stubFailing(new RuntimeException("must not be called"))))
  )

package com.risquanter.register.auth

import zio.*
import zio.json.*
import sttp.client3.*
import sttp.client3.httpclient.zio.HttpClientZioBackend
import sttp.model.Uri
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import com.risquanter.register.configs.SpiceDbConfig
import com.risquanter.register.domain.data.iron.{UserId, WorkspaceId}
import com.risquanter.register.domain.errors.{AuthError, Failure, OutageMode}

// ─── SpiceDB WriteRelationships request types (file-private) ─────────────────
// SpiceDbObjectRef / SpiceDbSubjectRef and their encoders are shared with
// AuthorizationServiceSpiceDB.scala (package-visible within `auth`).

private case class SpiceDbRelationship(
  resource: SpiceDbObjectRef,
  relation: String,
  subject:  SpiceDbSubjectRef
)
private given JsonEncoder[SpiceDbRelationship] = DeriveJsonEncoder.gen

private case class SpiceDbRelationshipUpdate(operation: String, relationship: SpiceDbRelationship)
private given JsonEncoder[SpiceDbRelationshipUpdate] = DeriveJsonEncoder.gen

private case class SpiceDbWriteRequest(updates: List[SpiceDbRelationshipUpdate])
private given JsonEncoder[SpiceDbWriteRequest] = DeriveJsonEncoder.gen

// ─── Live SpiceDB lifecycle-write adapter ────────────────────────────────────

/** Live SpiceDB HTTP adapter for resource lifecycle writes.
  *
  * Implements the single lifecycle write via SpiceDB's WriteRelationships REST
  * API: `workspace:{workspaceId}#owner_user@user:{userId}`, written with
  * `OPERATION_TOUCH` (idempotent upsert — safe under retry).
  *
  * This is resource lifecycle management, not policy administration: the write
  * is system-initiated as part of workspace creation. The SpiceDB service
  * account's write scope is limited to `owner_user`/`owner_team` relations on
  * `workspace` definitions — it cannot write arbitrary access grants.
  *
  * Fail-closed on all transport and SpiceDB-side errors: any failure mode is
  * an `Unanswered` at the authorization zone (never
  * [[com.risquanter.register.domain.errors.AuthForbidden]] — a rejected write
  * means service-account misconfiguration, not a user-level denial), which
  * fails the enclosing bootstrap request.
  *
  * @see ADR-024 §7 — resource lifecycle write clarification
  */
final class BootstrapProvisionerSpiceDB private (
  config:  SpiceDbConfig,
  backend: SttpBackend[Task, Any]
) extends BootstrapProvisioner:

  private val writeUri: Uri = Uri.unsafeParse(config.url.value + "/v1/relationships/write")

  private val timeout: FiniteDuration =
    FiniteDuration(config.timeoutSeconds.toLong, TimeUnit.SECONDS)

  override def recordOwnership(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId
  ): IO[AuthError | Failure, Unit] =
    for
      outcome <- sendWriteRequest(userId, workspaceId).either
      _       <- logWriteResult(userId, workspaceId, outcome)
      result  <- ZIO.fromEither(outcome)
    yield result

  private def sendWriteRequest(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId
  ): IO[Failure, Unit] =
    val body = SpiceDbWriteRequest(
      updates = List(
        SpiceDbRelationshipUpdate(
          operation = BootstrapProvisionerSpiceDB.TouchOperation,
          relationship = SpiceDbRelationship(
            resource = SpiceDbObjectRef.Resource(ResourceType.Workspace, workspaceId.toSafeId),
            relation = BootstrapProvisionerSpiceDB.OwnerRelation,
            subject  = SpiceDbSubjectRef(`object` = SpiceDbObjectRef.User(userId))
          )
        )
      )
    )
    basicRequest
      .post(writeUri)
      .contentType("application/json")
      .auth.bearer(config.token.reveal)
      .body(body.toJson)
      .response(asStringAlways)
      .readTimeout(timeout)
      .send(backend)
      .mapError(e => authorizationOutage(OutageMode.ConnectionRefused, peerMessage(e)))
      .flatMap { response =>
        // Success body ({"writtenAt":{"token":"..."}}) is intentionally ignored:
        // the app never does write-then-check (pure PEP; consistency is a
        // check-side config concern — see SpiceDbConsistency).
        if response.code.isSuccess then ZIO.unit
        else ZIO.fail(authorizationOutage(OutageMode.Status(response.code), Nil))
      }

  // user.value — explicit PII opt-in; ownership writes are audit-relevant
  // (the same designated audit context as authz.check).
  private def logWriteResult(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId,
    outcome:     Either[Failure, Unit]
  ): UIO[Unit] =
    val resultStr = outcome match
      case Right(_) => "written"
      case Left(f)  => s"error reason=${f.logMessage}"
    ZIO.logInfo(
      s"authz.recordOwnership user=${userId.value} workspace=${workspaceId.value} " +
      s"relation=${BootstrapProvisionerSpiceDB.OwnerRelation} result=$resultStr"
    )

  override def bootstrapToken(): UIO[Checked[LifecycleMarker.Bootstrap.type]] =
    ZIO.succeed(Checked[LifecycleMarker.Bootstrap.type]())

  override def systemMaintenanceToken(): UIO[Checked[LifecycleMarker.SystemMaintenance.type]] =
    ZIO.succeed(Checked[LifecycleMarker.SystemMaintenance.type]())

// ─── Companion object ────────────────────────────────────────────────────────

object BootstrapProvisionerSpiceDB:

  /** The SpiceDB WriteRelationships operation that upserts a relationship, so a
    * retried write is idempotent. Spelling fixed by the SpiceDB v1 API.
    */
  private val TouchOperation: String = "OPERATION_TOUCH"

  /** The relation the creator holds on a workspace. Spelling fixed by the Zed
    * schema in `infra/spicedb/schema.zed`.
    */
  private val OwnerRelation: String = "owner_user"

  /** Layer with injectable SttpBackend — use SttpBackendStub in unit tests. */
  val layer: ZLayer[SpiceDbConfig & SttpBackend[Task, Any], Nothing, BootstrapProvisioner] =
    ZLayer {
      for
        config  <- ZIO.service[SpiceDbConfig]
        backend <- ZIO.service[SttpBackend[Task, Any]]
      yield new BootstrapProvisionerSpiceDB(config, backend)
    }

  /** Production layer — creates and manages its own HTTP client lifecycle.
    *
    * The HTTP backend is scoped to this layer's lifetime (application shutdown).
    * Use [[layer]] with a SttpBackendStub for unit tests.
    */
  val liveLayer: ZLayer[SpiceDbConfig, Throwable, BootstrapProvisioner] =
    ZLayer.scoped {
      for
        config  <- ZIO.service[SpiceDbConfig]
        backend <- HttpClientZioBackend.scoped()
      yield new BootstrapProvisionerSpiceDB(config, backend)
    }

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
import com.risquanter.register.domain.errors.{AuthError, AuthServiceUnavailable}

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
  * Fail-closed on all transport and SpiceDB-side errors: any failure mode maps
  * to [[AuthServiceUnavailable]] (never [[com.risquanter.register.domain.errors.AuthForbidden]] —
  * a rejected write means service-account misconfiguration, not a user-level
  * denial), which fails the enclosing bootstrap request.
  *
  * @see ADR-024 §7 — resource lifecycle write clarification
  * @see AUTHORIZATION-IMPLEMENTATION-PLAN.md §D — BootstrapProvisioner design
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
  ): IO[AuthError, Unit] =
    for
      outcome <- sendWriteRequest(userId, workspaceId).either
      _       <- logWriteResult(userId, workspaceId, outcome)
      result  <- ZIO.fromEither(outcome)
    yield result

  private def sendWriteRequest(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId
  ): IO[AuthError, Unit] =
    val body = SpiceDbWriteRequest(
      updates = List(
        SpiceDbRelationshipUpdate(
          operation = "OPERATION_TOUCH",
          relationship = SpiceDbRelationship(
            resource = SpiceDbObjectRef("workspace", workspaceId.value),
            relation = "owner_user",
            subject  = SpiceDbSubjectRef(`object` = SpiceDbObjectRef("user", userId.value))
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
      .mapError(e =>
        AuthServiceUnavailable(s"SpiceDB ownership write failed: ${e.getMessage}", Some(e))
      )
      .flatMap { response =>
        // Success body ({"writtenAt":{"token":"..."}}) is intentionally ignored:
        // the app never does write-then-check (pure PEP; consistency is a
        // check-side config concern — see SpiceDbConsistency).
        if response.code.isSuccess then ZIO.unit
        else
          ZIO.fail(AuthServiceUnavailable(
            s"SpiceDB ownership write returned HTTP ${response.code.code}"
          ))
      }

  // user.value — explicit PII opt-in; ownership writes are audit-relevant
  // (same designated audit context as authz.check — AUTHORIZATION-PLAN §L2.2).
  private def logWriteResult(
    userId:      UserId.Authenticated,
    workspaceId: WorkspaceId,
    outcome:     Either[AuthError, Unit]
  ): UIO[Unit] =
    val resultStr = outcome match
      case Right(_)                        => "written"
      case Left(e: AuthServiceUnavailable) => s"error reason=${e.reason}"
      case Left(_)                         => "error"
    ZIO.logInfo(
      s"authz.recordOwnership user=${userId.value} workspace=${workspaceId.value} " +
      s"relation=owner_user result=$resultStr"
    )

  override def bootstrapToken(): UIO[Checked[Permission.Bootstrap.type]] =
    ZIO.succeed(Checked[Permission.Bootstrap.type]())

  override def systemMaintenanceToken(): UIO[Checked[Permission.SystemMaintenance.type]] =
    ZIO.succeed(Checked[Permission.SystemMaintenance.type]())

// ─── Companion object ────────────────────────────────────────────────────────

object BootstrapProvisionerSpiceDB:

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

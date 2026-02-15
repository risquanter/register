package com.risquanter.register.http.responses

import java.time.Instant
import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.WorkspaceKey

final case class WorkspaceBootstrapResponse(
  workspaceKey: WorkspaceKey,
  tree: SimulationResponse,
  expiresAt: Option[Instant]
)

object WorkspaceBootstrapResponse:
  given codec: JsonCodec[WorkspaceBootstrapResponse] = DeriveJsonCodec.gen[WorkspaceBootstrapResponse]

final case class WorkspaceRotateResponse(
  workspaceKey: WorkspaceKey,
  expiresAt: Option[Instant]
)

object WorkspaceRotateResponse:
  given codec: JsonCodec[WorkspaceRotateResponse] = DeriveJsonCodec.gen[WorkspaceRotateResponse]

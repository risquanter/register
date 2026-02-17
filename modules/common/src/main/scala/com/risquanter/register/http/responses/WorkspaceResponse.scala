package com.risquanter.register.http.responses

import java.time.Instant
import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.WorkspaceKeySecret

final case class WorkspaceBootstrapResponse(
  workspaceKey: WorkspaceKeySecret,
  tree: SimulationResponse,
  expiresAt: Option[Instant]
)

object WorkspaceBootstrapResponse:
  given codec: JsonCodec[WorkspaceBootstrapResponse] = DeriveJsonCodec.gen[WorkspaceBootstrapResponse]

final case class WorkspaceRotateResponse(
  workspaceKey: WorkspaceKeySecret,
  expiresAt: Option[Instant]
)

object WorkspaceRotateResponse:
  given codec: JsonCodec[WorkspaceRotateResponse] = DeriveJsonCodec.gen[WorkspaceRotateResponse]

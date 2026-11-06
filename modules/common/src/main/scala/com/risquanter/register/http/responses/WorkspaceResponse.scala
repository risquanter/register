package com.risquanter.register.http.responses

import java.time.Instant
import sttp.tapir.{Codec, CodecFormat, Schema}
import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.{NonNegativeInt, SeedEntityId, WorkspaceKeySecret}
import com.risquanter.register.domain.data.iron.given
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** The bootstrap's answer: the new workspace's key, its first tree, and the
  * figures a client needs to reproduce it. One of the two answers that carry
  * the key (ADR-021).
  */
final case class WorkspaceBootstrapResponse(
  workspaceKey: WorkspaceKeySecret,
  tree: TreeSummaryResponse,
  expiresAt: Option[Instant],
  seedEntityId: SeedEntityId.SeedEntityId
)

object WorkspaceBootstrapResponse:
  given codec: JsonCodec[WorkspaceBootstrapResponse] = DeriveJsonCodec.gen[WorkspaceBootstrapResponse]

/** The relative reference a bootstrap answers in `Location`. It resolves
  * against the request's target `/workspaces` to `/w/{key}`, the workspace's
  * own URI. It carries the same credential the body carries, to its holder.
  *
  * {{{
  * WorkspaceLocation(key)   // renders as "w/" + the key, and parses back
  * }}}
  */
final case class WorkspaceLocation(key: WorkspaceKeySecret)

object WorkspaceLocation:
  private val keyCodec: Codec[String, WorkspaceKeySecret, CodecFormat.TextPlain] =
    summon[Codec[String, WorkspaceKeySecret, CodecFormat.TextPlain]]

  /** Both directions go through the key's own codec, so this adds no site that
    * reads the raw credential (ADR-022). */
  given codec: Codec[String, WorkspaceLocation, CodecFormat.TextPlain] =
    prefixedLocation(LocationRoute.Workspace, keyCodec)(WorkspaceLocation(_), _.key)

/** The rotate answer: the replacement key. The other answer that carries the
  * key (ADR-021). */
final case class WorkspaceRotateResponse(
  workspaceKey: WorkspaceKeySecret,
  expiresAt: Option[Instant]
)

object WorkspaceRotateResponse:
  given codec: JsonCodec[WorkspaceRotateResponse] = DeriveJsonCodec.gen[WorkspaceRotateResponse]

/** How many expired workspaces the admin sweep removed. */
final case class EvictExpiredResponse(evicted: NonNegativeInt)

object EvictExpiredResponse:
  given codec: JsonCodec[EvictExpiredResponse] = DeriveJsonCodec.gen[EvictExpiredResponse]
  given schema: Schema[EvictExpiredResponse] = Schema.derived[EvictExpiredResponse]

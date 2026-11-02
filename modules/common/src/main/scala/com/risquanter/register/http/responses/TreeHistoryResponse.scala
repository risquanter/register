package com.risquanter.register.http.responses

import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.iron.CommitHash

/** The four writes a save makes to one tree. */
@jsonDiscriminator(TreeWriteKind.TagField)
enum TreeWriteKind:
  case Create, Update, Delete, Revert

object TreeWriteKind:
  /** The key naming which case a payload states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. */
  final val TagField = "kind"

  given codec: JsonCodec[TreeWriteKind] = DeriveJsonCodec.gen[TreeWriteKind]

  given schema: Schema[TreeWriteKind] =
    import sttp.tapir.generic.auto.*
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[TreeWriteKind]

/** The kind of write a history entry shows. A tree write is one case holding
  * its kind, so each write is named once. The server derives it from the
  * commit message, which embeds the `WorkspaceId` and never leaves the server;
  * a message the server did not write is `Other`.
  */
@jsonDiscriminator(HistoryOperation.TagField)
enum HistoryOperation:
  case TreeWrite(write: TreeWriteKind)
  case Merge()
  case Other()

object HistoryOperation:
  /** The key naming which case a payload states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. */
  final val TagField = "kind"

  given codec: JsonCodec[HistoryOperation] = DeriveJsonCodec.gen[HistoryOperation]

  given schema: Schema[HistoryOperation] =
    import sttp.tapir.generic.auto.*
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[HistoryOperation]

/** One commit in a tree's history on a branch. `at` is an ISO-8601 timestamp. */
final case class TreeHistoryEntry(commitHash: CommitHash, operation: HistoryOperation, at: String)

object TreeHistoryEntry:
  given codec: JsonCodec[TreeHistoryEntry] = DeriveJsonCodec.gen[TreeHistoryEntry]

/** Response DTO for `GET /w/{key}/risk-trees/{treeId}/history`. */
final case class TreeHistoryResponse(entries: List[TreeHistoryEntry])

object TreeHistoryResponse:
  given codec: JsonCodec[TreeHistoryResponse] = DeriveJsonCodec.gen[TreeHistoryResponse]

package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec, JsonEncoder, JsonDecoder}
import com.risquanter.register.domain.data.iron.CommitHash

/** The kind of write a history entry records. Parsed server-side from the
  * commit-message convention; a raw or unrecognized message maps to `Other`.
  * String-encoded on the wire (the raw commit message, which embeds the
  * WorkspaceId, never leaves the server).
  */
enum HistoryOperation:
  case Create, Update, Delete, Merge, Revert, Other

object HistoryOperation:
  given JsonEncoder[HistoryOperation] = JsonEncoder[String].contramap(_.toString)
  given JsonDecoder[HistoryOperation] = JsonDecoder[String].map {
    case "Create" => Create
    case "Update" => Update
    case "Delete" => Delete
    case "Merge"  => Merge
    case "Revert" => Revert
    case _        => Other
  }

/** One commit in a tree's history on a branch. `at` is an ISO-8601 timestamp. */
final case class TreeHistoryEntry(commitHash: CommitHash, operation: HistoryOperation, at: String)

object TreeHistoryEntry:
  given codec: JsonCodec[TreeHistoryEntry] = DeriveJsonCodec.gen[TreeHistoryEntry]

/** Response DTO for `GET /w/{key}/risk-trees/{treeId}/history`. */
final case class TreeHistoryResponse(entries: List[TreeHistoryEntry])

object TreeHistoryResponse:
  given codec: JsonCodec[TreeHistoryResponse] = DeriveJsonCodec.gen[TreeHistoryResponse]

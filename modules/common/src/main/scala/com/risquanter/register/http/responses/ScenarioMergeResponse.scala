package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.CommitHash

/** One conflicting storage path found by the merge preview, wire form of the
  * server-side `MergeConflictPath`. `path` is workspace-relative (e.g.
  * `risk-trees/{treeId}/nodes/{nodeId}`) and never contains a `WorkspaceId`;
  * `treeId`/`nodeId` are the parsed coordinates when the path has a known
  * shape (`nodeId` empty for a tree's `meta` conflict).
  */
final case class MergeConflictEntry(path: String, treeId: Option[String], nodeId: Option[String])

object MergeConflictEntry:
  given codec: JsonCodec[MergeConflictEntry] = DeriveJsonCodec.gen[MergeConflictEntry]

/** Response DTO for `GET /w/{key}/scenarios/{name}/merge-preview`.
  *
  * `status` is `"clean"` (merge would apply without conflicts), `"conflicts"`
  * (`conflicts` populated), or `"missing-scenario"` (the scenario does not
  * exist — a non-error outcome of a read-only preview, mirroring
  * `ScenarioDiffResponse`'s missing-tree statuses).
  */
final case class MergePreviewResponse(status: String, conflicts: List[MergeConflictEntry])

object MergePreviewResponse:
  given codec: JsonCodec[MergePreviewResponse] = DeriveJsonCodec.gen[MergePreviewResponse]

/** Response DTO for `POST /w/{key}/scenarios/{name}/merge`: main's new head
  * commit after the merge.
  */
final case class MergeScenarioResponse(mergeCommit: CommitHash)

object MergeScenarioResponse:
  given codec: JsonCodec[MergeScenarioResponse] = DeriveJsonCodec.gen[MergeScenarioResponse]

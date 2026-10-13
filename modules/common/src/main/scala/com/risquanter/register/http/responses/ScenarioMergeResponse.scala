package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.RiskTree.{safeNameEncoder, safeNameDecoder}
import com.risquanter.register.domain.data.iron.{CommitHash, SafeName, TreeId}

/** One conflicting storage path found by the merge preview, wire form of the
  * server-side `MergeConflictPath`. `path` is workspace-relative (e.g.
  * `risk-trees/{treeId}/nodes/{nodeId}`) and never contains a `WorkspaceId`;
  * `treeId`/`nodeId` are the parsed coordinates when the path has a known
  * shape (`nodeId` empty for a tree's `meta` conflict).
  */
final case class MergeConflictEntry(path: String, treeId: Option[String], nodeId: Option[String])

object MergeConflictEntry:
  given codec: JsonCodec[MergeConflictEntry] = DeriveJsonCodec.gen[MergeConflictEntry]

/** The outcome of a merge preview, as a closed set.
  *
  * Each case carries the values that belong to it, so there is no case whose
  * payload lives in a sibling field that may or may not be populated. A match on
  * this type is exhaustive, which is what lets the browser drop the wildcard arm
  * that renders an unrecognised status as an empty conflict list.
  */
@jsonDiscriminator("kind")
enum MergePreviewStatus:
  /** The merge would apply with no intervention. */
  case Clean()

  /** Paths changed on both branches. */
  case Conflicts(paths: List[MergeConflictEntry])

  /** The merged node set would repeat a name within a tree, listed per tree. */
  case DuplicateNames(perTree: Map[TreeId, List[SafeName.SafeName]])

  /** The scenario does not exist — a non-error outcome of a read-only preview,
    * mirroring `ScenarioDiffResponse`'s missing-tree statuses.
    */
  case ScenarioMissing()

object MergePreviewStatus:
  given codec: JsonCodec[MergePreviewStatus] = DeriveJsonCodec.gen[MergePreviewStatus]

/** Response DTO for `GET /w/{key}/scenarios/{name}/merge-preview`. */
final case class MergePreviewResponse(status: MergePreviewStatus)

object MergePreviewResponse:
  given codec: JsonCodec[MergePreviewResponse] = DeriveJsonCodec.gen[MergePreviewResponse]

/** Response DTO for `POST /w/{key}/scenarios/{name}/merge`: main's new head
  * commit after the merge.
  */
final case class MergeScenarioResponse(mergeCommit: CommitHash)

object MergeScenarioResponse:
  given codec: JsonCodec[MergeScenarioResponse] = DeriveJsonCodec.gen[MergeScenarioResponse]

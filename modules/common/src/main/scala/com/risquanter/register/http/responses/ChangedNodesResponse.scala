package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}

/** One node's content-hash change outcome, wire form of `NodeChangeStatus`
  * (`modules/server`, service-layer only — not cross-compiled, hence the
  * `String` status here rather than sharing the domain enum directly).
  * `status` is one of `"identical"` / `"changed"` / `"added"` / `"removed"`.
  */
final case class NodeChangeEntry(nodeId: String, status: String)

object NodeChangeEntry:
  given codec: JsonCodec[NodeChangeEntry] = DeriveJsonCodec.gen[NodeChangeEntry]

/** Response DTO for `GET /w/{key}/risk-trees/{treeId}/changed-nodes`.
  *
  * `status` is `"ok"` (tree found on both sides — `entries` populated),
  * `"missing-on-a"`, `"missing-on-b"`, or `"missing-on-both"` (tree missing on
  * one or both sides — `entries` empty). Wire form of `ChangedNodesResult`
  * (`modules/server`, service-layer only). A discriminated field on one
  * response type, not `Option[ChangedNodesResponse]`, so the "which side is
  * missing" distinction survives to the wire instead of collapsing to a bare
  * absent body.
  */
final case class ChangedNodesResponse(status: String, entries: List[NodeChangeEntry])

object ChangedNodesResponse:
  given codec: JsonCodec[ChangedNodesResponse] = DeriveJsonCodec.gen[ChangedNodesResponse]

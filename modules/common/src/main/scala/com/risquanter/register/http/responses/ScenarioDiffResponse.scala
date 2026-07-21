package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}

/** One node's content-hash diff outcome (UC5), wire form of `NodeDiffStatus`
  * (`modules/server`, service-layer only — not cross-compiled, hence the
  * `String` status here rather than sharing the domain enum directly).
  * `status` is one of `"identical"` / `"changed"` / `"added"` / `"removed"`.
  */
final case class NodeDiffEntry(nodeId: String, status: String)

object NodeDiffEntry:
  given codec: JsonCodec[NodeDiffEntry] = DeriveJsonCodec.gen[NodeDiffEntry]

/** Response DTO for `GET /w/{key}/risk-trees/{treeId}/diff`.
  *
  * `status` is `"ok"` (tree found on both branches — `entries` populated),
  * `"missing-on-a"`, `"missing-on-b"`, or `"missing-on-both"` (tree missing
  * on one or both branches — `entries` empty). Wire form of
  * `ScenarioDiffResult` (`modules/server`, service-layer only). A discriminated
  * field on one response type, not `Option[ScenarioDiffResponse]`, so the
  * "which side is missing" distinction survives to the wire instead of
  * collapsing to a bare absent body.
  */
final case class ScenarioDiffResponse(status: String, entries: List[NodeDiffEntry])

object ScenarioDiffResponse:
  given codec: JsonCodec[ScenarioDiffResponse] = DeriveJsonCodec.gen[ScenarioDiffResponse]

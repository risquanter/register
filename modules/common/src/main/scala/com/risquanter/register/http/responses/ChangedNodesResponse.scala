package com.risquanter.register.http.responses

import sttp.tapir.Schema
import sttp.tapir.generic.Configuration
import sttp.tapir.generic.auto.schemaForCaseClass
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** One node's content-hash comparison between two revisions (ADR-032: the
  * domain content relation, so a renamed or moved node is `Identical`). */
enum NodeChangeStatus:
  case Identical, Changed, Added, Removed

  /** Whether the node's figures differ between the two revisions. */
  def differs: Boolean = this match
    case Identical                 => false
    case Changed | Added | Removed => true

object NodeChangeStatus:
  given codec: JsonCodec[NodeChangeStatus] = DeriveJsonCodec.gen[NodeChangeStatus]

/** One node's change outcome. */
final case class NodeChangeEntry(nodeId: NodeId, status: NodeChangeStatus)

object NodeChangeEntry:
  given codec: JsonCodec[NodeChangeEntry] = DeriveJsonCodec.gen[NodeChangeEntry]
  given schema: Schema[NodeChangeEntry] =
    Schema.derived[NodeChangeEntry]

/** The outcome of a changed-nodes comparison, as a closed set (ADR-038 §2). A
  * tree missing on one or both sides is a distinct, non-error outcome, which is
  * why it is a case here and not an absent body. */
@jsonDiscriminator(ChangedNodesStatus.TagField)
enum ChangedNodesStatus:
  /** The tree exists on both sides; one entry per node of either side. */
  case Ok(entries: List[NodeChangeEntry])
  case MissingOnA()
  case MissingOnB()
  case MissingOnBoth()

object ChangedNodesStatus:
  /** The key naming which outcome a response states. Read by this coproduct's
    * decoder and published in its schema, and stated here once so the two
    * cannot disagree. */
  final val TagField = "kind"

  given codec: JsonCodec[ChangedNodesStatus] = DeriveJsonCodec.gen[ChangedNodesStatus]

  /** Declared rather than left to the holder's derivation, so the response's
    * published description is the four outcomes and names the key that
    * decides between them. */
  given schema: Schema[ChangedNodesStatus] =
    given Configuration = Configuration.default.withDiscriminator(TagField)
    Schema.derived[ChangedNodesStatus]

/** Response DTO for `GET /w/{key}/risk-trees/{treeId}/changed-nodes`. */
final case class ChangedNodesResponse(status: ChangedNodesStatus)

object ChangedNodesResponse:
  given codec: JsonCodec[ChangedNodesResponse] = DeriveJsonCodec.gen[ChangedNodesResponse]

package com.risquanter.register.http.responses

import sttp.tapir.{Codec, CodecFormat, DecodeResult, Schema}
import zio.json.{DeriveJsonCodec, JsonCodec}

import com.risquanter.register.domain.data.iron.{SafeName, TreeId}
import com.risquanter.register.http.codecs.IronTapirCodecs

/** A tree's identity as the tree endpoints answer it: what a list, a write
  * acknowledgement and a bootstrap need to name the tree and address it. The
  * tree's content is the structure endpoint's answer, and its figures are
  * lec-multi's.
  */
final case class TreeSummaryResponse(id: TreeId, name: SafeName.SafeName)

object TreeSummaryResponse:
  given codec: JsonCodec[TreeSummaryResponse] = DeriveJsonCodec.gen[TreeSummaryResponse]
  given schema: Schema[TreeSummaryResponse] =
    import IronTapirCodecs.given
    Schema.derived[TreeSummaryResponse]

/** The trees a workspace holds on one branch. */
final case class TreeListResponse(trees: List[TreeSummaryResponse])

object TreeListResponse:
  given codec: JsonCodec[TreeListResponse] = DeriveJsonCodec.gen[TreeListResponse]
  given schema: Schema[TreeListResponse] = Schema.derived[TreeListResponse]

/** The relative reference a tree create answers in `Location`. It resolves
  * against the request's target `/w/{key}/risk-trees` to the new tree's URI,
  * so the header names the tree without writing the workspace key.
  *
  * {{{
  * TreeLocation(treeId)   // renders as "risk-trees/01HXYZ…", and parses back
  * }}}
  */
final case class TreeLocation(treeId: TreeId)

object TreeLocation:
  /** The collection segment of the tree routes, fixed by the route. */
  val Prefix: String = "risk-trees/"

  private val treeIdCodec: Codec[String, TreeId, CodecFormat.TextPlain] =
    import IronTapirCodecs.given
    summon[Codec[String, TreeId, CodecFormat.TextPlain]]

  /** Render and parse form one pair: `parse(render(l)) == Value(l)`. A value
    * without the prefix is a decode failure, which the client reports as an
    * unreadable answer rather than a location. */
  given codec: Codec[String, TreeLocation, CodecFormat.TextPlain] =
    Codec.string.mapDecode[TreeLocation] { raw =>
      if raw.startsWith(Prefix) then treeIdCodec.decode(raw.stripPrefix(Prefix)).map(TreeLocation(_))
      else DecodeResult.Mismatch(Prefix, raw)
    }(l => Prefix + treeIdCodec.encode(l.treeId))

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

/** The route a create names in `Location`. Each prefix is a contract with the
  * route it resolves to, so it is declared rather than derived.
  */
enum LocationRoute(val prefix: String):
  case Workspace extends LocationRoute("w/")
  case Tree      extends LocationRoute("risk-trees/")

/** A `Location` value as a route's prefix followed by one encoded value. Render
  * and parse form one pair: `parse(render(l)) == Value(l)`. A value without the
  * prefix is a decode failure, which a client reports as an unreadable answer
  * rather than a location.
  *
  * The header arrives as raw text, and this codec is where that text is parsed.
  */
private[responses] def prefixedLocation[L, V](route: LocationRoute, inner: Codec[String, V, CodecFormat.TextPlain])(
  wrap: V => L, unwrap: L => V): Codec[String, L, CodecFormat.TextPlain] =
  Codec.string.mapDecode[L] { raw =>
    if raw.startsWith(route.prefix) then inner.decode(raw.stripPrefix(route.prefix)).map(wrap)
    else DecodeResult.Mismatch(route.prefix, raw)
  }(location => route.prefix + inner.encode(unwrap(location)))

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
  private val treeIdCodec: Codec[String, TreeId, CodecFormat.TextPlain] =
    import IronTapirCodecs.given
    summon[Codec[String, TreeId, CodecFormat.TextPlain]]

  given codec: Codec[String, TreeLocation, CodecFormat.TextPlain] =
    prefixedLocation(LocationRoute.Tree, treeIdCodec)(TreeLocation(_), _.treeId)

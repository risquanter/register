package com.risquanter.register.http.requests

import zio.json.{DeriveJsonCodec, JsonCodec}
import sttp.tapir.Schema
import com.risquanter.register.domain.data.CurvePalette
import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.codecs.IronTapirCodecs.given

/** Request body for the LEC chart endpoint.
  *
  * Each entry specifies a node and which colour palette it belongs to.
  * The server resolves concrete hex colours by sorting nodes within each
  * palette group by p95 (descending) and assigning shades darkest →
  * lightest.
  *
  * @param curves Non-empty list of (nodeId, palette) entries.
  */
final case class LECChartRequest(
  curves: List[LECChartCurveEntry]
)

object LECChartRequest:
  given JsonCodec[LECChartRequest] = DeriveJsonCodec.gen
  given Schema[LECChartRequest] = Schema.derived

  /** Build a chart request from separate query and user node ID sets.
    *
    * Assigns palette colours based on set membership:
    *   - Query-only nodes → Green
    *   - User-only nodes  → Aqua
    *   - Overlap (both)   → Purple
    *
    * The three groups are mutually exclusive by construction.
    */
  def build(querySet: Set[NodeId], userSet: Set[NodeId]): LECChartRequest =
    val overlap   = querySet intersect userSet
    val queryOnly = querySet -- overlap
    val userOnly  = userSet  -- overlap

    val entries =
      queryOnly.toList.map(id => LECChartCurveEntry(id, CurvePalette.Green)) ++
      userOnly.toList.map(id  => LECChartCurveEntry(id, CurvePalette.Aqua)) ++
      overlap.toList.map(id   => LECChartCurveEntry(id, CurvePalette.Purple))

    LECChartRequest(entries)

/** Single curve entry in a chart request.
  *
  * @param nodeId  The node whose LEC curve to render.
  * @param palette Which colour palette to use for this curve.
  */
final case class LECChartCurveEntry(
  nodeId: NodeId,
  palette: CurvePalette
)

object LECChartCurveEntry:
  given JsonCodec[LECChartCurveEntry] = DeriveJsonCodec.gen
  given Schema[LECChartCurveEntry] = Schema.derived

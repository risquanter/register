package com.risquanter.register.http.responses

import zio.json.{DeriveJsonCodec, JsonCodec}
import com.risquanter.register.domain.data.iron.NodeId

/** Result of evaluating a proportional screening query over a risk tree.
  *
  * A query such as "at least 2/3 of leaves have P95 above 5M" produces
  * a boolean verdict together with the actual proportion and the specific
  * nodes that matched, so the frontend can highlight them in the tree.
  *
  * @param satisfied       true when the actual proportion meets the quantifier threshold
  * @param proportion      fraction of in-scope nodes that satisfy the condition (0.0–1.0)
  * @param rangeSize       total number of nodes the quantifier ranges over
  * @param sampleSize      number of nodes actually evaluated (equals rangeSize for exact mode)
  * @param satisfyingCount how many of those nodes satisfy the scope condition
 * @param satisfyingNodeIds the specific nodes that satisfied the condition
  * @param queryEcho       the original query text, echoed for display and logging
  */
final case class QueryResponse(
  satisfied: Boolean,
  proportion: Double,
  rangeSize: Int,
  sampleSize: Int,
  satisfyingCount: Int,
  satisfyingNodeIds: List[NodeId],
  queryEcho: String
)

object QueryResponse:
  given JsonCodec[QueryResponse] = DeriveJsonCodec.gen

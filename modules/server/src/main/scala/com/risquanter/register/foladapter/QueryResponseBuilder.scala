package com.risquanter.register.foladapter

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.responses.QueryResponse
import vague.datastore.RelationValue

/** Constructs [[QueryResponse]] from typed fol-engine evaluation results.
  *
  * This lives in `server` (not `common`) because it depends on
  * `RelationValue` from the fol-engine library, which is a server-only
  * dependency. `QueryResponse` itself remains a pure data carrier in
  * `common` for frontend consumption.
  */
object QueryResponseBuilder:

  /** Builds a response from fol-engine range/scope evaluation output.
    *
    * Satisfying and range elements arrive as `Set[RelationValue]` — the
    * typed sum type from the knowledge base layer. `Const` values are
    * resolved back to `NodeId` via `nodeIdLookup`; `Num` values (numeric
    * literals injected into the domain) are filtered out since they do
    * not represent tree nodes.
    *
    * @param satisfyingElements nodes whose scope formula evaluated to true
    * @param rangeElements      all nodes the quantifier ranged over
    * @param nodeIdLookup       maps KB constant names back to typed node IDs
    * @param queryEcho          original query text for echo-back
    * @param thresholdSatisfied whether the proportion met the quantifier threshold
    */
  def from(
    satisfyingElements: Set[RelationValue],
    rangeElements: Set[RelationValue],
    nodeIdLookup: Map[String, NodeId],
    queryEcho: String,
    thresholdSatisfied: Boolean
  ): QueryResponse =
    val matchingIds = satisfyingElements.toList.flatMap {
      case RelationValue.Const(name) => nodeIdLookup.get(name)
      case RelationValue.Num(_)      => None
    }
    QueryResponse(
      satisfied       = thresholdSatisfied,
      proportion      = if rangeElements.isEmpty then 0.0
                        else satisfyingElements.size.toDouble / rangeElements.size,
      rangeSize       = rangeElements.size,
      sampleSize      = rangeElements.size,
      satisfyingCount = satisfyingElements.size,
      matchingNodeIds = matchingIds,
      queryEcho       = queryEcho
    )

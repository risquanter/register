package com.risquanter.register.foladapter

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.responses.QueryResponse

import vql.result.EvaluationOutput
import vql.typed.{Value, extract}

/** Constructs [[QueryResponse]] from typed vql-engine evaluation results.
  *
  * This lives in `server` (not `common`) because it depends on
  * `EvaluationOutput[Value]` from the vql-engine library, which is a
  * server-only dependency. `QueryResponse` itself remains a pure data
  * carrier in `common` for frontend consumption.
  */
object QueryResponseBuilder:

  import RiskTreeKnowledgeBase.given

  /** Builds a response from vql-engine typed evaluation output.
    *
    * Satisfying and range elements arrive as `Set[Value]` — sort-tagged
    * runtime values from the typed pipeline. Node-sorted values are projected
    * to `NodeId` via `extract[NodeId]`; non-node values (Loss, Probability) are
    * filtered out since they do not represent tree nodes.
    *
    * @param output    Evaluation output containing result, range, and satisfying elements
    * @param queryEcho Original query text for echo-back
    */
  def from(
    output: EvaluationOutput[Value],
    queryEcho: String
  ): QueryResponse =
    val matchingIds = output.satisfyingElements.toList
      .filter(_.sort == RiskTreeKnowledgeBase.NodeSort)
      .flatMap(_.extract[NodeId].toOption)
    QueryResponse(
      satisfied       = output.satisfied,
      proportion      = output.proportion,
      rangeSize       = output.rangeElements.size,
      sampleSize      = output.rangeElements.size,
      satisfyingCount = output.satisfyingElements.size,
      satisfyingNodeIds = matchingIds,
      queryEcho       = queryEcho
    )

package com.risquanter.register.foladapter

import com.risquanter.register.domain.data.iron.NodeId
import com.risquanter.register.http.responses.QueryResponse

import fol.result.EvaluationOutput
import fol.typed.{Value, TypeId, TypeRepr}

/** Constructs [[QueryResponse]] from typed fol-engine evaluation results.
  *
  * This lives in `server` (not `common`) because it depends on
  * `EvaluationOutput[Value]` from the fol-engine library, which is a
  * server-only dependency. `QueryResponse` itself remains a pure data
  * carrier in `common` for frontend consumption.
  */
object QueryResponseBuilder:

  private val assetSort: TypeId = TypeId("Asset")

  /** TypeRepr instance for projecting Asset-sorted Values to String. */
  private given assetTypeRepr: TypeRepr[String] = new TypeRepr[String]:
    val typeId: TypeId = assetSort

  /** Builds a response from fol-engine typed evaluation output.
    *
    * Satisfying and range elements arrive as `Set[Value]` — sort-tagged
    * runtime values from the typed pipeline. Asset-sorted values are
    * projected to String via `Value.as[String]` (using the `TypeRepr`
    * instance above), then resolved to `NodeId` via `nodeIdLookup`.
    * Non-Asset values (Loss, Probability) are filtered out since they
    * do not represent tree nodes.
    *
    * @param output        Evaluation output containing result, range, and satisfying elements
    * @param nodeIdLookup  Maps node names back to typed node IDs
    * @param queryEcho     Original query text for echo-back
    */
  def from(
    output: EvaluationOutput[Value],
    nodeIdLookup: Map[String, NodeId],
    queryEcho: String
  ): QueryResponse =
    val matchingIds = output.satisfyingElements.toList.flatMap { v =>
      v.as[String].flatMap(nodeIdLookup.get)
    }
    QueryResponse(
      satisfied       = output.satisfied,
      proportion      = output.proportion,
      rangeSize       = output.rangeElements.size,
      sampleSize      = output.rangeElements.size,
      satisfyingCount = output.satisfyingElements.size,
      matchingNodeIds = matchingIds,
      queryEcho       = queryEcho
    )

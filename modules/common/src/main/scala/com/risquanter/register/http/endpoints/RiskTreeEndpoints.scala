package com.risquanter.register.http.endpoints

import sttp.tapir.*
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.http.responses.SimulationResponse
import com.risquanter.register.http.codecs.IronTapirCodecs.given
import com.risquanter.register.domain.data.{LECCurveResponse, LECPoint, LECNodeCurve, RiskLeaf, RiskTree}
import com.risquanter.register.domain.data.RiskLeaf.given // JsonCodecs for SafeId.SafeId
import com.risquanter.register.domain.data.iron.{PositiveInt, NonNegativeInt, SafeId, TreeId, NodeId, IronConstants}
import IronConstants.Zero
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

/**
  * Response DTO for the cache invalidation endpoint.
  *
  * Uses `List[String]` for node IDs on the wire per ADR-001 Option A
  * (public String API, internal Iron types).
  *
  * @param invalidatedNodes Node IDs whose cache entries were cleared (node + ancestors)
  * @param subscribersNotified Number of SSE subscribers who received the invalidation event
  */
final case class InvalidationResponse(
    invalidatedNodes: List[String],
    subscribersNotified: Int
)

object InvalidationResponse {
  given codec: zio.json.JsonCodec[InvalidationResponse] = zio.json.DeriveJsonCodec.gen[InvalidationResponse]
}

/** Tapir endpoint definitions for RiskTree API
  * Extends BaseEndpoint for standardized error handling
  * 
  * Iron types (PositiveInt, NonNegativeInt) are validated at the HTTP layer
  * via Tapir codecs—invalid input returns 400 before reaching controllers.
  */
trait RiskTreeEndpoints extends BaseEndpoint {
  
  val healthEndpoint =
    baseEndpoint
      .tag("system")
      .name("health")
      .description("Health check endpoint")
      .in("health")
      .get
      .out(jsonBody[Map[String, String]])
  
  val createEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("create")
      .description("Create a new risk tree")
      .in("risk-trees")
      .post
      .in(jsonBody[RiskTreeDefinitionRequest])
      .out(jsonBody[SimulationResponse])

  /** Full replacement update of an existing risk tree.
    *
    * Replaces the entire tree structure (nodes + topology).
    * The tree ID is preserved; all node IDs are regenerated.
    */
  val updateEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("update")
      .description("Full replacement update of an existing risk tree")
      .in("risk-trees" / path[TreeId]("id"))
      .put
      .in(jsonBody[RiskTreeUpdateRequest])
      .out(jsonBody[SimulationResponse])

  val getAllEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getAll")
      .description("Get all risk trees")
      .in("risk-trees")
      .get
      .out(jsonBody[List[SimulationResponse]])

  val getByIdEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getById")
      .description("Get a risk tree by ID")
      .in("risk-trees" / path[TreeId]("id"))
      .get
      .out(jsonBody[Option[SimulationResponse]])

  /** Get the full tree structure including all nodes.
    *
    * Returns the complete `RiskTree` with its flat node list and rootId,
    * enabling the client to render an expandable node hierarchy.
    * Separated from `getByIdEndpoint` (which returns summary-only
    * `SimulationResponse`) to keep list endpoints lightweight.
    */
  val getTreeStructureEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getTreeStructure")
      .description("Get the full tree structure with all nodes")
      .in("risk-trees" / path[TreeId]("id") / "structure")
      .get
      .out(jsonBody[Option[RiskTree]])

  /** Manual cache invalidation endpoint for testing SSE pipeline.
    * Triggers cache invalidation for a specific node and broadcasts SSE event.
    * 
    * @return Number of SSE subscribers notified about the invalidation
    */
  val invalidateCacheEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("invalidateCache")
      .description("Manually invalidate cache for a node (triggers SSE notification)")
      .in("risk-trees" / path[TreeId]("id") / "invalidate" / path[NodeId]("nodeId"))
      .post
      .out(jsonBody[InvalidationResponse])
  
  // ========================================
  // LEC Query APIs
  // ========================================
  
  /** Get LEC curve for a single node.
    * 
    * Returns loss exceedance curve with optional provenance metadata.
    * Uses cache-aside pattern for performance.
    */
  val getLECCurveEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getLECCurve")
      .description("Get LEC curve for a node (with optional provenance)")
      .in("risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "lec")
      .get
      .in(query[Boolean]("includeProvenance").default(false))
      .out(jsonBody[LECCurveResponse])
  
  /** Get exceedance probability at a specific threshold.
    * 
    * Returns P(Loss >= threshold) for a given node.
    */
  val probOfExceedanceEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("probOfExceedance")
      .description("Get probability of exceeding a loss threshold")
      .in("risk-trees" / path[TreeId]("treeId") / "nodes" / path[NodeId]("nodeId") / "prob-of-exceedance")
      .get
      .in(query[Long]("threshold"))
      .in(query[Boolean]("includeProvenance").default(false))
      .out(jsonBody[BigDecimal])
  
  /** Get LEC curves for multiple nodes with shared tick domain.
    * 
    * Used for multi-curve overlay visualization.
    * Request body contains array of node IDs.
    * Validation: JsonDecoder validates String → SafeId.SafeId via smart constructor.
    */
  val getLECCurvesMultiEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getLECCurvesMulti")
      .description("Get LEC curves for multiple nodes (shared tick domain)")
      .in("risk-trees" / path[TreeId]("treeId") / "nodes" / "lec-multi")
      .post
      .in(query[Boolean]("includeProvenance").default(false))
      .in(jsonBody[List[NodeId]].description("Array of node IDs"))
      .out(jsonBody[Map[String, LECNodeCurve]])

  /** Get a complete Vega-Lite chart specification for LEC visualization.
    *
    * Returns a render-ready Vega-Lite JSON spec for multi-curve overlay.
    * The frontend passes this directly to VegaEmbed — no client-side spec
    * construction needed.
    */
  val getLECChartEndpoint =
    baseEndpoint
      .tag("risk-trees")
      .name("getLECChart")
      .description("Get Vega-Lite chart spec for LEC visualization (render-ready)")
      .in("risk-trees" / path[TreeId]("treeId") / "lec-chart")
      .post
      .in(jsonBody[List[NodeId]].description("Array of node IDs to include in chart"))
      .out(stringBody.description("Vega-Lite JSON specification"))
}

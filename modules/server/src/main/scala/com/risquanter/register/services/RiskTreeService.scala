package com.risquanter.register.services

import zio.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.domain.data.{RiskTree, LECCurveResponse, LECPoint}
import com.risquanter.register.domain.data.iron.NonNegativeLong
import com.risquanter.register.domain.tree.NodeId

/** Service layer for RiskTree business logic.
  * 
  * Separation of Concerns:
  * - Config CRUD: Persists risk tree structure only (no execution)
  * - LEC Query APIs: Node-based queries composing on RiskResultResolver.ensureCached
  * 
  * Design Pattern (ADR-015):
  * - Tree Manipulation: POST/PUT/DELETE → synchronous, fast (config only)
  * - LEC Query: Node-based APIs that use cache-aside pattern
  * - Simulation parameters come from SimulationConfig (not API parameters)
  */
trait RiskTreeService {
  
  // ========================================
  // Config CRUD (NO execution)
  // ========================================
  
  /** Create risk tree definition - persists tree structure only
    * @param req Request containing tree definition
    * @return Persisted risk tree metadata (no LEC data)
    */
  def create(req: RiskTreeDefinitionRequest): Task[RiskTree]
  
  /** Update risk tree definition - modifies tree structure only
    * @param id Risk tree ID
    * @param req Updated tree definition
    * @return Updated risk tree metadata
    */
  def update(id: NonNegativeLong, req: RiskTreeUpdateRequest): Task[RiskTree]
  
  /** Delete risk tree configuration
    * @param id Risk tree ID
    * @return Deleted risk tree metadata
    */
  def delete(id: NonNegativeLong): Task[RiskTree]
  
  /** Retrieve all persisted risk tree configurations (no LEC data)
    * @return List of all risk trees
    */
  def getAll: Task[List[RiskTree]]
  
  /** Retrieve single risk tree configuration by ID (no LEC data)
    * @param id Risk tree ID
    * @return Optional risk tree metadata
    */
  def getById(id: NonNegativeLong): Task[Option[RiskTree]]
  
  // ========================================
  // LEC Query APIs (ADR-015: compose on ensureCached)
  // ========================================
  
  /** Get LEC curve for a single node.
    * 
    * Uses cache-aside pattern: returns cached result if available,
    * otherwise simulates and caches before returning.
    * 
    * @param nodeId Node identifier (SafeId)
    * @param includeProvenance Whether to include provenance metadata for reproducibility
    * @return LEC curve response with quantiles, curve points, and childIds for navigation
    */
  def getLECCurve(treeId: NonNegativeLong, nodeId: NodeId, includeProvenance: Boolean = false): Task[LECCurveResponse]
  
  /** Get exceedance probability at a threshold for a node.
    * 
    * @param nodeId Node identifier
    * @param threshold Loss threshold to compute P(Loss >= threshold)
    * @param includeProvenance Whether to include provenance metadata (currently unused for this endpoint)
    * @return Probability as BigDecimal
    */
  def probOfExceedance(treeId: NonNegativeLong, nodeId: NodeId, threshold: Long, includeProvenance: Boolean = false): Task[BigDecimal]
  
  /** Get LEC curves for multiple nodes with shared tick domain.
    * 
    * Used for multi-curve overlay (e.g., split pane comparison).
    * All curves share the same loss ticks for aligned rendering.
    * 
    * @param nodeIds Set of node identifiers
    * @param includeProvenance Whether to include provenance metadata for reproducibility
    * @return Map from nodeId to curve points
    */
  def getLECCurvesMulti(treeId: NonNegativeLong, nodeIds: Set[NodeId], includeProvenance: Boolean = false): Task[Map[NodeId, Vector[LECPoint]]]
}

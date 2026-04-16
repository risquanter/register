package com.risquanter.register.services

import zio.*
import com.risquanter.register.http.requests.{RiskTreeDefinitionRequest, RiskTreeUpdateRequest}
import com.risquanter.register.domain.data.{RiskTree, LECCurveResponse, LECPoint, LECNodeCurve}
import com.risquanter.register.domain.data.iron.{TreeId, NodeId}

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
  def update(id: TreeId, req: RiskTreeUpdateRequest): Task[RiskTree]
  
  /** Delete risk tree configuration
    * @param id Risk tree ID
    * @return Deleted risk tree metadata
    */
  def delete(id: TreeId): Task[RiskTree]
  
  /** Retrieve all persisted risk tree configurations (no LEC data)
    * @return List of all risk trees
    */
  def getAll: Task[List[RiskTree]]
  
  /** Retrieve single risk tree configuration by ID (no LEC data)
    * @param id Risk tree ID
    * @return Optional risk tree metadata
    */
  def getById(id: TreeId): Task[Option[RiskTree]]
  
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
  // TODO-REMOVE: No real-world clients. Remove along with LECCurveResponse,
  // getWorkspaceLECCurveEndpoint, and WorkspaceController.getLECCurve.
  @deprecated("No real-world clients. Use getLECCurvesMulti instead.", since = "2026-04-14")
  def getLECCurve(treeId: TreeId, nodeId: NodeId, includeProvenance: Boolean = false): Task[LECCurveResponse]
  
  /** Get exceedance probability at a threshold for a node.
    * 
    * @param nodeId Node identifier
    * @param threshold Loss threshold to compute P(Loss >= threshold)
    * @param includeProvenance Whether to include provenance metadata (currently unused for this endpoint)
    * @return Probability as Double (empirical frequency ratio: exceedingCount / nTrials)
    */
  def probOfExceedance(treeId: TreeId, nodeId: NodeId, threshold: Long, includeProvenance: Boolean = false): Task[Double]
  
  /** Get LEC curves for multiple nodes with shared tick domain.
    * 
    * Used for multi-curve overlay (e.g., split pane comparison).
    * All curves share the same loss ticks for aligned rendering.
    * Returns LECNodeCurve (id + name + curve + quantiles) per node.
    * 
    * @param nodeIds Set of node identifiers
    * @param includeProvenance Whether to include provenance metadata for reproducibility
    * @return Map from nodeId to LECNodeCurve (id, name, curve points, quantiles)
    */
  def getLECCurvesMulti(treeId: TreeId, nodeIds: Set[NodeId], includeProvenance: Boolean = false): Task[Map[NodeId, LECNodeCurve]]
}

object RiskTreeService:

  /** Best-effort cascade deletion — deletes each tree, swallowing individual failures.
    *
    * Used by both `WorkspaceController.deleteWorkspace` (explicit delete) and
    * `WorkspaceReaper` (TTL expiry). Extracted here as the single source of truth
    * for the cascade-delete semantic.
    */
  extension (self: RiskTreeService)
    def cascadeDeleteTrees(ids: Iterable[TreeId]): UIO[Unit] =
      ZIO.foreachDiscard(ids)(id => self.delete(id).ignore)

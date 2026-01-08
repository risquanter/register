package com.risquanter.register.services

import zio.*
import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.domain.data.{RiskTree, RiskTreeWithLEC}

/** Service layer for RiskTree business logic.
  * 
  * Separation of Concerns:
  * - Config CRUD: Persists risk tree structure only (no execution)
  * - LEC Computation: Executes Monte Carlo on-demand (separate endpoint)
  * 
  * Design Pattern:
  * - Tree Manipulation: POST/PUT/DELETE → synchronous, fast (config only)
  * - LEC Query: GET /risk-trees/:id/lec → executes bottom-up, returns hierarchical results
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
  def update(id: Long, req: RiskTreeDefinitionRequest): Task[RiskTree]
  
  /** Delete risk tree configuration
    * @param id Risk tree ID
    * @return Deleted risk tree metadata
    */
  def delete(id: Long): Task[RiskTree]
  
  /** Retrieve all persisted risk tree configurations (no LEC data)
    * @return List of all risk trees
    */
  def getAll: Task[List[RiskTree]]
  
  /** Retrieve single risk tree configuration by ID (no LEC data)
    * @param id Risk tree ID
    * @return Optional risk tree metadata
    */
  def getById(id: Long): Task[Option[RiskTree]]
  
  // ========================================
  // LEC Computation (on-demand execution)
  // ========================================
  
  /** Execute simulation and compute LEC for entire tree.
    * Returns hierarchical results preserving tree structure.
    * 
    * @param id Risk tree ID to execute
    * @param nTrialsOverride Optional override for number of trials (uses stored default if None)
    * @param parallelism Optional parallelism level. When None, uses config.defaultParallelism.
    *                    This allows clients to omit parallelism and rely on server-side defaults,
    *                    which is the recommended approach for most use cases.
    * @param depth Hierarchy depth to include (0=root only, 1=+children, max 5)
    * @param includeProvenance Whether to include provenance metadata for reproducibility
    * @return Risk tree with computed LEC data in hierarchical format
    */
  def computeLEC(
    id: Long,
    nTrialsOverride: Option[Int] = None,
    parallelism: Option[Int] = None,
    depth: Int = 0,
    includeProvenance: Boolean = false
  ): Task[RiskTreeWithLEC]
}

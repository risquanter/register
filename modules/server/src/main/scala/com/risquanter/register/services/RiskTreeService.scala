package com.risquanter.register.services

import zio.*
import com.risquanter.register.http.requests.RiskTreeDefinitionRequest
import com.risquanter.register.domain.data.{RiskTree, RiskTreeWithLEC}
import com.risquanter.register.domain.data.iron.{PositiveInt, NonNegativeInt, NonNegativeLong}

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
  def update(id: NonNegativeLong, req: RiskTreeDefinitionRequest): Task[RiskTree]
  
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
  // LEC Computation (on-demand execution)
  // ========================================
  
  /** Execute simulation and compute LEC for entire tree.
    * Returns hierarchical results preserving tree structure.
    * 
    * @param id Risk tree ID to execute
    * @param nTrialsOverride Optional override for number of trials (uses stored default if None)
    * @param parallelism Parallelism level (guaranteed positive by Iron type)
    * @param depth Hierarchy depth to include (0=root only, 1=+children, max 5)
    * @param includeProvenance Whether to include provenance metadata for reproducibility
    * @param seed3 Global seed 3 for HDR random number generation (enables reproducibility)
    * @param seed4 Global seed 4 for HDR random number generation (enables reproducibility)
    * @return Risk tree with computed LEC data in hierarchical format
    */
  def computeLEC(
    id: NonNegativeLong,
    nTrialsOverride: Option[PositiveInt],
    parallelism: PositiveInt,
    depth: NonNegativeInt,
    includeProvenance: Boolean,
    seed3: Long = 0L,
    seed4: Long = 0L
  ): Task[RiskTreeWithLEC]
}

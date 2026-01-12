package com.risquanter.register.services

import zio._
import com.risquanter.register.domain.data.{RiskNode, RiskTreeResult, TreeProvenance}
import com.risquanter.register.domain.data.iron.PositiveInt
import com.risquanter.register.domain.errors.SimulationFailure
import com.risquanter.register.services.helper.Simulator
import io.github.iltotore.iron.*

/**
 * Service for orchestrating Monte Carlo simulations.
 * Executes tree-based risk simulations with bottom-up aggregation.
 */
trait SimulationExecutionService {
  /**
   * Run a tree-based simulation with parallel execution.
   * 
   * @param simulationId Unique identifier for this execution
   * @param root Root node of the risk tree
   * @param nTrials Number of Monte Carlo trials (must be positive)
   * @param parallelism Degree of parallelism (must be positive, default: available processors)
   * @param includeProvenance Whether to capture provenance metadata
   * @return Tuple of (RiskTreeResult, Option[TreeProvenance])
   */
  def runTreeSimulation(
    simulationId: String,
    root: RiskNode,
    nTrials: PositiveInt,
    parallelism: PositiveInt = java.lang.Runtime.getRuntime.availableProcessors().refineUnsafe,
    includeProvenance: Boolean = false
  ): Task[(RiskTreeResult, Option[TreeProvenance])]
}

/**
 * Live implementation delegating to Simulator for Monte Carlo execution.
 * 
 * Error handling (ADR-002 Decision 5):
 * - Logs errors with full cause at this boundary (single logging point)
 * - Wraps in typed SimulationFailure for proper pattern matching at HTTP layer
 */
final class SimulationExecutionServiceLive extends SimulationExecutionService {
  
  override def runTreeSimulation(
    simulationId: String,
    root: RiskNode,
    nTrials: PositiveInt,
    parallelism: PositiveInt,
    includeProvenance: Boolean
  ): Task[(RiskTreeResult, Option[TreeProvenance])] = {
    Simulator.simulateTree(root, nTrials, parallelism, includeProvenance)
      .tapErrorCause(cause => 
        ZIO.logErrorCause(s"Simulation failed: simulationId=$simulationId", cause)
      )
      .mapError(error => SimulationFailure(simulationId, error))
  }
}

object SimulationExecutionService {
  /** Live layer for ZIO dependency injection */
  val live: ZLayer[Any, Nothing, SimulationExecutionService] =
    SimulationExecutionServiceLive.layer
}

object SimulationExecutionServiceLive {
  val layer: ZLayer[Any, Nothing, SimulationExecutionService] =
    ZLayer.succeed(new SimulationExecutionServiceLive())
  
  /** Factory method for testing - creates an instance directly */
  def apply(): SimulationExecutionServiceLive = new SimulationExecutionServiceLive()
}

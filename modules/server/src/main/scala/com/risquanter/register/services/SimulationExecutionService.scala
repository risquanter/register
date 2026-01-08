package com.risquanter.register.services

import zio._
import com.risquanter.register.domain.data.{RiskNode, RiskTreeResult, TreeProvenance}
import com.risquanter.register.domain.data.iron.PositiveInt
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
 */
final class SimulationExecutionServiceLive extends SimulationExecutionService {
  
  override def runTreeSimulation(
    simulationId: String,
    root: RiskNode,
    nTrials: PositiveInt,
    parallelism: PositiveInt,
    includeProvenance: Boolean
  ): Task[(RiskTreeResult, Option[TreeProvenance])] = {
    // Delegate to Simulator.simulateTree for actual Monte Carlo execution
    Simulator.simulateTree(root, nTrials, parallelism, includeProvenance)
      .mapError { error =>
        new RuntimeException(
          s"Tree simulation failed for simulationId=$simulationId: ${error.getMessage}",
          error
        )
      }
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

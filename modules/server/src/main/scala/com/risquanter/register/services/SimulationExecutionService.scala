package com.risquanter.register.services

import zio.{Task, ZIO, ZLayer}
import com.risquanter.register.simulation.{RiskSampler, MetalogDistribution}
import com.risquanter.register.domain.data.{RiskResult, RiskResultGroup}
import com.risquanter.register.domain.data.iron.Probability
import com.risquanter.register.services.helper.Simulator
import io.github.iltotore.iron.*

/**
 * Configuration for a single risk in a simulation.
 * 
 * @param riskId Unique identifier for the risk
 * @param entityId Entity identifier for HDR seeding
 * @param occurrenceProb Probability of risk occurrence per trial
 * @param lossDistribution Metalog distribution for loss amounts
 * @param seed3 Optional seed for reproducibility (default: 0L)
 * @param seed4 Optional seed for reproducibility (default: 0L)
 */
case class RiskConfig(
  riskId: String,
  entityId: Long,
  occurrenceProb: Probability,
  lossDistribution: MetalogDistribution,
  seed3: Long = 0L,
  seed4: Long = 0L
)

/**
 * Result of a simulation execution.
 * 
 * @param simulationId Unique identifier for this simulation run
 * @param aggregatedResult Combined results across all risks
 * @param individualResults Per-risk breakdown of results
 * @param nTrials Number of trials executed
 */
case class SimulationExecutionResult(
  simulationId: String,
  aggregatedResult: RiskResult,
  individualResults: Vector[RiskResult],
  nTrials: Int
)

/**
 * Service for orchestrating Monte Carlo risk simulations.
 * 
 * Capabilities:
 * - Execute multi-risk simulations with configurable parallelism
 * - Aggregate results using ZIO Prelude Identity semantics
 * - Provide both individual and combined risk outcomes
 * - Support deterministic execution via seed configuration
 */
trait SimulationExecutionService {
  
  /**
   * Run a Monte Carlo simulation for multiple risks.
   * 
   * Each risk is simulated independently, then results are aggregated
   * via outer join (sum losses per trial across all risks).
   * 
   * @param simulationId Unique identifier for this simulation run
   * @param risks Risk configurations to simulate
   * @param nTrials Number of Monte Carlo trials to execute
   * @param parallelism Maximum concurrent risk simulations (default: 8)
   * @return Simulation results with individual and aggregated outcomes
   */
  def runSimulation(
    simulationId: String,
    risks: Seq[RiskConfig],
    nTrials: Int,
    parallelism: Int = 8
  ): Task[SimulationExecutionResult]
  
  /**
   * Run simulation sequentially for deterministic ordering.
   * Useful for testing and debugging.
   * 
   * @param simulationId Unique identifier for this simulation run
   * @param risks Risk configurations to simulate
   * @param nTrials Number of Monte Carlo trials to execute
   * @return Simulation results with individual and aggregated outcomes
   */
  def runSimulationSequential(
    simulationId: String,
    risks: Seq[RiskConfig],
    nTrials: Int
  ): Task[SimulationExecutionResult]
}

/**
 * Live implementation of SimulationExecutionService.
 * 
 * Uses Simulator for parallel trial execution and ZIO Prelude
 * Identity for result aggregation.
 */
case class SimulationExecutionServiceLive() extends SimulationExecutionService {
  
  def runSimulation(
    simulationId: String,
    risks: Seq[RiskConfig],
    nTrials: Int,
    parallelism: Int = 8
  ): Task[SimulationExecutionResult] = for {
    // 1. Create samplers from risk configs
    samplers <- ZIO.succeed {
      risks.map(config => 
        RiskSampler.fromMetalog(
          entityId = config.entityId,
          riskId = config.riskId,
          occurrenceProb = config.occurrenceProb,
          lossDistribution = config.lossDistribution,
          seed3 = config.seed3,
          seed4 = config.seed4
        )
      ).toVector
    }
    
    // 2. Run simulation in parallel
    individualResults <- Simulator.simulate(samplers, nTrials, parallelism)
    
    // 3. Aggregate results using Identity.combine
    aggregatedResult <- ZIO.succeed {
      if (individualResults.isEmpty) {
        RiskResult.empty("AGGREGATE", nTrials)
      } else {
        individualResults.reduce((a, b) => 
          RiskResult.identity.combine(a, b)
        ).copy(riskName = "AGGREGATE")
      }
    }
    
    // 4. Wrap in execution result
    result = SimulationExecutionResult(
      simulationId = simulationId,
      aggregatedResult = aggregatedResult,
      individualResults = individualResults,
      nTrials = nTrials
    )
  } yield result
  
  def runSimulationSequential(
    simulationId: String,
    risks: Seq[RiskConfig],
    nTrials: Int
  ): Task[SimulationExecutionResult] = for {
    // 1. Create samplers from risk configs
    samplers <- ZIO.succeed {
      risks.map(config => 
        RiskSampler.fromMetalog(
          entityId = config.entityId,
          riskId = config.riskId,
          occurrenceProb = config.occurrenceProb,
          lossDistribution = config.lossDistribution,
          seed3 = config.seed3,
          seed4 = config.seed4
        )
      ).toVector
    }
    
    // 2. Run simulation sequentially
    individualResults <- Simulator.simulateSequential(samplers, nTrials)
    
    // 3. Aggregate results using Identity.combine
    aggregatedResult <- ZIO.succeed {
      if (individualResults.isEmpty) {
        RiskResult.empty("AGGREGATE", nTrials)
      } else {
        individualResults.reduce((a, b) => 
          RiskResult.identity.combine(a, b)
        ).copy(riskName = "AGGREGATE")
      }
    }
    
    // 4. Wrap in execution result
    result = SimulationExecutionResult(
      simulationId = simulationId,
      aggregatedResult = aggregatedResult,
      individualResults = individualResults,
      nTrials = nTrials
    )
  } yield result
}

object SimulationExecutionService {
  /** Live layer for ZIO dependency injection */
  val live: ZLayer[Any, Nothing, SimulationExecutionService] =
    ZLayer.succeed(SimulationExecutionServiceLive())
}

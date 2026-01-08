package com.risquanter.register.configs

/** Simulation execution configuration.
  *
  * @param defaultNTrials Default number of Monte Carlo trials per simulation
  * @param maxTreeDepth Maximum allowed depth for risk tree hierarchy
  * @param defaultParallelism Default ZIO fiber parallelism for tree traversal
  * @param maxConcurrentSimulations Maximum concurrent simulations (semaphore permits)
  * @param maxNTrials Hard limit on trials per simulation (reject if exceeded)
  * @param maxParallelism Hard limit on parallelism per simulation (reject if exceeded)
  */
final case class SimulationConfig(
  defaultNTrials: Int,
  maxTreeDepth: Int,
  defaultParallelism: Int,
  maxConcurrentSimulations: Int,
  maxNTrials: Int,
  maxParallelism: Int
)

package com.risquanter.register.configs

import zio.config.magnolia.{DeriveConfig, deriveConfig}

/** Simulation execution configuration.
  *
  * @param defaultNTrials Default number of Monte Carlo trials per simulation
  * @param maxTreeDepth Maximum allowed depth for risk tree hierarchy
  * @param defaultParallelism Default ZIO fiber parallelism for tree traversal
  * @param maxConcurrentSimulations Maximum concurrent simulations (semaphore permits)
  * @param maxNTrials Hard limit on trials per simulation (reject if exceeded)
  * @param maxParallelism Hard limit on parallelism per simulation (reject if exceeded)
  * @param defaultSeed3 Global seed 3 for HDR random number generation (reproducibility)
  * @param defaultSeed4 Global seed 4 for HDR random number generation (reproducibility)
  */
final case class SimulationConfig(
  defaultNTrials: Int,
  maxTreeDepth: Int,
  defaultParallelism: Int,
  maxConcurrentSimulations: Int,
  maxNTrials: Int,
  maxParallelism: Int,
  defaultSeed3: Long,
  defaultSeed4: Long
)

object SimulationConfig {
  given DeriveConfig[SimulationConfig] = DeriveConfig.derived
}
